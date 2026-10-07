/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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

    /**
     * Speech that resumes within this many seconds continues the sentence instead of starting a
     * new one, see [VoiceSentence]. Long enough for somebody who stops to think.
     */
    const val SENTENCE_GAP = 4f

    /** Hands-free listening turns the microphone off after this long without speech. */
    const val HANDS_FREE_IDLE_TIMEOUT_MS = 10_000L

    /**
     * Debug builds only: when this file exists in the app's external files dir, it is used
     * instead of the microphone, so that the whole pipeline can be tested with `adb`.
     */
    private const val TEST_WAV = "voice-test.wav"

    private const val WARM_UP_WINDOW_MS = 30 * 60_000L

    /** The field is read back this long after its last change, see [onFieldChanged]. */
    private const val FIELD_SETTLE_MS = 800L

    /** Corrections are looked for this long after a session; later edits are something else. */
    private const val FIELD_WATCH_MS = 10 * 60_000L

    /**
     * What may take a preview's composing state away without the cursor having moved (see
     * `FcitxInputMethodService.composingLostTo`): the session then takes the preview back and
     * goes on. Anything else is treated as the user moving the cursor, which ends dictation.
     */
    private val RECOVERABLE_LOSSES = setOf("restart", "view_finish")

    /** An editor that keeps taking the preview away is not dictated into for ever. */
    private const val MAX_PREVIEW_RECOVERIES = 3

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

            override fun deleteBeforeCursor(n: Int) = service.deleteBeforeCursor(n)

            override fun insert(text: String) = service.commitText(text)
        }).also {
            edits = it
            editsService = service
        }
    }

    /** A sentence as it stands in the text field, which the next utterance may continue. */
    private class Sentence(val entry: VoiceEdits.Entry, val separator: String, var text: String) {
        /** The large model's words for the utterances of this sentence so far; null if it failed. */
        var accurate: Deferred<String?>? = null
    }

    private var sentence: Sentence? = null

    /** The transcript of the last utterance, including what it continued. */
    private var lastFinal = ""

    /**
     * Something other than dictation changed the text (a key of the dictation panel): what is
     * said next is a new utterance, and the last one gets no full stop.
     */
    fun closeSentence() {
        sentence = null
        current?.closeSentence()
    }

    /**
     * The recordings of the last session, if they are kept (see [VoiceSamples]) and its text
     * may still be corrected in the field it was dictated into.
     */
    private var kept: VoiceSamples.Session? = null

    /** When [kept]'s session ended; 0 while it runs. */
    private var keptEndedAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val fieldCheck = Runnable { editsService?.let(::checkField) }

    /**
     * The text field changed: something was typed or deleted, or the cursor moved. Once it has
     * been left alone for a moment, the dictated passage is read back (see [checkField]).
     */
    fun onFieldChanged() {
        if (kept == null) return
        handler.removeCallbacks(fieldCheck)
        handler.postDelayed(fieldCheck, FIELD_SETTLE_MS)
    }

    /**
     * Record how the text of the last session reads now, if recordings are kept: the user may
     * have corrected it, and the corrected text is what a model should have written. Only the
     * dictated passage is taken from the field, see [VoiceFieldText].
     */
    private fun checkField(service: FcitxInputMethodService) {
        handler.removeCallbacks(fieldCheck)
        val session = kept ?: return
        // not while text is still being written, by dictation or by the pinyin engine
        if (current != null || isRefining || service.hasComposingText) return
        val expired = keptEndedAt != 0L && SystemClock.elapsedRealtime() - keptEndedAt > FIELD_WATCH_MS
        if (expired || editsService !== service || !session.isIn(service.currentInputEditorInfo)) {
            kept = null
            return
        }
        val dictated = edits?.sessionText() ?: return
        if (dictated.length > VoiceFieldText.MAX_LENGTH) return
        val ic = service.currentInputConnection ?: return
        val reach = VoiceFieldText.reach(dictated.length)
        val before = ic.getTextBeforeCursor(reach, 0) ?: return
        val after = ic.getTextAfterCursor(reach, 0) ?: return
        val found = VoiceFieldText.locate(dictated, before.toString(), after.toString()) ?: return
        VoiceSamples.field(session, dictated, found)
    }

    /** Typed in the dictation panel while an utterance was being previewed, see [type]. */
    private val typedAhead = StringBuilder()

    private fun insertTypedAhead(service: FcitxInputMethodService) {
        if (typedAhead.isEmpty()) return
        val text = typedAhead.toString()
        typedAhead.clear()
        closeSentence()
        editsFor(service).insertTyped(text)
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
        // before this session writes anything: how the previous one reads by now
        checkField(service)
        // What the previous session still has to say is written before this one shows anything,
        // see onPartial.
        current?.stop(reason = "replaced")
        lastUsedAt = SystemClock.elapsedRealtime()
        // drop unfinished pinyin composition, so that it doesn't interleave with dictated text
        service.postFcitxJob { reset() }
        val edits = editsFor(service)
        edits.startSession()
        val recordings = VoiceSamples.begin(service, service.currentInputEditorInfo)
        kept = recordings
        keptEndedAt = 0
        sentence = null
        lastFinal = ""
        typedAhead.clear()
        val saving = VoicePower.isSaving(service)
        // Energy policy changes previews, never the selected final recognition pipeline.
        val refine = VoiceRefiner.isActive(service)
        val source = createSource(service)
        val diag = VoiceDiagnostics.begin(
            service, service.currentInputEditorInfo,
            "mode" to if (idleTimeoutMs == 0L) "push_to_talk" else "hands_free",
            "source" to if (source is MicrophoneSource) "microphone" else "test_file",
            "refine" to refine,
            "saving" to saving,
            "loaded" to VoiceEngine.isLoaded,
            "replaces" to (current != null),
            "connection" to (service.currentInputConnection != null)
        )
        // The large model is loaded when the first words are heard rather than when the session
        // starts: a session in which nothing is said (the space bar held by accident) must not
        // cost reading more than a gigabyte.
        var refinerRequested = false
        lateinit var session: VoiceSession
        // separator between the text already in the editor and the utterance in progress;
        // decided when its first preview arrives, as the preview itself hides the text before it
        var joiner: String? = null
        var previewShown = false
        // what the preview reads, separator included
        var previewText = ""
        // the editor it was written into, see FcitxInputMethodService.editorSerial
        var previewEditor = 0
        var recoveries = 0
        var deferred = false
        // Set when the user moved the cursor while an utterance was being previewed: the editor
        // then keeps the preview as ordinary text where it was, and writing anything more would
        // put the same words a second time at the new cursor position.
        var abandoned = false

        /**
         * The editor made the preview ordinary text, but not because the cursor moved: if it
         * still stands right before the cursor, remove it, to be written again.
         */
        fun reclaimPreview(): Boolean {
            val ic = service.currentInputConnection ?: return false
            if (!service.hasCollapsedSelection) return false
            // the editor may still mark the text as composing, which a deletion would skip
            ic.finishComposingText()
            return edits.reclaimPreview(previewText)
        }

        fun previewWasDetached(): Boolean {
            if (abandoned) return true
            if (!previewShown || service.hasComposingText) return false
            previewShown = false
            val lostTo = service.composingLostTo
            // never in another editor, whatever its text reads
            val recovered = lostTo in RECOVERABLE_LOSSES && service.editorSerial == previewEditor &&
                    recoveries < MAX_PREVIEW_RECOVERIES && reclaimPreview()
            Timber.d("Voice preview lost to $lostTo, taken back: $recovered")
            VoiceDiagnostics.log(
                diag, "preview_lost",
                "to" to (lostTo ?: "unknown"), "recovered" to recovered, "earlier" to recoveries
            )
            if (recovered) {
                recoveries++
                return false
            }
            abandoned = true
            session.stop(discard = true, reason = "preview_lost")
            return true
        }

        /**
         * Composing text that is not this session's preview: the last words of the session this
         * one replaced, which it has yet to write. Nothing may be written over them.
         */
        fun foreignPreview() = !previewShown && service.hasComposingText

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
            source,
            minSilence,
            idleTimeoutMs,
            SENTENCE_GAP,
            if (saving) PartialPacer.SAVING_INTERVAL_MS else PartialPacer.INTERVAL_MS,
            object : VoiceSession.Listener by listener {
                override fun onPartial(text: String) {
                    if (previewWasDetached()) return
                    val shown = VoiceText.stripTrailingPunctuation(text)
                    if (shown.isEmpty()) {
                        clearPreview()
                        insertTypedAhead(service)
                        applyRefinements(service)
                    } else if (foreignPreview()) {
                        // shown with the next preview, or written with the final text
                        if (!deferred) VoiceDiagnostics.log(diag, "preview_deferred")
                        deferred = true
                    } else {
                        val preview = joinerFor(shown) + shown
                        service.setVoicePreview(preview)
                        // not if there is no connection to the editor to write it to
                        previewShown = service.hasComposingText
                        previewText = preview
                        previewEditor = service.editorSerial
                        if (!previewShown) VoiceDiagnostics.log(diag, "preview_refused")
                        if (refine && !refinerRequested) {
                            refinerRequested = true
                            service.lifecycleScope.launch {
                                runCatching {
                                    VoiceRefiner.ensureLoaded(service) {
                                        session.needsSpeculativeRefiner
                                    }
                                }
                            }
                        }
                    }
                    listener.onPartial(shown)
                }

                override fun onFinal(text: String, samples: FloatArray, continues: Boolean) {
                    if (previewWasDetached()) return
                    // the previous session did not get to write its last words: they stay as
                    // they are, and its final text is not written over this one's
                    if (foreignPreview()) {
                        VoiceDiagnostics.log(diag, "foreign_preview_finished")
                        service.finishComposing()
                    }
                    val ic = service.currentInputConnection
                    ic?.beginBatchEdit()
                    var written = sentence.takeIf { continues }
                    if (written != null) {
                        // the sentence is rewritten where it stands, which the preview would hide
                        if (previewShown) service.setVoicePreview("")
                        if (edits.replace(written.entry, written.separator + text)) {
                            written.text = text
                        } else {
                            written = null
                        }
                    }
                    if (written == null) {
                        // What this utterance continues is no longer as dictation left it: only
                        // add what is new.
                        val added = if (continues) VoiceText.continuation(lastFinal, text) else text
                        if (added.isNotEmpty()) {
                            val separator = joinerFor(added)
                            // replaces the composing preview, if there is one
                            written = Sentence(edits.insert(separator + added), separator, added)
                        } else {
                            clearPreview()
                        }
                    }
                    ic?.endBatchEdit()
                    lastFinal = text
                    sentence = written
                    previewShown = false
                    joiner = null
                    Timber.d("Voice final inserted")
                    applyRefinements(service)
                    val recording = recordings?.let {
                        VoiceSamples.utterance(it, text, samples, continues)
                    }
                    if (refine && written != null) refine(service, written, samples, recording)
                    insertTypedAhead(service)
                    listener.onFinal(text, samples, continues)
                }

                override fun onSentenceEnd(stop: String) {
                    val ended = sentence
                    sentence = null
                    if (ended != null && stop.isNotEmpty() && !abandoned) {
                        if (edits.append(ended.entry, stop)) ended.text += stop
                    }
                    listener.onSentenceEnd(stop)
                }

                override fun onState(state: VoiceSession.State) {
                    if (state == VoiceSession.State.Stopped) {
                        // never leave a preview behind, e.g. after cancelling
                        clearPreview()
                        // the rest belongs to the session that replaced this one, if one did
                        if (current === session) {
                            insertTypedAhead(service)
                            applyRefinements(service)
                            current = null
                        }
                        if (kept === recordings) keptEndedAt = SystemClock.elapsedRealtime()
                        onFieldChanged()
                        VoiceDiagnostics.end(
                            service, diag, *session.summary(),
                            "abandoned" to abandoned, "recoveries" to recoveries
                        )
                    }
                    listener.onState(state)
                }
            },
            diag
        )
        current = session
        session.start()
        return session
    }

    /**
     * With the large model installed: transcribe [samples], the last utterance of [sentence],
     * again with the large model and, if it heard different words, write the merged text over
     * what was inserted for the sentence. Earlier utterances of the sentence are not transcribed
     * again: their words are taken from when they were refined. [recording] is the name under
     * which the utterance is kept, if it is (see [VoiceSamples]).
     */
    private fun refine(
        service: FcitxInputMethodService, sentence: Sentence, samples: FloatArray, recording: String?
    ) {
        val earlier = sentence.accurate
        val words = service.lifecycleScope.async {
            if (!sentence.entry.canRefine) return@async null
            val head = earlier?.await()
            if (earlier != null && head == null) return@async null
            val tail = runCatching {
                VoiceRefiner.transcribe(service, samples) { sentence.entry.canRefine }
            }
                .onFailure { Timber.w(it, "Voice refinement failed") }
                .getOrNull() ?: return@async null
            if (recording != null) VoiceSamples.refined(service, recording, tail)
            if (head == null) tail else head + VoiceText.joiner(head, tail) + tail
        }
        sentence.accurate = words
        refiningJobs++
        notifyRefining()
        service.lifecycleScope.launch {
            val accurate = try {
                words.await()
            } finally {
                // also when the service is destroyed meanwhile, or the count never comes down
                refiningJobs--
            }
            // unless the sentence went on meanwhile: its next utterance then brings all the words
            if (!accurate.isNullOrEmpty() && sentence.accurate === words) {
                editsFor(service).refine(
                    sentence.entry, sentence.separator + VoiceRefine.refine(sentence.text, accurate)
                )
            }
            applyRefinements(service)
            notifyRefining()
            onFieldChanged()
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
        // the last chance to see how the dictated text was corrected
        editsService?.let(::checkField)
        current?.stop(discard, "input_view_finish")
    }

    /** Whether [undoLastSession] has something to remove. */
    val canUndoLastSession get() = edits?.canUndoSession == true

    /**
     * Remove everything the last session inserted, provided it is still right before the cursor
     * (i.e. nothing else has edited the text since).
     * @return whether the text was removed
     */
    fun undoLastSession(service: FcitxInputMethodService) =
        editsFor(service).undoSession().also { undone ->
            if (undone) kept?.let(VoiceSamples::undone)
        }

    /**
     * Text typed from the dictation panel (punctuation, space). While an utterance is being
     * previewed it waits until that is final: inserting it now would replace the preview.
     */
    fun type(service: FcitxInputMethodService, text: String) {
        if (current != null && service.hasComposingText) {
            typedAhead.append(text)
            return
        }
        closeSentence()
        editsFor(service).insertTyped(text)
    }
}
