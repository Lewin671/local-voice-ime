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
        // a space typed while dictating is not doubled
        assertEquals("", VoiceText.joiner("word ", "next"))
    }

    @Test
    fun continuationIsWhatTheWholeSentenceAdds() {
        assertEquals("把话说完。", VoiceText.continuation("不是一下子", "不是一下子把话说完。"))
        assertEquals("，记得带电脑。", VoiceText.continuation("3点开会", "3点开会，记得带电脑。"))
        // the earlier part was heard differently this time: only the common beginning is skipped
        assertEquals("字把话说完。", VoiceText.continuation("不是一下子", "不是一下字把话说完。"))
        assertEquals("", VoiceText.continuation("Hello", "Hello"))
    }

    @Test
    fun previewHasNoTrailingPunctuation() {
        assertEquals("开饭时间早上9点", VoiceText.stripTrailingPunctuation("开饭时间早上9点。"))
        assertEquals("你觉得怎么样", VoiceText.stripTrailingPunctuation("你觉得怎么样？"))
        assertEquals("Hello world", VoiceText.stripTrailingPunctuation("Hello world. "))
        // punctuation inside the text stays
        assertEquals("好的，我知道了", VoiceText.stripTrailingPunctuation("好的，我知道了。"))
        assertEquals("", VoiceText.stripTrailingPunctuation("。"))
        assertEquals("3.5", VoiceText.stripTrailingPunctuation("3.5"))
    }

    @Test
    fun cutOffUtteranceLosesOnlyItsFullStop() {
        assertEquals("然后我们就", VoiceText.stripTrailingFullStop("然后我们就。"))
        assertEquals("and then we", VoiceText.stripTrailingFullStop("and then we."))
        assertEquals("真的吗？", VoiceText.stripTrailingFullStop("真的吗？"))
        assertEquals("好的，", VoiceText.stripTrailingFullStop("好的，"))
    }
}
