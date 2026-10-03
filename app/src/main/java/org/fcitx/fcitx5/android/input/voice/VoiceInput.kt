/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/**
 * Entry points shared by the voice UIs (hands-free [VoiceInputWindow] and push-to-talk
 * [VoiceInputComponent]), and the only place that writes dictated text into the editor.
 * At most one [VoiceSession] is alive at any time.
 */
object VoiceInput {

    /** Trailing silence that ends an utterance in hands-free mode. */
    const val SILENCE_HANDS_FREE = 0.7f

    /** While the user holds the key, pauses are more likely to be mid-sentence. */
    const val SILENCE_PUSH_TO_TALK = 1.2f

    /** Hands-free listening turns the microphone off after this long without speech. */
    const val HANDS_FREE_IDLE_TIMEOUT_MS = 10_000L

    /**
     * Debug builds only: when this file exists in the app's external files dir, it is used
     * instead of the microphone, so that the whole pipeline can be tested with `adb`.
     */
    private const val TEST_WAV = "voice-test.wav"

    private const val WARM_UP_WINDOW_MS = 30 * 60_000L

    private var current: VoiceSession? = null

    private var lastUsedAt = 0L

    /** Everything the last session put into the editor, for [undoLastSession]. */
    private var sessionInserted = ""

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun createSource(context: Context): AudioSource {
        if (BuildConfig.DEBUG) {
            val wav = context.getExternalFilesDir(null)?.resolve(TEST_WAV)
            if (wav?.exists() == true) return WavFileSource(wav)
        }
        return MicrophoneSource()
    }

    /**
     * Start a new session (stopping the previous one, if any).
     *
     * The utterance in progress is shown in the editor as composing text and replaced by the
     * final transcript when it is complete; [listener] is for UI feedback only.
     *
     * @param idleTimeoutMs stop by itself after this long without speech; 0 to never do so
     */
    fun start(
        service: FcitxInputMethodService,
        minSilence: Float,
        idleTimeoutMs: Long,
        listener: VoiceSession.Listener
    ): VoiceSession {
        current?.stop()
        lastUsedAt = SystemClock.elapsedRealtime()
        // drop unfinished pinyin composition, so that it doesn't interleave with dictated text
        service.postFcitxJob { reset() }
        sessionInserted = ""
        lateinit var session: VoiceSession
        // separator between the text already in the editor and the utterance in progress;
        // decided when its first preview arrives, as the preview itself hides the text before it
        var joiner: String? = null
        var previewShown = false
        // Set when the user moved the cursor while an utterance was being previewed: the editor
        // then keeps the preview as ordinary text where it was, and writing anything more would
        // put the same words a second time at the new cursor position.
        var abandoned = false

        fun previewWasDetached(): Boolean {
            if (abandoned) return true
            if (previewShown && !service.hasComposingText) {
                abandoned = true
                previewShown = false
                session.stop(discard = true)
            }
            return abandoned
        }

        fun joinerFor(text: String): String = joiner ?: VoiceText.joiner(
            service.currentInputConnection?.getTextBeforeCursor(1, 0), text
        ).also { joiner = it }

        fun clearPreview() {
            if (previewShown) {
                service.setVoicePreview("")
                previewShown = false
            }
            joiner = null
        }

        session = VoiceSession(
            service,
            service.lifecycleScope,
            createSource(service),
            minSilence,
            idleTimeoutMs,
            object : VoiceSession.Listener by listener {
                override fun onPartial(text: String) {
                    if (previewWasDetached()) return
                    val shown = VoiceText.stripTrailingPunctuation(text)
                    if (shown.isEmpty()) {
                        clearPreview()
                    } else {
                        service.setVoicePreview(joinerFor(shown) + shown)
                        previewShown = true
                    }
                    listener.onPartial(shown)
                }

                override fun onFinal(text: String) {
                    if (previewWasDetached()) return
                    val inserted = joinerFor(text) + text
                    // replaces the composing preview, if there is one
                    service.commitText(inserted)
                    previewShown = false
                    joiner = null
                    sessionInserted += inserted
                    listener.onFinal(text)
                }

                override fun onState(state: VoiceSession.State) {
                    if (state == VoiceSession.State.Stopped) {
                        // never leave a preview behind, e.g. after cancelling
                        clearPreview()
                        if (current === session) current = null
                    }
                    listener.onState(state)
                }
            }
        )
        current = session
        session.start()
        return session
    }

    /**
     * Called when the keyboard is shown for an editor. If dictation was used a short while ago,
     * load the model again in the background (it is freed after a few idle minutes), so that the
     * preview appears without delay when the user speaks.
     */
    fun warmUp(service: FcitxInputMethodService) {
        if (lastUsedAt == 0L || SystemClock.elapsedRealtime() - lastUsedAt > WARM_UP_WINDOW_MS) return
        service.lifecycleScope.launch {
            runCatching { VoiceEngine.ensureLoaded(service) }
        }
    }

    /** Called when the keyboard is hidden: stop listening, but keep what was already said. */
    fun stopCurrent(discard: Boolean = false) {
        current?.stop(discard)
    }

    /** Whether [undoLastSession] has something to remove. */
    val canUndoLastSession get() = sessionInserted.isNotEmpty()

    /**
     * Remove everything the last session inserted, provided it is still right before the cursor
     * (i.e. nothing else has edited the text since).
     * @return whether the text was removed
     */
    fun undoLastSession(service: FcitxInputMethodService): Boolean {
        val ic = service.currentInputConnection ?: return false
        val inserted = sessionInserted
        sessionInserted = ""
        if (inserted.isEmpty() ||
            !ic.getSelectedText(0).isNullOrEmpty() ||
            ic.getTextBeforeCursor(inserted.length, 0)?.toString() != inserted
        ) return false
        ic.deleteSurroundingText(inserted.length, 0)
        return true
    }

    /** Text typed from the dictation panel (punctuation); after it, undo no longer applies. */
    fun type(service: FcitxInputMethodService, text: String) {
        sessionInserted = ""
        service.commitText(text)
    }
}
