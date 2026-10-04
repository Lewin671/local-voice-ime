/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Keeps a sentence together when the speaker pauses in the middle of it.
 *
 * The VAD ends an utterance after a short pause, and the recognizer closes whatever it is given
 * with a full stop, so somebody who stops to think would get two sentences where one was meant.
 * Waiting longer before ending an utterance would fix that, but the recognizer would be decoding
 * the same words over and over during the pause. Instead:
 *
 * - an utterance is still inserted after the short pause, with its full stop held back;
 * - when speech resumes within [maxGapSeconds], the audio of both is transcribed as one piece,
 *   and the result replaces what was inserted: the recognizer then punctuates the pause knowing
 *   how the sentence goes on;
 * - when it does not, the sentence is over and gets its full stop.
 *
 * Nothing is decoded while nobody speaks; a resumed sentence costs one decode of its earlier
 * part, which is why a sentence is not extended beyond [maxSeconds] of audio.
 *
 * Positions are sample indices in the recording. Pure Kotlin, unit-tested.
 */
class VoiceSentence(sampleRate: Int, maxGapSeconds: Float, maxSeconds: Float) {

    private val maxGap = (maxGapSeconds * sampleRate).toLong()
    private val maxSamples = (maxSeconds * sampleRate).toInt()

    // of the sentence that may still go on
    private var audio: FloatArray? = null
    private var end = 0L
    private var stop = ""

    /** Whether an utterance was inserted that the next one may continue. */
    val isOpen get() = audio != null

    /** Whether an utterance of [samples] samples that begins at [start] continues the sentence. */
    fun continuesWith(start: Long, samples: Int): Boolean {
        val audio = audio ?: return false
        return start - end <= maxGap && audio.size + samples <= maxSamples
    }

    /** Whether the pause has become too long at [position] for the sentence to go on. */
    fun isOver(position: Long) = isOpen && position - end > maxGap

    /** What to transcribe for an utterance: the sentence so far and [samples], if one is open. */
    fun join(samples: FloatArray): FloatArray = audio?.plus(samples) ?: samples

    /**
     * [text] was recognized from [audio], which ends at [end] and may be continued.
     * @return the text to insert: without its closing full stop, which is held back
     */
    fun keep(audio: FloatArray, end: Long, text: String): String {
        val bare = VoiceText.stripTrailingFullStop(text)
        this.audio = audio
        this.end = end
        stop = text.trimEnd().substring(bare.length)
        return bare
    }

    /**
     * The sentence is over.
     * @return the full stop that was held back, if any
     */
    fun close(): String {
        audio = null
        return stop.also { stop = "" }
    }
}
