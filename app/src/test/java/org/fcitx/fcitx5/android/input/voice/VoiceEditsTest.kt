/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEditsTest {

    /** A text field with a cursor; `|` in [toString] marks the cursor. */
    private class FakeEditor(text: String = "") : VoiceEdits.Editor {
        var before = text
        var after = ""
        override var hasSelection = false
        override var hasPreview = false
        override fun textBeforeCursor(n: Int) = before.takeLast(n)
        override fun deleteBeforeCursor(n: Int) {
            before = before.dropLast(n)
        }

        override fun insert(text: String) {
            before += text
        }

        fun backspace() = deleteBeforeCursor(1)

        fun moveCursorToStart() {
            after = before + after
            before = ""
        }

        override fun toString() = "$before|$after"
    }

    @Test
    fun refinementReplacesTheUtteranceInPlace() {
        val editor = FakeEditor("你好，")
        val edits = VoiceEdits(editor)
        val entry = edits.insert("开饭时间早上9点。")
        edits.refine(entry, "开放时间早上9点。")
        assertEquals(1 to 0, edits.applyRefinements())
        assertEquals("你好，开放时间早上9点。|", editor.toString())
        assertFalse(edits.hasPendingRefinements)
    }

    @Test
    fun laterUtterancesAreKeptWhenAnEarlierOneIsRefined() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        val first = edits.insert("第一句有错子。")
        val second = edits.insert("第二句。")
        edits.insertTyped("！")
        edits.refine(first, "第一句有错字。")
        edits.refine(second, "第二局。")
        assertEquals(2 to 0, edits.applyRefinements())
        assertEquals("第一句有错字。第二局。！|", editor.toString())
    }

    @Test
    fun editedTextIsNeverTouched() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        val entry = edits.insert("开饭时间。")
        editor.backspace()
        edits.refine(entry, "开放时间。")
        assertEquals(0 to 1, edits.applyRefinements())
        assertEquals("开饭时间|", editor.toString())
    }

    @Test
    fun movedCursorOrSelectionDropsTheRefinement() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        val entry = edits.insert("开饭时间。")
        editor.moveCursorToStart()
        edits.refine(entry, "开放时间。")
        assertEquals(0 to 1, edits.applyRefinements())
        assertEquals("|开饭时间。", editor.toString())

        val editor2 = FakeEditor()
        val edits2 = VoiceEdits(editor2)
        val entry2 = edits2.insert("开饭时间。")
        editor2.hasSelection = true
        edits2.refine(entry2, "开放时间。")
        assertEquals(0 to 1, edits2.applyRefinements())
        assertEquals("开饭时间。|", editor2.toString())
    }

    @Test
    fun refinementWaitsWhileAPreviewIsShowing() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        val entry = edits.insert("开饭时间。")
        edits.refine(entry, "开放时间。")
        editor.hasPreview = true
        assertEquals(0 to 0, edits.applyRefinements())
        assertTrue(edits.hasPendingRefinements)
        editor.hasPreview = false
        assertEquals(1 to 0, edits.applyRefinements())
        assertEquals("开放时间。|", editor.toString())
    }

    @Test
    fun unchangedRefinementIsNotQueued() {
        val edits = VoiceEdits(FakeEditor())
        val entry = edits.insert("没有变化。")
        edits.refine(entry, "没有变化。")
        assertFalse(edits.hasPendingRefinements)
    }

    @Test
    fun undoRemovesTheLastSessionOnly() {
        val editor = FakeEditor("手打的，")
        val edits = VoiceEdits(editor)
        edits.startSession()
        edits.insert("第一次说的。")
        edits.startSession()
        edits.insert("第二次")
        edits.insert("说了两句。")
        assertTrue(edits.canUndoSession)
        assertTrue(edits.undoSession())
        assertEquals("手打的，第一次说的。|", editor.toString())
        assertFalse(edits.canUndoSession)
    }

    @Test
    fun undoAppliesToTheRefinedTextAndCancelsPendingRefinements() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        edits.startSession()
        val entry = edits.insert("开饭时间。")
        edits.refine(entry, "开放时间。")
        edits.applyRefinements()
        val second = edits.insert("还有一句。")
        assertTrue(edits.undoSession())
        assertEquals("|", editor.toString())
        // a refinement that arrives after the undo has nothing to write to
        edits.refine(second, "还有一句话。")
        assertEquals(0 to 1, edits.applyRefinements())
        assertEquals("|", editor.toString())
    }

    @Test
    fun undoDoesNothingAfterAnEditOrTypedPunctuation() {
        val editor = FakeEditor()
        val edits = VoiceEdits(editor)
        edits.startSession()
        edits.insert("说的话。")
        editor.backspace()
        assertFalse(edits.undoSession())
        assertEquals("说的话|", editor.toString())

        val editor2 = FakeEditor()
        val edits2 = VoiceEdits(editor2)
        edits2.startSession()
        edits2.insert("说的话")
        edits2.insertTyped("？")
        assertFalse(edits2.canUndoSession)
    }
}
