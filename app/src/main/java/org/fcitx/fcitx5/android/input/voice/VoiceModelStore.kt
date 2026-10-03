/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.voice.VoiceModels.Error
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * One downloadable model on this device: what is there of it, and the operations that change
 * that. Pure Kotlin, see `VoiceModelStoreTest`.
 *
 * Two rules keep fast taps and a stalled connection from corrupting anything:
 *
 * - **Operations on the files run one after the other.** Cancelling a download does not stop a
 *   read that is blocked on the network, so whatever comes next (a resumed download, a
 *   deletion) first waits for its predecessor to be really gone.
 * - **Only the newest operation publishes [state].** One that was paused or superseded finishes
 *   silently, however it ends.
 */
class VoiceModelStore(
    private val model: VoiceModel,
    private val dir: File,
    private val fetch: (URL, File, Long, String, (Long) -> Unit) -> Unit = VoiceModelFetch::fetch,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val marker = model.files.joinToString("\n") { it.sha256 }

    private fun onDisk(): State {
        val installed = runCatching { File(dir, MARKER).readText() }.getOrNull() == marker &&
                model.files.all { File(dir, it.name).length() == it.size }
        if (installed) return State.Installed
        return State.Absent(model.files.sumOf {
            minOf(it.size, File(dir, it.name).length() + File(dir, it.name + ".part").length())
        })
    }

    private val _state = MutableStateFlow(onDisk())

    val state: StateFlow<State> = _state

    /** The newest operation, running or not. Guarded by `this`, as is every write to [_state]. */
    private var last: Job? = null

    /**
     * Run [block] after everything started before it has ended. An operation that is cancelled,
     * even before it ran, still waits for its predecessor: the one after it relies on that.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun enqueue(block: suspend CoroutineScope.(publish: (State) -> Unit) -> Unit): Job {
        val previous = last
        // ATOMIC: the wait below must happen even if this job is cancelled before it starts
        return scope.launch(start = CoroutineStart.ATOMIC) {
            val self = coroutineContext.job
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            block { state ->
                synchronized(this@VoiceModelStore) {
                    if (last === self && self.isActive) _state.value = state
                }
            }
        }.also { last = it }
    }

    /** Start or continue downloading. Does nothing unless the model is [State.Absent]. */
    @Synchronized
    fun download() {
        val current = _state.value as? State.Absent ?: return
        _state.value = State.Downloading(current.downloaded)
        enqueue { publish ->
            val present = (onDisk() as? State.Absent)?.downloaded ?: 0
            val error = try {
                dir.mkdirs()
                if (dir.usableSpace < model.size - present + SPACE_MARGIN) {
                    Error.Storage
                } else {
                    var before = 0L
                    var reported = present
                    for (file in model.files) {
                        fetch(model.url(file), File(dir, file.name), file.size, file.sha256) {
                            ensureActive()
                            val total = before + it
                            if (total - reported >= PROGRESS_STEP) {
                                reported = total
                                publish(State.Downloading(total))
                            }
                        }
                        before += file.size
                    }
                    ensureActive()
                    File(dir, MARKER).writeText(marker)
                    null
                }
            } catch (e: VoiceModelFetch.WrongContentException) {
                Timber.w(e, "Voice model download rejected")
                Error.Content
            } catch (e: IOException) {
                Timber.w(e, "Voice model download failed")
                if (dir.usableSpace < (1L shl 20)) Error.Storage else Error.Network
            }
            publish(
                when (val now = onDisk()) {
                    is State.Absent -> now.copy(error = error ?: Error.Content)
                    else -> now
                }
            )
        }
    }

    /** Stop downloading and keep what has arrived. */
    @Synchronized
    fun pause() {
        val current = _state.value as? State.Downloading ?: return
        last?.cancel()
        _state.value = State.Absent(current.downloaded)
    }

    /** Remove the model from the device, whether it is installed or partly downloaded. */
    suspend fun delete() {
        synchronized(this) {
            last?.cancel()
            // not usable from this moment on; the files go once nothing writes to them any more
            _state.value = State.Absent(0)
            enqueue { dir.deleteRecursively() }
        }.join()
    }

    private companion object {
        /** Written last, so a directory that has it holds every file, complete and verified. */
        const val MARKER = "installed"

        /** Free space to leave on the device. */
        const val SPACE_MARGIN = 200L shl 20

        /** Progress is reported in steps of this many bytes. */
        const val PROGRESS_STEP = 1L shl 20
    }
}
