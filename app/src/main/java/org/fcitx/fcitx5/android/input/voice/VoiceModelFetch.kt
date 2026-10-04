/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads one file (of a speech model, or the package of a new version of the app):
 * resumable, and accepted only if its size and SHA-256 are the expected ones. [read] asks for
 * the short document that says which version is the newest. Pure Kotlin, see
 * `VoiceModelFetchTest`.
 *
 * **This is the only code in the app that opens a network connection** (`docs/PRIVACY.md`).
 * It sends a plain GET for a fixed URL and nothing else: no identifiers, no request body.
 */
object VoiceModelFetch {

    /** The server delivered something else than the pinned file. Whatever was received is discarded. */
    class WrongContentException(message: String) : IOException(message)

    private const val TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 1 shl 16

    /** Instead of the platform's default, which names the phone's model and Android build. */
    private const val USER_AGENT = "local-voice-ime"

    /** The server answered, but not with the document asked for. */
    class HttpException(val code: Int, url: URL) : IOException("HTTP $code for $url")

    /** The body at [url] as text, which must not be longer than [limit] bytes. */
    fun read(url: URL, limit: Int, timeoutMs: Int = TIMEOUT_MS): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpException(code, url)
            val body = connection.inputStream.use { it.readNBytesCompat(limit + 1) }
            if (body.size > limit) throw WrongContentException("More than $limit bytes at $url")
            return body.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** `InputStream.readNBytes` is not available before Android 13. */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Download [url] to [dest]. Data is collected in `<dest>.part`, which survives a failure or
     * a cancellation and is continued by the next call; [dest] only ever exists complete and
     * verified.
     *
     * [onProgress] is called with the number of bytes present so far, at least once per
     * [BUFFER_SIZE] bytes. Throw from it to cancel.
     */
    fun fetch(url: URL, dest: File, size: Long, sha256: String, onProgress: (Long) -> Unit) {
        if (dest.length() == size && dest.exists()) return onProgress(size)
        dest.delete()
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        if (part.length() > size) part.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        // hash what an earlier attempt left behind
        if (part.exists()) part.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                onProgress(done)
            }
        }

        if (done < size) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                // the checksum is over the bytes as stored, so no transparent compression
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (done > 0) connection.setRequestProperty("Range", "bytes=$done-")
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK && done > 0) {
                    // the server ignored the range: start over
                    digest.reset()
                    done = 0
                } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $code for $url")
                }
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(done)
                    out.seek(done)
                    connection.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (done + n > size) {
                                part.delete()
                                throw WrongContentException("More than $size bytes at $url")
                            }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            // the connection was closed early: keep the part for the next attempt
            if (part.length() < size) throw IOException("Incomplete download of $url: $done of $size bytes")
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != size || actual != sha256) {
            part.delete()
            throw WrongContentException("Unexpected content at $url: sha256 $actual, ${part.length()} bytes")
        }
        if (!part.renameTo(dest)) throw IOException("Cannot move $part to $dest")
    }
}
