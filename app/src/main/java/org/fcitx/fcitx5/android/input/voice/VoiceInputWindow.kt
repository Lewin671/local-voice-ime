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
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/**
 * Hands-free dictation panel: replaces the keyboard. Listening starts as soon as the panel is
 * shown; every utterance is inserted when the speaker pauses; the microphone turns off when the
 * stop button is tapped, the panel is left, or nobody has spoken for a while.
 *
 * See "Hands-free dictation", "Paused", "Microphone access needed" and "Speech model needed"
 * in `docs/design/mockup.html`.
 */
class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat

    private val palette by lazy { VoicePalette(theme) }

    private var session: VoiceSession? = null

    private val status by lazy { VoiceStatusUi(context, palette) }

    private val ui by lazy {
        // full-width punctuation while a Chinese input method is active
        val chinese = fcitx.runImmediately { inputMethodEntryCached }.languageCode.startsWith("zh")
        Ui(context, palette, punctuationFor(chinese))
    }

    private fun punctuationFor(chinese: Boolean) =
        if (chinese) listOf("，", "。", "？") else listOf(",", ".", "?")

    // the status row takes the place of the toolbar
    override val showTitle = false

    override fun onCreateBarExtension(): View = status.root

    private val listener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Preparing -> {
                    status.preparing()
                    ui.showListening()
                }
                VoiceSession.State.Listening -> {
                    status.listening()
                    ui.showListening()
                }
                VoiceSession.State.Finishing -> {
                    status.recognizing()
                    ui.waveform.mode = WaveformView.Mode.Idle
                }
                VoiceSession.State.Stopped -> {
                    val timedOut = session?.endedByIdleTimeout == true
                    session = null
                    when {
                        // an error message stays until the next attempt
                        failed -> {}
                        VoiceInput.isRefining -> status.refining()
                        // say why the microphone went off by itself
                        timedOut -> status.set(R.string.voice_off_after_silence, palette.secondaryText)
                        else -> status.off()
                    }
                    ui.showPaused()
                }
            }
        }

        override fun onFinal(text: String, samples: FloatArray, continues: Boolean) {
            InputFeedbacks.hapticFeedback(ui.root)
            // offer the punctuation of the language that was just spoken
            ui.setPunctuation(punctuationFor(text.any { it in '\u4e00'..'\u9fff' }))
        }

        override fun onLevel(level: Float) {
            ui.waveform.level = level
        }

        override fun onError(e: Throwable) {
            failed = true
            status.error(e)
        }
    }

    private var failed = false

    private fun start() {
        if (session != null) return
        failed = false
        if (!VoiceEngine.isAvailable(context)) {
            status.off()
            ui.showCard(Ui.Card.Model, VoiceModels.SenseVoice.size)
            return
        }
        if (!VoiceInput.hasPermission(context)) {
            status.off()
            ui.showCard(Ui.Card.Permission)
            return
        }
        InputFeedbacks.hapticFeedback(ui.root)
        session = VoiceInput.start(
            service,
            VoiceInput.SILENCE_HANDS_FREE,
            VoiceInput.HANDS_FREE_IDLE_TIMEOUT_MS,
            listener
        )
    }

    override fun onCreateView(): View = ui.root.also {
        ui.micButton.setOnClickListener {
            if (session != null) {
                InputFeedbacks.hapticFeedback(it)
                session?.stop(reason = "panel_button")
            } else {
                start()
            }
        }
        ui.cardButton.setOnClickListener {
            when (ui.card) {
                // the keyboard does not go online; the settings do, after saying what is fetched
                Ui.Card.Model -> AppUtil.launchMainToVoiceInput(context)
                Ui.Card.Permission -> VoiceInput.requestPermission(context)
                null -> {}
            }
        }
        // coming back from the settings or the permission dialog
        ui.onShown = {
            val resolved = when (ui.card) {
                Ui.Card.Model -> VoiceEngine.isAvailable(context)
                Ui.Card.Permission -> VoiceInput.hasPermission(context)
                null -> false
            }
            if (resolved) start()
        }
        ui.keyboardKey.setOnClickListener { windowManager.attachWindow(KeyboardWindow) }
        ui.punctuationKeys.forEach { key ->
            key.setOnClickListener { VoiceInput.type(service, key.tag as String) }
        }
        ui.spaceKey.setOnClickListener { VoiceInput.type(service, " ") }
        ui.backspaceKey.apply {
            // always exactly one character; removing a whole utterance must be an explicit undo
            setOnClickListener {
                VoiceInput.closeSentence()
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            repeatEnabled = true
            onRepeatListener = {
                VoiceInput.closeSentence()
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(it)
            }
        }
        ui.returnKey.setOnClickListener {
            VoiceInput.closeSentence()
            // send, search or a new line: whatever the text field asks for
            service.handleReturnKey()
        }
    }

    // refinement can outlast listening; say so until it is done
    private val onRefiningChanged: () -> Unit = {
        if (session == null && !failed) {
            if (VoiceInput.isRefining) status.refining() else status.off()
        }
    }

    override fun onAttached() {
        VoiceInput.refiningListeners[this] = onRefiningChanged
        start()
    }

    override fun onDetached() {
        VoiceInput.refiningListeners.remove(this)
        // what has been said is still transcribed and inserted
        session?.stop(reason = "panel_detached")
    }

    class Ui(private val ctx: Context, private val palette: VoicePalette, punctuation: List<String>) {

        private val keyRadius = ctx.dp(ThemeManager.prefs.keyRadius.getValue().toFloat())

        private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

        private fun oval(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        private fun pressable(background: GradientDrawable) = RippleDrawable(
            ColorStateList.valueOf(palette.pressHighlight), background, null
        )

        val waveform = WaveformView(ctx).apply { color = palette.primary }

        // --- card: what is missing, and the one button that fixes it -------------------------

        enum class Card(@StringRes val title: Int, @StringRes val body: Int, @StringRes val button: Int) {
            /** The speech model has not been downloaded; [body] takes its size. */
            Model(R.string.voice_model_needed_title, R.string.voice_model_needed_body, R.string.voice_open_settings),
            Permission(R.string.voice_permission_title, R.string.voice_permission_body, R.string.voice_allow_microphone)
        }

        /** The card on screen, if any. */
        var card: Card? = null
            private set

        private val cardTitle = TextView(ctx).apply {
            setTextColor(palette.text)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private val cardBody = TextView(ctx).apply {
            setTextColor(palette.secondaryText)
            textSize = 13f
        }

        val cardButton = TextView(ctx).apply {
            setTextColor(palette.onPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            background = pressable(rounded(palette.primary, ctx.dp(18f)))
        }

        private val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = ctx.dp(14)
            setPadding(p, ctx.dp(12), p, ctx.dp(12))
            background = rounded(palette.key, ctx.dp(16f))
            visibility = View.GONE
            addView(cardTitle, LinearLayout.LayoutParams(-1, -2))
            addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(cardButton, LinearLayout.LayoutParams(-2, ctx.dp(34)).apply {
                topMargin = ctx.dp(10)
            })
        }

        // --- stop / microphone button ----------------------------------------------------

        private val micIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }

        private val micHalo = View(ctx).apply { background = oval(palette.primaryContainer) }

        val micButton = FrameLayout(ctx).apply {
            val icon = ctx.dp(28)
            addView(micIcon, FrameLayout.LayoutParams(icon, icon, Gravity.CENTER))
        }

        private val micArea = FrameLayout(ctx).apply {
            addView(micHalo, FrameLayout.LayoutParams(ctx.dp(78), ctx.dp(78), Gravity.CENTER))
            addView(micButton, FrameLayout.LayoutParams(ctx.dp(64), ctx.dp(64), Gravity.CENTER))
        }

        // --- utility row -----------------------------------------------------------------

        private fun CustomGestureView.styleAsKey(color: Int) = apply {
            background = rounded(color, keyRadius)
            foreground = RippleDrawable(
                ColorStateList.valueOf(palette.pressHighlight), null, rounded(-1, keyRadius)
            )
        }

        private fun iconKey(
            @DrawableRes icon: Int,
            @StringRes description: Int,
            accent: Boolean,
            color: Int = if (accent) palette.primary else palette.functionKey
        ) =
            CustomGestureView(ctx).apply {
                styleAsKey(color)
                contentDescription = ctx.getString(description)
                addView(ImageView(ctx).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(
                        if (accent) palette.onPrimary else palette.secondaryText
                    )
                }, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
            }

        private fun textKey(text: String) = CustomGestureView(ctx).apply {
            styleAsKey(palette.key)
            addView(TextView(ctx).apply {
                setTextColor(palette.text)
                textSize = 20f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            setKeyText(text)
        }

        // the text a key types is kept in its tag
        private fun CustomGestureView.setKeyText(text: String) {
            tag = text
            contentDescription = text
            (getChildAt(0) as TextView).text = text
        }

        fun setPunctuation(punctuation: List<String>) {
            punctuationKeys.zip(punctuation).forEach { (key, text) -> key.setKeyText(text) }
        }

        val keyboardKey =
            iconKey(R.drawable.ic_baseline_keyboard_24, R.string.back_to_keyboard, false)

        val punctuationKeys = punctuation.map(::textKey)

        // types text like the punctuation keys, so it is drawn as one of them
        val spaceKey =
            iconKey(R.drawable.ic_baseline_space_bar_24, R.string.voice_space, false, palette.key)

        val backspaceKey = iconKey(R.drawable.ic_baseline_backspace_24, R.string.backspace, false)

        val returnKey = iconKey(R.drawable.ic_baseline_keyboard_return_24, R.string.voice_enter, true)

        private val utilityRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = ctx.dp(3)
            setPadding(ctx.dp(1), 0, ctx.dp(1), 0)
            fun add(v: View, weight: Float) = addView(v, LinearLayout.LayoutParams(0, -1, weight)
                .apply { setMargins(gap, 0, gap, 0) })
            add(keyboardKey, 1.5f)
            add(punctuationKeys[0], 1f)
            add(spaceKey, 2f)
            punctuationKeys.drop(1).forEach { add(it, 1f) }
            add(backspaceKey, 1.5f)
            add(returnKey, 1.5f)
        }

        // --- layout ----------------------------------------------------------------------

        /** Called whenever the panel becomes visible again, e.g. after the settings or the permission dialog. */
        var onShown: (() -> Unit)? = null

        val root: View = object : LinearLayout(ctx) {
            override fun onVisibilityAggregated(isVisible: Boolean) {
                super.onVisibilityAggregated(isVisible)
                if (isVisible) onShown?.invoke()
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(FrameLayout(ctx).apply {
                addView(waveform, FrameLayout.LayoutParams(-1, -1))
                addView(cardView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                    setMargins(ctx.dp(12), 0, ctx.dp(12), 0)
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(micArea, LinearLayout.LayoutParams(-1, ctx.dp(82)).apply {
                bottomMargin = ctx.dp(8)
            })
            addView(utilityRow, LinearLayout.LayoutParams(-1, ctx.dp(44)).apply {
                bottomMargin = ctx.dp(6)
            })
        }

        init {
            showPaused()
        }

        private fun showMic(listening: Boolean) {
            micArea.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            cardView.visibility = View.GONE
            card = null
            micHalo.visibility = if (listening) View.VISIBLE else View.INVISIBLE
            micButton.background = pressable(
                oval(if (listening) palette.primary else palette.primaryContainer)
            )
            micButton.contentDescription = ctx.getString(
                if (listening) R.string.voice_stop else R.string.voice_start
            )
            micIcon.setImageResource(
                if (listening) R.drawable.ic_voice_stop_24
                else R.drawable.ic_baseline_keyboard_voice_24
            )
            micIcon.imageTintList = ColorStateList.valueOf(
                if (listening) palette.onPrimary else palette.primary
            )
        }

        fun showListening() {
            showMic(listening = true)
            waveform.mode = WaveformView.Mode.Live
        }

        fun showPaused() {
            showMic(listening = false)
            waveform.level = 0f
            waveform.mode = WaveformView.Mode.Idle
        }

        /** Instead of the waveform and the microphone; [modelSize] is the download [Card.Model] names. */
        fun showCard(card: Card, modelSize: Long = 0) {
            this.card = card
            cardTitle.setText(card.title)
            cardBody.text = when (card) {
                Card.Model -> ctx.getString(card.body, Formatter.formatShortFileSize(ctx, modelSize))
                Card.Permission -> ctx.getString(card.body)
            }
            cardButton.setText(card.button)
            waveform.visibility = View.GONE
            micArea.visibility = View.GONE
            cardView.visibility = View.VISIBLE
        }
    }
}
