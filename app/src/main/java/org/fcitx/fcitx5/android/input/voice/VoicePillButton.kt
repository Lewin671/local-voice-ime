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

    enum class Mode {
        /** Opens hands-free dictation. */
        Speak,

        /** Shown for a few seconds after push-to-talk inserted text: removes it again. */
        Undo
    }

    private val icon = ImageView(context)

    private val label = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    var mode = Mode.Speak
        set(value) {
            field = value
            when (value) {
                Mode.Speak -> {
                    icon.setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
                    label.setText(R.string.voice_speak)
                    contentDescription = context.getString(R.string.voice_input)
                }
                Mode.Undo -> {
                    icon.setImageResource(R.drawable.ic_baseline_undo_24)
                    label.setText(R.string.undo)
                    contentDescription = context.getString(R.string.voice_undo)
                }
            }
        }

    init {
        val palette = VoicePalette(theme)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(11), 0, dp(13), 0)
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
        icon.imageTintList = ColorStateList.valueOf(palette.primary)
        label.setTextColor(palette.primary)
        addView(icon, LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(5) })
        addView(label, LayoutParams(-2, -2))
        mode = Mode.Speak
    }
}
