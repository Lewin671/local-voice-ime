/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class VoiceSampleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: String, text: String = "你好 \"world\"\n") =
        VoiceSampleRecord.utterance(id, "s1", 1700000000000, 0.5f, text, false, "model", "1.0")

    private val halfSecond = FloatArray(8000) { if (it % 2 == 0) 0.5f else -0.5f }

    private fun lines(dir: File) = File(dir, "log.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun anUtteranceIsKeptAsWavAndLogLine() {
        val dir = tmp.newFolder()
        val store = VoiceSampleStore(dir, free = { Long.MAX_VALUE })
        assertEquals(VoiceSampleStore.Stats(0, 0, false), store.stats())
        assertTrue(store.add("s1-01", halfSecond, 16000, record("s1-01")))

        val wav = File(dir, "audio/s1-01.wav").readBytes()
        assertEquals(44 + 16000, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(16000, b.getInt(40))
        assertEquals(16384, b.getShort(44).toInt())
        assertEquals(-16384, b.getShort(46).toInt())

        val line = lines(dir).single()
        assertEquals("utterance", line["type"]!!.jsonPrimitive.content)
        assertEquals("audio/s1-01.wav", line["audio"]!!.jsonPrimitive.content)
        // quotes and line breaks survive: one record stays one line
        assertEquals("你好 \"world\"\n", line["text"]!!.jsonPrimitive.content)
        assertFalse(line["continues"]!!.jsonPrimitive.boolean)

        val stats = store.stats()
        assertEquals(1, stats.recordings)
        assertEquals(wav.size + File(dir, "log.jsonl").length(), stats.bytes)
    }

    @Test
    fun samplesOutOfRangeAreClipped() {
        val wav = VoiceSampleStore.wav(floatArrayOf(2f, -2f), 16000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, b.getShort(44).toInt())
        assertEquals(-32768, b.getShort(46).toInt())
    }

    @Test
    fun theFileHoldsTheSamplesTheRecognizerWasGiven() {
        // what AudioSource makes of the microphone's 16-bit samples
        val pcm = shortArrayOf(0, 1, -1, 2, -2, 99, -99, 1000, -1000, 32767, -32768)
        val wav = VoiceSampleStore.wav(FloatArray(pcm.size) { pcm[it] / 32768f }, 16000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + 2 * i))
    }

    @Test
    fun laterRecordsAreAppendedButOnlyToSomethingKept() {
        val dir = tmp.newFolder()
        val store = VoiceSampleStore(dir, free = { Long.MAX_VALUE })
        store.append(VoiceSampleRecord.undone("s0"))
        assertFalse(File(dir, "log.jsonl").exists())
        store.add("s1-01", halfSecond, 16000, record("s1-01"))
        store.append(VoiceSampleRecord.refined("s1-01", "NI HAO", "large"))
        store.append(VoiceSampleRecord.field("s1", "你好。", "您好。"))
        store.append(VoiceSampleRecord.undone("s1"))
        assertEquals(listOf("utterance", "refined", "field", "undone"),
            lines(dir).map { it["type"]!!.jsonPrimitive.content })
        assertEquals("您好。", lines(dir)[2]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingIsKeptBeyondTheLimit() {
        val dir = tmp.newFolder()
        val store = VoiceSampleStore(dir, maxBytes = 20000, free = { Long.MAX_VALUE })
        assertTrue(store.add("a", halfSecond, 16000, record("a")))
        assertFalse(store.stats().full)
        assertTrue(store.add("b", halfSecond, 16000, record("b")))
        assertTrue(store.stats().full)
        assertFalse(store.add("c", halfSecond, 16000, record("c")))
        assertFalse(File(dir, "audio/c.wav").exists())
        assertEquals(2, lines(dir).size)
    }

    @Test
    fun nothingIsKeptWhenThePhoneIsNearlyFull() {
        val dir = tmp.newFolder()
        var free = 10L
        val store = VoiceSampleStore(dir, minFree = 100, free = { free })
        assertTrue(store.stats().full)
        assertFalse(store.add("a", halfSecond, 16000, record("a")))
        free = 1000
        assertTrue(store.add("a", halfSecond, 16000, record("a")))
    }

    @Test
    fun theFirstRecordingCreatesTheDirectory() {
        // free space as the phone reports it, for a directory that is not there yet
        val dir = File(tmp.newFolder(), "voice-samples")
        val store = VoiceSampleStore(dir, minFree = 1)
        assertFalse(store.stats().full)
        assertTrue(store.add("a", halfSecond, 16000, record("a")))
        assertTrue(File(dir, "audio/a.wav").exists())
    }

    @Test
    fun aNewStoreCountsWhatIsAlreadyThere() {
        val dir = tmp.newFolder()
        VoiceSampleStore(dir, free = { Long.MAX_VALUE }).apply {
            add("a", halfSecond, 16000, record("a"))
            add("b", halfSecond, 16000, record("b"))
        }
        val again = VoiceSampleStore(dir, free = { Long.MAX_VALUE })
        assertEquals(2, again.stats().recordings)
        assertEquals(2 * 16044 + File(dir, "log.jsonl").length(), again.stats().bytes)
    }

    @Test
    fun exportIsAZipOfTheSameFiles() {
        val dir = tmp.newFolder()
        val store = VoiceSampleStore(dir, free = { Long.MAX_VALUE })
        store.add("b", halfSecond, 16000, record("b"))
        store.add("a", halfSecond, 16000, record("a"))
        val out = ByteArrayOutputStream()
        assertEquals(2, store.export(out))
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(listOf("log.jsonl", "audio/a.wav", "audio/b.wav"), entries.keys.toList())
        assertTrue(entries["log.jsonl"]!!.contentEquals(File(dir, "log.jsonl").readBytes()))
        assertTrue(entries["audio/a.wav"]!!.contentEquals(File(dir, "audio/a.wav").readBytes()))
        // exporting keeps the recordings
        assertEquals(2, store.stats().recordings)
    }

    @Test
    fun anEmptyStoreExportsAnEmptyArchive() {
        val out = ByteArrayOutputStream()
        assertEquals(0, VoiceSampleStore(tmp.newFolder(), free = { Long.MAX_VALUE }).export(out))
        ZipInputStream(out.toByteArray().inputStream()).use { assertEquals(null, it.nextEntry) }
    }

    @Test
    fun clearRemovesEverything() {
        val dir = tmp.newFolder()
        val store = VoiceSampleStore(dir, free = { Long.MAX_VALUE })
        store.add("a", halfSecond, 16000, record("a"))
        store.clear()
        assertEquals(VoiceSampleStore.Stats(0, 0, false), store.stats())
        assertFalse(File(dir, "audio").exists())
        assertFalse(File(dir, "log.jsonl").exists())
        // and can be used again
        assertTrue(store.add("a", halfSecond, 16000, record("a")))
        assertEquals(1, lines(dir).size)
    }
}
