/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Finds a dictated passage in a text field after the user may have corrected it, so that kept
 * recordings (see [VoiceSamples]) come with the text the user settled on.
 *
 * Only the passage itself is returned: what stands around it in the field is somebody's text
 * that was not dictated, and is none of our business. Pure Kotlin; see `VoiceFieldTextTest`.
 */
object VoiceFieldText {

    /** Longer passages are not looked for: the comparison takes time proportional to length². */
    const val MAX_LENGTH = 600

    /** How much to read on each side of the cursor for a passage of [length] characters. */
    fun reach(length: Int) = length + 40

    /**
     * The part of the field that [dictated] has become, or null if it is no longer there.
     *
     * [before] and [after] are the text on either side of the cursor. The answer is the
     * stretch of them that differs least from [dictated] (insertions, deletions and replacements
     * of characters), provided that at least three fifths of it are still as dictated; of
     * equally good stretches, the one nearest to the cursor.
     */
    fun locate(dictated: String, before: String, after: String): String? {
        val n = dictated.length
        if (n == 0 || n > MAX_LENGTH) return null
        val field = before + after
        val m = field.length
        if (m == 0) return null
        // cost[j]: fewest edits that turn the first i characters of the passage into a stretch
        // of the field ending at j; from[j]: where that stretch begins
        var cost = IntArray(m + 1)
        var from = IntArray(m + 1) { it }
        var nextCost = IntArray(m + 1)
        var nextFrom = IntArray(m + 1)
        for (i in 1..n) {
            nextCost[0] = i
            nextFrom[0] = 0
            val c = dictated[i - 1]
            for (j in 1..m) {
                var best = cost[j - 1] + if (c == field[j - 1]) 0 else 1
                var start = from[j - 1]
                if (cost[j] + 1 < best) {
                    best = cost[j] + 1
                    start = from[j]
                }
                if (nextCost[j - 1] + 1 < best) {
                    best = nextCost[j - 1] + 1
                    start = nextFrom[j - 1]
                }
                nextCost[j] = best
                nextFrom[j] = start
            }
            cost = nextCost.also { nextCost = cost }
            from = nextFrom.also { nextFrom = from }
        }
        val cursor = before.length
        var end = -1
        var distance = Int.MAX_VALUE
        for (j in 0..m) {
            if (end >= 0 && cost[j] > cost[end]) continue
            val d = when {
                cursor < from[j] -> from[j] - cursor
                cursor > j -> cursor - j
                else -> 0
            }
            if (end < 0 || cost[j] < cost[end] || d < distance) {
                end = j
                distance = d
            }
        }
        if (cost[end] * 5 > n * 2 || from[end] >= end) return null
        return field.substring(from[end], end)
    }
}
