/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class VoiceAudioBufferTest {
    @Test
    fun compactionAndGrowthPreserveEverySampleAndSnapshot() {
        val buffer = VoiceAudioBuffer(8)
        buffer.append(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f))
        val snapshot = buffer.toArray()
        buffer.dropFirst(4)
        buffer.append(floatArrayOf(6f, 7f, 8f, 9f)) // compaction without growth
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f, 8f, 9f), buffer.toArray(), 0f)
        buffer.dropFirst(1)
        buffer.append(FloatArray(12) { (it + 10).toFloat() }) // growth with a nonzero head
        assertArrayEquals(FloatArray(17) { (it + 5).toFloat() }, buffer.toArray(), 0f)
        assertArrayEquals(FloatArray(6) { it.toFloat() }, snapshot, 0f)
        buffer.dropFirst(buffer.size)
        buffer.append(floatArrayOf(-1f))
        assertArrayEquals(floatArrayOf(-1f), buffer.toArray(), 0f)
    }

    @Test
    fun randomizedCaptureBacklogDropsAndVadWindowsMatchReferenceExactly() {
        val random = Random(671)
        val buffer = VoiceAudioBuffer(64)
        val reference = mutableListOf<Float>()
        val window = FloatArray(32)
        repeat(10_000) {
            if (random.nextBoolean() || reference.isEmpty()) {
                val chunk = FloatArray(random.nextInt(1, 161)) {
                    Float.fromBits(random.nextInt())
                }
                buffer.append(chunk)
                reference.addAll(chunk.toList())
            } else {
                val n = random.nextInt(reference.size + 1)
                buffer.dropFirst(n)
                reference.subList(0, n).clear()
            }
            assertEquals(reference.size, buffer.size)
            assertBits(reference.toFloatArray(), buffer.toArray())
            if (buffer.size >= window.size) {
                val from = random.nextInt(buffer.size - window.size + 1)
                buffer.copyInto(window, from)
                assertBits(reference.subList(from, from + window.size).toFloatArray(), window)
                assertBits(window, buffer.copyOfRange(from, from + window.size))
            }
        }
    }

    private fun assertBits(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i].toRawBits(), actual[i].toRawBits())
    }
}
