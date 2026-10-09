/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.fcitx.fcitx5.android.input.voice.VoiceTunedStore.Check
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

class VoiceTunedStoreTest {

    private val root = File(Files.createTempDirectory("voice-tuned").toFile(), "voice-models")

    private val sha = "a".repeat(64)

    private fun release(tag: String) = VoiceModelRelease(
        tag, listOf(VoiceModelRelease.File("model.int8.onnx", 10, sha), VoiceModelRelease.File("tokens.txt", 5, sha))
    )

    private fun answer(tag: String) = """{"tag_name": "$tag", "assets": [
        {"name": "model.int8.onnx", "size": 10, "digest": "sha256:$sha",
         "browser_download_url": "https://github.com/Lewin671/sensevoice-finetune/releases/download/$tag/model.int8.onnx"},
        {"name": "tokens.txt", "size": 5, "digest": "sha256:$sha",
         "browser_download_url": "https://github.com/Lewin671/sensevoice-finetune/releases/download/$tag/tokens.txt"}]}"""

    /** What the network says to a check; replaced by the tests. */
    private var onRead: () -> String = { answer("model-20261012") }

    private val asked = Collections.synchronizedList(mutableListOf<URL>())

    /** Stands for `VoiceModels`: one store per directory, as in the app. */
    private val stores = HashMap<String, VoiceModelStore>()

    private fun modelStore(model: VoiceModel) = synchronized(stores) {
        stores.getOrPut(model.id) {
            VoiceModelStore(model, File(root, model.id), fetch = { _, dest, size, _, onProgress ->
                dest.writeBytes(ByteArray(size.toInt()))
                onProgress(size)
            })
        }
    }

    private fun store(pinned: String = "model-20261009") = VoiceTunedStore(
        root, release(pinned),
        state = { modelStore(it).state },
        remove = { modelStore(it).delete() },
        read = { asked += it; onRead() }
    )

