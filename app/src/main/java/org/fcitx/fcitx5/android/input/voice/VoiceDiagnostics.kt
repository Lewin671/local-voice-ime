/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Off unless switched on (*Settings → Voice input → Keep voice diagnostics*): a journal of what
 * happened to dictation sessions, to find out afterwards why one ended. It says which path was
 * taken, never what was said: an event is a name with numbers, truth values and fixed words
 * (see [VoiceDiagnosticStore.encode]). No audio, no text, not the app dictated into, and nothing
 * from fields marked private. `docs/DIAGNOSTICS.md` lists every event.
 *
 * Events are only taken while a session runs ([watching]), on any thread; they are written on a
 * thread of their own, so that dictation never waits for storage. The journal stays in the app's
 * private storage and leaves the phone in one way only: the user's export.
 */
object VoiceDiagnostics {

    private const val DIR = "voice-diagnostics"

    private val enabled by AppPrefs.getInstance().voiceInput.keepDiagnostics

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-diagnostics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val dispatcher = worker.asCoroutineDispatcher()

    @Volatile
    private var store: VoiceDiagnosticStore? = null

    private fun store(context: Context) = store ?: synchronized(this) {
        store ?: VoiceDiagnosticStore(File(context.applicationContext.noBackupFilesDir, DIR))
            .also { store = it }
    }

    enum class Export { None, Done, Failed }

    data class Status(val bytes: Long = 0, val export: Export = Export.None)

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state.asStateFlow()

    private val sequence = AtomicLong()

    private val sessions = AtomicInteger()

    /** The session whose events are being kept; 0 if there is none. */
    @Volatile
    private var session = 0

    /** Whether [log] keeps anything right now. Ask first where making the event costs something. */
    val watching get() = session != 0

    /**
     * Set when a private field got the keyboard's attention while a session was still running:
     * nothing more is taken, not even of that session, until the next one begins elsewhere.
     */
    @Volatile
    private var suspended = false

    /** Whether events are taken at this moment: the switch may have been turned off meanwhile. */
    private fun accepts(id: Int) = id != 0 && !suspended && enabled

    @Volatile
    private var processReported = false

    private fun isPrivate(info: EditorInfo?) =
        info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    /**
     * Input starts in the editor described by [info]. To be called before anything is logged
     * about it: what happens in a private field is not noted, also not as part of a session that
     * began elsewhere and is still finishing.
     */
    fun onEditor(info: EditorInfo?) {
        // whichever session is still running: an older one may be finishing after a newer ended
        if (isPrivate(info)) {
            suspended = true
            session = 0
        }
    }

    /**
     * A dictation session begins in the field described by [info].
     * @return its number in the journal, or 0 if nothing is kept of it
     */
    fun begin(context: Context, info: EditorInfo?, vararg fields: Pair<String, Any?>): Int {
        if (!enabled || isPrivate(info)) {
            session = 0
            return 0
        }
        suspended = false
        val app = context.applicationContext
        if (!processReported) {
            processReported = true
            reportProcess(app)
        }
        val id = sessions.incrementAndGet()
        session = id
        write(app, id, "session_start", fields)
        return id
    }

    /** The session numbered [id] is over. */
    fun end(context: Context, id: Int, vararg fields: Pair<String, Any?>) {
        if (accepts(id)) write(context.applicationContext, id, "session_end", fields)
        if (session == id) session = 0
    }

    /** Something happened while a session runs; ignored otherwise. */
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val id = session
        if (!accepts(id)) return
        write(null, id, event, fields)
    }

    /** Something happened in the session numbered [id], which need not be the newest. */
    fun log(id: Int, event: String, vararg fields: Pair<String, Any?>) {
        if (!accepts(id)) return
        write(null, id, event, fields)
    }

    private fun write(context: Context?, id: Int, event: String, fields: Array<out Pair<String, Any?>>) {
        val line = VoiceDiagnosticStore.encode(
            event,
            listOf(
                "n" to sequence.incrementAndGet(),
                "t" to System.currentTimeMillis(),
                "up" to SystemClock.elapsedRealtime(),
                "s" to id.takeIf { it != 0 },
                "th" to VoiceDiagnosticStore.tokenOf(Thread.currentThread().name)
            ) + fields
        )
        worker.execute {
            // a session can only have begun after the store was made for its first event
            val store = context?.let(::store) ?: store ?: return@execute
            store.append(line)
            if (event == "session_end") state.update { it.copy(bytes = store.bytes) }
        }
    }

    /**
     * Once per process: that it is a new one, and how the previous ones ended, as far as Android
     * tells (a session that was never closed in the journal is then explained by the reason
     * given here, e.g. low memory or a crash).
     */
    private fun reportProcess(context: Context) {
        write(
            context, 0, "process_start",
            arrayOf(
                "app" to VoiceDiagnosticStore.tokenOf(BuildConfig.VERSION_NAME),
                "build" to BuildConfig.BUILD_TYPE,
                "sdk" to Build.VERSION.SDK_INT
            )
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 3)) {
                write(
                    context, 0, "previous_exit",
                    arrayOf(
                        "at" to exit.timestamp,
                        "reason" to exit.reason,
                        "status" to exit.status,
                        "importance" to exit.importance,
                        "pss_kb" to exit.pss,
                        "rss_kb" to exit.rss
                    )
                )
            }
        }
    }

    /** Find out how much is kept, for the settings. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        worker.execute { state.update { it.copy(bytes = store(app).bytes) } }
    }

    suspend fun delete(context: Context) = withContext(dispatcher) {
        store(context).delete()
        state.update { Status() }
    }

    /**
     * Write the journal into [target], a document the user created for it: a line that describes
     * the phone and the settings dictation depends on, then the events, oldest first.
     */
    suspend fun export(context: Context, target: Uri, vararg settings: Pair<String, Any?>) {
        val header = VoiceDiagnosticStore.encode(
            "export",
            listOf(
                "t" to System.currentTimeMillis(),
                "up" to SystemClock.elapsedRealtime(),
                "app" to VoiceDiagnosticStore.tokenOf(BuildConfig.VERSION_NAME),
                "build" to BuildConfig.BUILD_TYPE,
                "sdk" to Build.VERSION.SDK_INT,
                "maker" to VoiceDiagnosticStore.tokenOf(Build.MANUFACTURER),
                "model" to VoiceDiagnosticStore.tokenOf(Build.MODEL)
            ) + settings
        )
        val done = withContext(dispatcher) {
            runCatching {
                val out = context.contentResolver.openOutputStream(target)
                    ?: error("The document cannot be written")
                out.use {
                    it.write((header + "\n").toByteArray(Charsets.US_ASCII))
                    store(context).writeTo(it)
                }
            }.onFailure { Timber.w(it, "Could not export the voice diagnostics") }.isSuccess
        }
        state.update { it.copy(export = if (done) Export.Done else Export.Failed) }
    }
}
