/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Text and editor keys wait behind a dictation preview, in the order they were pressed. */
class VoicePendingKeys {
    private data class Pending(val editor: Int, val action: () -> Unit)
    private val pending = ArrayDeque<Pending>()
    val isEmpty get() = pending.isEmpty()

    fun add(editor: Int, action: () -> Unit) {
        pending.addLast(Pending(editor, action))
    }

    fun clear() = pending.clear()

    fun flush(editor: () -> Int) {
        // An editor callback can enqueue more work; leave that for the next final result.
        val ready = pending.toList()
        pending.clear()
        ready.forEach { if (it.editor == editor()) it.action() }
    }
}
