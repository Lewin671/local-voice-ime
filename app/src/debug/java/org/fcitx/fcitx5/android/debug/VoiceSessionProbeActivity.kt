/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fcitx.fcitx5.android.input.voice.AudioSource
import org.fcitx.fcitx5.android.input.voice.PartialPacer
import org.fcitx.fcitx5.android.input.voice.VoiceEngine
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.fcitx.fcitx5.android.input.voice.WavFileSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Debug-only production-pipeline probe: exact audio fingerprints, text and capture teardown. */
class VoiceSessionProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = TextView(this)
        setContentView(display)
        val result = JSONObject().put("captureStoppedBeforeFinishing", false)
        val finals = JSONArray()
        val stops = JSONArray()
        val captured = MessageDigest.getInstance("SHA-256")
        var capturedSamples = 0L
        var stopCalls = 0
        var captureStopped = false
        val delegate = WavFileSource(File(getExternalFilesDir(null), "voice-probe.wav"), 0)
        val source = object : AudioSource {
            override fun start() = delegate.start()
            override suspend fun read(buffer: FloatArray): Int {
                val n = delegate.read(buffer)
                if (n > 0) {
                    captured.update(bytes(buffer, n))
                    capturedSamples += n
                }
                return n
            }
            override fun stop() {
                delegate.stop()
                stopCalls++
                captureStopped = true
            }
        }
        val session = VoiceSession(this, scope, source, 0.7f, 0, 4f,
            PartialPacer.INTERVAL_MS, object : VoiceSession.Listener {
                override fun onFinal(text: String, samples: FloatArray, continues: Boolean) {
                    finals.put(JSONObject().put("text", text).put("continues", continues)
                        .put("samples", samples.size).put("sha256", digest(samples)))
                }
                override fun onSentenceEnd(stop: String) { stops.put(stop) }
                override fun onError(e: Throwable) { result.put("error", e.toString()) }
                override fun onState(state: VoiceSession.State) {
                    if (state == VoiceSession.State.Finishing) {
                        result.put("captureStoppedBeforeFinishing", captureStopped)
                    }
                    if (state == VoiceSession.State.Stopped) {
                        result.put("finals", finals).put("sentenceStops", stops)
                            .put("capturedSamples", capturedSamples)
                            .put("capturedSha256", hex(captured.digest()))
                            .put("sourceStopCalls", stopCalls)
                            .put("standardModelLoadedAtEnd", VoiceEngine.isLoaded)
                        val temporary = File(filesDir, "voice-probe-result.json.tmp")
                        temporary.writeText(result.toString(2))
                        check(temporary.renameTo(File(filesDir, "voice-probe-result.json")))
                        display.text = "DONE"
                    }
                }
            })
        if (intent.getBooleanExtra("cancelBeforeStart", false)) session.stop(discard = true)
        session.start()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bytes(samples: FloatArray, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { buffer.putInt(samples[it].toRawBits()) }
        return buffer.array()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun digest(samples: FloatArray) =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes(samples, samples.size)))
}
