/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * Which version of the fine-tuned model this device uses, and whether a newer one has been
 * published. Pure Kotlin, see `VoiceTunedStoreTest`.
 *
 * The app knows one version by itself ([pinned]). Nothing here runs by itself either: [check]
 * asks the network once, when the user asked for it. A newer version it finds is downloaded
 * like any model, when the user says so; the version in use keeps working until the newer one
 * is complete and verified, then gives way to it and is deleted.
 */
class VoiceTunedStore(
    /** Where the models are: every version has a directory of its own in it. */
    private val root: File,
    private val pinned: VoiceModelRelease,
    /** What is on the device of a model. */
    private val state: (VoiceModel) -> StateFlow<State>,
    /** Removes a model from the device once nothing uses it any more. */
    private val remove: suspend (VoiceModel) -> Unit,
    private val read: (URL) -> String = { VoiceModelFetch.read(it, ANSWER_LIMIT, CHECK_TIMEOUT_MS) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    enum class Check { Idle, Checking, UpToDate, Failed }

    /** A version, and the model it is on this device. */
    class Version(val release: VoiceModelRelease) {
        val model = release.model()
    }

    /**
     * @param current the version to download or, once installed, to recognize with
     * @param update a newer one; only while [current] is installed
     */
    class Versions(val current: Version, val update: Version?)

    @Serializable
    private class Record(val current: VoiceModelRelease, val update: VoiceModelRelease? = null)

    private val record = File(root, RECORD)

    private val _check = MutableStateFlow(Check.Idle)
    val check: StateFlow<Check> = _check

    private val _versions = MutableStateFlow(remembered())
    val versions: StateFlow<Versions> = _versions

    init {
        // versions that were replaced, by an update or by a newer app
        val known = _versions.value.let { listOfNotNull(it.current.model.id, it.update?.model?.id) }
        root.listFiles().orEmpty()
            .filter { it.name.startsWith(VoiceModelRelease.ID_PREFIX) && it.name !in known }
            .forEach { it.deleteRecursively() }
        // so that an app that knows a newer version still knows what this one installed
        if (!record.exists()) publish(_versions.value)
        _versions.value.update?.let(::replaceWhenInstalled)
    }

    private fun installed(version: Version) = state(version.model).value == State.Installed

    /** What earlier checks found, and the version this app brings if that is newer. */
    private fun remembered(): Versions {
        val saved = runCatching { Json.decodeFromString(Record.serializer(), record.readText()) }.getOrNull()
        val current = Version(saved?.current ?: pinned)
        val newest = listOfNotNull(saved?.update, pinned).maxBy { it.tag }
        return when {
            !newest.isNewerThan(current.release) -> Versions(current, null)
            installed(current) -> Versions(current, Version(newest))
            else -> Versions(Version(newest), null)
        }
    }

    private fun publish(versions: Versions) {
        _versions.value = versions
        runCatching {
            root.mkdirs()
            record.writeText(
                Json.encodeToString(Record.serializer(), Record(versions.current.release, versions.update?.release))
            )
        }.onFailure { Timber.w(it, "Could not record the fine-tuned model's version") }
    }

    /** [update] takes the place of the version in use as soon as it is complete. */
    private fun replaceWhenInstalled(update: Version) {
        scope.launch {
            state(update.model).first { it == State.Installed }
            val old = synchronized(this@VoiceTunedStore) {
                val now = _versions.value
                if (now.update !== update) return@launch
                publish(Versions(update, null))
                now.current
            }
            remove(old.model)
        }
    }

    /** Ask which version is the newest. Does nothing while a check is running. */
    @Synchronized
    fun check() {
        if (_check.value == Check.Checking) return
        _check.value = Check.Checking
        scope.launch {
            val release = try {
                VoiceModelRelease.parse(read(URL(VoiceModelRelease.LATEST)))
            } catch (e: IOException) {
                Timber.w(e, "Model update check failed")
                null
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Model update check: unusable answer")
                null
            }
            val before = _versions.value
            release?.let(::found).orEmpty().forEach { remove(it.model) }
            _check.value = when {
                release == null -> Check.Failed
                _versions.value === before -> Check.UpToDate
                else -> Check.Idle
            }
        }
    }

    /** Take note of [release] if it is newer than what is known; returns the versions it replaces. */
    @Synchronized
    private fun found(release: VoiceModelRelease): List<Version> {
        val now = _versions.value
        if (!release.isNewerThan((now.update ?: now.current).release)) return emptyList()
        val newest = Version(release)
        return if (installed(now.current)) {
            publish(Versions(now.current, newest))
            replaceWhenInstalled(newest)
            listOfNotNull(now.update)
        } else {
            publish(Versions(newest, null))
            listOfNotNull(now.current, now.update)
        }
    }

    /**
     * Remove the version in use from the device. A newer one that was found, downloaded in part
     * or not at all, is then the one to download.
     */
    suspend fun delete() {
        val old = synchronized(this) {
            val now = _versions.value
            now.update?.let { publish(Versions(it, null)) }
            now.current
        }
        remove(old.model)
    }

    private companion object {
        const val RECORD = "fine-tuned.json"

        /** GitHub's description of a release is a few kilobytes plus its notes. */
        const val ANSWER_LIMIT = 1 shl 20

        /** Someone is looking at a progress bar. */
        const val CHECK_TIMEOUT_MS = 15_000
    }
}
