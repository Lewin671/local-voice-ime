/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A failure the user can be told about in plain words.
 */
class VoiceException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind {
        /** The microphone could not be opened at all. */
        MicrophoneUnavailable,

        /** The microphone is being used by something else. */
        MicrophoneBusy,

        /** The speech model could not be loaded (e.g. not enough memory). */
        ModelLoadFailed
    }
}

/**
 * Source of 16 kHz mono audio, as float samples in [-1, 1].
 */
interface AudioSource {
    fun start()

    /**
     * Blocks/suspends until [buffer] is filled.
     * @return number of samples read, or a negative value when the source is exhausted
     */
    suspend fun read(buffer: FloatArray): Int

    fun stop()
}

class MicrophoneSource : AudioSource {

    private var record: AudioRecord? = null
    private var pcm = ShortArray(0)

    @SuppressLint("MissingPermission") // checked by VoiceInput before a session is created
    override fun start() {
        val rate = VoiceEngine.SAMPLE_RATE
        val minSize = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 2 seconds, so that nothing is dropped while a decode keeps the consumer busy
        val bufferBytes = maxOf(minSize, rate * 2 * 2)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw VoiceException(VoiceException.Kind.MicrophoneUnavailable)
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // typically: a call is in progress, or another app holds the microphone
            r.release()
            throw VoiceException(VoiceException.Kind.MicrophoneBusy)
        }
        record = r
    }

    override suspend fun read(buffer: FloatArray): Int {
        val r = record ?: return -1
        if (pcm.size != buffer.size) pcm = ShortArray(buffer.size)
        val n = r.read(pcm, 0, pcm.size)
        for (i in 0 until n) buffer[i] = pcm[i] / 32768f
        return n
    }

    override fun stop() {
        val r = record ?: return
        record = null
        r.runCatching {
            try {
                stop()
            } finally {
                release()
            }
        }
    }
}

/**
 * Plays back a 16 kHz mono 16-bit PCM WAV file in real time, followed by trailing silence.
 * Only used by debug builds for automated end-to-end tests (see `docs/TESTING.md`).
 */
class WavFileSource(private val file: File, private val trailingSilenceMs: Int = 1500) :
    AudioSource {

    private var samples = ShortArray(0)
    private var position = 0
    private var silenceLeft = 0

    override fun start() {
        RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray(f.length().toInt())
            f.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // walk RIFF chunks to locate "data"
            var offset = 12
            var dataOffset = -1
            var dataSize = 0
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = bb.getInt(offset + 4)
                if (id == "data") {
                    dataOffset = offset + 8
                    dataSize = minOf(size, bytes.size - dataOffset)
                    break
                }
                offset += 8 + size + (size and 1)
            }
            check(dataOffset > 0) { "Not a PCM WAV file: $file" }
            samples = ShortArray(dataSize / 2)
            bb.position(dataOffset)
            bb.asShortBuffer().get(samples)
        }
        position = 0
        silenceLeft = VoiceEngine.SAMPLE_RATE * trailingSilenceMs / 1000
    }

    override suspend fun read(buffer: FloatArray): Int {
        if (position >= samples.size && silenceLeft <= 0) return -1
        delay(buffer.size * 1000L / VoiceEngine.SAMPLE_RATE)
        for (i in buffer.indices) {
            buffer[i] = if (position < samples.size) {
                samples[position++] / 32768f
            } else {
                silenceLeft--
                0f
            }
        }
        return buffer.size
    }

    override fun stop() {}
}
