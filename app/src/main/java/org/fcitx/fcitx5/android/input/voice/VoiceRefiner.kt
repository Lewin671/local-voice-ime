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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The large, slow, accurate model of the high-accuracy build (FireRedASR2 AED). It transcribes
 * each utterance a second time after the fast model's text has been inserted; [VoiceRefine]
 * then merges its words into that text.
 *
 * It has its own thread, so that a refinement (seconds) never delays the live preview of the
 * next utterance, and it is only present when the build bundles the model (see
 * `scripts/fetch-voice-assets.sh --refiner`).
 */
object VoiceRefiner {

    private const val ASSET_DIR = "voice/refiner"

    /** About 1.4 GB: freed again soon after the last use. */
    private const val IDLE_RELEASE_MINUTES = 3L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "voice-refiner").apply { isDaemon = true }
    }

    private val dispatcher = executor.asCoroutineDispatcher()

    private var idleRelease: ScheduledFuture<*>? = null

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var available: Boolean? = null

    private val enabled by AppPrefs.getInstance().keyboard.voiceRefine

    /** Whether this build bundles the large model. */
    fun isAvailable(context: Context): Boolean {
        available?.let { return it }
        val files = runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        return ("encoder.int8.onnx" in files && "decoder.int8.onnx" in files).also { available = it }
    }

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

    /**
     * The model is loaded from plain files rather than from the APK: reading 1.2 GB through the
     * asset manager takes several times longer (12 s instead of 3 s on the test emulator), on
     * every load. So the assets are copied to the app's private storage once per model version.
     */
    private fun extract(context: Context): File {
        val dir = File(context.filesDir, "voice-refiner")
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        // the .onnx assets are stored uncompressed, so their size is known without reading them
        val version = names.filter { it.endsWith(".onnx") }.joinToString("-") { name ->
            context.assets.openFd("$ASSET_DIR/$name").use { it.length.toString() }
        }
        val marker = File(dir, "version")
        if (marker.exists() && marker.readText() == version) return dir
        val t0 = SystemClock.elapsedRealtime()
        dir.deleteRecursively()
        dir.mkdirs()
        for (name in names) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it, 1 shl 20) }
            }
        }
        marker.writeText(version)
        Timber.i("Voice refiner extracted in ${SystemClock.elapsedRealtime() - t0} ms")
        return dir
    }

    suspend fun ensureLoaded(context: Context) = withContext(dispatcher) {
        touch()
        if (recognizer != null) return@withContext
        val t0 = SystemClock.elapsedRealtime()
        val dir = extract(context.applicationContext)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = 4,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(null, config)
        Timber.i("Voice refiner loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * Transcribe an utterance with the large model. The result is bare text (no punctuation,
     * English in capitals); an empty string when nothing was recognized.
     */
    suspend fun transcribe(context: Context, samples: FloatArray): String {
        ensureLoaded(context)
        return withContext(dispatcher) {
            val r = recognizer ?: return@withContext ""
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
}
