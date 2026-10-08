/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
 * The right half says where the audio goes whenever the microphone may be on. In the dictation
 * strip a [waveform] sits between the two halves and takes the room the status leaves.
 */
class VoiceStatusUi(
    ctx: Context,
    private val palette: VoicePalette,
    private val waveform: View? = null
) {

    private val dot = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    }

    private val label = TextView(ctx).apply {
        // screen readers announce every change of the status
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setTextColor(palette.secondaryText)
        textSize = if (waveform == null) 12f else 13f
        if (waveform == null) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // cut short rather than push the lock off the row
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val lock = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_voice_lock_24)
        imageTintList = ColorStateList.valueOf(palette.secondaryText)
    }

    private val onDevice = TextView(ctx).apply {
        setText(R.string.voice_on_device)
        setTextColor(palette.secondaryText)
        textSize = 12f
        if (waveform == null) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
    }

    // what is on its right is measured first and so keeps its width
    private val state = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply {
            marginEnd = ctx.dp(6)
        })
        addView(label, LinearLayout.LayoutParams(-2, -2))
        waveform?.let {
            addView(it, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = ctx.dp(12)
                marginEnd = ctx.dp(12)
            })
        }
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (waveform == null) {
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            addView(state, LinearLayout.LayoutParams(0, -1, 1f))
            addView(lock, LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply {
                marginEnd = ctx.dp(5)
            })
            addView(onDevice, LinearLayout.LayoutParams(-2, -2))
        } else {
            // the strip: where the audio goes first, the trace fading out towards it
            setPadding(ctx.dp(16), 0, 0, 0)
            addView(lock, LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply {
                marginEnd = ctx.dp(4)
            })
            addView(onDevice, LinearLayout.LayoutParams(-2, -2))
            addView(state, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    /**
     * Whether the lock, "On-device" and the waveform are shown. The dictation strip shows them
     * exactly while the microphone is on; it also has things to say when it is off.
     */
    var microphoneOn = true
        set(value) {
            field = value
            val visibility = if (value) View.VISIBLE else View.GONE
            lock.visibility = visibility
            onDevice.visibility = visibility
            waveform?.visibility = visibility
        }

    fun set(@StringRes text: Int, @ColorInt dotColor: Int) {
        root.contentDescription = null
        label.visibility = View.VISIBLE
        label.setText(text)
        if (waveform == null) {
            (dot.background as GradientDrawable).setColor(dotColor)
        } else {
            // the strip has no dot: its words are coloured themselves
            dot.visibility = View.GONE
            label.setTextColor(if (dotColor == palette.error) dotColor else palette.secondaryText)
        }
    }

    /**
     * No words: in the dictation strip the moving waveform says that the microphone is on.
     * A screen reader is told [text] instead.
     */
    fun wordless(@StringRes text: Int) {
        dot.visibility = View.GONE
        label.visibility = View.GONE
        val said = root.context.getString(text)
        if (root.contentDescription != said) root.announceForAccessibility(said)
        root.contentDescription = said
    }

    /** Recording, while the speech model is still loading: nothing said now is lost. */
    fun preparing() = set(R.string.voice_preparing, palette.primary)

    fun listening() = set(R.string.voice_listening, palette.primary)

    fun recognizing() = set(R.string.voice_recognizing, palette.primary)

    /** The microphone is off, and why, or what it takes to turn it on. */
    fun notice(@StringRes text: Int) = set(text, palette.secondaryText)

    fun error(@StringRes text: Int) = set(text, palette.error)

    fun error(e: Throwable) = error(
        when ((e as? VoiceException)?.kind) {
            VoiceException.Kind.MicrophoneBusy -> R.string.voice_microphone_busy
            VoiceException.Kind.ModelLoadFailed -> R.string.voice_model_load_failed
            else -> R.string.voice_microphone_unavailable
        }
    )
}
