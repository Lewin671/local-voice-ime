/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRefinementWorkTest {
    @Test
    fun retiringWhileQueuedSkipsBothModelLoadAndDecode() = runBlocking {
        val workerAvailable = CompletableDeferred<Unit>()
        var alive = true
        var loads = 0
        var decodes = 0
        val pending = async {
            workerAvailable.await()
            VoiceRefinementWork.run({ alive }, { loads++ }) {
                decodes++
                "Accurate words"
            }
        }
        alive = false
        workerAvailable.complete(Unit)
        assertNull(pending.await())
        assertEquals(0, loads)
        assertEquals(0, decodes)
    }

    @Test
    fun retirementDuringModelLoadingSkipsDecode() = runBlocking {
        var alive = true
        var decodes = 0
        val result = VoiceRefinementWork.run({ alive }, { alive = false }) {
            decodes++
            "Accurate words"
        }
        assertNull(result)
        assertEquals(0, decodes)
    }

    @Test
    fun retainedTextReceivesExactlyTheSameDecodeResult() = runBlocking {
        var loads = 0
        var decodes = 0
        val result = VoiceRefinementWork.run({ true }, { loads++ }) {
            decodes++
            "准确的 Words 123"
        }
        assertEquals("准确的 Words 123", result)
        assertEquals(1, loads)
        assertEquals(1, decodes)
    }
}
