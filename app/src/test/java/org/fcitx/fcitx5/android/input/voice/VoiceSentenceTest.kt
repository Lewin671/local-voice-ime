/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSentenceTest {

    // 10 samples per second: a gap of up to 4 s continues, a sentence holds 15 s of audio
    private fun sentence() = VoiceSentence(10, 4f, 15f)

    @Test
    fun theFullStopIsHeldBackUntilTheSentenceIsOver() {
        val s = sentence()
        assertFalse(s.isOpen)
        assertEquals("用户可能不是一下子", s.keep(FloatArray(30), 30, "用户可能不是一下子。"))
        assertTrue(s.isOpen)
        assertFalse(s.isOver(70))
        assertTrue(s.isOver(71))
        assertEquals("。", s.close())
        assertFalse(s.isOpen)
        assertEquals("", s.close())
    }

    @Test
    fun otherClosingMarksAreInsertedAtOnce() {
        val s = sentence()
        assertEquals("你吃饭了吗？", s.keep(FloatArray(20), 20, "你吃饭了吗？"))
        assertEquals("", s.close())
        assertEquals("Send it by Friday", s.keep(FloatArray(20), 20, "Send it by Friday. "))
        assertEquals(".", s.close())
    }

    @Test
    fun speechAfterAShortPauseContinuesTheSentence() {
        val s = sentence()
        assertFalse(s.continuesWith(0, 10))
        val first = FloatArray(30) { 1f }
        assertEquals(first, s.join(first))
        s.keep(first, 30, "用户可能不是一下子。")
        assertTrue(s.continuesWith(55, 20))
        val joined = s.join(FloatArray(20) { 2f })
        assertEquals(50, joined.size)
        assertEquals(1f, joined[29])
        assertEquals(2f, joined[30])
        // the sentence now ends where the second utterance ended
        assertEquals("用户可能不是一下子把话说完", s.keep(joined, 75, "用户可能不是一下子把话说完。"))
        assertFalse(s.isOver(115))
        assertTrue(s.isOver(116))
    }

    @Test
    fun aLongPauseOrALongSentenceStartsANewOne() {
        val s = sentence()
        s.keep(FloatArray(100), 100, "一。")
        assertTrue(s.continuesWith(140, 50))
        assertFalse(s.continuesWith(141, 50))
        assertFalse(s.continuesWith(120, 51))
    }
}
