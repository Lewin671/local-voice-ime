/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {

    @Test
    fun normalizeKeepsPlainText() {
        assertEquals("今天天气不错。", VoiceText.normalize(" 今天天气不错。 "))
        assertEquals("Hello world.", VoiceText.normalize("Hello world."))
        assertEquals("", VoiceText.normalize("   "))
    }

    @Test
    fun normalizeRemovesSpacesAroundCjk() {
        assertEquals("我在用Android手机", VoiceText.normalize("我在用 Android 手机"))
        assertEquals("你好，世界", VoiceText.normalize("你好， 世界"))
    }

    @Test
    fun normalizeKeepsSpacesBetweenLatinWords() {
        assertEquals("打开Visual Studio Code吧", VoiceText.normalize("打开 Visual Studio  Code 吧"))
    }

    @Test
    fun normalizeUsesFullWidthPunctuationAfterCjk() {
        assertEquals("你好，world", VoiceText.normalize("你好,world"))
        assertEquals("真的吗？", VoiceText.normalize("真的吗?"))
        // punctuation after latin text and decimal points are left alone
        assertEquals("OK, 3.5", VoiceText.normalize("OK, 3.5"))
    }

    @Test
    fun joinerOnlySeparatesLatinText() {
        assertEquals(" ", VoiceText.joiner("Hello.", "How are you"))
        assertEquals(" ", VoiceText.joiner("word", "next"))
        assertEquals("", VoiceText.joiner("你好。", "Hello"))
        assertEquals("", VoiceText.joiner("Hello", "你好"))
        assertEquals("", VoiceText.joiner("", "Hello"))
        assertEquals("", VoiceText.joiner(null, "Hello"))
    }
}
