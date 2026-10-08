/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Colours of the voice UI, derived from the active keyboard [Theme] so that dictation looks at
 * home in every theme (see the token table in `docs/design/DESIGN.md`).
 */
class VoicePalette(theme: Theme) {

    /** Opaque background of the listening surface. */
    @ColorInt
    val surface: Int = ColorUtils.setAlphaComponent(theme.backgroundColor, 0xff)

    @ColorInt
    val key: Int = theme.keyBackgroundColor

    @ColorInt
    val functionKey: Int = theme.altKeyBackgroundColor

    @ColorInt
    val text: Int = theme.keyTextColor

    @ColorInt
    val secondaryText: Int = theme.altKeyTextColor

    @ColorInt
    val primary: Int = theme.accentKeyBackgroundColor

    @ColorInt
    val onPrimary: Int = theme.accentKeyTextColor

    /** Low-emphasis tint of [primary]; content on it uses [primary] itself. */
    @ColorInt
    val primaryContainer: Int = ColorUtils.blendARGB(surface, primary, 0.2f)

    /** Under a glyph that has no label to carry it: on a dark keyboard [primaryContainer] is too faint. */
    @ColorInt
    val buttonContainer: Int = ColorUtils.blendARGB(surface, primary, if (theme.isDark) 0.32f else 0.2f)

    @ColorInt
    val error: Int = if (theme.isDark) 0xfff2b8b5.toInt() else 0xffb3261e.toInt()

    @ColorInt
    val errorContainer: Int = if (theme.isDark) 0xff5c1d1a.toInt() else 0xfff9dedc.toInt()

    @ColorInt
    val pressHighlight: Int = theme.keyPressHighlightColor

    @ColorInt
    val outline: Int = ColorUtils.setAlphaComponent(secondaryText, 0x80)

    companion object {
        @ColorInt
        val Transparent = Color.TRANSPARENT
    }
}
