/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.lang.management.ManagementFactory

/** Compare the old session buffer with production code, without a model, device or microphone. */
private class PreviousBuffer {
    private var data = FloatArray(64000)
    var size = 0
    fun append(src: FloatArray) {
        if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
        src.copyInto(data, size)
        size += src.size
    }
    fun window(from: Int) = data.copyOfRange(from, from + 512)
    fun drop(n: Int) {
        data.copyInto(data, 0, n, size)
        size -= n
    }
}

private data class Measurement(val ms: Double, val bytes: Long, val checksum: Double)

private fun measure(run: () -> Double): Measurement {
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    bean.isThreadAllocatedMemoryEnabled = true
    @Suppress("DEPRECATION") // JDK 17 does not have Thread.threadId().
    val id = Thread.currentThread().id
    val bytes = bean.getThreadAllocatedBytes(id)
    val start = System.nanoTime()
    val checksum = run()
    return Measurement((System.nanoTime() - start) / 1e6,
        bean.getThreadAllocatedBytes(id) - bytes, checksum)
}

fun main() {
    // 10 minutes at 16 kHz, including a quiet lead-in or 20-second utterances. Model work and
    // recognition snapshots are deliberately excluded: this isolates the storage overhead.
    val chunk = FloatArray(1600) { (it % 97 - 48) / 32768f }
    fun previous(speech: Boolean): Double {
        val buffer = PreviousBuffer()
        var fed = 0
        var checksum = 0.0
        repeat(6000) { tick ->
            buffer.append(chunk)
            while (fed + 512 <= buffer.size) {
                val window = buffer.window(fed)
                checksum += window.sum()
                fed += 512
            }
            val n = if (speech) {
                if ((tick + 1) % 200 == 0) buffer.size - 5120 else 0
            } else maxOf(0, buffer.size - 5120)
            if (n > 0) buffer.drop(n)
            fed = maxOf(0, fed - n)
        }
        return checksum
    }
    fun current(speech: Boolean): Double {
        val buffer = VoiceAudioBuffer()
        val window = FloatArray(512)
        var fed = 0
        var checksum = 0.0
        repeat(6000) { tick ->
            buffer.append(chunk)
            while (fed + 512 <= buffer.size) {
                buffer.copyInto(window, fed)
                checksum += window.sum()
                fed += 512
            }
            val n = if (speech) {
                if ((tick + 1) % 200 == 0) buffer.size - 5120 else 0
            } else maxOf(0, buffer.size - 5120)
            if (n > 0) buffer.dropFirst(n)
            fed = maxOf(0, fed - n)
        }
        return checksum
    }
    for (speech in listOf(false, true)) {
        repeat(5) { previous(speech); current(speech) }
        val old = mutableListOf<Measurement>()
        val new = mutableListOf<Measurement>()
        repeat(9) { i ->
            // Alternate order to reduce warm-up/order bias.
            if (i % 2 == 0) {
                old += measure { previous(speech) }
                new += measure { current(speech) }
            } else {
                new += measure { current(speech) }
                old += measure { previous(speech) }
            }
            check(old.last().checksum == new.last().checksum)
        }
        fun report(label: String, runs: List<Measurement>) {
            val median = runs.map { it.ms }.sorted()[runs.size / 2]
            val bytes = runs.map { it.bytes }.sorted()[runs.size / 2]
            println("${if (speech) "speech" else "silence"} $label " +
                "median_ms=%.3f allocated_bytes=$bytes".format(median))
        }
        report("before", old)
        report("after", new)
    }
    for (adaptive in listOf(false, true)) {
        val pacer = PartialPacer()
        pacer.speechStarted(0)
        var previews = 0
        var audioMs = 0L
        for (now in 0L..20_000L step 100) {
            pacer.heard(-20f)
            if (pacer.isDue(now, audioDurationMs = if (adaptive) now else 0)) {
                previews++
                audioMs += now
                pacer.decoded(now, now, changed = true)
            }
        }
        println("20s_preview ${if (adaptive) "after" else "before"} " +
            "decodes=$previews prefix_audio_ms=$audioMs")
    }
}
