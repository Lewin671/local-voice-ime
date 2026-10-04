/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Run on the native worker, after queued requests ahead of this one. A permanently retired
 * editor entry cannot use its result, so skip both loading and decoding where possible. Do not
 * interrupt an in-flight native call. Pure Kotlin so loading races can be tested on the JVM.
 */
internal object VoiceRefinementWork {
    suspend fun run(
        needed: () -> Boolean,
        load: suspend () -> Unit,
        decode: () -> String
    ): String? {
        if (!needed()) return null
        load()
        if (!needed()) return null
        return decode()
    }
}
