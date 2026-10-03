/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.voice.VoiceModels.Error
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VoiceModelStoreTest {

    private val model = VoiceModel(
        id = "test", name = "Test", host = "example.invalid", baseUrl = "/",
        files = listOf(VoiceModel.File("a.onnx", 10, "aa"), VoiceModel.File("b.txt", 4, "bb"))
    )

    private val dir = File(Files.createTempDirectory("voice-model-store").toFile(), "test")

    /** What the fake download of one file does; replaced by the tests. */
    private var onFetch: (dest: File, size: Long) -> Unit = { dest, size -> dest.writeBytes(ByteArray(size.toInt())) }

    private val running = AtomicInteger()
    private val overlapped = AtomicInteger()
    private val fetched = Collections.synchronizedList(mutableListOf<String>())

    private fun fetch(url: URL, dest: File, size: Long, sha256: String, onProgress: (Long) -> Unit) {
        if (running.incrementAndGet() > 1) overlapped.incrementAndGet()
        try {
            fetched += dest.name
            onProgress(0)
            onFetch(dest, size)
            onProgress(size)
        } finally {
            running.decrementAndGet()
        }
    }

    private fun store() = VoiceModelStore(model, dir, ::fetch)

    private fun VoiceModelStore.await(what: String, condition: (State) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition(state.value)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what; state is ${state.value}" }
            Thread.sleep(5)
        }
    }

    /** A download that hangs in the network until [release] is counted down. */
    private class Stall {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hang() {
            entered.countDown()
            release.await()
        }
    }

    @After
    fun tearDown() {
        dir.parentFile!!.deleteRecursively()
    }

    @Test
    fun downloadsEveryFileAndIsInstalledAfterwards() {
        val store = store()
        assertEquals(State.Absent(0), store.state.value)
        store.download()
        store.await("installation") { it == State.Installed }
        assertEquals(listOf("a.onnx", "b.txt"), fetched)
        // a new process finds it installed
        assertEquals(State.Installed, store().state.value)
    }

    @Test
    fun aFailureIsReportedAndWhatArrivedIsKept() {
        onFetch = { dest, _ ->
            File(dest.path + ".part").writeBytes(ByteArray(3))
            throw IOException("connection reset")
        }
        val store = store()
        store.download()
        store.await("the failure") { it is State.Absent }
        assertEquals(State.Absent(3, Error.Network), store.state.value)

        onFetch = { _, _ -> throw VoiceModelFetch.WrongContentException("other content") }
        store.download()
        store.await("the rejection") { it is State.Absent }
        assertEquals(Error.Content, (store.state.value as State.Absent).error)
    }

    @Test
    fun aPausedDownloadThatFailsLaterDoesNotDisturbTheResumedOne() {
        val stall = Stall()
        onFetch = { _, _ ->
            stall.hang()
            // what a read blocked on a dead connection does, long after the pause
            throw IOException("timeout")
        }
        val store = store()
        store.download()
        assertTrue(stall.entered.await(5, TimeUnit.SECONDS))
        store.pause()
        assertEquals(State.Absent(0), store.state.value)

        onFetch = { dest, size -> dest.writeBytes(ByteArray(size.toInt())) }
        store.download()
        assertEquals(State.Downloading(0), store.state.value)
        stall.release.countDown()
        // never "failed": the only states from here on are downloading and installed
        store.await("installation") {
            assertFalse("stale failure published: $it", it is State.Absent)
            it == State.Installed
        }
        assertEquals(0, overlapped.get())
    }

    @Test
    fun pausingAQueuedDownloadStillWaitsForTheOneBeforeIt() {
        val stall = Stall()
        onFetch = { _, _ -> stall.hang() }
        val store = store()
        store.download()
        assertTrue(stall.entered.await(5, TimeUnit.SECONDS))
        store.pause()
        store.download()   // queued behind the stalled one
        store.pause()      // cancelled before it ran
        onFetch = { dest, size -> dest.writeBytes(ByteArray(size.toInt())) }
        store.download()
        Thread.sleep(100)
        assertEquals("a download started while the stalled one was still running", 1, fetched.size)
        stall.release.countDown()
        store.await("installation") { it == State.Installed }
        assertEquals(0, overlapped.get())
    }

    @Test
    fun aDownloadStartedDuringDeletionSurvivesIt() {
        val stall = Stall()
        onFetch = { dest, _ ->
            File(dest.path + ".part").writeBytes(ByteArray(3))
            stall.hang()
        }
        val store = store()
        store.download()
        assertTrue(stall.entered.await(5, TimeUnit.SECONDS))
        store.pause()

        val deleted = CountDownLatch(1)
        thread { runBlocking { store.delete() }; deleted.countDown() }
        store.await("the deletion to be requested") { it == State.Absent(0) }
        Thread.sleep(50)
        assertEquals("deletion finished while the old download was still writing", 1, deleted.count)

        onFetch = { dest, size -> dest.writeBytes(ByteArray(size.toInt())) }
        store.download()
        stall.release.countDown()
        assertTrue(deleted.await(5, TimeUnit.SECONDS))
        store.await("installation") { it == State.Installed }
        assertEquals(0, overlapped.get())
        assertEquals(State.Installed, store().state.value)
        assertFalse(File(dir, "a.onnx.part").exists())
    }

    @Test
    fun deletingAnInstalledModelRemovesIt() {
        val store = store()
        store.download()
        store.await("installation") { it == State.Installed }
        runBlocking { store.delete() }
        assertEquals(State.Absent(0), store.state.value)
        assertFalse(dir.exists())
        assertEquals(State.Absent(0), store().state.value)
    }
}