    /** A start of the app: nothing is remembered but what is on the device. */
    private fun restart(pinned: String = "model-20261009"): VoiceTunedStore {
        synchronized(stores) { stores.clear() }
        return store(pinned)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun VoiceTunedStore.checkAndWait() {
        check()
        await("the check to end") { check.value != Check.Checking }
    }

    private fun install(model: VoiceModel) {
        modelStore(model).download()
        await("the download of ${model.id}") { modelStore(model).state.value == State.Installed }
    }

    private fun dir(tag: String) = File(root, "sense-voice-small-tuned-${tag.removePrefix("model-")}-int8")

    @After
    fun tearDown() {
        root.parentFile!!.deleteRecursively()
    }

    @Test
    fun nothingIsAskedUntilACheckIsRequested() {
        val store = store()
        assertEquals(Check.Idle, store.check.value)
        assertEquals("model-20261009", store.versions.value.current.release.tag)
        assertNull(store.versions.value.update)
        Thread.sleep(50)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aCheckAsksOneAddress() {
        val store = store()
        store.checkAndWait()
        assertEquals(listOf(URL("https://api.github.com/repos/Lewin671/sensevoice-finetune/releases/latest")), asked)
    }

    @Test
    fun aNewerVersionIsTheOneToDownloadWhenNoneIsInstalled() {
        val store = store()
        store.checkAndWait()
        assertEquals(Check.Idle, store.check.value)
        assertEquals("model-20261012", store.versions.value.current.release.tag)
        assertNull(store.versions.value.update)
    }

    @Test
    fun aPartOfTheOldVersionGoesWhenANewerOneIsFound() {
        val store = store()
        dir("model-20261009").mkdirs()
        File(dir("model-20261009"), "model.int8.onnx.part").writeBytes(ByteArray(3))
        store.checkAndWait()
        await("the old part to go") { !dir("model-20261009").exists() }
    }

    @Test
    fun theInstalledVersionStaysUntilTheNewerOneIsComplete() {
        val store = store()
        val old = store.versions.value.current
        install(old.model)
        store.checkAndWait()
        val update = store.versions.value.update!!
        assertEquals(old, store.versions.value.current)
        assertEquals("model-20261012", update.release.tag)
        assertEquals(State.Installed, modelStore(old.model).state.value)

        install(update.model)
        await("the newer version to take over") { store.versions.value.current === update }
        assertNull(store.versions.value.update)
        await("the old version to go") { !dir("model-20261009").exists() }
        assertEquals(State.Installed, modelStore(update.model).state.value)
    }

    @Test
    fun whatWasFoundIsRememberedAcrossStarts() {
        val first = store()
        install(first.versions.value.current.model)
        first.checkAndWait()

        val second = restart()
        assertEquals("model-20261009", second.versions.value.current.release.tag)
        assertEquals("model-20261012", second.versions.value.update?.release?.tag)
        assertEquals(1, asked.size)

        // and an update that completes after a restart still takes over
        install(second.versions.value.update!!.model)
        await("the newer version to take over") { second.versions.value.current.release.tag == "model-20261012" }
        val third = restart()
        assertEquals("model-20261012", third.versions.value.current.release.tag)
        assertNull(third.versions.value.update)
        assertEquals(State.Installed, modelStore(third.versions.value.current.model).state.value)
    }

    @Test
    fun theNewestVersionIsUpToDate() {
        onRead = { answer("model-20261009") }
        val store = store()
        store.checkAndWait()
        assertEquals(Check.UpToDate, store.check.value)
        assertNull(store.versions.value.update)

        // an older answer changes nothing either
        onRead = { answer("model-20260101") }
        store.checkAndWait()
        assertEquals(Check.UpToDate, store.check.value)
        assertEquals("model-20261009", store.versions.value.current.release.tag)
    }

    @Test
    fun aFailedCheckChangesNothing() {
        val store = store()
        for (failure in listOf<() -> String>({ throw IOException("offline") }, { "<html>" }, { """{"tag_name": "v1.0"}""" })) {
            onRead = failure
            store.checkAndWait()
            assertEquals(Check.Failed, store.check.value)
            assertEquals("model-20261009", store.versions.value.current.release.tag)
            assertNull(store.versions.value.update)
        }
        assertEquals("model-20261009", restart().versions.value.current.release.tag)
    }

    @Test
    fun anEvenNewerVersionReplacesAnUpdateThatWasNotInstalled() {
        val store = store()
        install(store.versions.value.current.model)
        store.checkAndWait()
        val skipped = store.versions.value.update!!
        dir("model-20261012").mkdirs()
        File(dir("model-20261012"), "tokens.txt.part").writeBytes(ByteArray(2))

        onRead = { answer("model-20261101") }
        store.checkAndWait()
        assertEquals("model-20261009", store.versions.value.current.release.tag)
        assertEquals("model-20261101", store.versions.value.update?.release?.tag)
        await("the skipped version to go") { !dir("model-20261012").exists() }

        // completing the skipped one late must not make it the version in use
        install(skipped.model)
        Thread.sleep(50)
        assertEquals("model-20261009", store.versions.value.current.release.tag)
    }

    @Test
    fun deletingTheVersionInUseLeavesTheNewerOneToDownload() {
        val store = store()
        install(store.versions.value.current.model)
        store.checkAndWait()
        kotlinx.coroutines.runBlocking { store.delete() }
        assertEquals("model-20261012", store.versions.value.current.release.tag)
        assertNull(store.versions.value.update)
        assertFalse(dir("model-20261009").exists())
        assertEquals("model-20261012", restart().versions.value.current.release.tag)
    }

    @Test
    fun aNewerAppBringsANewerVersion() {
        val old = store()
        install(old.versions.value.current.model)

        // installed: offered as an update, like one a check found
        val updated = restart(pinned = "model-20261020")
        assertEquals("model-20261009", updated.versions.value.current.release.tag)
        assertEquals("model-20261020", updated.versions.value.update?.release?.tag)
        assertEquals(State.Installed, modelStore(updated.versions.value.current.model).state.value)
    }

    @Test
    fun directoriesOfVersionsNoLongerKnownAreRemoved() {
        dir("model-20261008").mkdirs()
        File(dir("model-20261008"), "model.int8.onnx").writeBytes(ByteArray(4))
        val other = File(root, "sense-voice-small-int8").apply { mkdirs() }
        store()
        assertFalse(dir("model-20261008").exists())
        assertTrue(other.exists())
    }
}
