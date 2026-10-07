/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceModelResidencyTest {

    private val minute = 60_000L
    private val gb = 1L shl 30

    private val residency = VoiceModelResidency(
        minIdleMs = 3 * minute, maxIdleMs = 10 * minute, recheckMs = minute, spareBytes = 2 * gb
    )

    @Test
    fun staysForTheMinimumWhateverTheMemory() {
        assertEquals(3 * minute, residency.keepFor(0, availableBytes = 0, lowMemory = true))
        assertEquals(minute, residency.keepFor(2 * minute, availableBytes = 0, lowMemory = true))
        assertEquals(1, residency.keepFor(3 * minute - 1, availableBytes = 8 * gb, lowMemory = false))
    }

    @Test
    fun isFreedAfterTheMinimumWithoutMemoryToSpare() {
        assertEquals(0, residency.keepFor(3 * minute, availableBytes = 2 * gb - 1, lowMemory = false))
        assertEquals(0, residency.keepFor(3 * minute, availableBytes = 8 * gb, lowMemory = true))
        // no answer from the system reads as nothing available
        assertEquals(0, residency.keepFor(3 * minute, availableBytes = 0, lowMemory = false))
    }

    @Test
    fun staysWithMemoryToSpareAndAsksAgain() {
        assertEquals(minute, residency.keepFor(3 * minute, availableBytes = 2 * gb, lowMemory = false))
        assertEquals(minute, residency.keepFor(7 * minute, availableBytes = 8 * gb, lowMemory = false))
    }

    @Test
    fun isFreedOnceMemoryGetsScarceWhileItStays() {
        var idle = 3 * minute
        val available = listOf(4 * gb, 3 * gb, 1 * gb)
        var checks = 0
        for (bytes in available) {
            checks++
            val wait = residency.keepFor(idle, bytes, lowMemory = false)
            if (wait == 0L) break
            idle += wait
        }
        assertEquals(3, checks)
        assertEquals(5 * minute, idle)
    }

    @Test
    fun neverStaysBeyondTheMaximum() {
        assertEquals(30_000L, residency.keepFor(10 * minute - 30_000, availableBytes = 8 * gb, lowMemory = false))
        assertEquals(0, residency.keepFor(10 * minute, availableBytes = 8 * gb, lowMemory = false))
        // the device slept through the timer: the idle time is far beyond every limit
        assertEquals(0, residency.keepFor(8 * 60 * minute, availableBytes = 8 * gb, lowMemory = false))
    }

    @Test
    fun followedFromTheLastUseItIsFreedAtTheMaximum() {
        var idle = 0L
        var checks = 0
        while (true) {
            val wait = residency.keepFor(idle, availableBytes = 8 * gb, lowMemory = false)
            if (wait == 0L) break
            idle += wait
            checks++
        }
        assertEquals(10 * minute, idle)
        // once for the minimum, then every minute
        assertEquals(8, checks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAMaximumBelowTheMinimum() {
        VoiceModelResidency(minIdleMs = 2, maxIdleMs = 1, recheckMs = 1, spareBytes = 0)
    }
}
