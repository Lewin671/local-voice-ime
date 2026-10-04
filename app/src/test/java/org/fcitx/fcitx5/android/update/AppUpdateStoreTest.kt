/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.fcitx.fcitx5.android.update.AppUpdateStore.Check
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

class AppUpdateStoreTest {

    private val dir = File(Files.createTempDirectory("app-update").toFile(), "app-update")

    private val sha = "a".repeat(64)

    private fun answer(tag: String, abi: String = "arm64-v8a") = """{"tag_name": "$tag", "body": "Notes of $tag",
        "assets": [{"name": "app-$tag-$abi.apk", "size": 10, "digest": "sha256:$sha",
        "browser_download_url": "https://github.com/Lewin671/local-voice-ime/releases/download/$tag/app-$tag-$abi.apk"}]}"""

    /** What the network says to a check; replaced by the tests. */
    private var onRead: () -> String = { answer("v0.7.0") }

    private val asked = Collections.synchronizedList(mutableListOf<URL>())
    private val fetched = Collections.synchronizedList(mutableListOf<URL>())

    private fun store(current: String = "v0.6.2-0-g59f996d8") = AppUpdateStore(
        dir, current, listOf("arm64-v8a"),
        read = { asked += it; onRead() },
        fetch = { url, dest, size, _, onProgress ->
            fetched += url
            dest.writeBytes(ByteArray(size.toInt()))
            onProgress(size)
        }
    )

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun AppUpdateStore.checkAndWait() {
        check()
        await("the check to end") { check.value != Check.Checking }
    }

    private fun AppUpdateStore.downloadAndWait(): File {
        val found = found.value!!
        found.download!!.download()
        await("the download") { found.download!!.state.value == State.Installed }
        return found.apk!!
    }

    @After
    fun tearDown() {
        dir.parentFile!!.deleteRecursively()
    }

    @Test
    fun nothingIsAskedUntilACheckIsRequested() {
        val store = store()
        assertEquals(Check.Idle, store.check.value)
        assertNull(store.found.value)
        Thread.sleep(50)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aCheckFindsANewerVersion() {
        val store = store()
        store.checkAndWait()
        assertEquals(Check.Idle, store.check.value)
        assertEquals("0.7.0", store.found.value?.release?.version)
        assertEquals("Notes of v0.7.0", store.found.value?.release?.notes)
        assertEquals(State.Absent(0), store.found.value?.download?.state?.value)
        assertEquals(listOf(URL("https://api.github.com/repos/Lewin671/local-voice-ime/releases/latest")), asked)
    }

    @Test
    fun theInstalledVersionOrAnOlderOneIsNothingNewer() {
        for (tag in listOf("v0.6.2", "v0.6.1")) {
            onRead = { answer(tag) }
            val store = store()
            store.checkAndWait()
            assertEquals(Check.UpToDate, store.check.value)
            assertNull(store.found.value)
        }
    }

    @Test
    fun aFailedCheckSaysSoAndKeepsWhatWasFound() {
        val store = store()
        store.checkAndWait()
        for (failure in listOf<() -> String>({ throw IOException("unreachable") }, { "<html>" })) {
            onRead = failure
            store.checkAndWait()
            assertEquals(Check.Failed, store.check.value)
            assertEquals("0.7.0", store.found.value?.release?.version)
        }
        onRead = { answer("v0.7.0") }
        store.checkAndWait()
        assertEquals(Check.Idle, store.check.value)
    }

    @Test
    fun thePackageIsFetchedFromTheReleaseAndOfferedOnceComplete() {
        val store = store()
        store.checkAndWait()
        assertNull(store.found.value!!.apk)
        val apk = store.downloadAndWait()
        assertEquals(10, apk.length())
        assertEquals(
            listOf(URL("https://github.com/Lewin671/local-voice-ime/releases/download/v0.7.0/app-v0.7.0-arm64-v8a.apk")),
            fetched
        )
    }

    @Test
    fun aReleaseWithoutAPackageForThisDeviceIsFoundButCannotBeDownloaded() {
        onRead = { answer("v0.7.0", abi = "x86_64") }
        val store = store()
        store.checkAndWait()
        assertEquals("0.7.0", store.found.value?.release?.version)
        assertNull(store.found.value?.download)
        assertNull(store.found.value?.apk)
    }

    @Test
    fun whatWasFoundAndDownloadedIsRememberedWithoutAsking() {
        store().run { checkAndWait(); downloadAndWait() }
        asked.clear()
        val later = store()
        assertEquals("0.7.0", later.found.value?.release?.version)
        assertNotNull(later.found.value?.apk)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun checkingAgainKeepsTheDownloadOfTheSameVersion() {
        val store = store()
        store.checkAndWait()
        val apk = store.downloadAndWait()
        store.checkAndWait()
        assertEquals(apk, store.found.value?.apk)
        assertEquals(1, fetched.size)
    }

    @Test
    fun aLaterVersionReplacesTheOneFoundBefore() {
        val store = store()
        store.checkAndWait()
        val old = store.downloadAndWait()
        onRead = { answer("v0.8.0") }
        store.checkAndWait()
        assertEquals("0.8.0", store.found.value?.release?.version)
        assertNull(store.found.value?.apk)
        assertFalse(old.exists())
    }

    @Test
    fun afterTheUpdateNothingOfItIsLeft() {
        store().run { checkAndWait(); downloadAndWait() }
        val updated = store(current = "v0.7.0-0-gabcdef12")
        assertNull(updated.found.value)
        assertFalse(dir.exists())
    }

    @Test
    fun deletingTheDownloadKeepsTheVersionOnOffer() {
        val store = store()
        store.checkAndWait()
        val apk = store.downloadAndWait()
        kotlinx.coroutines.runBlocking { store.found.value!!.download!!.delete() }
        assertFalse(apk.exists())
        assertEquals(State.Absent(0), store.found.value?.download?.state?.value)
        assertEquals("0.7.0", store(current = "v0.6.2").found.value?.release?.version)
    }
}
