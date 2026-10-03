/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/**
 * Push-to-talk dictation: hold the space bar to speak, release to finish.
 *
 * The keyboard must stay in place while the key is held (otherwise the touch gesture would be
 * cancelled), so feedback is shown in a non-interactive overlay [view] above the keyboard.
 */
class VoiceInputComponent : UniqueViewComponent<VoiceInputComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private var session: VoiceSession? = null

    private var isPasswordField = false

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        isPasswordField = capFlags.has(CapabilityFlag.Password)
    }

    private val status by lazy {
        TextView(context).apply {
            setTextColor(theme.popupTextColor)
            textSize = 13f
            gravity = Gravity.CENTER
        }
    }

    private val partial by lazy {
        TextView(context).apply {
            setTextColor(theme.popupTextColor)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = PARTIAL_LINES
            visibility = View.GONE
        }
    }

    private val level by lazy {
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList =
                android.content.res.ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        }
    }

    private val card by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dp(16)
            setPadding(p, context.dp(12), p, context.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(16).toFloat()
                setColor(theme.popupBackgroundColor)
            }
            elevation = context.dp(6).toFloat()
            addView(partial, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(8)
            })
            addView(level, LinearLayout.LayoutParams(-1, context.dp(4)).apply {
                bottomMargin = context.dp(8)
            })
            addView(status, LinearLayout.LayoutParams(-1, -2))
        }
    }

    override val view by lazy {
        FrameLayout(context).apply {
            visibility = View.GONE
            val margin = context.dp(32)
            addView(
                card,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(margin, 0, margin, context.dp(120))
                }
            )
        }
    }

    private val listener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Listening -> status.setText(R.string.voice_release_to_finish)
                VoiceSession.State.Finishing -> status.setText(R.string.voice_recognizing)
                VoiceSession.State.Stopped -> {
                    session = null
                    view.visibility = View.GONE
                }
            }
        }

        override fun onPartial(text: String) {
            val perLine = ((card.width - card.paddingLeft - card.paddingRight) / partial.textSize).toInt()
            partial.text = VoiceText.tail(text, perLine * PARTIAL_LINES)
            partial.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

        override fun onFinal(text: String) = onPartial("")

        override fun onLevel(level: Float) {
            this@VoiceInputComponent.level.progress = (level * 100).toInt()
        }

        override fun onError(e: Throwable) {
            status.text = e.localizedMessage ?: e.toString()
        }
    }

    /** Whether dictation can be offered for the current editor; never on password fields. */
    val isAvailable get() = !isPasswordField && VoiceEngine.isAvailable(context)

    /** Space bar is being held. */
    fun startPushToTalk() {
        if (!isAvailable || session != null) return
        if (!VoiceInput.hasPermission(context)) {
            VoiceInput.requestPermission(context)
            return
        }
        onPartialReset()
        view.visibility = View.VISIBLE
        session = VoiceInput.start(service, VoiceInput.SILENCE_PUSH_TO_TALK, listener)
    }

    /** Space bar was released; [cancel] when the finger slid away from the key. */
    fun finishPushToTalk(cancel: Boolean) {
        session?.stop(discard = cancel)
    }

    /** Open the hands-free dictation window. */
    fun showWindow() {
        windowManager.attachWindow(VoiceInputWindow())
    }

    companion object {
        private const val PARTIAL_LINES = 3
    }

    private fun onPartialReset() {
        partial.text = ""
        partial.visibility = View.GONE
        level.progress = 0
    }
}
