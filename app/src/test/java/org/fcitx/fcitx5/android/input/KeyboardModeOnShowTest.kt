/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModeOnShowTest {

    @Test
    fun onScreenKeyboardStaysWhateverTheDevice() {
        for (restarting in listOf(false, true)) {
            for (hasPhysicalKeyboard in listOf(false, true)) {
                assertTrue(
                    KeyboardModeOnShow.useVirtualKeyboard(
                        wasVirtual = true,
                        restarting = restarting,
                        hasPhysicalKeyboard = hasPhysicalKeyboard
                    )
                )
            }
        }
    }

    @Test
    fun injectedKeyDoesNotHideTheKeyboardBeyondTheNextShow() {
        assertTrue(
            KeyboardModeOnShow.useVirtualKeyboard(
                wasVirtual = false, restarting = false, hasPhysicalKeyboard = false
            )
        )
    }

    @Test
    fun restartingInputKeepsTheModeWhileTyping() {
        assertFalse(
            KeyboardModeOnShow.useVirtualKeyboard(
                wasVirtual = false, restarting = true, hasPhysicalKeyboard = false
            )
        )
    }

    @Test
    fun physicalKeyboardKeepsItsMode() {
        for (restarting in listOf(false, true)) {
            assertFalse(
                KeyboardModeOnShow.useVirtualKeyboard(
                    wasVirtual = false, restarting = restarting, hasPhysicalKeyboard = true
                )
            )
        }
    }
}
