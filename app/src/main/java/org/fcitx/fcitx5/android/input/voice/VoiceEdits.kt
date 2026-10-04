/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Bookkeeping of what dictation wrote into the text field, so that it can be changed afterwards
 * (undo, refinement) without ever touching text that dictation did not write.
 *
 * The rule: a piece of dictated text may only be modified while the editor still shows it,
 * followed by exactly the dictated pieces after it, directly before the cursor, with nothing
 * selected. Anything else means the user has edited or moved, and the modification is dropped.
 *
 * Free of Android dependencies; see `VoiceEditsTest`.
 */
class VoiceEdits(private val editor: Editor) {

    /** The few things this class needs from the text field. */
    interface Editor {
        /** The [n] characters before the cursor (fewer if there are not that many). */
        fun textBeforeCursor(n: Int): String?

        val hasSelection: Boolean

        /** Whether a composing preview is currently shown at the cursor. */
        val hasPreview: Boolean

        fun deleteBeforeCursor(n: Int)

        fun insert(text: String)
    }

    /** A piece of text that dictation inserted. */
    class Entry internal constructor(internal var text: String) {
        // Read on the native worker; only the editor thread retires an entry. Retirement is
        // permanent, unlike a cursor move or a preview that might temporarily hide the text.
        @Volatile
        private var retired = false

        internal val canRefine get() = !retired

        internal fun retire() {
            retired = true
        }
    }

    private class Refinement(val entry: Entry, var text: String)

    private val entries = ArrayList<Entry>()

    private val refinements = ArrayList<Refinement>()

    /** First entry of the session that [undoSession] would remove. */
    private var sessionFirst: Entry? = null

    private var newSession = true

    /** First thing written since [startSession], dictated or typed in the panel. */
    private var sessionStart: Entry? = null

    val hasPendingRefinements get() = refinements.isNotEmpty()

    val canUndoSession get() = sessionFirst != null

    /** A new dictation session begins: undo now refers to what it is going to insert. */
    fun startSession() {
        sessionFirst = null
        newSession = true
        sessionStart = null
    }

    /**
     * Everything written since [startSession], as dictation left it (refinements included), or
     * null if nothing was, or it was undone. Says nothing about whether the editor still shows
     * it: [VoiceFieldText] looks for it there.
     */
    fun sessionText(): String? {
        val index = entries.indexOf(sessionStart ?: return null)
        if (index < 0) return null
        return entries.subList(index, entries.size).joinToString("") { it.text }
    }

    /** Insert a dictated utterance. */
    fun insert(text: String): Entry {
        editor.insert(text)
        val entry = add(text)
        if (newSession) {
            sessionFirst = entry
            newSession = false
        }
        return entry
    }

    /**
     * Insert text typed by hand from the dictation panel (punctuation, space). It becomes part of the
     * dictated run, so earlier utterances can still be refined; undo no longer applies after it.
     */
    fun insertTyped(text: String) {
        editor.insert(text)
        add(text)
        sessionFirst = null
        newSession = false
    }

    private fun add(text: String) = Entry(text).also {
        entries += it
        if (sessionStart == null) sessionStart = it
        while (entries.size > MAX_ENTRIES) entries.removeAt(0).retire()
    }

    /** The text from [entry] to the cursor, if the editor still shows it as dictated. */
    private fun stillInPlace(entry: Entry): String? {
        val index = entries.indexOf(entry)
        if (index < 0 || editor.hasSelection) return null
        val expected = entries.subList(index, entries.size).joinToString("") { it.text }
        if (expected.isEmpty()) return null
        return expected.takeIf { editor.textBeforeCursor(it.length) == it }
    }

    private fun isLastAndInPlace(entry: Entry) =
        entries.lastOrNull() === entry && !editor.hasPreview && stillInPlace(entry) != null

    /**
     * Write [text] over [entry]: the utterance went on after a pause and was transcribed again
     * as a whole (see [VoiceSentence]). Only what differs is rewritten.
     * @return false, with nothing changed, unless [entry] is the last thing dictated and in place
     */
    fun replace(entry: Entry, text: String): Boolean {
        if (!isLastAndInPlace(entry)) return false
        // a refinement of the shorter text would undo this
        refinements.removeAll { it.entry === entry }
        val keep = entry.text.commonPrefixWith(text).length
        if (keep < entry.text.length) editor.deleteBeforeCursor(entry.text.length - keep)
        if (keep < text.length) editor.insert(text.substring(keep))
        entry.text = text
        return true
    }

    /**
     * Add [text] to the end of [entry]: the full stop of a sentence that turned out to be over.
     * @return false, with nothing changed, unless [entry] is the last thing dictated and in place
     */
    fun append(entry: Entry, text: String): Boolean {
        if (!isLastAndInPlace(entry)) return false
        editor.insert(text)
        entry.text += text
        refinements.forEach { if (it.entry === entry) it.text += text }
        return true
    }

    /** A better version of [entry] is available; it is written by [applyRefinements]. */
    fun refine(entry: Entry, text: String) {
        if (text != entry.text) refinements += Refinement(entry, text)
    }

    /**
     * Write the refinements that are still applicable, drop the others.
     * Does nothing while a preview is showing: the preview sits right behind the dictated text.
     * @return how many were applied and how many dropped
     */
    fun applyRefinements(): Pair<Int, Int> {
        if (refinements.isEmpty() || editor.hasPreview) return 0 to 0
        var applied = 0
        var dropped = 0
        val pending = refinements.toList()
        refinements.clear()
        for (r in pending) {
            val shown = stillInPlace(r.entry)
            if (shown == null) {
                dropped++
                continue
            }
            val tail = shown.substring(r.entry.text.length)
            editor.deleteBeforeCursor(shown.length)
            editor.insert(r.text + tail)
            r.entry.text = r.text
            applied++
        }
        return applied to dropped
    }

    /**
     * Remove everything the last session inserted, if it is still in place.
     * @return whether the text was removed
     */
    fun undoSession(): Boolean {
        val first = sessionFirst ?: return false
        sessionFirst = null
        val shown = stillInPlace(first) ?: return false
        editor.deleteBeforeCursor(shown.length)
        // what was removed can no longer be refined either
        val removed = entries.subList(entries.indexOf(first), entries.size)
        removed.forEach { it.retire() }
        removed.clear()
        return true
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
