/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * The files of the voice diagnostics (see [VoiceDiagnostics] and `docs/DIAGNOSTICS.md`): a journal
 * of one event per line that never grows beyond [maxBytes]. When the current file is half of
 * that, it replaces the previous one, so the oldest events go first.
 *
 * What a line may contain is decided by [encode], which is the only way to make one: numbers,
 * truth values and short tokens from a fixed alphabet. Words somebody said or typed cannot be
 * written, whatever a caller passes in.
 *
 * No Android classes, unit-tested. Not thread-safe: [VoiceDiagnostics] uses it from one thread.
 */
internal class VoiceDiagnosticStore(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    private val current = File(dir, "events.jsonl")
    private val previous = File(dir, "events.1.jsonl")

    /** What the journal takes on this device. */
    val bytes get() = current.length() + previous.length()

    /** Add a line made by [encode]. Failing to write is not an error anybody could act on. */
    fun append(line: String) {
        runCatching {
            dir.mkdirs()
            val data = (line + "\n").toByteArray(Charsets.US_ASCII)
            if (current.length() + data.size > maxBytes / 2 && current.length() > 0) {
                previous.delete()
                current.renameTo(previous)
            }
            FileOutputStream(current, true).use { it.write(data) }
        }
    }

    /** Everything that is kept, oldest first. A last line cut short by a crash stays as it is. */
    fun writeTo(out: OutputStream) {
        for (file in arrayOf(previous, current)) {
            if (file.exists()) file.inputStream().use { it.copyTo(out) }
        }
    }

    fun delete() {
        previous.delete()
        current.delete()
        dir.delete()
    }

    companion object {
        const val MAX_BYTES = 1L shl 20

        private val TOKEN = Regex("[A-Za-z0-9_.-]{1,48}")

        /** Stands for a value that [encode] refused. */
        const val REFUSED = "refused"

        /**
         * One line of the journal: the [event] and its [fields] as a JSON object. Values are
         * numbers, truth values or tokens (letters, digits, `_`, `.`, `-`; at most 48 of them);
         * anything else, such as a sentence, is written as [REFUSED]. Fields that are null are
         * left out.
         */
        fun encode(event: String, fields: List<Pair<String, Any?>>): String {
            val line = StringBuilder(96)
            line.append("{\"e\":\"").append(token(event)).append('"')
            for ((key, value) in fields) {
                if (value == null) continue
                line.append(",\"").append(token(key)).append("\":")
                when (value) {
                    is Boolean, is Int, is Long -> line.append(value)
                    is Float -> line.append(if (value.isFinite()) value else 0)
                    else -> line.append('"').append(token(value.toString())).append('"')
                }
            }
            return line.append('}').toString()
        }

        private fun token(text: String) = if (TOKEN.matches(text)) text else REFUSED

        /** [text] cut down to a token: for the name of a phone, which is not a fixed word. */
        fun tokenOf(text: String) =
            text.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48).ifEmpty { REFUSED }
    }
}
