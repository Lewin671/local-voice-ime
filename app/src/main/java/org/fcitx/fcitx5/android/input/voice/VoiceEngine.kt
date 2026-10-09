/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Process-wide holder of the on-device speech recognizer.
 *
 * Everything runs locally: the model is read from the app's private storage, where the user
 * downloaded it to (see [VoiceModels]), and neither audio nor text leaves the device (the app's
 * only network use is downloading a model, see `docs/PRIVACY.md`).
 *
 * All native calls are confined to a single thread; the recognizer is loaded lazily on first use
 * and freed again after a few idle minutes, as it takes ~250 MB of memory.
 */
object VoiceEngine {

    const val SAMPLE_RATE = 16000

    /** Longer utterances are cut at this length, even without a pause. */
    const val MAX_SPEECH_SECONDS = 20f

    /** Window size (in samples) expected by the VAD model. */
    const val VAD_WINDOW = 512

    private val preferTuned by AppPrefs.getInstance().voiceInput.voiceTuned

    /** The model to recognize with now: see [VoiceModels.recognition]. */
    private fun model(context: Context) = VoiceModels.recognition(
        VoiceModels.isInstalled(context, VoiceModels.SenseVoice),
        VoiceModels.isInstalled(context, VoiceModels.SenseVoiceTuned),
        preferTuned
    )

    /** The model [recognizer] was made from. */
    @Volatile
    private var loaded: VoiceModel? = null

    /** Id of the model that transcribes, for what is recorded about an utterance. */
    val modelId get() = (loaded ?: VoiceModels.SenseVoice).id

    /** Small enough (2 MB) to be part of the APK. */
    private const val VAD_MODEL = "voice/silero_vad.onnx"

    /** The recognizer is freed after being unused for this long. */
    private const val IDLE_RELEASE_MINUTES = 5L

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "voice-engine").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private val dispatcher = executor.asCoroutineDispatcher()

    private var idleRelease: ScheduledFuture<*>? = null

    // must be called on the engine thread
    private fun touch() {
        idleRelease?.cancel(false)
        idleRelease = executor.schedule(::releaseNow, IDLE_RELEASE_MINUTES, TimeUnit.MINUTES)
    }

    private fun releaseNow() {
        if (recognizer != null) VoiceDiagnostics.log("model_release")
        recognizer?.release()
        recognizer = null
        loaded = null
    }

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** Whether the model is in memory; if not, the next session has to wait for [ensureLoaded]. */
    val isLoaded get() = recognizer != null

    /** Whether a speech model has been downloaded; there is no voice input without one. */
    fun isAvailable(context: Context) = model(context) != null

    private val numThreads: Int
        get() = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2

    /** Recheck demand on the worker; an already running native load is never interrupted. */
    suspend fun ensureLoaded(
        context: Context,
        needed: () -> Boolean = { true }
    ): Boolean = withContext(dispatcher) {
        VoiceModelLoad.run(needed) { load(context) }
    }

    // must be called on the engine thread
    private fun load(context: Context) {
        touch()
        val model = checkNotNull(model(context)) { "The speech model is not installed" }
        if (recognizer != null && loaded === model) return
        // the user chose the other model since
        releaseNow()
        val t0 = SystemClock.elapsedRealtime()
        val dir = VoiceModels.dir(context, model)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, "model.int8.onnx").path,
                    // auto-detect, so that Mandarin, English and code-switching all work
                    language = "auto",
                    // spoken numbers -> digits, and punctuation
                    useInverseTextNormalization = true
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = numThreads,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(null, config)
        loaded = model
        Timber.i("Voice recognizer ${model.id} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * Transcribe a whole utterance. Returns an empty string when nothing was recognized.
     *
     * The model is loaded again if it was freed meanwhile: a session can outlast the idle
     * release (the space bar held through minutes of silence), and what is said after that must
     * not come back as "nothing recognized".
     */
    suspend fun transcribe(context: Context, samples: FloatArray): String = withContext(dispatcher) {
        if (recognizer == null) {
            VoiceDiagnostics.log("model_reload")
            try {
                load(context)
            } catch (e: Throwable) {
                throw VoiceException(VoiceException.Kind.ModelLoadFailed, e)
            }
        }
        val r = recognizer ?: throw VoiceException(VoiceException.Kind.ModelLoadFailed)
        touch()
        val t0 = SystemClock.elapsedRealtime()
        val stream = r.createStream()
        val text = try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text
        } finally {
            stream.release()
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        val audioMs = samples.size * 1000L / SAMPLE_RATE
        Timber.d("Voice decode: audio=${audioMs}ms elapsed=${elapsed}ms rtf=${"%.3f".format(elapsed.toFloat() / audioMs.coerceAtLeast(1))}")
        VoiceText.normalize(text)
    }

    /**
     * Create a voice activity detector. Caller owns the instance and must `release()` it.
     *
     * @param minSilence trailing silence (seconds) that ends an utterance
     */
    fun createVad(context: Context, minSilence: Float): Vad = Vad(
        context.applicationContext.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = VAD_MODEL,
                threshold = 0.5f,
                minSilenceDuration = minSilence,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = MAX_SPEECH_SECONDS
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1
        )
    )

    /**
     * Free the recognizer (~250 MB). It will be loaded again on next use.
     */
    fun release() {
        executor.execute(::releaseNow)
    }

    /** Delete [model]. On the engine thread, so that it cannot happen while it is being loaded. */
    suspend fun uninstall(context: Context, model: VoiceModel) = withContext(dispatcher) {
        if (loaded === model) releaseNow()
        VoiceModels.delete(context, model)
    }
}
