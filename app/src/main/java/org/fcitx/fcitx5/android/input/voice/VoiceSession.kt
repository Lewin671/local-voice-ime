/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One dictation session: captures audio, splits it into utterances with VAD, and transcribes them.
 *
 * "Simulated streaming": the recognizer is a non-streaming model, so while the user is still
 * speaking the current utterance is re-decoded periodically to produce [Listener.onPartial]
 * previews; when the VAD decides the utterance is over, it is decoded once more as a whole
 * and delivered via [Listener.onFinal].
 *
 * All [Listener] callbacks are invoked on the main thread.
 */
class VoiceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val source: AudioSource,
    /** Trailing silence (seconds) that ends an utterance. */
    private val minSilence: Float,
    /** Stop by itself after this long without speech; 0 to keep listening. */
    private val idleTimeoutMs: Long,
    /** A pause (seconds) up to which the next utterance continues the sentence, see [VoiceSentence]. */
    sentenceGap: Float,
    /** Shortest time between two previews, see [PartialPacer]. */
    private val previewIntervalMs: Long,
    private val listener: Listener
) {

    enum class State {
        /** Recording already, but the speech model is still being loaded. */
        Preparing,
        Listening,

        /** Recording has stopped; what was said last is being transcribed. */
        Finishing,
        Stopped
    }

    interface Listener {
        fun onState(state: State) {}

        /** Preview of the utterance being spoken; replaced by the next partial or final. */
        fun onPartial(text: String) {}

        /**
         * A finished utterance and its audio. If it [continues] the utterance before it, [text]
         * is the transcript of both and replaces what was delivered for that one. A closing full
         * stop is not part of [text]; it comes with [onSentenceEnd].
         */
        fun onFinal(text: String, samples: FloatArray, continues: Boolean) {}

        /**
         * No utterance will continue the last one any more: the pause got too long, or the
         * session is ending. [stop] is the full stop that was held back, if there was one.
         */
        fun onSentenceEnd(stop: String) {}

        /** Input level in [0, 1], for visual feedback. */
        fun onLevel(level: Float) {}

        fun onError(e: Throwable) {}
    }

    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var discard = false

    @Volatile
    private var sentenceCloseRequested = false

    val isRunning get() = job?.isActive == true

    /** Whether the session ended by itself because nobody spoke for the idle timeout. */
    @Volatile
    var endedByIdleTimeout = false
        private set

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            try {
                run()
            } catch (e: Throwable) {
                if (isActive) {
                    Timber.w(e, "Voice session failed")
                    emit { onError(e) }
                }
            } finally {
                withContext(NonCancellable) {
                    source.stop()
                    emit { onState(State.Stopped) }
                }
            }
        }
    }

    /**
     * Stop capturing; pending speech is still transcribed and delivered, unless [discard] is set.
     */
    fun stop(discard: Boolean = false) {
        this.discard = discard
        stopRequested = true
    }

    /**
     * Something else was typed after the last utterance: the next one starts on its own, and no
     * full stop is added to the last.
     */
    fun closeSentence() {
        sentenceCloseRequested = true
    }

    private suspend inline fun emit(crossinline block: Listener.() -> Unit) =
        withContext(Dispatchers.Main.immediate) { listener.block() }

    private suspend fun run() = coroutineScope {
        // Start capturing right away: loading the model can take seconds after a cold start,
        // and whatever is said meanwhile is queued in `chunks` instead of being lost.
        source.start()
        val cold = !VoiceEngine.isLoaded
        emit { onState(if (cold) State.Preparing else State.Listening) }
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        // Only visual feedback may be conflated. Audio remains lossless even if the main
        // thread stalls; the microphone reader must never wait for a waveform redraw.
        val levels = Channel<Float>(Channel.CONFLATED)
        val levelReporter = launch {
            for (level in levels) emit { onLevel(level) }
        }
        // reader: never waits for decoding, so no audio is dropped on slow devices
        val reader = launch(Dispatchers.IO) {
            val chunkSize = VoiceEngine.SAMPLE_RATE / 10
            var lastLevel = 0f
            try {
                while (isActive && !stopRequested) {
                    val buf = FloatArray(chunkSize)
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    chunks.send(chunk)
                    // reported from here, so that the level is live even while the model loads
                    val level = levelOf(chunk).let { if (it < QUIET_LEVEL && !speaking) 0f else it }
                    // nothing to report while it stays silent: the UI has nothing to redraw then
                    if (level > 0f || lastLevel > 0f) levels.trySend(level)
                    lastLevel = level
                }
            } finally {
                stopRequested = true
                chunks.close()
                levels.close()
            }
        }

        val vad = try {
            VoiceEngine.ensureLoaded(context)
            VoiceEngine.createVad(context, minSilence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reader.cancel()
            throw VoiceException(VoiceException.Kind.ModelLoadFailed, e)
        }
        if (cold) emit { onState(State.Listening) }
        try {
            consume(chunks, vad)
            reader.join()
            levelReporter.join()
            emit { onState(State.Finishing) }
            vad.flush()
            drain(vad)
            if (sentence.isOpen) endSentence()
        } finally {
            vad.release()
        }
    }

    // Audio that has not been finalized yet: the utterance in progress, or a short lead-in while
    // waiting for speech. `bufferStart` is the position of buffer[0] in the whole recording, which
    // is the coordinate system of the segments reported by the VAD.
    private val buffer = VoiceAudioBuffer()
    private val vadWindow = FloatArray(VoiceEngine.VAD_WINDOW)
    private var bufferStart = 0L
    private var fed = 0

    // read by the reader coroutine
    @Volatile
    private var speaking = false
    private var lastPartial = ""
    private val pacer = PartialPacer(previewIntervalMs)
    private val sentence = VoiceSentence(VoiceEngine.SAMPLE_RATE, sentenceGap, MAX_SENTENCE_SECONDS)

    private suspend fun endSentence() {
        val stop = sentence.close()
        emit { onSentenceEnd(stop) }
    }

    private fun closeSentenceIfRequested() {
        if (sentenceCloseRequested) {
            sentenceCloseRequested = false
            sentence.close()
        }
    }

    private fun dropFromBuffer(n: Int) {
        val drop = n.coerceIn(0, buffer.size)
        buffer.dropFirst(drop)
        bufferStart += drop
        fed = (fed - drop).coerceAtLeast(0)
    }

    /** Transcribe and deliver the utterances that the VAD has completed. */
    private suspend fun drain(vad: Vad) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            // The VAD cuts tightly around the speech. Recognition is more reliable with a little
            // audio before the first and after the last sound, which is still in the buffer.
            val start = (segment.start - bufferStart).toInt()
            val end = start + segment.samples.size
            val samples = if (start >= 0 && end <= buffer.size) {
                buffer.copyOfRange(maxOf(0, start - MARGIN_SAMPLES), minOf(buffer.size, end + MARGIN_SAMPLES))
            } else segment.samples
            // keep what was recorded after this utterance: it may be the start of the next one
            dropFromBuffer(end)
            speaking = vad.isSpeechDetected()
            // what is left in the buffer belongs to the next utterance
            pacer.speechStarted(SystemClock.elapsedRealtime())
            closeSentenceIfRequested()
            if (sentence.isOpen && !sentence.continuesWith(segment.start.toLong(), samples.size)) endSentence()
            // after a short pause, the sentence so far is transcribed again together with this
            val continues = sentence.isOpen
            val audio = sentence.join(samples)
            var text = if (discard) "" else VoiceEngine.transcribe(audio)
            if (segment.samples.size >= FORCED_SPLIT_SAMPLES) {
                // cut off by the length limit, not by a pause: the sentence goes on at once
                text = VoiceText.stripTrailingFullStop(text)
                sentence.close()
            } else if (text.isNotEmpty()) {
                text = sentence.keep(audio, segment.start.toLong() + segment.samples.size, text)
            }
            lastPartial = ""
            if (text.isNotEmpty()) emit { onFinal(text, samples, continues) } else emit { onPartial("") }
        }
    }

    private suspend fun consume(chunks: Channel<FloatArray>, vad: Vad) {
        val window = VoiceEngine.VAD_WINDOW
        var lastSpeechAt = SystemClock.elapsedRealtime()

        for (first in chunks) {
            // take everything that queued up while we were decoding
            var chunk: FloatArray? = first
            var sumSquares = 0.0
            var count = 0
            while (chunk != null) {
                buffer.append(chunk)
                for (v in chunk) sumSquares += v * v
                count += chunk.size
                chunk = chunks.tryReceive().getOrNull()
            }

            closeSentenceIfRequested()
            if (!speaking && sentence.isOver(bufferStart + fed)) endSentence()

            while (fed + window <= buffer.size) {
                // sherpa-onnx 1.13.8 copies the input before acceptWaveform returns.
                buffer.copyInto(vadWindow, fed)
                vad.acceptWaveform(vadWindow)
                fed += window
                if (!speaking && vad.isSpeechDetected()) {
                    speaking = true
                    pacer.speechStarted(SystemClock.elapsedRealtime())
                }
            }
            drain(vad)
            val now = SystemClock.elapsedRealtime()
            if (!speaking) {
                // only keep a short lead-in while waiting for speech
                dropFromBuffer(buffer.size - 10 * window)
                if (idleTimeoutMs > 0 && now - lastSpeechAt > idleTimeoutMs) {
                    // don't keep the microphone open when nobody is talking
                    endedByIdleTimeout = true
                    stopRequested = true
                }
                continue
            }
            lastSpeechAt = now
            pacer.heard(dbOf(sumSquares, count))
            if (pacer.isDue(now, stopping = stopRequested,
                    audioDurationMs = buffer.size * 1000L / VoiceEngine.SAMPLE_RATE)) {
                val text = VoiceEngine.transcribe(buffer.toArray())
                val changed = text != lastPartial
                pacer.decoded(now, SystemClock.elapsedRealtime(), changed)
                if (changed) {
                    lastPartial = text
                    emit { onPartial(text) }
                }
            }
        }
    }

    /** Input level in [0, 1]: -55 dB .. -10 dB RMS mapped linearly. */
    private fun levelOf(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (v in samples) sumSquares += v * v
        return ((dbOf(sumSquares, samples.size) + 55f) / 45f).coerceIn(0f, 1f)
    }

    /** RMS level in decibels of [count] samples whose squares add up to [sumSquares]. */
    private fun dbOf(sumSquares: Double, count: Int): Float {
        val rms = sqrt(sumSquares / count.coerceAtLeast(1)).toFloat()
        return 20f * log10(rms.coerceAtLeast(1e-6f))
    }

    private companion object {
        /**
         * Below this level (about -46 dB) and without speech, the input counts as silence and is
         * reported as 0: the waveform then rests instead of animating the noise of a quiet room
         * for as long as the speaker pauses.
         */
        const val QUIET_LEVEL = 0.2f

        // the VAD cuts slightly before the limit; anything this long was not ended by a pause
        const val MARGIN_SAMPLES = VoiceEngine.SAMPLE_RATE * 3 / 10

        /** A resumed sentence is decoded again from its beginning; beyond this, a new one starts. */
        const val MAX_SENTENCE_SECONDS = 15f

        const val FORCED_SPLIT_SAMPLES =
            ((VoiceEngine.MAX_SPEECH_SECONDS - 1f) * VoiceEngine.SAMPLE_RATE).toInt()
    }
}
