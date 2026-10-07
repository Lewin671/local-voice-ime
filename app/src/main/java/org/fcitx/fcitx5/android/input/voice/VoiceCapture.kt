/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.channels.SendChannel

/** The reader owns capture teardown; queued audio outlives the microphone. */
internal class VoiceCapture(private val source: AudioSource) {
    private var closed = false

    /** The error that ended reading, if it was not the end of the source or a stop. */
    val error get() = source.error

    /** What [pump] has done so far, for [VoiceDiagnostics]; written by the reader only. */
    @Volatile
    var chunkCount = 0
        private set

    @Volatile
    var samples = 0L
        private set

    @Volatile
    var emptyReads = 0
        private set

    /** Whether the source ended reading by itself, exhausted or failed, rather than a stop. */
    @Volatile
    var endedBySource = false
        private set

    fun start() = source.start()

    /** Preserve the last completed read even if stopping was requested during that read. */
    suspend fun pump(
        chunkSize: Int,
        chunks: SendChannel<FloatArray>,
        keepReading: () -> Boolean,
        onStopped: () -> Unit = {},
        onChunk: (FloatArray) -> Unit
    ) {
        try {
            while (keepReading()) {
                val buffer = FloatArray(chunkSize)
                val n = source.read(buffer)
                if (n < 0) {
                    endedBySource = true
                    break
                }
                if (n == 0) {
                    emptyReads++
                    continue
                }
                val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                chunkCount++
                samples += n
                chunks.send(chunk)
                onChunk(chunk)
            }
        } finally {
            try {
                onStopped()
            } finally {
                close()
            }
        }
    }

    /** Also used after a failure before the reader starts; never release the source twice. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        source.stop()
    }
}
