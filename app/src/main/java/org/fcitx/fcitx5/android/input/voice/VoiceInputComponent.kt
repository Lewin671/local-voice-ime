/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/**
 * Push-to-talk dictation: hold the space bar to speak, release to insert, slide up to cancel.
 *
 * The keyboard must stay in place while the key is held (otherwise the touch gesture would be
 * cancelled), so the listening surface is a non-interactive overlay [view] that covers the
 * keyboard. See "Hold space" in `docs/design/mockup.html`.
 */
class VoiceInputComponent : UniqueViewComponent<VoiceInputComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val windowManager: InputWindowManager by manager.must()

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private val palette by lazy { VoicePalette(theme) }

    private var session: VoiceSession? = null

    private var cancelling = false

    private var isPasswordField = false

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        isPasswordField = capFlags.has(CapabilityFlag.Password)
    }

    private val status by lazy { VoiceStatusUi(context, palette) }

    private val cancelIcon by lazy {
        ImageView(context).apply { setImageResource(R.drawable.ic_voice_close_24) }
    }

    private val cancelText by lazy {
        TextView(context).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private val cancelPill by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(12), 0, context.dp(14), 0)
            addView(cancelIcon, LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply {
                marginEnd = context.dp(6)
            })
            addView(cancelText, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private val waveform by lazy { WaveformView(context) }

    private val heldText by lazy {
        TextView(context).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            gravity = Gravity.CENTER
            // the space bar is narrow on small phones: shrink the label rather than wrap it
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 9, 13, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** Stand-in for the space bar under the finger; says what releasing will do. */
    private val heldKey by lazy {
        FrameLayout(context).apply {
            setPadding(context.dp(8), 0, context.dp(8), 0)
            addView(heldText, FrameLayout.LayoutParams(-1, context.dp(20), Gravity.CENTER))
        }
    }

    private val column by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                status.root,
                LinearLayout.LayoutParams(-1, context.dp(KawaiiBarComponent.HEIGHT))
            )
            addView(cancelPill, LinearLayout.LayoutParams(-2, context.dp(30)).apply {
                topMargin = context.dp(14)
            })
            addView(waveform, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    override val view by lazy {
        FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(palette.surface)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(heldKey, FrameLayout.LayoutParams(0, 0))
        }
    }

    private fun roundRect(color: Int, radius: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            strokeColor?.let { setStroke(context.dp(1), it, context.dp(4f), context.dp(3f)) }
        }

    private fun render() {
        val accent = if (cancelling) palette.error else palette.primary
        waveform.color = accent
        waveform.mode = if (cancelling) WaveformView.Mode.Flat else WaveformView.Mode.Live

        val pillRadius = context.dp(15f)
        cancelPill.background =
            if (cancelling) roundRect(palette.errorContainer, pillRadius)
            else roundRect(VoicePalette.Transparent, pillRadius, palette.outline)
        val pillColor = if (cancelling) palette.error else palette.secondaryText
        cancelIcon.imageTintList = ColorStateList.valueOf(pillColor)
        cancelText.setTextColor(pillColor)
        cancelText.setText(
            if (cancelling) R.string.voice_release_to_cancel else R.string.voice_slide_up_to_cancel
        )

        heldKey.background =
            roundRect(accent, context.dp(ThemeManager.prefs.keyRadius.getValue().toFloat()))
        val onAccent = if (cancelling) palette.surface else palette.onPrimary
        heldText.setTextColor(onAccent)
        heldText.setText(
            if (cancelling) R.string.voice_slide_down_to_keep else R.string.voice_release_to_insert
        )
    }

    // every keyboard layout has its own space bar; only the one on screen is of interest
    private fun findShown(root: View, id: Int): View? {
        if (root.id == id && root.isShown && root.width > 0) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findShown(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    /** Put [heldKey] exactly over the space bar, and keep the waveform above it. */
    private fun alignToSpaceBar() {
        val space = findShown(inputView.keyboardView, R.id.button_space) ?: return
        val a = IntArray(2).also { space.getLocationInWindow(it) }
        val b = IntArray(2).also { inputView.keyboardView.getLocationInWindow(it) }
        // the visible key cap is inset from the touch area by the key margins
        val prefs = ThemeManager.prefs
        val hMargin = context.dp(prefs.keyHorizontalMargin.getValue())
        val vMargin = context.dp(prefs.keyVerticalMargin.getValue())
        val top = a[1] - b[1]
        (heldKey.layoutParams as FrameLayout.LayoutParams).apply {
            width = space.width - 2 * hMargin
            height = space.height - 2 * vMargin
            leftMargin = a[0] - b[0] + hMargin
            topMargin = top + vMargin
            gravity = Gravity.TOP or Gravity.START
        }
        column.setPadding(0, 0, 0, (inputView.keyboardView.height - top).coerceAtLeast(0))
        heldKey.requestLayout()
    }

    private fun show() {
        cancelling = false
        status.listening()
        waveform.level = 0f
        render()
        alignToSpaceBar()
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (disableAnimation) {
            view.alpha = 1f
        } else {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun hide() {
        view.animate().cancel()
        if (disableAnimation) {
            view.visibility = View.GONE
        } else {
            view.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private val listener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Listening -> status.listening()
                VoiceSession.State.Finishing -> {
                    status.recognizing()
                    waveform.mode = WaveformView.Mode.Idle
                }
                VoiceSession.State.Stopped -> {
                    session = null
                    hide()
                }
            }
        }

        override fun onFinal(text: String) {
            InputFeedbacks.hapticFeedback(view)
        }

        override fun onLevel(level: Float) {
            waveform.level = level
        }

        override fun onError(e: Throwable) {
            status.error(R.string.voice_microphone_unavailable)
        }
    }

    /** Whether dictation can be offered for the current editor; never on password fields. */
    val isAvailable get() = !isPasswordField && VoiceEngine.isAvailable(context)

    /** Space bar is being held. */
    fun startPushToTalk() {
        if (!isAvailable || session != null) return
        if (!VoiceInput.hasPermission(context)) {
            // the panel explains why the microphone is needed and offers to grant access
            showWindow()
            return
        }
        show()
        session = VoiceInput.start(service, VoiceInput.SILENCE_PUSH_TO_TALK, 0, listener)
    }

    /** The finger moved while holding the space bar; [cancel] when it is above the cancel line. */
    fun movePushToTalk(cancel: Boolean) {
        if (session == null || cancel == cancelling) return
        cancelling = cancel
        InputFeedbacks.hapticFeedback(view)
        render()
    }

    /** Space bar was released; [cancel] when the finger was above the cancel line. */
    fun finishPushToTalk(cancel: Boolean) {
        val s = session ?: return
        if (cancel) {
            // double tick
            InputFeedbacks.hapticFeedback(view)
            view.postDelayed({ InputFeedbacks.hapticFeedback(view) }, 80)
        }
        s.stop(discard = cancel)
    }

    /** Open the hands-free dictation panel. */
    fun showWindow() {
        windowManager.attachWindow(VoiceInputWindow())
    }

    companion object {
        const val FADE_MS = 140L
    }
}
