/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** One symbol visit spans both pages; a new field or input method starts a fresh visit. */
class SymbolSpaceReturn {
    private var typed = false

    fun reset() {
        typed = false
    }

    fun onSymbol() {
        typed = true
    }

    /** Called only for a short space tap, never for a hold or cursor gesture. */
    fun onSpace(): Boolean = typed.also { reset() }
}
