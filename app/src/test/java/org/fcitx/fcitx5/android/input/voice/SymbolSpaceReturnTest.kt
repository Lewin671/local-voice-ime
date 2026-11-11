/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolSpaceReturnTest {
    @Test
    fun spacesWithoutSymbolsStayOnSymbols() {
        val visit = SymbolSpaceReturn()
        repeat(3) { assertFalse(visit.onSpace()) }
    }

    @Test
    fun severalSymbolsThenSpaceReturnOnlyOnce() {
        val visit = SymbolSpaceReturn()
        repeat(5) { visit.onSymbol() }
        assertTrue(visit.onSpace())
        assertFalse(visit.onSpace())
    }

    @Test
    fun aFreshVisitDoesNotRememberEarlierTyping() {
        val visit = SymbolSpaceReturn()
        visit.onSymbol()
        visit.reset()
        assertFalse(visit.onSpace())
        visit.onSymbol()
        assertTrue(visit.onSpace())
    }
}
