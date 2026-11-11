/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePendingKeysTest {
    @Test
    fun textBackspaceAndReturnKeepTheirOrderBehindThePreview() {
        val keys = VoicePendingKeys()
        val text = StringBuilder("dictated")
        keys.add(1) { text.append("1") }
        keys.add(1) { text.deleteCharAt(text.lastIndex) }
        keys.add(1) { text.append("#") }
        keys.add(1) { text.append("\n") }
        assertEquals("dictated", text.toString())
        keys.flush { 1 }
        assertEquals("dictated#\n", text.toString())
        assertTrue(keys.isEmpty)
    }

    @Test
    fun editsNeverRunInAnotherFieldOrAfterBeingCleared() {
        val keys = VoicePendingKeys()
        val calls = mutableListOf<String>()
        keys.add(1) { calls.add("old field") }
        keys.add(2) { calls.add("current field") }
        keys.flush { 2 }
        assertEquals(listOf("current field"), calls)
        keys.add(2) { calls.add("cancelled") }
        keys.clear()
        keys.flush { 2 }
        assertEquals(listOf("current field"), calls)
    }

    @Test
    fun aReturnThatChangesFieldsDoesNotSendLaterKeysToTheNewField() {
        val keys = VoicePendingKeys()
        var editor = 1
        val calls = mutableListOf<String>()
        keys.add(1) { editor = 2 }
        keys.add(1) { calls.add("backspace") }
        keys.flush { editor }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun callbacksEnqueuedDuringFlushWaitForTheNextFinalResult() {
        val keys = VoicePendingKeys()
        val calls = mutableListOf<Int>()
        keys.add(1) {
            calls.add(1)
            keys.add(1) { calls.add(2) }
        }
        keys.flush { 1 }
        assertEquals(listOf(1), calls)
        keys.flush { 1 }
        assertEquals(listOf(1, 2), calls)
    }
}
