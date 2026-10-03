/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import java.io.File

/**
 * Entry points shared by the voice UIs (hands-free [VoiceInputWindow] and push-to-talk
 * [VoiceInputComponent]). At most one [VoiceSession] is alive at any time.
 */
object VoiceInput {

    /** Trailing silence that ends an utterance in hands-free mode. */
    const val SILENCE_HANDS_FREE = 0.7f

    /** While the user holds the key, pauses are more likely to be mid-sentence. */
    const val SILENCE_PUSH_TO_TALK = 1.2f

    /**
     * Debug builds only: when this file exists in the app's external files dir, it is used
     * instead of the microphone, so that the whole pipeline can be tested with `adb`.
     */
    private const val TEST_WAV = "voice-test.wav"

    private var current: VoiceSession? = null

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
     * Start a new session (stopping the previous one, if any). Recognized utterances are
     * committed to the current editor; [listener] is for UI feedback only.
     */
    fun start(
        service: FcitxInputMethodService,
        minSilence: Float,
        listener: VoiceSession.Listener
    ): VoiceSession {
        current?.stop()
        // drop unfinished pinyin composition, so that it doesn't interleave with dictated text
        service.postFcitxJob { reset() }
        lateinit var session: VoiceSession
        session = VoiceSession(
            service,
            service.lifecycleScope,
            createSource(service),
            minSilence,
            object : VoiceSession.Listener by listener {
                override fun onFinal(text: String) {
                    commit(service, text)
                    listener.onFinal(text)
                }

                override fun onState(state: VoiceSession.State) {
                    if (state == VoiceSession.State.Stopped && current === session) current = null
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

    private fun commit(service: FcitxInputMethodService, text: String) {
        val before = service.currentInputConnection?.getTextBeforeCursor(1, 0)
        service.commitText(VoiceText.joiner(before, text) + text)
    }
}
