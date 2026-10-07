/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class VoiceDiagnosticStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun VoiceDiagnosticStore.lines() =
        ByteArrayOutputStream().also(::writeTo).toString("US-ASCII").lines().filter { it.isNotEmpty() }

    @Test
    fun anEventIsANameWithNumbersTruthValuesAndFixedWords() {
        assertEquals(
            """{"e":"stop_request","s":3,"up":1234567890123,"discard":true,"reason":"release","level":0.5}""",
            VoiceDiagnosticStore.encode(
                "stop_request",
                listOf(
                    "s" to 3, "up" to 1234567890123L, "discard" to true, "reason" to "release",
                    "code" to null, "level" to 0.5f
                )
            )
        )
    }

    @Test
    fun wordsSomebodySaidCannotBeWritten() {
        val said = "开放时间早上9点至下午5点。"
        val typed = "meet me at 5, the code is 4711"
        val line = VoiceDiagnosticStore.encode(
            "final",
            listOf(
                "text" to said, "typed" to typed, "quote" to "a\"b", "path" to "/data/user/0/x",
                "long" to "x".repeat(49), "empty" to "", "list" to listOf(said), said to 1,
                "nan" to Float.NaN
            )
        )
        assertFalse(line.any { it.code > 126 })
        for (leak in listOf("开放", "meet", "4711", "/data", "xxxx")) assertFalse(leak, leak in line)
        assertEquals(
            """{"e":"final","text":"refused","typed":"refused","quote":"refused","path":"refused",""" +
                    """"long":"refused","empty":"refused","list":"refused","refused":1,"nan":0}""",
            line
        )
        assertEquals("refused", VoiceDiagnosticStore.encode(said, emptyList()).substring(6, 13))
    }

    @Test
    fun theNameOfAPhoneIsCutDownToAToken() {
        assertEquals("Pixel_8_Pro", VoiceDiagnosticStore.tokenOf("Pixel 8 Pro"))
        assertEquals("__", VoiceDiagnosticStore.tokenOf("小米"))
        assertEquals("refused", VoiceDiagnosticStore.tokenOf(""))
        assertEquals(48, VoiceDiagnosticStore.tokenOf("a".repeat(100)).length)
    }

    @Test
    fun theJournalKeepsTheNewestEventsWithinItsLimit() {
        val dir = File(folder.root, "voice-diagnostics")
        val store = VoiceDiagnosticStore(dir, maxBytes = 2000)
        val event = { n: Int -> VoiceDiagnosticStore.encode("progress", listOf("n" to n, "pad" to "x".repeat(40))) }
        repeat(200) { store.append(event(it)) }
        assertTrue(store.bytes <= 2000)
        val lines = store.lines()
        // oldest first, without a gap, up to the newest
        assertEquals(event(199), lines.last())
        val first = 200 - lines.size
        assertTrue(first > 0)
        lines.forEachIndexed { i, line -> assertEquals(event(first + i), line) }
        // another object on the same files goes on where this one stopped
        VoiceDiagnosticStore(dir, maxBytes = 2000).append(event(200))
        assertEquals(event(200), store.lines().last())
    }

    @Test
    fun deletingLeavesNothingBehindAndKeepingCanGoOn() {
        val dir = File(folder.root, "voice-diagnostics")
        val store = VoiceDiagnosticStore(dir, maxBytes = 400)
        repeat(20) { store.append(VoiceDiagnosticStore.encode("state", listOf("n" to it))) }
        store.delete()
        assertFalse(dir.exists())
        assertEquals(0, store.bytes)
        assertEquals(emptyList<String>(), store.lines())
        store.append(VoiceDiagnosticStore.encode("state", emptyList()))
        assertEquals(listOf("""{"e":"state"}"""), store.lines())
    }

    @Test
    fun aDirectoryThatCannotBeWrittenDoesNotDisturbDictation() {
        val blocked = File(folder.root, "file").apply { writeText("x") }
        val store = VoiceDiagnosticStore(File(blocked, "voice-diagnostics"))
        store.append(VoiceDiagnosticStore.encode("state", emptyList()))
        assertEquals(0, store.bytes)
        assertEquals(emptyList<String>(), store.lines())
    }
}
