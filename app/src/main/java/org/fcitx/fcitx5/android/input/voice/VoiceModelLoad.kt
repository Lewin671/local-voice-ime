/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Called on the native worker, after preceding queued requests, never on the UI thread. */
internal object VoiceModelLoad {
    inline fun run(needed: () -> Boolean, load: () -> Unit): Boolean {
        if (!needed()) return false
        load()
        return true
    }
}
