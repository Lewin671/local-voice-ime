/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp

/**
 * Status row shown in place of the toolbar while a voice surface is up:
 * `● Listening                         🔒 On-device`
 *
 * The right half never changes: whenever the microphone may be on, the screen says where the
 * audio goes.
 */
class VoiceStatusUi(ctx: Context, private val palette: VoicePalette) {

    private val dot = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    }

    private val label = TextView(ctx).apply {
        // screen readers announce every change of the status
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setTextColor(palette.secondaryText)
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val lock = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_voice_lock_24)
        imageTintList = ColorStateList.valueOf(palette.secondaryText)
    }

    private val onDevice = TextView(ctx).apply {
        setText(R.string.voice_on_device)
        setTextColor(palette.secondaryText)
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply {
            marginEnd = ctx.dp(6)
        })
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        addView(lock, LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply {
            marginEnd = ctx.dp(5)
        })
        addView(onDevice, LinearLayout.LayoutParams(-2, -2))
    }

    fun set(@StringRes text: Int, @ColorInt dotColor: Int) {
        label.setText(text)
        (dot.background as GradientDrawable).setColor(dotColor)
    }

    /** Recording, while the speech model is still loading: nothing said now is lost. */
    fun preparing() = set(R.string.voice_preparing, palette.primary)

    fun listening() = set(R.string.voice_listening, palette.primary)

    fun recognizing() = set(R.string.voice_recognizing, palette.primary)

    /** High-accuracy build: the microphone is off, inserted text is still being re-checked. */
    fun refining() = set(R.string.voice_refining, palette.primary)

    fun off() = set(R.string.voice_microphone_off, palette.secondaryText)

    fun error(@StringRes text: Int) = set(text, palette.error)

    fun error(e: Throwable) = error(
        when ((e as? VoiceException)?.kind) {
            VoiceException.Kind.MicrophoneBusy -> R.string.voice_microphone_busy
            VoiceException.Kind.ModelLoadFailed -> R.string.voice_model_load_failed
            else -> R.string.voice_microphone_unavailable
        }
    )
}
