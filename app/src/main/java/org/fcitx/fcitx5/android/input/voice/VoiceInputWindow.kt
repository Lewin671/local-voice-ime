/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import splitties.dimensions.dp

/**
 * Hands-free dictation: replaces the keyboard with a big microphone button. Listening starts as
 * soon as the window is shown, every utterance is committed when the speaker pauses, and
 * listening goes on until the microphone button is tapped or the window is closed.
 */
class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()

    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat

    private var session: VoiceSession? = null

    private val ui by lazy { Ui(context, theme) }

    private val listener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Listening -> ui.setStatus(R.string.voice_listening, active = true)
                VoiceSession.State.Finishing -> ui.setStatus(R.string.voice_recognizing, active = false)
                VoiceSession.State.Stopped -> {
                    session = null
                    ui.setLevel(0f)
                    ui.setPartial("")
                    ui.setStatus(R.string.voice_tap_to_speak, active = false)
                }
            }
        }

        override fun onPartial(text: String) = ui.setPartial(text)

        override fun onFinal(text: String) = ui.setPartial("")

        override fun onLevel(level: Float) = ui.setLevel(level)

        override fun onError(e: Throwable) {
            ui.setPartial(e.localizedMessage ?: e.toString())
        }
    }

    private fun start() {
        if (!VoiceEngine.isAvailable(context)) {
            ui.setStatus(R.string.voice_model_missing, active = false)
            return
        }
        if (!VoiceInput.hasPermission(context)) {
            ui.setStatus(R.string.voice_permission_required, active = false)
            VoiceInput.requestPermission(context)
            return
        }
        session = VoiceInput.start(service, VoiceInput.SILENCE_HANDS_FREE, listener)
    }

    override fun onCreateView(): View = ui.root.also {
        ui.micButton.setOnClickListener {
            InputFeedbacks.hapticFeedback(it)
            session?.stop() ?: start()
        }
        ui.backspaceButton.apply {
            setOnClickListener { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
            repeatEnabled = true
            onRepeatListener = {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(it)
            }
        }
        ui.returnButton.setOnClickListener {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onAttached() {
        ui.setPartial("")
        start()
    }

    override fun onDetached() {
        // what has been said is still transcribed and committed
        session?.stop()
    }

    override val title by lazy { context.getString(R.string.voice_input) }

    class Ui(private val ctx: Context, private val theme: Theme) {

        private val accent = theme.accentKeyBackgroundColor
        private val idle = theme.altKeyBackgroundColor

        private fun circle(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        val partial = TextView(ctx).apply {
            setTextColor(theme.keyTextColor)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = PARTIAL_LINES
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
        }

        private val halo = View(ctx).apply {
            background = circle(ColorUtils.setAlphaComponent(accent, 0x55))
        }

        private val micIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        }

        val micButton = FrameLayout(ctx).apply {
            contentDescription = ctx.getString(R.string.voice_input)
            addView(micIcon, FrameLayout.LayoutParams(-1, -1))
        }

        private val micContainer = FrameLayout(ctx).apply {
            clipChildren = false
            val halo = ctx.dp(72)
            val button = ctx.dp(72)
            addView(this@Ui.halo, FrameLayout.LayoutParams(halo, halo, Gravity.CENTER))
            addView(micButton, FrameLayout.LayoutParams(button, button, Gravity.CENTER))
        }

        private fun sideButton(@DrawableRes icon: Int, description: Int) =
            ToolButton(ctx, icon, theme).apply {
                contentDescription = ctx.getString(description)
            }

        val backspaceButton = sideButton(R.drawable.ic_baseline_backspace_24, R.string.backspace)

        val returnButton = sideButton(R.drawable.ic_baseline_keyboard_return_24, R.string.voice_enter)

        private val status = TextView(ctx).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ctx.dp(8))
        }

        private val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            val side = ctx.dp(56)
            addView(backspaceButton, LinearLayout.LayoutParams(side, side))
            addView(micContainer, LinearLayout.LayoutParams(ctx.dp(160), ctx.dp(112)))
            addView(returnButton, LinearLayout.LayoutParams(side, side))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(partial, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
        }

        init {
            setStatus(R.string.voice_tap_to_speak, active = false)
        }

        fun setStatus(text: Int, active: Boolean) {
            status.setText(text)
            micButton.background = circle(if (active) accent else idle)
            micIcon.imageTintList = ColorStateList.valueOf(
                if (active) theme.accentKeyTextColor else theme.altKeyTextColor
            )
            if (!active) setLevel(0f)
        }

        fun setPartial(text: String) {
            // full-width characters are about as wide as the text size
            val perLine = ((partial.width - partial.paddingLeft - partial.paddingRight) / partial.textSize).toInt()
            partial.text = VoiceText.tail(text, perLine * PARTIAL_LINES)
        }

        companion object {
            private const val PARTIAL_LINES = 2
        }

        fun setLevel(level: Float) {
            val scale = 1f + level * 0.45f
            halo.scaleX = scale
            halo.scaleY = scale
        }
    }
}
