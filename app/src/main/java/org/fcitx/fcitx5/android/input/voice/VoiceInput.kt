/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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

    private var current: VoiceSession? = null

    /** What the last finished utterance put into the editor, for [backspace]. */
    private var lastInserted = ""

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
        // drop unfinished pinyin composition, so that it doesn't interleave with dictated text
        service.postFcitxJob { reset() }
        lastInserted = ""
        lateinit var session: VoiceSession
        // separator between the text already in the editor and the utterance in progress;
        // decided when its first preview arrives, as the preview itself hides the text before it
        var joiner: String? = null
        var previewShown = false

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
                    if (text.isEmpty()) {
                        clearPreview()
                    } else {
                        service.setVoicePreview(joinerFor(text) + text)
                        previewShown = true
                    }
                    listener.onPartial(text)
                }

                override fun onFinal(text: String) {
                    val inserted = joinerFor(text) + text
                    // replaces the composing preview, if there is one
                    service.commitText(inserted)
                    previewShown = false
                    joiner = null
                    lastInserted = inserted
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

    /** Called when the keyboard is hidden: stop listening, but keep what was already said. */
    fun stopCurrent(discard: Boolean = false) {
        current?.stop(discard)
    }

    /**
     * Backspace for the dictation panel: right after an utterance was inserted (and nothing else
     * touched the text since) it removes that whole utterance; otherwise a single character.
     */
    fun backspace(service: FcitxInputMethodService) {
        val ic = service.currentInputConnection ?: return
        val last = lastInserted
        lastInserted = ""
        if (last.isNotEmpty() &&
            ic.getSelectedText(0).isNullOrEmpty() &&
            ic.getTextBeforeCursor(last.length, 0)?.toString() == last
        ) {
            ic.deleteSurroundingText(last.length, 0)
        } else {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** Text typed from the dictation panel (punctuation); ends the "undo last utterance" window. */
    fun type(service: FcitxInputMethodService, text: String) {
        lastInserted = ""
        service.commitText(text)
    }
}
