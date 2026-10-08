/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
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
import androidx.annotation.StringRes
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
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.utils.AppUtil
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import timber.log.Timber

/**
 * Both ways of dictating, and the entry point other components use.
 *
 * Push-to-talk: hold the space bar to speak, release to insert, slide up to cancel. The keyboard
 * must stay in place while the key is held (otherwise the touch gesture would be cancelled), so
 * the listening surface is a non-interactive overlay [view] that covers the keyboard. See "Hold
 * space" in `docs/design/mockup.html`.
 *
 * Hands-free: tap the microphone pill. The keyboard stays as it is and keeps working; the
 * [strip] takes the toolbar's place for as long as it has something to say. See "Hands-free
 * dictation" there, and "Keys while dictating hands-free" in `docs/design/DESIGN.md`.
 */
class VoiceInputComponent : UniqueViewComponent<VoiceInputComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val keyActions: CommonKeyActionListener by manager.must()
    private val toolbar: KawaiiBarComponent by manager.must()

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private val palette by lazy { VoicePalette(theme) }

    private var session: VoiceSession? = null

    private var cancelling = false

    private var failed = false

    private var pill: VoicePillButton? = null

    private val hideSurface = Runnable { hide() }

    private val endUndo = Runnable { pill?.mode = VoicePillButton.Mode.Speak }

    private var isPasswordField = false

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        isPasswordField = capFlags.has(CapabilityFlag.Password)
        // an undo offer does not carry over to another text field
        endUndoOffer()
        // back from the settings or the permission dialog
        if (need != null) {
            if (isAvailable && missing() == null) startDictation() else dismissStrip()
        }
        if (isAvailable && VoiceEngine.isAvailable(context)) VoiceInput.warmUp(service)
    }

    private val status by lazy { VoiceStatusUi(context, palette) }

    private val cancelIcon by lazy {
        ImageView(context).apply { setImageResource(R.drawable.ic_voice_close_24) }
    }

    private val cancelText by lazy {
        TextView(context).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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

    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (v.visibility == View.VISIBLE) {
            v.alpha = 1f
            return
        }
        v.visibility = View.VISIBLE
        if (disableAnimation) {
            v.alpha = 1f
        } else {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun fadeOut(v: View) {
        v.animate().cancel()
        if (disableAnimation || v.visibility != View.VISIBLE) {
            v.visibility = View.GONE
        } else {
            v.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { v.visibility = View.GONE }.start()
        }
    }

    private fun show() {
        view.removeCallbacks(hideSurface)
        cancelling = false
        failed = false
        waveform.level = 0f
        render()
        alignToSpaceBar()
        fadeIn(view)
    }

    private fun hide() = fadeOut(view)

    private val listener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Preparing -> status.preparing()
                VoiceSession.State.Listening -> status.listening()
                VoiceSession.State.Finishing -> {
                    status.recognizing()
                    waveform.mode = WaveformView.Mode.Idle
                }
                VoiceSession.State.Stopped -> {
                    session = null
                    if (failed) {
                        // leave the reason on screen long enough to be read
                        waveform.mode = WaveformView.Mode.Idle
                        view.postDelayed(hideSurface, ERROR_VISIBLE_MS)
                    } else {
                        hide()
                        showAfterSession()
                    }
                }
            }
        }

        override fun onFinal(text: String, samples: FloatArray, continues: Boolean) {
            InputFeedbacks.hapticFeedback(view)
        }

        override fun onLevel(level: Float) {
            waveform.level = level
        }

        override fun onError(e: Throwable) {
            failed = true
            status.error(e)
        }
    }

    /**
     * Whether dictation can be offered for the current editor; never on password fields. It is
     * offered before the speech model has been downloaded, too: the strip then says how to get it.
     */
    val isAvailable get() = !isPasswordField

    /** Space bar is being held. */
    fun startPushToTalk() {
        // not while the microphone is on already
        if (!isAvailable || session != null || dictation != null) return
        missing()?.let {
            // the strip says what is missing (the speech model, microphone access) and offers the fix
            showNeed(it)
            return
        }
        dismissStrip()
        endUndoOffer()
        VoiceHints.onPushToTalkUsed(context)
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
        s.stop(discard = cancel, reason = if (cancel) "release_cancel" else "release")
    }

    /**
     * The toolbar's microphone pill. It normally starts hands-free dictation; for a few seconds
     * after push-to-talk inserted text, it offers to undo that instead.
     */
    fun bindPill(pill: VoicePillButton) {
        this.pill = pill
        VoiceInput.refiningListeners["toolbar"] = {
            // refinement finished: the undo offer starts counting now
            if (pill.mode == VoicePillButton.Mode.Refining && !VoiceInput.isRefining) {
                if (pill.undoes && VoiceInput.canUndoLastSession) offerUndo() else endUndoOffer()
            }
        }
        pill.setOnClickListener {
            when {
                pill.mode != VoicePillButton.Mode.Speak && pill.undoes -> {
                    InputFeedbacks.hapticFeedback(pill)
                    VoiceInput.undoLastSession(service)
                    endUndoOffer()
                }
                else -> startDictation()
            }
        }
    }

    /** What the pill shows once push-to-talk is over. */
    private fun showAfterSession() {
        if (!VoiceInput.canUndoLastSession) return
        if (VoiceInput.isRefining) {
            showRefining(undoes = true)
        } else {
            offerUndo()
        }
    }

    private fun showRefining(undoes: Boolean) {
        val pill = pill ?: return
        pill.removeCallbacks(endUndo)
        pill.undoes = undoes
        pill.mode = VoicePillButton.Mode.Refining
    }

    private fun offerUndo() {
        val pill = pill ?: return
        pill.undoes = true
        pill.mode = VoicePillButton.Mode.Undo
        pill.removeCallbacks(endUndo)
        pill.postDelayed(endUndo, UNDO_OFFER_MS)
    }

    private fun endUndoOffer() {
        pill?.removeCallbacks(endUndo)
        pill?.mode = VoicePillButton.Mode.Speak
    }

    // ---- hands-free dictation --------------------------------------------------------------

    private var dictation: VoiceSession? = null

    private var dictationFailed = false

    /** What dictation cannot start without, and the button that gets it. */
    private enum class Need(
        @StringRes val text: Int, val action: VoiceStripUi.Action, @StringRes val button: Int
    ) {
        Model(R.string.voice_model_needed_title, VoiceStripUi.Action.OpenSettings, R.string.voice_open_settings),
        Permission(R.string.voice_permission_title, VoiceStripUi.Action.Allow, R.string.voice_allow)
    }

    /** What the strip is asking for at the moment, if anything. */
    private var need: Need? = null

    private fun missing() = when {
        !VoiceEngine.isAvailable(context) -> Need.Model
        !VoiceInput.hasPermission(context) -> Need.Permission
        else -> null
    }

    private val stripUi by lazy { VoiceStripUi(context, theme, palette) }

    /** Covers the toolbar, candidates included, while dictation is on or has something to say. */
    val strip: View by lazy {
        @SuppressLint("ViewConstructor")
        object : FrameLayout(context) {
            override fun onDetachedFromWindow() {
                // e.g. the keyboard is built again for another theme: nobody would see the
                // microphone being on
                stopDictation("view_detached")
                super.onDetachedFromWindow()
            }
        }.apply {
            visibility = View.GONE
            setBackgroundColor(palette.surface)
            // nothing under it is to be touched through it
            isClickable = true
            addView(stripUi.root, FrameLayout.LayoutParams(-1, -1))
            stripUi.pill.setOnClickListener {
                when (stripUi.action) {
                    VoiceStripUi.Action.Stop -> {
                        InputFeedbacks.hapticFeedback(it)
                        stopDictation("strip_button")
                    }
                    VoiceStripUi.Action.Speak -> startDictation()
                    // the keyboard does not go online; the settings do, after saying what is fetched
                    VoiceStripUi.Action.OpenSettings -> AppUtil.launchMainToVoiceInput(context)
                    VoiceStripUi.Action.Allow -> VoiceInput.requestPermission(context)
                    VoiceStripUi.Action.None -> {}
                }
            }
            stripUi.hideButton.setOnClickListener { service.requestHideSelf(0) }
        }
    }

    private val dismiss = Runnable { dismissStrip() }

    /** Take the strip away, unless the microphone is on: then it stays until that is over. */
    private fun dismissStrip() {
        if (dictation != null) return
        need = null
        // asked on every key press
        if (strip.visibility != View.VISIBLE) return
        strip.removeCallbacks(dismiss)
        fadeOut(strip)
        toolbar.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun showStrip() {
        strip.removeCallbacks(dismiss)
        fadeIn(strip)
        // what the strip covers is not there for a screen reader either
        toolbar.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun showNeed(need: Need) {
        this.need = need
        stripUi.needs(need.text, need.action, need.button)
        showStrip()
    }

    private val dictationListener = object : VoiceSession.Listener {
        override fun onState(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.Preparing,
                VoiceSession.State.Listening -> stripUi.listening()
                VoiceSession.State.Finishing -> stripUi.finishing()
                VoiceSession.State.Stopped -> {
                    val timedOut = dictation?.endedByIdleTimeout == true
                    dictation = null
                    val typing = heldKeys.isNotEmpty()
                    Timber.d("Voice dictation over: timed out=$timedOut, failed=$dictationFailed, typing=$typing")
                    releaseKeys()
                    when {
                        // typing has the toolbar now
                        typing -> dismissStrip()
                        // an error stays long enough to be read
                        dictationFailed -> strip.postDelayed(dismiss, NOTICE_VISIBLE_MS)
                        // say why the microphone went off by itself
                        timedOut -> {
                            stripUi.off(R.string.voice_off_after_silence)
                            strip.postDelayed(dismiss, NOTICE_VISIBLE_MS)
                        }
                        else -> dismissStrip()
                    }
                    // refinement can outlast listening; the pill says so until it is done
                    if (VoiceInput.isRefining) showRefining(undoes = false)
                }
            }
        }

        override fun onFinal(text: String, samples: FloatArray, continues: Boolean) {
            InputFeedbacks.hapticFeedback(strip)
        }

        override fun onLevel(level: Float) {
            stripUi.waveform.level = level
        }

        override fun onError(e: Throwable) {
            dictationFailed = true
            stripUi.error(e)
        }
    }

    /** The microphone pill was tapped: listen until told otherwise, the keyboard stays. */
    fun startDictation() {
        if (!isAvailable || dictation != null || session != null) return
        endUndoOffer()
        missing()?.let {
            showNeed(it)
            return
        }
        need = null
        dictationFailed = false
        heldKeys.clear()
        InputFeedbacks.hapticFeedback(strip)
        stripUi.listening()
        showStrip()
        dictation = VoiceInput.start(
            service,
            VoiceInput.SILENCE_HANDS_FREE,
            VoiceInput.HANDS_FREE_IDLE_TIMEOUT_MS,
            dictationListener
        )
    }

    /** Turn the microphone off; what has been said is still transcribed and inserted. */
    private fun stopDictation(reason: String) {
        dictation?.stop(reason = reason)
    }

    // Keys pressed after the one that ended dictation, until its last words are written.
    private val heldKeys = ArrayDeque<Pair<KeyAction, KeyActionListener.Source>>()

    private var replaying = false

    private fun releaseKeys() {
        replaying = true
        try {
            while (heldKeys.isNotEmpty()) {
                val (action, source) = heldKeys.removeFirst()
                keyActions.listener.onKeyAction(action, source)
            }
        } finally {
            replaying = false
        }
    }

    // null for the space bar's own gesture, which is push-to-talk's business
    private fun effectOf(action: KeyAction) = when (action) {
        is KeyAction.FcitxKeyAction -> VoiceKeys.ofText(action.act)
        is KeyAction.SymAction -> VoiceKeys.ofSym(action.sym.sym)
        is KeyAction.CommitAction,
        is KeyAction.CapsAction,
        is KeyAction.LayoutSwitchAction -> VoiceKeys.Effect.Keeps
        is KeyAction.MoveSelectionAction,
        is KeyAction.DeleteSelectionAction -> VoiceKeys.Effect.Edits
        is KeyAction.SpaceLongPressAction,
        is KeyAction.SpaceHoldMoveAction,
        is KeyAction.SpaceReleaseAction -> null
        // whatever else starts a composition or leaves the keyboard
        else -> VoiceKeys.Effect.Types
    }

    /**
     * A key of the keyboard was pressed. While dictating hands-free, a letter turns the
     * microphone off, and is typed once what was said has been written: pinyin and the preview
     * of an utterance cannot share the text field.
     * @return true if the key is held back; it is passed on again later
     */
    fun onKeyAction(action: KeyAction, source: KeyActionListener.Source): Boolean {
        if (replaying) return false
        val effect = effectOf(action) ?: return false
        if (dictation == null) {
            // what typing shows in the toolbar must not be hidden by a strip that only says something
            dismissStrip()
            return false
        }
        if (heldKeys.isNotEmpty()) {
            // behind the keys that are waiting already
            heldKeys += action to source
            return true
        }
        return when (effect) {
            VoiceKeys.Effect.Keeps -> false
            VoiceKeys.Effect.Edits -> {
                VoiceInput.closeSentence()
                false
            }
            VoiceKeys.Effect.Types -> {
                stopDictation("typing")
                heldKeys += action to source
                true
            }
        }
    }

    override fun onWindowAttached(window: InputWindow) {
        if (window is KeyboardWindow) return
        // another panel has the toolbar: neither a live microphone nor a notice under its title
        stopDictation("window")
        dismissStrip()
    }

    companion object {
        const val FADE_MS = 140L
        const val ERROR_VISIBLE_MS = 2500L
        const val NOTICE_VISIBLE_MS = 4000L
        const val UNDO_OFFER_MS = 8000L
    }
}
