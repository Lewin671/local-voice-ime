/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Merges two transcripts of the same audio: [fast] comes from the small model and is well
 * formatted (punctuation, digits, capitalisation) but contains more wrong words; [accurate]
 * comes from the large model and has better words but no formatting at all (no punctuation,
 * English in capitals, numbers spelled out).
 *
 * The result has the words of [accurate] in the formatting of [fast]: the two are aligned token
 * by token and only the tokens that differ are replaced. Pure Kotlin; see `VoiceRefineTest` and
 * the measurements in `docs/MODELS.md`.
 */
object VoiceRefine {

    private fun Char.isCjk() = this in '一'..'鿿' || this in '㐀'..'䶿'

    private fun Char.isWordChar() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** A word or CJK character, and whatever (punctuation, spaces) follows it. */
    private class Token(val text: String, var trail: String = "") {
        val key = text.lowercase()
        val isLatin get() = text[0].isWordChar()
    }

    private const val INNER = "'.:%-"   // may appear inside a word or number: don't, 3.5, 9:30

    private fun tokenize(s: String): Pair<String, MutableList<Token>> {
        val tokens = mutableListOf<Token>()
        val gap = StringBuilder()
        var lead = ""
        fun flushGap() {
            if (tokens.isEmpty()) lead += gap else tokens.last().trail += gap
            gap.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isCjk() -> {
                    flushGap()
                    tokens += Token(c.toString())
                    i++
                }
                c.isWordChar() -> {
                    flushGap()
                    var j = i + 1
                    while (j < s.length && (s[j].isWordChar() ||
                                (s[j] in INNER && j + 1 < s.length && s[j + 1].isWordChar()))
                    ) j++
                    // a trailing % belongs to the number
                    if (j < s.length && s[j] == '%') j++
                    tokens += Token(s.substring(i, j))
                    i = j
                }
                else -> {
                    gap.append(c)
                    i++
                }
            }
        }
        flushGap()
        return lead to tokens
    }

    private const val NUMERALS = "零〇一二两三四五六七八九十百千万亿点半第"

    private val ENGLISH_NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "and", "point", "percent"
    )

    private fun Token.isSpelledOutNumber() =
        text.all { it in NUMERALS } || key in ENGLISH_NUMBER_WORDS

    private const val SENTENCE_END = "。！？.!?"
    private const val PUNCTUATION = "，。！？,.!?、；;：:"

    private enum class Op { Equal, Replace, Delete, Insert }

    /** A run of the alignment: a[a1, a2) corresponds to b[b1, b2). */
    private class Block(val op: Op, val a1: Int, val a2: Int, val b1: Int, val b2: Int)

    /** Edit-distance alignment of the two token sequences, grouped into blocks. */
    private fun align(a: List<Token>, b: List<Token>): Pair<List<Block>, Int> {
        val n = a.size
        val m = b.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val sub = d[i - 1][j - 1] + if (a[i - 1].key == b[j - 1].key) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        // backtrace into per-token steps: 'e'qual, 's'ubstitute, 'd'elete (from a), 'i'nsert (from b)
        val steps = ArrayList<Char>(n + m)
        var i = n
        var j = m
        var matches = 0
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].key == b[j - 1].key && d[i][j] == d[i - 1][j - 1]) {
                steps += 'e'; i--; j--; matches++
            } else if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1) {
                steps += 's'; i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                steps += 'd'; i--
            } else {
                steps += 'i'; j--
            }
        }
        steps.reverse()
        val blocks = mutableListOf<Block>()
        var ai = 0
        var bi = 0
        var k = 0
        while (k < steps.size) {
            val equal = steps[k] == 'e'
            val a1 = ai
            val b1 = bi
            while (k < steps.size && (steps[k] == 'e') == equal) {
                when (steps[k]) {
                    'e', 's' -> { ai++; bi++ }
                    'd' -> ai++
                    else -> bi++
                }
                k++
            }
            val op = when {
                equal -> Op.Equal
                ai == a1 -> Op.Insert
                bi == b1 -> Op.Delete
                else -> Op.Replace
            }
            blocks += Block(op, a1, ai, b1, bi)
        }
        return blocks to matches
    }

    fun refine(fast: String, accurate: String): String {
        val (lead, a0) = tokenize(fast)
        val b = tokenize(accurate).second
        if (b.isEmpty()) return fast
        if (a0.isEmpty()) return accurate.trim()
        var a: List<Token> = a0
        var (blocks, matches) = align(a, b)
        var closing = ""
        if (2.0 * matches / (a.size + b.size) < 0.4) {
            // The two disagree almost everywhere, so there is nothing to align: take the accurate
            // words as they are and only keep how the fast transcript ended.
            closing = fast.trim().takeLast(1).takeIf { it[0] in PUNCTUATION } ?: ""
            a = emptyList()
            blocks = listOf(Block(Op.Insert, 0, 0, 0, b.size))
        }

        val out = mutableListOf<Token>()

        // Words that only the accurate model heard arrive in capitals; decide their case here.
        fun emitNew(source: Token) {
            var text = source.text
            if (source.isLatin) {
                text = text.lowercase()
                val previous = out.lastOrNull()
                val sentenceStart = previous == null ||
                        (previous.isLatin && previous.trail.any { it in SENTENCE_END })
                text = when {
                    text == "i" || text.startsWith("i'") -> "I" + text.substring(1)
                    sentenceStart -> text.replaceFirstChar { it.uppercase() }
                    else -> text
                }
            }
            out += Token(text)
        }

        for (block in blocks) {
            when (block.op) {
                Op.Equal -> for (k in block.a1 until block.a2) out += Token(a[k].text, a[k].trail)
                Op.Insert -> for (k in block.b1 until block.b2) emitNew(b[k])
                Op.Delete -> {
                    // words the accurate model did not hear: drop them, keep clause punctuation
                    val mark = a.subList(block.a1, block.a2).joinToString("") { it.trail }
                        .lastOrNull { it in PUNCTUATION }
                    val previous = out.lastOrNull()
                    if (mark != null && previous != null && previous.trail.isBlank()) {
                        previous.trail = mark + if (mark in ",.!?;:") " " else ""
                    }
                }
                Op.Replace -> {
                    val old = a.subList(block.a1, block.a2)
                    val new = b.subList(block.b1, block.b2)
                    if (old.all { t -> t.text.any { it in '0'..'9' } } &&
                        new.all { it.isSpelledOutNumber() }
                    ) {
                        // the same number, already formatted by the fast model
                        old.forEach { out += Token(it.text, it.trail) }
                    } else {
                        new.forEachIndexed { k, token ->
                            emitNew(token)
                            // word for word: every replacement inherits what followed the original
                            if (old.size == new.size) out.last().trail = old[k].trail
                        }
                        out.last().trail = old.last().trail
                    }
                }
            }
        }

        val sb = StringBuilder(lead)
        out.forEachIndexed { index, token ->
            sb.append(token.text)
            val next = out.getOrNull(index + 1)
            if (token.trail.isNotEmpty()) sb.append(token.trail)
            else if (next != null && token.isLatin && next.isLatin) sb.append(' ')
        }
        sb.append(closing)
        return VoiceText.normalize(sb.toString())
    }
}
