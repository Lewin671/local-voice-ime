/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceFieldTextTest {

    private fun locate(dictated: String, before: String, after: String = "") =
        VoiceFieldText.locate(dictated, before, after)

    @Test
    fun untouchedTextIsFoundAsDictated() {
        assertEquals("开放时间早上9点。", locate("开放时间早上9点。", "你好，开放时间早上9点。"))
    }

    @Test
    fun aReplacedWordIsPartOfThePassage() {
        assertEquals("我提了一个pull request。", locate("我提了一个普尔request。", "我提了一个pull request。"))
    }

    @Test
    fun theCursorMayBeInsideThePassage() {
        assertEquals("今天开会讨论预算", locate("今天开会讨论运算", "今天开会讨论预", "算"))
    }

    @Test
    fun textAroundThePassageIsLeftOut() {
        // what was typed before and after the dictated sentence is not ours to keep
        assertEquals(
            "明天下午三点见", locate("明天下午三点钟见", "老板好，明天下午三点见", "，另外合同已经寄出")
        )
    }

    @Test
    fun deletedPunctuationAtTheEndShortensThePassage() {
        assertEquals("好的没问题", locate("好的没问题。", "好的没问题"))
    }

    @Test
    fun wordsAddedInTheMiddleAreKept() {
        assertEquals("我们下周一再讨论", locate("我们下周讨论", "我们下周一再讨论"))
    }

    @Test
    fun aRewrittenFieldIsNotAMatch() {
        assertNull(locate("开放时间早上9点至下午5点。", "晚上一起吃饭吗"))
    }

    @Test
    fun anEmptiedFieldIsNotAMatch() {
        assertNull(locate("你好", "", ""))
    }

    @Test
    fun shortPassagesMustBeIntact() {
        // one changed character of two could be anything
        assertNull(locate("好的", "你好啊"))
        assertEquals("好的", locate("好的", "嗯，好的"))
    }

    @Test
    fun ofTwoEqualCopiesTheOneAtTheCursorIsTaken() {
        assertEquals("收到", locate("收到", "收到，谢谢。收到", "了"))
        // the copy that contains the cursor wins over an identical one elsewhere
        val found = VoiceFieldText.locate("好的谢谢", "好的谢谢 ", "好的谢谢")
        assertEquals("好的谢谢", found)
    }

    @Test
    fun veryLongPassagesAreNotLookedFor() {
        val long = "字".repeat(VoiceFieldText.MAX_LENGTH + 1)
        assertNull(locate(long, long))
    }
}
