/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.io.IOException

/** ONNX worker scheduling only: no change to model math, graph optimization or thread count. */
internal object VoiceRuntimeOptions {
    private const val CONFIG = "SessionConfig.session.intra_op.allow_spinning=0\n" +
            "SessionConfig.session.inter_op.allow_spinning=0\n"

    /**
     * sherpa-onnx 1.13.8 accepts CPU session options in a local file. Publish the fixed config
     * atomically; if storage is unavailable, default CPU scheduling still recognizes speech.
     * Pure Kotlin, unit-tested. The caller supplies app-private storage.
     */
    @Synchronized
    fun sleepingCpuProvider(storage: File): String {
        val dir = File(storage, "voice-runtime")
        val config = File(dir, "refiner-cpu-v1.config")
        val temporary = File(dir, "refiner-cpu-v1.config.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create runtime directory")
            if (!config.isFile || config.readText() != CONFIG) {
                temporary.writeText(CONFIG)
                if (!temporary.renameTo(config)) throw IOException("Cannot publish runtime options")
            }
            "cpu:${config.absolutePath}"
        } catch (_: IOException) {
            temporary.delete()
            "cpu"
        }
    }
}
