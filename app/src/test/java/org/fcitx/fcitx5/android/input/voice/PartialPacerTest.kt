/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialPacerTest {

    /**
     * Speech that lasts [durationMs], checked for a preview every 100 ms (the size of an audio
     * chunk); each decode takes [costOf] the time since speech started and reports new text
     * until [silentFrom].
     * @return the times at which a preview was decoded
     */
    private fun simulate(
        pacer: PartialPacer,
        durationMs: Long,
        silentFrom: Long = Long.MAX_VALUE,
        costOf: (Long) -> Long = { 0L }
    ): List<Long> {
        val decodes = mutableListOf<Long>()
        pacer.speechStarted(0)
        var now = 0L
        while (now <= durationMs) {
            if (pacer.isDue(now)) {
                decodes += now
                val cost = costOf(now)
                pacer.decoded(now, now + cost, changed = now < silentFrom)
                now += cost
            }
            now += 100
        }
        return decodes
    }

    @Test
    fun firstPreviewComesOneIntervalAfterSpeechStarts() {
        val pacer = PartialPacer()
        pacer.speechStarted(1000)
        assertFalse(pacer.isDue(1200))
        assertTrue(pacer.isDue(1300))
    }

    @Test
    fun previewsFollowFastDecodesAtTheMinimumInterval() {
        assertEquals(listOf(300L, 600L, 900L, 1200L), simulate(PartialPacer(), 1200))
    }

    @Test
    fun decodingNeverTakesMoreThanAThirdOfTheTime() {
        // a slow device: decoding takes 10 % of the length of the audio
        val costOf = { now: Long -> now / 10 }
        val decodes = simulate(PartialPacer(), 20_000, costOf = costOf)
        // every decode is followed by twice as long without one
        decodes.zipWithNext { a, b ->
            val idle = b - (a + costOf(a))
            assertTrue("decode at $a, next at $b", idle >= 2 * costOf(a))
        }
        // and there is still a preview at least every 8 seconds at the very end
        assertTrue(decodes.zipWithNext { a, b -> b - a }.max() <= 8_000)
    }

    @Test
    fun pausesAreDecodedHalfAsOften() {
        // the first decode after the words stopped cannot know yet; the following ones wait longer
        assertEquals(
            listOf(300L, 600L, 900L, 1200L, 1800L, 2400L),
            simulate(PartialPacer(), 2400, silentFrom = 1000)
        )
    }

    @Test
    fun newWordsEndThePause() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.decoded(300, 300, changed = false)
        assertFalse(pacer.isDue(600))
        pacer.decoded(900, 900, changed = true)
        assertTrue(pacer.isDue(1200))
    }

    @Test
    fun nextUtteranceStartsOver() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.decoded(5000, 6000, changed = false)
        pacer.speechStarted(6100)
        assertFalse(pacer.isDue(6300))
        assertTrue(pacer.isDue(6400))
    }

    @Test
    fun savingEnergyHalvesTheRate() {
        assertEquals(
            listOf(600L, 1200L),
            simulate(PartialPacer(PartialPacer.SAVING_INTERVAL_MS), 1200)
        )
    }
}
