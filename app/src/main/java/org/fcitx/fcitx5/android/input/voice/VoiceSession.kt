/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.Vad
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
    private val listener: Listener
) {

    enum class State { Listening, Finishing, Stopped }

    interface Listener {
        fun onState(state: State) {}

        /** Preview of the utterance being spoken; replaced by the next partial or final. */
        fun onPartial(text: String) {}

        /** A finished utterance. */
        fun onFinal(text: String) {}

        /** Input level in [0, 1], for visual feedback. */
        fun onLevel(level: Float) {}

        fun onError(e: Throwable) {}
    }

    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var discard = false

    val isRunning get() = job?.isActive == true

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

    private suspend inline fun emit(crossinline block: Listener.() -> Unit) =
        withContext(Dispatchers.Main.immediate) { listener.block() }

    private suspend fun run() = coroutineScope {
        // Start capturing right away: loading the model can take seconds after a cold start,
        // and whatever is said meanwhile is queued in `chunks` instead of being lost.
        source.start()
        emit { onState(State.Listening) }
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        // reader: never waits for decoding, so no audio is dropped on slow devices
        val reader = launch(Dispatchers.IO) {
            val chunkSize = VoiceEngine.SAMPLE_RATE / 10
            while (isActive && !stopRequested) {
                val buf = FloatArray(chunkSize)
                val n = source.read(buf)
                if (n < 0) break
                if (n > 0) chunks.send(if (n == buf.size) buf else buf.copyOf(n))
            }
            chunks.close()
        }

        VoiceEngine.ensureLoaded(context)
        val vad = VoiceEngine.createVad(context, minSilence)
        try {
            consume(chunks, vad)
            reader.join()
            emit { onState(State.Finishing) }
            vad.flush()
            drain(vad)
        } finally {
            vad.release()
        }
    }

    // Audio that has not been finalized yet: the utterance in progress, or a short lead-in while
    // waiting for speech. `bufferStart` is the position of buffer[0] in the whole recording, which
    // is the coordinate system of the segments reported by the VAD.
    private val buffer = FloatBuffer()
    private var bufferStart = 0L
    private var fed = 0
    private var speaking = false
    private var lastPartial = ""

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
            // keep what was recorded after this utterance: it may be the start of the next one
            dropFromBuffer((segment.start + segment.samples.size - bufferStart).toInt())
            speaking = vad.isSpeechDetected()
            val text = if (discard) "" else VoiceEngine.transcribe(segment.samples)
            lastPartial = ""
            if (text.isNotEmpty()) emit { onFinal(text) } else emit { onPartial("") }
        }
    }

    private suspend fun consume(chunks: Channel<FloatArray>, vad: Vad) {
        val window = VoiceEngine.VAD_WINDOW
        var lastPartialAt = 0L
        var lastDecodeCost = 0L

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
            val rms = sqrt(sumSquares / count.coerceAtLeast(1)).toFloat()
            // -55 dB .. -10 dB mapped to 0 .. 1
            val db = 20f * log10(rms.coerceAtLeast(1e-6f))
            emit { onLevel(((db + 55f) / 45f).coerceIn(0f, 1f)) }

            while (fed + window <= buffer.size) {
                vad.acceptWaveform(buffer.copyOfRange(fed, fed + window))
                fed += window
                if (!speaking && vad.isSpeechDetected()) {
                    speaking = true
                    lastPartialAt = SystemClock.elapsedRealtime()
                }
            }
            drain(vad)
            if (!speaking) {
                // only keep a short lead-in while waiting for speech
                dropFromBuffer(buffer.size - 10 * window)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            // re-decode at most ~3 times per second, and back off when decoding is slow
            if (now - lastPartialAt >= maxOf(300L, lastDecodeCost * 2)) {
                val text = VoiceEngine.transcribe(buffer.toArray())
                lastPartialAt = SystemClock.elapsedRealtime()
                lastDecodeCost = lastPartialAt - now
                if (text != lastPartial) {
                    lastPartial = text
                    emit { onPartial(text) }
                }
            }
        }
    }

    /** Minimal growable float array. */
    private class FloatBuffer {
        private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 4)
        var size = 0
            private set

        fun append(src: FloatArray) {
            if (size + src.size > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + src.size))
            }
            src.copyInto(data, size)
            size += src.size
        }

        fun copyOfRange(from: Int, to: Int) = data.copyOfRange(from, to)

        fun toArray() = data.copyOf(size)

        fun dropFirst(n: Int) {
            data.copyInto(data, 0, n, size)
            size -= n
        }

        fun clear() {
            size = 0
        }
    }
}
