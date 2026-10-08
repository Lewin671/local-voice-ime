/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The recordings a user chose to keep (see [VoiceSamples] and docs/TRAINING_DATA.md): one WAV
 * file per dictated utterance in `audio/`, and `log.jsonl`, to which a line is added for
 * everything that is learned about an utterance, then or later.
 *
 * Plain files and no Android classes, so that it is covered by JVM tests. Calls may come from
 * any thread; they wait for each other.
 */
class VoiceSampleStore(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val minFree: Long = MIN_FREE_BYTES,
    // asked of a directory that exists: one that does not has no space at all
    private val free: () -> Long = { generateSequence(dir) { it.parentFile }.first { it.exists() }.usableSpace }
) {

    data class Stats(val recordings: Int, val bytes: Long, val full: Boolean)

    private val audio = File(dir, AUDIO_DIR)
    private val log = File(dir, LOG_FILE)

    // of what is in [dir]; null until it has been looked at
    private var recordings: Int? = null
    private var bytes = 0L

    private fun count(): Int {
        recordings?.let { return it }
        val files = audio.listFiles().orEmpty()
        bytes = files.sumOf { it.length() } + log.length()
        return files.size.also { recordings = it }
    }

    private fun isFull() = bytes >= maxBytes || free() < minFree

    @Synchronized
    fun stats(): Stats {
        val n = count()
        return Stats(n, bytes, isFull())
    }

    /**
     * Keep an utterance: [samples] as `audio/<id>.wav` and [record] as a line of the log.
     * @return false, with nothing written, when the limit is reached
     */
    @Synchronized
    fun add(id: String, samples: FloatArray, sampleRate: Int, record: JsonObject): Boolean {
        val n = count()
        if (isFull()) return false
        audio.mkdirs()
        val file = File(audio, "$id.wav")
        try {
            file.writeBytes(wav(samples, sampleRate))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        recordings = n + 1
        bytes += file.length()
        write(record)
        return true
    }

    /** Add what was learned later about an utterance or a session that was kept. */
    @Synchronized
    fun append(record: JsonObject) {
        if (count() > 0) write(record)
    }

    private fun write(record: JsonObject) {
        val line = (record.toString() + "\n").toByteArray()
        log.appendBytes(line)
        bytes += line.size
    }

    @Synchronized
    fun clear() {
        audio.deleteRecursively()
        log.delete()
        recordings = 0
        bytes = 0
    }

    /**
     * Write everything as one ZIP archive, laid out like [dir].
     * @return how many recordings it holds
     */
    @Synchronized
    fun export(out: OutputStream): Int {
        val files = audio.listFiles().orEmpty().sortedBy { it.name }
        ZipOutputStream(out).use { zip ->
            if (log.exists()) {
                zip.putNextEntry(ZipEntry(LOG_FILE))
                log.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (f in files) {
                zip.putNextEntry(ZipEntry("$AUDIO_DIR/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.size
    }

    companion object {
        const val MAX_BYTES = 1L shl 30
        const val MIN_FREE_BYTES = 500L shl 20
        const val AUDIO_DIR = "audio"
        const val LOG_FILE = "log.jsonl"

        /**
         * Mono 16-bit PCM, which every speech toolkit reads. The microphone delivers 16-bit
         * samples and the recognizer gets them divided by 32768 ([AudioSource]); multiplying by
         * the same number and rounding gives those samples back exactly, so that a model
         * trained or tested on the file hears what the recognizer heard.
         */
        fun wav(samples: FloatArray, sampleRate: Int): ByteArray {
            val data = samples.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (v in samples) b.putShort(Math.round(v * 32768f).coerceIn(-32768, 32767).toShort())
            return b.array()
        }
    }
}

/** The lines of the log; every field is described in docs/TRAINING_DATA.md. */
object VoiceSampleRecord {

    const val VERSION = 1

    /** An utterance was recognized: [text] is what the fast model wrote for it. */
    fun utterance(
        id: String, session: String, timeMs: Long, seconds: Float, text: String,
        continues: Boolean, model: String, app: String
    ) = buildJsonObject {
        put("v", VERSION)
        put("type", "utterance")
        put("id", id)
        put("session", session)
        put("timeMs", timeMs)
        put("audio", "${VoiceSampleStore.AUDIO_DIR}/$id.wav")
        put("seconds", seconds)
        put("text", text)
        put("continues", continues)
        put("model", model)
        put("app", app)
    }

    /** The large model transcribed the same audio: [text] is its raw output. */
    fun refined(id: String, text: String, model: String) = buildJsonObject {
        put("v", VERSION)
        put("type", "refined")
        put("id", id)
        put("text", text)
        put("model", model)
    }

    /** What the session wrote was removed with Undo. */
    fun undone(session: String) = buildJsonObject {
        put("v", VERSION)
        put("type", "undone")
        put("session", session)
    }

    /** The session wrote [dictated]; some time later the field read [text] in its place. */
    fun field(session: String, dictated: String, text: String) = buildJsonObject {
        put("v", VERSION)
        put("type", "field")
        put("session", session)
        put("dictated", dictated)
        put("text", text)
    }
}
