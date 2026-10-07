/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * How long a speech model that is not being used stays in memory.
 *
 * Reading the large model again costs more CPU time than refining a short utterance with it
 * (`docs/PERFORMANCE.md`), so somebody who dictates a message every few minutes pays for the
 * load each time if the model is freed quickly. Memory that nothing else needs costs nothing to
 * keep occupied, so the model
 *
 * - always stays for [minIdleMs] after its last use;
 * - stays beyond that, up to [maxIdleMs], only while the system reports at least [spareBytes]
 *   available and is not low on memory, which is asked again every [recheckMs].
 *
 * Pure Kotlin, unit-tested.
 */
internal class VoiceModelResidency(
    private val minIdleMs: Long,
    private val maxIdleMs: Long,
    private val recheckMs: Long,
    private val spareBytes: Long
) {
    init {
        require(minIdleMs in 1..maxIdleMs && recheckMs > 0)
    }

    /**
     * The model has not been used for [idleMs].
     * @return how long to wait before asking again; 0 to free the model now
     */
    fun keepFor(idleMs: Long, availableBytes: Long, lowMemory: Boolean): Long {
        if (idleMs < minIdleMs) return minIdleMs - idleMs.coerceAtLeast(0)
        if (idleMs >= maxIdleMs || lowMemory || availableBytes < spareBytes) return 0
        return minOf(recheckMs, maxIdleMs - idleMs)
    }
}
