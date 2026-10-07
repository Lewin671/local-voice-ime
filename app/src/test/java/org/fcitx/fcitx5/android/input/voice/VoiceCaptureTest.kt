/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class VoiceCaptureTest {
    private class Source(val readBlock: suspend (FloatArray) -> Int) : AudioSource {
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override suspend fun read(buffer: FloatArray) = readBlock(buffer)
        override fun stop() { stops++ }
    }

    @Test
    fun microphoneClosesBeforeQueuedAudioIsDecodedWithoutLosingAnySamples() = runBlocking {
        val random = Random(671)
        val input = FloatArray(32_007) { Float.fromBits(random.nextInt()) }
        var offset = 0
        val source = Source { buffer ->
            if (offset == input.size) -1 else {
                val n = minOf(buffer.size, input.size - offset)
                input.copyInto(buffer, 0, offset, offset + n)
                offset += n
                n
            }
        }
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        val capture = VoiceCapture(source)
        capture.start()
        capture.pump(1600, chunks, { true }) {}
        assertEquals(1, source.stops) // consumer has not even started
        chunks.close()
        val received = mutableListOf<Float>()
        for (chunk in chunks) received.addAll(chunk.toList())
        assertEquals(input.size, received.size)
        input.indices.forEach { assertEquals(input[it].toRawBits(), received[it].toRawBits()) }
        capture.close() // outer cleanup is harmless
        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
    }

    @Test
    fun stopDuringReadPreservesTheLastPartialChunkAndDoesNotReadAgain() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        var keepReading = true
        var reads = 0
        val source = Source { buffer ->
            reads++
            reading.complete(Unit)
            finishRead.await()
            buffer[0] = 1f
            buffer[1] = -1f
            2
        }
        val capture = VoiceCapture(source)
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        val reader = async { capture.pump(1600, chunks, { keepReading }) {} }
        reading.await()
        keepReading = false
        finishRead.complete(Unit)
        reader.await()
        assertArrayEquals(floatArrayOf(1f, -1f), chunks.receive(), 0f)
        assertTrue(chunks.tryReceive().isFailure)
        assertEquals(1, reads)
        assertEquals(1, source.stops)
    }

    @Test
    fun aSourceThatFailsIsToldApartFromOneThatIsExhaustedOrStopped() = runBlocking {
        class Failing(val code: Int?) : AudioSource {
            var reads = 0
            override var error: Int? = null
            override fun start() {}
            override suspend fun read(buffer: FloatArray): Int {
                if (++reads <= 2) return buffer.size
                error = code
                return -1
            }
            override fun stop() {}
        }
        // the microphone died: what was read before is kept, and the error is there to be said
        val failed = VoiceCapture(Failing(-6))
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        failed.pump(1600, chunks, { true }) {}
        assertEquals(-6, failed.error)
        assertTrue(failed.endedBySource)
        assertEquals(2, failed.chunkCount)
        assertEquals(3200L, failed.samples)
        assertEquals(1600, chunks.receive().size)
        assertEquals(1600, chunks.receive().size)
        // a file that is over
        val exhausted = VoiceCapture(Failing(null))
        exhausted.pump(1600, Channel(Channel.UNLIMITED), { true }) {}
        assertEquals(null, exhausted.error)
        assertTrue(exhausted.endedBySource)
        // a stop that was asked for
        var left = 1
        val stopped = VoiceCapture(Failing(-6))
        stopped.pump(1600, Channel(Channel.UNLIMITED), { left-- > 0 }) {}
        assertEquals(null, stopped.error)
        assertFalse(stopped.endedBySource)
        assertEquals(1, stopped.chunkCount)
    }

    @Test
    fun emptyReadsAreCounted() = runBlocking {
        var reads = 0
        val capture = VoiceCapture(Source { if (++reads <= 3) 0 else -1 })
        capture.pump(1600, Channel(Channel.UNLIMITED), { true }) {}
        assertEquals(3, capture.emptyReads)
        assertEquals(0, capture.chunkCount)
    }

    @Test
    fun zeroLengthReadsDoNotEnqueueAudioOrLevels() = runBlocking {
        var reads = 0
        var levels = 0
        val source = Source { if (++reads == 1) 0 else -1 }
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        VoiceCapture(source).pump(1600, chunks, { true }) { levels++ }
        assertEquals(2, reads)
        assertEquals(0, levels)
        assertTrue(chunks.tryReceive().isFailure)
        assertEquals(1, source.stops)
    }

    @Test
    fun cancellationDuringSuspendedReadReleasesCaptureOnce() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val source = Source {
            reading.complete(Unit)
            CompletableDeferred<Int>().await()
        }
        val capture = VoiceCapture(source)
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        val reader = launch { capture.pump(1600, chunks, { true }) {} }
        reading.await()
        reader.cancel()
        reader.join()
        capture.close()
        assertEquals(1, source.stops)
        assertTrue(chunks.tryReceive().isFailure)
    }

    @Test
    fun readFailureReleasesCaptureAndKeepsEarlierAudio() = runBlocking {
        var reads = 0
        val failure = IllegalStateException("read failed")
        val source = Source { buffer ->
            if (++reads == 2) throw failure
            buffer[0] = 42f
            1
        }
        val capture = VoiceCapture(source)
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        try {
            capture.pump(1600, chunks, { true }) {}
            fail("Expected read failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertArrayEquals(floatArrayOf(42f), chunks.receive(), 0f)
        capture.close()
        assertEquals(1, source.stops)
    }

    @Test
    fun cleanupBeforeReaderStartsAndAfterFailedStopDoesNotReleaseTwice() {
        var stops = 0
        val source = object : AudioSource {
            override fun start() {}
            override suspend fun read(buffer: FloatArray) = -1
            override fun stop() { stops++; throw IllegalStateException("stop failed") }
        }
        val capture = VoiceCapture(source)
        assertTrue(runCatching { capture.close() }.isFailure)
        capture.close()
        assertEquals(1, stops)
    }

    @Test
    fun readersSignalEofBeforeReleasingTheSource() = runBlocking {
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        var signalled = false
        var stops = 0
        val source = object : AudioSource {
            override fun start() {}
            override suspend fun read(buffer: FloatArray) = -1
            override fun stop() {
                assertTrue(signalled)
                assertTrue(chunks.tryReceive().isClosed)
                stops++
            }
        }
        VoiceCapture(source).pump(1600, chunks, { true }, onStopped = {
            signalled = true
            chunks.close()
        }) {}
        assertEquals(1, stops)
    }

    @Test
    fun failedEndNotificationStillReleasesCapture() = runBlocking {
        val source = Source { -1 }
        val capture = VoiceCapture(source)
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        assertTrue(runCatching {
            capture.pump(1600, chunks, { true }, onStopped = {
                throw IllegalStateException("notification failed")
            }) {}
        }.isFailure)
        capture.close()
        assertEquals(1, source.stops)
    }
}
