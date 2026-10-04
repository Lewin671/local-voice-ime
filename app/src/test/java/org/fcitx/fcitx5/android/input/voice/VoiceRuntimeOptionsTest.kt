/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceRuntimeOptionsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun fixedOptionsOnlyChangeWorkerWaitingAndAreReused() {
        val provider = VoiceRuntimeOptions.sleepingCpuProvider(temporary.root)
        val file = File(provider.removePrefix("cpu:"))
        assertEquals("SessionConfig.session.intra_op.allow_spinning=0\n" +
                "SessionConfig.session.inter_op.allow_spinning=0\n", file.readText())
        file.setLastModified(1_234_567_890_000L)
        val modified = file.lastModified()
        assertEquals(provider, VoiceRuntimeOptions.sleepingCpuProvider(temporary.root))
        assertEquals(modified, file.lastModified())
    }

    @Test
    fun staleOrTruncatedOptionsAreRepairedBeforeNativeLoading() {
        val provider = VoiceRuntimeOptions.sleepingCpuProvider(temporary.root)
        val file = File(provider.removePrefix("cpu:"))
        val expected = file.readText()
        file.writeText("SessionConfig.session.intra_op.allow_spinning=")
        assertEquals(provider, VoiceRuntimeOptions.sleepingCpuProvider(temporary.root))
        assertEquals(expected, file.readText())
        assertFalse(File(file.parentFile, "refiner-cpu-v1.config.tmp").exists())
    }

    @Test
    fun unavailableStorageFallsBackToRecognitionWithDefaultCpuScheduling() {
        val unavailable = temporary.newFile("not-a-directory")
        assertEquals("cpu", VoiceRuntimeOptions.sleepingCpuProvider(unavailable))
    }
}
