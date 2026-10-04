/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Debug builds only: measures how a speech model behaves on this device: load time, decode time
 * for recordings of different lengths, and memory. Driven by `scripts/bench/device-bench.sh`,
 * which copies the model and recordings into the app's private storage first.
 *
 * Extras: `dir` (model directory), `kind` (sensevoice | transducer | funasr_nano | firered_aed |
 * qwen3), `wavs` (directory with 16 kHz mono WAV files), `threads`, `out` (result file),
 * optional `punct` (CT-Transformer punctuation model file, applied to the transcripts),
 * `provider` (default cpu; accepts a sherpa-onnx provider configuration file).
 */
class VoiceBenchActivity : Activity() {

    private lateinit var log: TextView

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { log.append(line + "\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log = TextView(this).apply { textSize = 12f; fitsSystemWindows = true }
        setContentView(log)
        val dir = File(intent.getStringExtra("dir")!!)
        val kind = intent.getStringExtra("kind")!!
        val wavs = File(intent.getStringExtra("wavs")!!)
        val out = File(intent.getStringExtra("out")!!)
        val threads = intent.getIntExtra("threads", 4)
        val punct = intent.getStringExtra("punct")
        val provider = intent.getStringExtra("provider") ?: "cpu"
        thread(name = "voice-bench") {
            val result = runCatching { run(dir, kind, wavs, threads, punct, provider) }
                .getOrElse { e ->
                    say("FAILED: $e")
                    JSONObject().put("error", e.toString())
                }
            out.writeText(result.toString(2))
            say("DONE")
        }
    }

    private fun pssMb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024
    }

    private fun config(dir: File, kind: String, threads: Int,
                       provider: String): OfflineRecognizerConfig {
        val files = dir.listFiles().orEmpty()
        // prefer quantized files, as shipped in the *-int8 archives
        fun onnx(prefix: String): String =
            files.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
                .sortedBy { if ("int8" in it.name) 0 else 1 }
                .firstOrNull()?.path ?: error("no $prefix*.onnx in $dir")
        val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") }?.path ?: ""
        val model = when (kind) {
            "sensevoice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = onnx("model"), language = "auto", useInverseTextNormalization = true
                )
            )
            "transducer" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = onnx("encoder"),
                    decoder = files.first { it.name.startsWith("decoder") }.path,
                    joiner = onnx("joiner")
                )
            )
            "funasr_nano" -> OfflineModelConfig(
                funasrNano = OfflineFunAsrNanoModelConfig(
                    encoderAdaptor = onnx("encoder_adaptor"),
                    llm = onnx("llm"),
                    embedding = onnx("embedding"),
                    tokenizer = files.first { it.isDirectory && it.name.startsWith("Qwen") }.path
                )
            )
            "firered_aed" -> OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = onnx("encoder"), decoder = onnx("decoder")
                )
            )
            "qwen3" -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = onnx("conv_frontend"),
                    encoder = onnx("encoder"),
                    decoder = onnx("decoder"),
                    tokenizer = File(dir, "tokenizer").path
                )
            )
            else -> error("unknown kind $kind")
        }
        model.tokens = tokens
        model.numThreads = threads
        model.provider = provider
        return OfflineRecognizerConfig(modelConfig = model)
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = bb.getInt(offset + 4)
            if (id == "data") {
                val n = minOf(size, bytes.size - offset - 8) / 2
                bb.position(offset + 8)
                val shorts = ShortArray(n).also { bb.asShortBuffer().get(it) }
                return FloatArray(n) { shorts[it] / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("not a PCM WAV file: $file")
    }

    private fun run(dir: File, kind: String, wavs: File, threads: Int, punct: String?,
                    provider: String): JSONObject {
        val result = JSONObject()
        result.put("model", dir.name).put("kind", kind).put("threads", threads)
        result.put("provider", provider)
        result.put("cores", Runtime.getRuntime().availableProcessors())
        result.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val before = pssMb()

        var t = SystemClock.elapsedRealtime()
        val recognizer = OfflineRecognizer(null, config(dir, kind, threads, provider))
        val loadMs = SystemClock.elapsedRealtime() - t
        result.put("loadMs", loadMs).put("pssBeforeMb", before).put("pssLoadedMb", pssMb())
        say("loaded in $loadMs ms, PSS ${before} -> ${pssMb()} MB")

        val punctuation = punct?.let {
            OfflinePunctuation(
                null,
                OfflinePunctuationConfig(OfflinePunctuationModelConfig(ctTransformer = it))
            )
        }

        val runs = JSONArray()
        var peak = pssMb()
        for (wav in wavs.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.name }) {
            val samples = readWav(wav)
            val audioMs = samples.size / 16L
            // twice: the first run of a model pays for one-time initialisation
            val times = LongArray(2)
            val cpuTimes = LongArray(2)
            var text = ""
            for (i in 0..1) {
                t = SystemClock.elapsedRealtime()
                val cpuStart = Process.getElapsedCpuTime()
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                text = recognizer.getResult(stream).text
                stream.release()
                times[i] = SystemClock.elapsedRealtime() - t
                cpuTimes[i] = Process.getElapsedCpuTime() - cpuStart
                peak = maxOf(peak, pssMb())
            }
            val run = JSONObject().put("wav", wav.name).put("audioMs", audioMs)
                .put("firstMs", times[0]).put("secondMs", times[1])
                .put("firstCpuMs", cpuTimes[0]).put("secondCpuMs", cpuTimes[1])
                .put("rtf", times[1].toDouble() / audioMs).put("text", text)
            if (punctuation != null) {
                t = SystemClock.elapsedRealtime()
                val punctuated = punctuation.addPunctuation(text)
                run.put("punctMs", SystemClock.elapsedRealtime() - t).put("punctuated", punctuated)
            }
            runs.put(run)
            say("${wav.name}: audio ${audioMs} ms, decode ${times[0]} / ${times[1]} ms -> $text")
        }
        result.put("runs", runs).put("pssPeakMb", peak)
        recognizer.release()
        punctuation?.release()
        return result
    }

    companion object {
        const val TAG = "VoiceBench"
    }
}
