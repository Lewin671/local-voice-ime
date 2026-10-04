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
 * - progressively farther apart after three seconds of audio, up to three times that interval:
 *   re-decoding a long prefix is expensive even on a device that decodes it quickly;
 * - by [COST_FACTOR] times what the last decode took, which keeps the recognizer busy for at
 *   most a third of the time however long the utterance gets or however slow the device is;
 * - by twice the current interval after a decode that changed nothing, until speech resumes
 *   after quiet audio: a pause must not leave the next words waiting at the slower rate;
 * - until something was said since the last one: what [heard] reports as far quieter than the
 *   utterance itself is a pause, and brings no new words. As loudness can mislead (a bang makes
 *   everything after it look quiet), a preview is decoded after [QUIET_MAX_WAIT_MS] regardless.
 *
 * Times are milliseconds of any monotonic clock. Pure Kotlin, unit-tested.
 */
class PartialPacer(private val minIntervalMs: Long = INTERVAL_MS) {

    private var lastEnd = 0L
    private var lastCost = 0L
    private var unchanged = false

    // Loudest audio of the utterance and whether the latest observed audio came near it.
    private var peakDb = Float.NEGATIVE_INFINITY
    private var spoken = false
    private var wasQuiet = false

    /** A new utterance began: its first preview is due one interval from [now]. */
    fun speechStarted(now: Long) {
        lastEnd = now
        lastCost = 0L
        unchanged = false
        peakDb = Float.NEGATIVE_INFINITY
        spoken = false
        wasQuiet = false
    }

    /** Audio of the utterance arrived; [db] is its level (RMS, decibels). */
    fun heard(db: Float) {
        if (db > peakDb) peakDb = db
        val voiced = db >= peakDb - QUIET_BELOW_PEAK_DB
        if (voiced && wasQuiet) unchanged = false
        wasQuiet = !voiced
        // A voiced chunk must not keep authorizing previews after the speaker becomes quiet.
        // Keep the fallback below: relative loudness alone can misclassify a soft voice.
        spoken = voiced
    }

    /** Once capture is stopping, only the full final decode is useful. */
    fun isDue(now: Long, stopping: Boolean = false, audioDurationMs: Long = 0): Boolean {
        if (stopping) return false
        if (!spoken && now - lastEnd < QUIET_MAX_WAIT_MS) return false
        val interval = maxOf(minIntervalMs, minOf(minIntervalMs * MAX_INTERVAL_FACTOR,
            audioDurationMs * minIntervalMs / FULL_RATE_AUDIO_MS))
        val pause = if (unchanged) interval * 2 else interval
        return now - lastEnd >= maxOf(pause, lastCost * COST_FACTOR)
    }

    /** A preview was decoded between [start] and [end]; [changed] is whether its text is new. */
    fun decoded(start: Long, end: Long, changed: Boolean) {
        lastEnd = end
        lastCost = end - start
        unchanged = !changed
        spoken = false
    }

    companion object {
        /** About three previews per second for the beginning of an utterance. */
        const val INTERVAL_MS = 300L

        /** With the device asking to save energy ([VoicePower]), previews come half as often. */
        const val SAVING_INTERVAL_MS = 600L

        const val COST_FACTOR = 2

        const val FULL_RATE_AUDIO_MS = 3000L
        const val MAX_INTERVAL_FACTOR = 3L

        /** Audio this far below the loudest of the utterance is taken for a pause. */
        const val QUIET_BELOW_PEAK_DB = 20f

        /** Longer than any pause that does not end the utterance. */
        const val QUIET_MAX_WAIT_MS = 1500L
    }
}
