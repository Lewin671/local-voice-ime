/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Transcribed from Apple's system keyboard; see the references in docs/design/DESIGN.md. */
object SymbolLayout {
    private val digits = "1234567890".map(Char::toString)
    private val englishPunctuation = listOf(".", ",", "?", "!", "'")

    fun rows(chinese: Boolean, more: Boolean): List<List<String>> = when {
        chinese && more -> listOf(
            listOf("【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="),
            listOf("_", "—", "\\", "｜", "～", "《", "》", "$", "&", "·"),
            listOf("…", "，", "^_^", "？", "！", "'")
        )
        chinese -> listOf(
            digits,
            listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"),
            listOf("。", "，", "、", "？", "！", ".")
        )
        more -> listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
            englishPunctuation
        )
        else -> listOf(
            digits,
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
            englishPunctuation
        )
    }
}
