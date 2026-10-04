/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps what is dictated, for a user who wants to fine-tune a speech model on their own voice:
 * the audio of every utterance, what the models wrote for it, and how the text read after the
 * user corrected it. Off unless switched on in the settings; nothing is kept from fields that
 * are marked private; nothing is ever sent, the user exports a file. See docs/PRIVACY.md,
 * docs/TRAINING_DATA.md and "Recordings" in docs/design/mockup.html.
 *
 * Dictation must not notice any of this: files are written on a thread of their own, and a
 * failure there is logged and forgotten.
 */
object VoiceSamples {

    enum class Export { None, Running, Done, Failed }

    /** What is on the phone, for the settings screen. */
    data class Status(
        val recordings: Int = 0,
        val bytes: Long = 0,
        /** The limit is reached: nothing more is kept. */
        val full: Boolean = false,
        val export: Export = Export.None,
        /** With [Export.Done]: how many recordings the file holds. */
        val exported: Int = 0
    )

    /** One dictation session whose utterances are kept. Used on the main thread only. */
    class Session internal constructor(
        internal val id: String,
        internal val context: Context,
        private val fieldId: Int,
        private val app: String?
    ) {
        internal var utterances = 0

        /** The last text reported with [field]. */
        internal var lastField: String? = null

        /** Whether [info] is the text field this session wrote into. */
        fun isIn(info: EditorInfo?) = info != null && info.fieldId == fieldId && info.packageName == app
    }

    private const val DIR = "voice-samples"

    private val enabled by AppPrefs.getInstance().voiceInput.keepRecordings

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-samples").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val dispatcher = worker.asCoroutineDispatcher()

    @Volatile
    private var store: VoiceSampleStore? = null

    private fun store(context: Context) = store ?: synchronized(this) {
        store ?: VoiceSampleStore(File(context.applicationContext.filesDir, DIR)).also { store = it }
    }

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state.asStateFlow()

    private fun publish(store: VoiceSampleStore) {
        val s = store.stats()
        state.update {
            // "Exported N recordings" is said until there is something that was not exported
            val export = if (it.export == Export.Done && it.recordings != s.recordings) Export.None else it.export
            it.copy(recordings = s.recordings, bytes = s.bytes, full = s.full, export = export)
        }
    }

    private fun submit(context: Context, block: (VoiceSampleStore) -> Unit) {
        worker.execute {
            runCatching {
                val store = store(context)
                block(store)
                publish(store)
            }.onFailure { Timber.w(it, "Could not keep a voice recording") }
        }
    }

    /** Apps set this on fields whose content should not be remembered (incognito tabs). */
    private fun isPrivate(info: EditorInfo) =
        info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    /**
     * A dictation session begins in the field described by [info].
     * @return what to report its utterances to, or null if they are not to be kept
     */
    fun begin(context: Context, info: EditorInfo?): Session? {
        if (!enabled || info == null || isPrivate(info)) return null
        if (state.value.full) {
            // room may have been made meanwhile: the next session finds out
            refresh(context)
            return null
        }
        val time = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return Session(time, context.applicationContext, info.fieldId, info.packageName)
    }

    /**
     * An utterance was recognized: [samples] is its audio and [text] what the fast model wrote
     * (for the whole sentence so far, if it [continues] one).
     * @return the name under which it is kept, or null if the switch was turned off meanwhile
     */
    fun utterance(session: Session, text: String, samples: FloatArray, continues: Boolean): String? {
        if (!enabled) return null
        val id = "%s-%02d".format(Locale.US, session.id, ++session.utterances)
        val record = VoiceSampleRecord.utterance(
            id, session.id, System.currentTimeMillis(),
            samples.size.toFloat() / VoiceEngine.SAMPLE_RATE, text, continues,
            VoiceModels.SenseVoice.id, BuildConfig.VERSION_NAME
        )
        submit(session.context) { it.add(id, samples, VoiceEngine.SAMPLE_RATE, record) }
        return id
    }

    /** The large model's transcript of the audio kept as [id]. */
    fun refined(context: Context, id: String, text: String) {
        val record = VoiceSampleRecord.refined(id, text, VoiceModels.FireRedAsr2.id)
        submit(context) { it.append(record) }
    }

    /** The user removed what the session wrote. */
    fun undone(session: Session) {
        if (!enabled || session.utterances == 0) return
        val record = VoiceSampleRecord.undone(session.id)
        submit(session.context) { it.append(record) }
    }

    /** Where the session wrote [dictated], the field now reads [text]. */
    fun field(session: Session, dictated: String, text: String) {
        if (!enabled || session.utterances == 0 || text == session.lastField) return
        session.lastField = text
        val record = VoiceSampleRecord.field(session.id, dictated, text)
        submit(session.context) { it.append(record) }
    }

    /** Look at what is on the phone; [status] follows. */
    fun refresh(context: Context) = submit(context) {}

    suspend fun delete(context: Context) = withContext(dispatcher) {
        val store = store(context)
        store.clear()
        state.update { Status() }
        publish(store)
    }

    /** Write everything into [target], a document the user created for it. */
    suspend fun export(context: Context, target: Uri) {
        state.update { it.copy(export = Export.Running) }
        val exported = withContext(dispatcher) {
            runCatching {
                val out = context.contentResolver.openOutputStream(target)
                    ?: error("The document cannot be written")
                out.use { store(context).export(it) }
            }.onFailure { Timber.w(it, "Could not export the voice recordings") }.getOrNull()
        }
        state.update {
            if (exported == null) it.copy(export = Export.Failed)
            else it.copy(export = Export.Done, exported = exported)
        }
    }
}
