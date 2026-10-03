/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/**
 * The microphone pill in the toolbar: the one tinted element of the idle keyboard, opening
 * hands-free dictation.
 */
@SuppressLint("ViewConstructor")
class VoicePillButton(context: Context, theme: Theme) : LinearLayout(context) {

    init {
        val palette = VoicePalette(theme)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(11), 0, dp(13), 0)
        contentDescription = context.getString(R.string.voice_input)
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(palette.pressHighlight),
            GradientDrawable().apply {
                cornerRadius = dp(15f)
                setColor(palette.primaryContainer)
            },
            null
        )
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            imageTintList = ColorStateList.valueOf(palette.primary)
        }, LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(5) })
        addView(TextView(context).apply {
            setText(R.string.voice_speak)
            setTextColor(palette.primary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(-2, -2))
    }
}
