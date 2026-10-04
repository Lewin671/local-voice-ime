/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The large, slow, accurate model (FireRedASR2 AED), optional and downloaded. It transcribes
 * each utterance a second time after the fast model's text has been inserted; [VoiceRefine]
 * then merges its words into that text.
 *
 * It has its own thread, so that a refinement (seconds) never delays the live preview of the
 * next utterance. The model is not part of the APK: it is there once the user has downloaded
 * it in the settings (see [VoiceModels]).
 */
object VoiceRefiner {

    private val model = VoiceModels.FireRedAsr2

    /** About 1.4 GB: freed again soon after the last use. */
    private const val IDLE_RELEASE_MINUTES = 3L

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "voice-refiner").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private val dispatcher = executor.asCoroutineDispatcher()

    private var idleRelease: ScheduledFuture<*>? = null

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private val enabled by AppPrefs.getInstance().voiceInput.voiceRefine

    /** Whether the large model has been downloaded. */
    fun isAvailable(context: Context) = VoiceModels.isInstalled(context, model)

    /** Whether dictated text should be refined: the model is there and the user wants it. */
    fun isActive(context: Context) = isAvailable(context) && enabled

    // must be called on the refiner thread
    private fun touch() {
        idleRelease?.cancel(false)
        idleRelease = executor.schedule(::releaseNow, IDLE_RELEASE_MINUTES, TimeUnit.MINUTES)
    }

    private fun releaseNow() {
        recognizer?.release()
        recognizer = null
    }

    suspend fun ensureLoaded(
        context: Context,
        needed: () -> Boolean = { true }
    ) = withContext(dispatcher) {
        VoiceModelLoad.run(needed) {
            touch()
            if (recognizer != null || !isAvailable(context)) return@run
            val t0 = SystemClock.elapsedRealtime()
            val dir = VoiceModels.dir(context, model)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    fireRedAsr = OfflineFireRedAsrModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 4,
                    // Background refinement trades a little latency for less CPU spinning.
                    provider = VoiceRuntimeOptions.sleepingCpuProvider(context.noBackupFilesDir)
                )
            )
            recognizer = OfflineRecognizer(null, config)
            Timber.i("Voice refiner loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /**
     * Transcribe an utterance with the large model. The result is bare text (no punctuation,
     * English in capitals); an empty string when nothing was recognized, or null when the
     * request's editor entry was permanently retired before decoding. [needed] runs on the
     * native worker and must not access UI state.
     */
    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        needed: () -> Boolean = { true }
    ): String? = withContext(dispatcher) {
        VoiceRefinementWork.run(needed, { ensureLoaded(context) }) {
            val r = recognizer ?: return@run ""
            touch()
            val t0 = SystemClock.elapsedRealtime()
            val stream = r.createStream()
            val text = try {
                stream.acceptWaveform(samples, VoiceEngine.SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text
            } finally {
                stream.release()
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            val audioMs = samples.size * 1000L / VoiceEngine.SAMPLE_RATE
            Timber.d("Voice refine: audio=${audioMs}ms elapsed=${elapsed}ms")
            text.trim()
        }
    }

    fun release() {
        executor.execute(::releaseNow)
    }

    /** Delete the model. On the refiner thread, so that it cannot happen while it is being loaded. */
    suspend fun uninstall(context: Context) = withContext(dispatcher) {
        releaseNow()
        VoiceModels.delete(context, model)
    }
}
