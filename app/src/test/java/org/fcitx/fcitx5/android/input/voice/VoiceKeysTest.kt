/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceKeys.Effect
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceKeysTest {

    @Test
    fun lettersTurnTheMicrophoneOff() {
        for (key in listOf("a", "Z", "é", "ß")) assertEquals(key, Effect.Types, VoiceKeys.ofText(key))
        // 'q', 'M', 'ü' as key symbols, and a Unicode key symbol for 'я'
        for (sym in listOf(0x71, 0x4d, 0xfc, 0x100044f)) {
            assertEquals(sym.toString(16), Effect.Types, VoiceKeys.ofSym(sym))
        }
    }

    @Test
    fun punctuationDigitsAndSpaceKeepIt() {
        for (key in listOf(",", ".", "?", "，", "。", "7", "@", "…", " ")) {
            assertEquals(key, Effect.Keeps, VoiceKeys.ofText(key))
        }
        // space, '1', '!', multiplication sign, Tab, keypad 5, and a Unicode key symbol for '。'
        for (sym in listOf(0x20, 0x31, 0x21, 0xd7, 0xff09, 0xffb5, 0x1003002)) {
            assertEquals(sym.toString(16), Effect.Keeps, VoiceKeys.ofSym(sym))
        }
    }

    @Test
    fun editingKeysEndTheSentence() {
        // BackSpace, Return, Left, Delete
        for (sym in listOf(0xff08, 0xff0d, 0xff51, 0xffff)) {
            assertEquals(sym.toString(16), Effect.Edits, VoiceKeys.ofSym(sym))
        }
    }
}
