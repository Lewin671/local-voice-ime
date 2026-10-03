/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Decides when the utterance in progress is decoded again for a preview.
 *
 * Every preview decodes the whole utterance so far, so previews are what dictation spends most of
 * its energy on. They are therefore spaced out
 *
 * - by at least [minIntervalMs];
 * - by [COST_FACTOR] times what the last decode took, which keeps the recognizer busy for at
 *   most a third of the time however long the utterance gets or however slow the device is;
 * - by twice the minimum after a decode that changed nothing: the speaker is pausing, and
 *   decoding the same words again only produces heat.
 *
 * Times are milliseconds of any monotonic clock. Pure Kotlin, unit-tested.
 */
class PartialPacer(private val minIntervalMs: Long = INTERVAL_MS) {

    private var lastEnd = 0L
    private var lastCost = 0L
    private var unchanged = false

    /** A new utterance began: its first preview is due one interval from [now]. */
    fun speechStarted(now: Long) {
        lastEnd = now
        lastCost = 0L
        unchanged = false
    }

    fun isDue(now: Long): Boolean {
        val pause = if (unchanged) minIntervalMs * 2 else minIntervalMs
        return now - lastEnd >= maxOf(pause, lastCost * COST_FACTOR)
    }

    /** A preview was decoded between [start] and [end]; [changed] is whether its text is new. */
    fun decoded(start: Long, end: Long, changed: Boolean) {
        lastEnd = end
        lastCost = end - start
        unchanged = !changed
    }

    companion object {
        /** About three previews per second while the words keep coming. */
        const val INTERVAL_MS = 300L

        /** With the device asking to save energy ([VoicePower]), previews come half as often. */
        const val SAVING_INTERVAL_MS = 600L

        const val COST_FACTOR = 2
    }
}
