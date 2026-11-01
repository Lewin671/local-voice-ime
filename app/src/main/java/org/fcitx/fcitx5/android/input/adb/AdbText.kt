/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.adb

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/**
 * The text an [AdbTextReceiver] broadcast asks to commit: either as is (extra `text`) or as
 * UTF-8 encoded in Base64 (extra `base64`), which survives the shell quoting of
 * `adb shell am broadcast` whatever the characters. Pure Kotlin, unit-tested.
 */
object AdbText {

    const val EXTRA_TEXT = "text"
    const val EXTRA_BASE64 = "base64"

    /** Far more than anyone types into one field; keeps a mistaken command from flooding it. */
    const val MAX_LENGTH = 100_000

    sealed interface Parsed {
        data class Text(val text: String) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    private val base64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun parse(text: String?, base64Text: String?): Parsed {
        if (text != null && base64Text != null) {
            return Parsed.Invalid("give either --es $EXTRA_TEXT or --es $EXTRA_BASE64, not both")
        }
        val result = when {
            text != null -> text
            base64Text != null -> decode(base64Text) ?: return Parsed.Invalid(
                "--es $EXTRA_BASE64 is not Base64-encoded UTF-8"
            )
            else -> return Parsed.Invalid("missing --es $EXTRA_TEXT or --es $EXTRA_BASE64")
        }
        if (result.isEmpty()) return Parsed.Invalid("the text is empty")
        if (result.length > MAX_LENGTH) {
            return Parsed.Invalid("the text is longer than $MAX_LENGTH characters")
        }
        return Parsed.Text(result)
    }

    private fun decode(encoded: String): String? {
        // `base64` on Linux wraps its output at 76 characters
        val compact = encoded.filterNot { it.isWhitespace() }
        val bytes = try {
            base64.decode(compact)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
