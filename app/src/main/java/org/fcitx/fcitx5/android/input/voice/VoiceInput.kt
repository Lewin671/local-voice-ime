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
import timber.log.Timber

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

    /** What dictation wrote into the text field, see [VoiceEdits]. */
    private var edits: VoiceEdits? = null
    private var editsService: FcitxInputMethodService? = null

    private fun editsFor(service: FcitxInputMethodService): VoiceEdits {
        edits?.takeIf { editsService === service }?.let { return it }
        return VoiceEdits(object : VoiceEdits.Editor {
            override fun textBeforeCursor(n: Int) =
                service.currentInputConnection?.getTextBeforeCursor(n, 0)?.toString()

            override val hasSelection
                get() = !service.currentInputConnection?.getSelectedText(0).isNullOrEmpty()

            override val hasPreview get() = service.hasComposingText

            override fun deleteBeforeCursor(n: Int) {
                service.currentInputConnection?.deleteSurroundingText(n, 0)
            }

            override fun insert(text: String) = service.commitText(text)
        }).also {
            edits = it
            editsService = service
        }
    }

    private var refiningJobs = 0

    /** Whether inserted text is still being re-checked by the large model. */
    val isRefining get() = refiningJobs > 0 || edits?.hasPendingRefinements == true

    /**
     * Called on the main thread whenever [isRefining] may have changed. Keyed by owner, so that
     * a UI that is created again (e.g. after a theme change) replaces its previous listener.
     */
    val refiningListeners = LinkedHashMap<Any, () -> Unit>()

    private fun notifyRefining() = refiningListeners.values.toList().forEach { it() }

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun testFile(context: Context, name: String) =
        if (BuildConfig.DEBUG) context.getExternalFilesDir(null)?.resolve(name)?.takeIf { it.exists() }
        else null

    private fun createSource(context: Context): AudioSource =
        testFile(context, TEST_WAV)?.let { WavFileSource(it) } ?: MicrophoneSource()

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
        val edits = editsFor(service)
        edits.startSession()
        val saving = VoicePower.isSaving(service)
        val refine = VoiceRefiner.isActive(service) && !saving
        // The large model is loaded when the first words are heard rather than when the session
        // starts: a session in which nothing is said (the space bar held by accident) must not
        // cost reading more than a gigabyte.
        var refinerRequested = false
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
            if (saving) PartialPacer.SAVING_INTERVAL_MS else PartialPacer.INTERVAL_MS,
            object : VoiceSession.Listener by listener {
                override fun onPartial(text: String) {
                    if (previewWasDetached()) return
                    val shown = VoiceText.stripTrailingPunctuation(text)
                    if (shown.isEmpty()) {
                        clearPreview()
                        applyRefinements(service)
                    } else {
                        service.setVoicePreview(joinerFor(shown) + shown)
                        previewShown = true
                        if (refine && !refinerRequested) {
                            refinerRequested = true
                            service.lifecycleScope.launch {
                                runCatching { VoiceRefiner.ensureLoaded(service) }
                            }
                        }
                    }
                    listener.onPartial(shown)
                }

                override fun onFinal(text: String, samples: FloatArray) {
                    if (previewWasDetached()) return
                    val separator = joinerFor(text)
                    // replaces the composing preview, if there is one
                    val entry = edits.insert(separator + text)
                    previewShown = false
                    joiner = null
                    Timber.d("Voice final inserted")
                    applyRefinements(service)
                    if (refine) refine(service, entry, separator, text, samples)
                    listener.onFinal(text, samples)
                }

                override fun onState(state: VoiceSession.State) {
                    if (state == VoiceSession.State.Stopped) {
                        // never leave a preview behind, e.g. after cancelling
                        clearPreview()
                        applyRefinements(service)
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
     * With the large model installed: transcribe [samples] again with the large model and, if it heard
     * different words, write the merged text over what was inserted for this utterance.
     */
    private fun refine(
        service: FcitxInputMethodService,
        entry: VoiceEdits.Entry,
        separator: String,
        fast: String,
        samples: FloatArray
    ) {
        refiningJobs++
        notifyRefining()
        service.lifecycleScope.launch {
            val accurate = runCatching { VoiceRefiner.transcribe(service, samples) }
                .onFailure { Timber.w(it, "Voice refinement failed") }
                .getOrNull()
            refiningJobs--
            if (!accurate.isNullOrEmpty()) {
                editsFor(service).refine(entry, separator + VoiceRefine.refine(fast, accurate))
            }
            applyRefinements(service)
            notifyRefining()
        }
    }

    /**
     * Write finished refinements into the editor; those whose text is no longer as dictation
     * left it are dropped (see [VoiceEdits]).
     */
    private fun applyRefinements(service: FcitxInputMethodService) {
        val edits = edits ?: return
        if (!edits.hasPendingRefinements) return
        val ic = service.currentInputConnection
        ic?.beginBatchEdit()
        val (applied, dropped) = edits.applyRefinements()
        ic?.endBatchEdit()
        if (applied > 0) Timber.d("Voice refinement applied")
        if (dropped > 0) Timber.d("Voice refinement skipped: the text is no longer as inserted")
        if (applied + dropped > 0) notifyRefining()
    }

    /**
     * Called when the keyboard is shown for an editor. If dictation was used a short while ago,
     * load the model again in the background (it is freed after a few idle minutes), so that the
     * preview appears without delay when the user speaks.
     */
    fun warmUp(service: FcitxInputMethodService) {
        if (lastUsedAt == 0L || SystemClock.elapsedRealtime() - lastUsedAt > WARM_UP_WINDOW_MS) return
        if (VoicePower.isSaving(service)) return
        service.lifecycleScope.launch {
            runCatching { VoiceEngine.ensureLoaded(service) }
        }
    }

    /** Called when the keyboard is hidden: stop listening, but keep what was already said. */
    fun stopCurrent(discard: Boolean = false) {
        current?.stop(discard)
    }

    /** Whether [undoLastSession] has something to remove. */
    val canUndoLastSession get() = edits?.canUndoSession == true

    /**
     * Remove everything the last session inserted, provided it is still right before the cursor
     * (i.e. nothing else has edited the text since).
     * @return whether the text was removed
     */
    fun undoLastSession(service: FcitxInputMethodService) = editsFor(service).undoSession()

    /** Text typed from the dictation panel (punctuation). */
    fun type(service: FcitxInputMethodService, text: String) = editsFor(service).insertTyped(text)
}
