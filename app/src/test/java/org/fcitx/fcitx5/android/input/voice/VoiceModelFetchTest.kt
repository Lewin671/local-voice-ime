/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class VoiceModelFetchTest {

    private val content = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var dir: File
    private lateinit var dest: File
    private lateinit var part: File

    /** `Range` header of each request the server saw. */
    private val ranges = mutableListOf<String?>()

    // how the fake server behaves
    private var served = content
    private var honourRange = true
    private var status = 0

    /** Close the connection after this many bytes of the body. */
    private var cutAfter = -1

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("voice-model-fetch").toFile()
        dest = File(dir, "model.onnx")
        part = File(dir, "model.onnx.part")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.onnx") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val from = range?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            val body = served.copyOfRange(from ?: 0, served.size)
            if (status != 0) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(if (from == null) 200 else 206, body.size.toLong())
                // closing the exchange before the announced length was sent drops the connection
                runCatching {
                    exchange.responseBody.write(body, 0, if (cutAfter < 0) body.size else cutAfter)
                }
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun fetch(onProgress: (Long) -> Unit = {}) = VoiceModelFetch.fetch(
        URL("http://127.0.0.1:${server.address.port}/model.onnx"),
        dest, content.size.toLong(), sha256, onProgress
    )

    @Test
    fun downloadsAndReportsProgress() {
        val progress = mutableListOf<Long>()
        fetch { progress += it }
        assertArrayEquals(content, dest.readBytes())
        assertFalse(part.exists())
        assertEquals(progress.sorted(), progress)
        assertEquals(content.size.toLong(), progress.last())
        assertEquals(listOf<String?>(null), ranges)
    }

    @Test
    fun aFileThatIsAlreadyThereIsNotFetchedAgain() {
        fetch()
        fetch()
        assertEquals(1, ranges.size)
    }

    @Test
    fun anInterruptedDownloadIsContinued() {
        cutAfter = 100_000
        assertThrows(IOException::class.java) { fetch() }
        assertFalse(dest.exists())
        val kept = part.length()
        assertTrue(kept in 1 until content.size)

        cutAfter = -1
        fetch()
        assertArrayEquals(content, dest.readBytes())
        assertEquals(listOf(null, "bytes=$kept-"), ranges)
    }

    @Test
    fun cancellingKeepsWhatHasArrived() {
        class Cancelled : RuntimeException()
        assertThrows(Cancelled::class.java) { fetch { if (it > 100_000) throw Cancelled() } }
        assertTrue(part.length() in 1 until content.size)
        fetch()
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresTheRange() {
        part.writeBytes(content.copyOf(1000))
        honourRange = false
        fetch()
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun otherContentIsRejectedAndDiscarded() {
        served = content.copyOf().also { it[1234] = 0 }
        assertThrows(VoiceModelFetch.WrongContentException::class.java) { fetch() }
        assertFalse(dest.exists())
        assertFalse(part.exists())

        // a tampered partial file is caught as well, and the next attempt recovers
        served = content
        part.writeBytes(ByteArray(1000))
        assertThrows(VoiceModelFetch.WrongContentException::class.java) { fetch() }
        fetch()
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun longerContentIsRejected() {
        served = content + ByteArray(10)
        assertThrows(VoiceModelFetch.WrongContentException::class.java) { fetch() }
        assertFalse(part.exists())
    }

    @Test
    fun anHttpErrorKeepsThePartialFile() {
        part.writeBytes(content.copyOf(1000))
        status = 503
        assertThrows(IOException::class.java) { fetch() }
        assertEquals(1000, part.length())
    }
}
