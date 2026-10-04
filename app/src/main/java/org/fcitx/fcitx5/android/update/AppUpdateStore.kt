/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.input.voice.VoiceModel
import org.fcitx.fcitx5.android.input.voice.VoiceModelFetch
import org.fcitx.fcitx5.android.input.voice.VoiceModelStore
import org.fcitx.fcitx5.android.input.voice.VoiceModels
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * Whether a newer version of the app has been published, and its package on this device.
 * Pure Kotlin, see `AppUpdateStoreTest`.
 *
 * Nothing here runs by itself: [check] asks the network once, when the user asked for it. What
 * it found is kept in [dir] until that version (or a later one) is the installed one. The
 * package is downloaded like a speech model, by a [VoiceModelStore].
 */
class AppUpdateStore(
    private val dir: File,
    /** Version name of the running app. */
    private val current: String,
    /** The device's processor types, preferred first. */
    private val abis: List<String>,
    private val read: (URL) -> String = { VoiceModelFetch.read(it, ANSWER_LIMIT, CHECK_TIMEOUT_MS) },
    private val fetch: (URL, File, Long, String, (Long) -> Unit) -> Unit = VoiceModelFetch::fetch,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    enum class Check { Idle, Checking, UpToDate, Failed }

    /** A version newer than the running one. */
    inner class Found(val release: AppRelease) {
        private val apkDir = File(dir, release.tag)

        /** Its package on this device; `null` if the release has none for this device. */
        val download = release.apk?.let { apk ->
            val model = VoiceModel(
                id = release.tag,
                name = release.version,
                host = AppRelease.HOST,
                baseUrl = AppRelease.downloadPath(release.tag),
                files = listOf(VoiceModel.File(apk.name, apk.size, apk.sha256))
            )
            VoiceModelStore(model, apkDir, fetch, scope)
        }

        /** The package, once it is complete and verified. */
        val apk: File?
            get() = release.apk?.takeIf { download?.state?.value == VoiceModels.State.Installed }
                ?.let { File(apkDir, it.name) }
    }

    private val record = File(dir, RECORD)

    private val _check = MutableStateFlow(Check.Idle)
    val check: StateFlow<Check> = _check

    private val _found = MutableStateFlow(remembered())
    val found: StateFlow<Found?> = _found

    /** What an earlier check found, unless the app has been updated since. */
    private fun remembered(): Found? {
        val release = runCatching { Json.decodeFromString(AppRelease.serializer(), record.readText()) }.getOrNull()
            ?.takeIf { AppRelease.isNewer(it.tag, current) }
        // also removes the package of the version that is now running
        if (release == null) dir.deleteRecursively()
        return release?.let(::Found)
    }

    /** Ask which version is the newest. Does nothing while a check is running. */
    @Synchronized
    fun check() {
        if (_check.value == Check.Checking) return
        _check.value = Check.Checking
        scope.launch {
            val release = try {
                AppRelease.parse(read(URL(AppRelease.LATEST)), abis)
            } catch (e: IOException) {
                Timber.w(e, "Update check failed")
                null
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Update check: unusable answer")
                null
            }
            val known = _found.value
            when {
                release == null -> {}
                release == known?.release -> {}
                else -> {
                    // a package downloaded for another version is of no use any more
                    known?.download?.delete()
                    dir.deleteRecursively()
                    if (AppRelease.isNewer(release.tag, current)) {
                        dir.mkdirs()
                        record.writeText(Json.encodeToString(AppRelease.serializer(), release))
                        _found.value = Found(release)
                    } else {
                        _found.value = null
                    }
                }
            }
            _check.value = when {
                release == null -> Check.Failed
                _found.value == null -> Check.UpToDate
                else -> Check.Idle
            }
        }
    }

    private companion object {
        const val RECORD = "release.json"

        /** GitHub's description of a release is a few kilobytes plus its notes. */
        const val ANSWER_LIMIT = 1 shl 20

        /** Someone is looking at a progress bar. */
        const val CHECK_TIMEOUT_MS = 15_000
    }
}
