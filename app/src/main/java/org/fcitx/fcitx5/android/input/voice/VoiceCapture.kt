/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.channels.SendChannel

/** The reader owns capture teardown; queued audio outlives the microphone. */
internal class VoiceCapture(private val source: AudioSource) {
    private var closed = false

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
                if (n < 0) break
                if (n == 0) continue
                val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
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
