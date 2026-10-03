/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRefineTest {

    private fun check(expected: String, fast: String, accurate: String) =
        assertEquals(expected, VoiceRefine.refine(fast, accurate))

    @Test
    fun wrongWordsAreReplacedPunctuationStays() {
        check(
            "最令人瞩目的政绩是与美国总统杜鲁门达成了第四点计划。",
            "最令人瞩目的政绩是与美国总统杜洛门达成了第四点计划。",
            "最令人瞩目的政绩是与美国总统杜鲁门达成了第四点计划"
        )
        check(
            "必须使用两仪环才能勉强庇护他们三人。等坠魔谷空间稳定，便可以去夺宝。韩立犹豫了下，说再想想。",
            "必须使用两仪环才能勉强庇护他们三人。等坠魔股空间稳定，便可以去夺宝。韩丽犹豫了一下，说再想想。",
            "必须使用两仪环才能勉强庇护他们三人等坠魔谷空间稳定便可以去夺宝韩立犹豫了下说再想想"
        )
    }

    @Test
    fun digitsFormattedByTheFastModelAreKept() {
        check(
            "今天下午3点，我们在会议室讨论产品规划。",
            "今天下午3点，我们在会议室讨论产品规化。",
            "今天下午三点我们在会议室讨论产品规划"
        )
        check(
            "开放时间早上9点至下午5点。",
            "开饭时间早上9点至下午5点。",
            "开放时间早上九点至下午五点"
        )
        check(
            "The chieftain presented him with 50 pieces of gold.",
            "The chieftain presented him with 50 pieces of code.",
            "THE CHIEFTAIN PRESENTED HIM WITH FIFTY PIECES OF GOLD"
        )
    }

    @Test
    fun englishFromTheAccurateModelIsNotLeftInCapitals() {
        check(
            "我刚刚把代码push到github上了，你帮我review一下这个pull request。",
            "我刚刚把代码push到gihub上了，你帮我review一下这个pool request。",
            "我刚刚把代码 PUSH到 GITHUB上了你帮我 REVIEW一下这个 PULL REQUEST"
        )
        // existing capitalisation is kept, new sentence starts and "I" are capitalised
        check(
            "Please send me the report. I will review it over the weekend.",
            "Please send me the report. I will revue it over the weekend.",
            "PLEASE SEND ME THE REPORT I WILL REVIEW IT OVER THE WEEKEND"
        )
        check(
            "That works. Thanks, I think so.",
            "That works. Tanks, eye think so.",
            "THAT WORKS THANKS I THINK SO"
        )
    }

    @Test
    fun identicalWordsChangeNothing() {
        val fast = "好的，我周末看一下，周一给你答复。"
        check(fast, fast, "好的我周末看一下周一给你答复")
        check("Hello, world!", "Hello, world!", "HELLO WORLD")
    }

    @Test
    fun missingAndExtraWords() {
        // a word only the accurate model heard
        check("我们现在办理的是刑事案件。", "我们现在办理是刑事案件。", "我们现在办理的是刑事案件")
        // a word only the fast model produced
        check("哦，stock吗？对，有。", "哦，the stock嘛？对，有。", "哦 STOCK吗对有")
    }

    @Test
    fun emptyOrUnrelatedTranscripts() {
        check("你好。", "你好。", "")
        check("你好", "", "你好")
        // nothing in common: the accurate words, ending like the fast transcript did
        check("明天上午十点开会。", "名田山无石电凯辉。", "明天上午十点开会")
    }
}
