/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.adb

import org.fcitx.fcitx5.android.input.adb.AdbText.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTextTest {

    private fun invalid(parsed: Parsed) = assertTrue(parsed.toString(), parsed is Parsed.Invalid)

    @Test
    fun plainTextIsTakenAsIs() {
        assertEquals(Parsed.Text("PM2.5 高于 12"), AdbText.parse("PM2.5 高于 12", null))
    }

    @Test
    fun base64IsUtf8() {
        // printf '净化器6 PM2.5 12–20 1挡' | base64
        assertEquals(
            Parsed.Text("净化器6 PM2.5 12–20 1挡"),
            AdbText.parse(null, "5YeA5YyW5ZmoNiBQTTIuNSAxMuKAkzIwIDHmjKE=")
        )
    }

    @Test
    fun base64MayLackPaddingOrBeWrapped() {
        assertEquals(Parsed.Text("ab"), AdbText.parse(null, "YWI"))
        assertEquals(Parsed.Text("abcd"), AdbText.parse(null, "YWJj\nZA==\n"))
    }

    @Test
    fun malformedBase64IsRejected() {
        invalid(AdbText.parse(null, "not base64!"))
    }

    @Test
    fun bytesThatAreNotUtf8AreRejected() {
        // 0xff 0xfe
        invalid(AdbText.parse(null, "//4="))
    }

    @Test
    fun exactlyOneNonEmptyTextIsRequired() {
        invalid(AdbText.parse(null, null))
        invalid(AdbText.parse("a", "YQ=="))
        invalid(AdbText.parse("", null))
        invalid(AdbText.parse(null, ""))
    }

    @Test
    fun overlongTextIsRejected() {
        assertEquals(
            Parsed.Text("x".repeat(AdbText.MAX_LENGTH)),
            AdbText.parse("x".repeat(AdbText.MAX_LENGTH), null)
        )
        invalid(AdbText.parse("x".repeat(AdbText.MAX_LENGTH + 1), null))
    }
}
