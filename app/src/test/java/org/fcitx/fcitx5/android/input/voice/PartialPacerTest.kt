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

    @Test
    fun stoppingSkipsEvenAnOverduePreviewWithoutChangingNormalPacing() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-20f)
        assertTrue(pacer.isDue(5000))
        assertFalse(pacer.isDue(5000, stopping = true))
        assertTrue(pacer.isDue(5000))
    }

    @Test
    fun longAudioSpacesPreviewsOutWithoutChangingTheFirstPreview() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-20f)
        assertTrue(pacer.isDue(300, audioDurationMs = 3000))
        pacer.decoded(300, 300, changed = true)
        pacer.heard(-20f)
        assertFalse(pacer.isDue(899, audioDurationMs = 6000))
        assertTrue(pacer.isDue(900, audioDurationMs = 6000))
        pacer.decoded(900, 900, changed = true)
        pacer.heard(-20f)
        assertFalse(pacer.isDue(1799, audioDurationMs = 20_000))
        assertTrue(pacer.isDue(1800, audioDurationMs = 20_000))
    }

    @Test
    fun audioLengthPacingKeepsTheComputeBudgetAndDoublesIntervalsInBatterySaver() {
        val normal = PartialPacer()
        val saving = PartialPacer(PartialPacer.SAVING_INTERVAL_MS)
        for (pacer in listOf(normal, saving)) {
            pacer.speechStarted(0)
            pacer.heard(-20f)
        }
        assertTrue(normal.isDue(900, audioDurationMs = 20_000))
        assertFalse(saving.isDue(1799, audioDurationMs = 20_000))
        assertTrue(saving.isDue(1800, audioDurationMs = 20_000))
        normal.decoded(900, 1900, changed = true)
        normal.heard(-20f)
        assertFalse(normal.isDue(3899, audioDurationMs = 20_000))
        assertTrue(normal.isDue(3900, audioDurationMs = 20_000))
    }

    @Test
    fun longUtterancesSpendLessWorkOnPrefixesWithTheSameAudioAndModel() {
        fun prefixWork(adaptive: Boolean, costOf: (Long) -> Long): Long {
            val pacer = PartialPacer()
            pacer.speechStarted(0)
            var now = 0L
            var work = 0L
            while (now <= 20_000) {
                pacer.heard(-20f)
                if (pacer.isDue(now, audioDurationMs = if (adaptive) now else 0)) {
                    work += now
                    val cost = costOf(now)
                    pacer.decoded(now, now + cost, changed = true)
                    now += cost
                }
                now += 100
            }
            return work
        }
        assertTrue(prefixWork(true) { 0 } < prefixWork(false) { 0 } / 2)
        // On slow hardware the existing decode-cost limit already dominates: no extra work.
        assertTrue(prefixWork(true) { it / 10 } <= prefixWork(false) { it / 10 })
    }

    @Test
    fun latestQuietAudioSuppressesAStaleVoicedChunkBeforeEndpointing() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-20f)
        pacer.decoded(300, 300, changed = true)
        pacer.heard(-20f)
        pacer.heard(-55f)
        assertFalse(pacer.isDue(600))
        assertFalse(pacer.isDue(1700))
        // A loud transient must never suppress quieter speech indefinitely.
        assertTrue(pacer.isDue(1800))
    }

    @Test
    fun resumedSpeechLeavesUnchangedBackoffButKeepsTheLongAudioInterval() {
        for (base in listOf(300L, 600L)) {
            val pacer = PartialPacer(base)
            pacer.speechStarted(0)
            pacer.heard(-20f)
            pacer.decoded(1000, 1000, changed = false)
            pacer.heard(-55f)
            pacer.heard(-20f)
            val due = 1000 + base * 3
            assertFalse(pacer.isDue(due - 1, audioDurationMs = 12_000))
            assertTrue(pacer.isDue(due, audioDurationMs = 12_000))
        }
    }

    @Test
    fun quietStateSurvivesAFallbackDecodeSoTheNextWordsCanResume() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-20f)
        pacer.decoded(300, 300, changed = true)
        pacer.heard(-55f)
        assertTrue(pacer.isDue(1800, audioDurationMs = 12_000))
        pacer.decoded(1800, 1800, changed = false)
        pacer.heard(-20f)
        assertTrue(pacer.isDue(2700, audioDurationMs = 12_000))
    }

    @Test
    fun resumptionDoesNotBypassComputeBudgetOrStopping() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-20f)
        pacer.decoded(1000, 2000, changed = false)
        pacer.heard(-55f)
        pacer.heard(-20f)
        assertFalse(pacer.isDue(3999, audioDurationMs = 12_000))
        assertTrue(pacer.isDue(4000, audioDurationMs = 12_000))
        assertFalse(pacer.isDue(4000, stopping = true, audioDurationMs = 12_000))
    }

    @Test
    fun continuedVoicedAudioRetainsUnchangedBackoffAndSavingPolicy() {
        for (base in listOf(300L, 600L)) {
            val pacer = PartialPacer(base)
            pacer.speechStarted(0)
            pacer.heard(-20f)
            pacer.decoded(1000, 1000, changed = false)
            repeat(10) { pacer.heard(-20f) }
            val due = 1000 + base * 6
            assertFalse(pacer.isDue(due - 1, audioDurationMs = 12_000))
            assertTrue(pacer.isDue(due, audioDurationMs = 12_000))
        }
    }

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
            pacer.heard(-20f)
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
        pacer.heard(-20f)
        assertFalse(pacer.isDue(1200))
        assertTrue(pacer.isDue(1300))
    }

    @Test
    fun aPauseIsNotDecoded() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.heard(-25f)
        assertTrue(pacer.isDue(300))
        pacer.decoded(300, 300, changed = true)
        // the room, 30 dB below the voice
        pacer.heard(-55f)
        assertFalse(pacer.isDue(600))
        assertFalse(pacer.isDue(1700))
        // the voice again, somewhat softer
        pacer.heard(-40f)
        assertTrue(pacer.isDue(1700))
    }

    @Test
    fun loudnessAloneNeverHoldsAPreviewBackForLong() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        // a door slams, then somebody speaks softly
        pacer.heard(-5f)
        pacer.decoded(300, 300, changed = true)
        pacer.heard(-40f)
        assertFalse(pacer.isDue(1700))
        assertTrue(pacer.isDue(1800))
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
        pacer.heard(-20f)
        assertTrue(pacer.isDue(1200))
    }

    @Test
    fun nextUtteranceStartsOver() {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        pacer.decoded(5000, 6000, changed = false)
        pacer.speechStarted(6100)
        pacer.heard(-20f)
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
