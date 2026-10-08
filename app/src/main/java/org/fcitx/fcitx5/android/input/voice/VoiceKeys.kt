/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What a key of the keyboard means for hands-free dictation that is going on, see "Keys while
 * dictating hands-free" in `docs/design/DESIGN.md`. No Android classes, unit-tested.
 */
object VoiceKeys {

    enum class Effect {
        /** Punctuation, a digit, space: typed as always, the microphone stays on. */
        Keeps,

        /** ⌫, ↵, moving the cursor: likewise, and what is said next is a new utterance. */
        Edits,

        /** A letter: the user went over to typing, the microphone turns off. */
        Types
    }

    /** A key that sends the characters [text] to the input method. */
    fun ofText(text: String) = if (text.any(Char::isLetter)) Effect.Types else Effect.Keeps

    /** A key that sends the X11 key symbol [sym] to the input method. */
    fun ofSym(sym: Int) = when {
        sym in EDITING -> Effect.Edits
        // Latin-1 and Unicode key symbols are the code points they stand for
        sym < 0x100 && sym.toChar().isLetter() -> Effect.Types
        sym in 0x1000100..0x110ffff && Character.isLetter(sym - 0x1000000) -> Effect.Types
        else -> Effect.Keeps
    }

    // BackSpace, Return, Home, the four arrows, End, Delete
    private val EDITING = setOf(0xff08, 0xff0d, 0xff50, 0xff51, 0xff52, 0xff53, 0xff54, 0xff57, 0xffff)
}
