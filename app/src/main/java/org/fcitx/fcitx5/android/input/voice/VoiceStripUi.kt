/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp

/**
 * The dictation strip: what the toolbar shows while hands-free dictation is on, or has
 * something to say about why it is not.
 * `··▂▅▇▅▂  🔒 On-device  (■)  ▾` while the microphone is on: the waveform says so, without words.
 * Words appear when there is something to say: `● Off after 10 s of silence  [Speak]  ▾`.
 *
 * See "Hands-free dictation" and the screens after it in `docs/design/mockup.html`.
 */
class VoiceStripUi(private val ctx: Context, theme: Theme, private val palette: VoicePalette) {

    /** What the pill does; it is where the toolbar has its microphone pill. */
    enum class Action { None, Stop, Speak, OpenSettings, Allow }

    val waveform = LevelTraceView(ctx).apply {
        // a little lighter than the things that act
        color = ColorUtils.setAlphaComponent(palette.primary, 0xcc)
    }

    private val status = VoiceStatusUi(ctx, palette, waveform)

    private val icon = ImageView(ctx)

    private val label = TextView(ctx).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
    }

    val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        addView(icon, LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).apply { marginEnd = ctx.dp(5) })
        addView(label, LinearLayout.LayoutParams(-2, -2))
    }

    val hideButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, theme).apply {
        contentDescription = ctx.getString(R.string.hide_keyboard)
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val height = ctx.dp(KawaiiBarComponent.HEIGHT)
        addView(status.root, LinearLayout.LayoutParams(0, -1, 1f))
        addView(pill, LinearLayout.LayoutParams(-2, ctx.dp(30)).apply { marginEnd = ctx.dp(2) })
        addView(hideButton, LinearLayout.LayoutParams(height, height))
    }

    var action = Action.None
        private set

    private fun show(
        action: Action,
        microphoneOn: Boolean = false,
        @StringRes text: Int = 0,
        @StringRes description: Int = text,
        icon: Int = 0
    ) {
        this.action = action
        status.microphoneOn = microphoneOn
        waveform.live = microphoneOn
        if (action == Action.None) {
            pill.visibility = View.GONE
            return
        }
        pill.visibility = View.VISIBLE
        // tinted like the toolbar's pill: the one filled accent of the keyboard is the enter key
        val content = palette.primary
        pill.background = RippleDrawable(
            ColorStateList.valueOf(palette.pressHighlight),
            GradientDrawable().apply {
                cornerRadius = ctx.dp(15f)
                setColor(if (text == 0) palette.buttonContainer else palette.primaryContainer)
            },
            null
        )
        // the glyph alone makes a round button
        pill.minimumWidth = ctx.dp(30)
        pill.contentDescription = ctx.getString(description)
        label.visibility = if (text == 0) View.GONE else View.VISIBLE
        if (text != 0) label.setText(text)
        label.setTextColor(content)
        this.icon.visibility = if (icon == 0) View.GONE else View.VISIBLE
        if (icon != 0) {
            this.icon.setImageResource(icon)
            this.icon.imageTintList = ColorStateList.valueOf(content)
        }
        // only next to an icon is the label's side padded a little more
        val both = icon != 0 && text != 0
        (this.icon.layoutParams as LinearLayout.LayoutParams).apply {
            marginEnd = if (both) ctx.dp(5) else 0
            val size = ctx.dp(if (text == 0) 20 else 17)
            width = size
            height = size
        }
        val side = if (text == 0) 0 else 14
        pill.setPadding(ctx.dp(if (both) 11 else side), 0, ctx.dp(if (both) 13 else side), 0)
    }

    /**
     * The microphone is on, whether or not the speech model is still loading: nothing said
     * meanwhile is lost, and the waveform already follows the voice.
     */
    fun listening() {
        status.wordless(R.string.voice_listening)
        show(
            Action.Stop, microphoneOn = true,
            description = R.string.voice_stop, icon = R.drawable.ic_voice_stop_24
        )
    }

    /** The microphone is off; the last words are still being written. */
    fun finishing() {
        status.recognizing()
        show(Action.None)
    }

    /** The microphone went off by itself, and why; one tap turns it on again. */
    fun off(@StringRes reason: Int) {
        status.notice(reason)
        show(
            Action.Speak,
            text = R.string.voice_speak, description = R.string.voice_input,
            icon = R.drawable.ic_baseline_keyboard_voice_24
        )
    }

    /** Over the full width: a cause that is cut short explains nothing. */
    fun error(e: Throwable) {
        status.error(e)
        show(Action.None)
    }

    /** Dictation cannot start; the pill is what the user can do about it. */
    fun needs(@StringRes what: Int, action: Action, @StringRes button: Int) {
        status.notice(what)
        show(action, text = button)
    }
}
