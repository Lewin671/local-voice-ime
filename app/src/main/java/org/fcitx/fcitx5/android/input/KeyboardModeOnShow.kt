/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Which keyboard the "follow the input device" mode starts with when the input view starts.
 *
 * A key press from a physical keyboard hides the on-screen keyboard, and only a touch reported
 * by the editor brings it back. Editors that report none (web pages; anything before API 34 that
 * does not call `viewClicked`) leave it hidden for good after a single injected key, as sent by
 * `adb shell input` or a remote control. So the on-screen keyboard comes back whenever the
 * keyboard is shown anew and the device has no physical keyboard to type on instead.
 *
 * Kept free of Android dependencies so that it can be covered by plain JVM unit tests.
 */
object KeyboardModeOnShow {

    /**
     * @param wasVirtual the on-screen keyboard was in use until now
     * @param restarting input restarts in the editor the keyboard is already shown for
     * @param hasPhysicalKeyboard the device reports a keyboard with keys to type on
     * @return should use the on-screen keyboard
     */
    fun useVirtualKeyboard(
        wasVirtual: Boolean,
        restarting: Boolean,
        hasPhysicalKeyboard: Boolean
    ): Boolean = wasVirtual || (!restarting && !hasPhysicalKeyboard)
}
