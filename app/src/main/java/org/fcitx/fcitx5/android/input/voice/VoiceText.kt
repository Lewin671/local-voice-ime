/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Pure text post-processing for recognizer output. Kept free of Android dependencies so that it
 * can be covered by plain JVM unit tests.
 */
object VoiceText {

    private fun Char.isCjk() = this in '一'..'鿿' || this in '㐀'..'䶿'

    private fun Char.isCjkPunctuation() = this in "，。？！、；：“”‘’（）《》"

    private val halfToFull = mapOf(
        ',' to '，', '?' to '？', '!' to '！', ';' to '；', ':' to '：'
    )

    /**
     * - trims the result
     * - removes spaces that the model inserts between CJK characters / punctuation
     * - converts ASCII punctuation that directly follows a CJK character to its full-width form
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val prev = sb.lastOrNull()
            if (c == ' ') {
                val next = s.getOrNull(i + 1)
                val prevWide = prev != null && (prev.isCjk() || prev.isCjkPunctuation())
                val nextWide = next != null && (next.isCjk() || next.isCjkPunctuation())
                // keep spaces only between two non-CJK tokens (i.e. English words)
                if (prev == null || prev == ' ' || prevWide || nextWide) continue
                sb.append(c)
            } else if (c in halfToFull && prev != null && prev.isCjk()) {
                sb.append(halfToFull.getValue(c))
            } else {
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    private const val TRAILING_PUNCTUATION = "。．.，,、；;：:？?！!…"

    /**
     * The model punctuates every intermediate result as if it were a complete sentence.
     * In a live preview that punctuation flickers, so it is only shown once the utterance is final.
     */
    fun stripTrailingPunctuation(text: String): String =
        text.trimEnd { it in TRAILING_PUNCTUATION || it.isWhitespace() }

    /**
     * For an utterance that was cut off because it reached the maximum length: the model still
     * ends it with a full stop, although the sentence goes on.
     */
    fun stripTrailingFullStop(text: String): String {
        val s = text.trimEnd()
        return if (s.endsWith('。') || s.endsWith('.')) s.dropLast(1) else s
    }

    /**
     * For an utterance that continues a sentence whose earlier part, [before], can no longer be
     * replaced in the text field: what [joined], the transcript of the whole sentence, adds to it.
     */
    fun continuation(before: String, joined: String): String =
        joined.substring(before.commonPrefixWith(joined).length)

    /**
     * Text to insert between [before] (text already in the editor, left of the cursor) and a newly
     * recognized segment: a space is only needed between two Latin words/sentences.
     */
    fun joiner(before: CharSequence?, segment: String): String {
        val last = before?.lastOrNull() ?: return ""
        val first = segment.firstOrNull() ?: return ""
        val lastLatin = last.isLetterOrDigit() && last.code < 0x250 || last in ".,?!;:"
        val firstLatin = first.isLetterOrDigit() && first.code < 0x250
        return if (lastLatin && firstLatin) " " else ""
    }
}
