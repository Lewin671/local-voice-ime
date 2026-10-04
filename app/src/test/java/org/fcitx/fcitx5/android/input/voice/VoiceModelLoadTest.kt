/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelLoadTest {
    @Test
    fun cancelledQueuedLoadDoesNotLoadOrExtendModelResidency() = runBlocking {
        val worker = CompletableDeferred<Unit>()
        var needed = true
        var touches = 0
        var loads = 0
        val request = async {
            worker.await()
            VoiceModelLoad.run({ needed }) { touches++; loads++ }
        }
        needed = false
        worker.complete(Unit)
        assertFalse(request.await())
        assertEquals(0, loads)
        assertEquals(0, touches)
    }

    @Test
    fun withdrawingSpeculationDoesNotSuppressIndependentRequiredLoad() {
        var speculative = false
        var committedEntry = true
        var loads = 0
        assertFalse(VoiceModelLoad.run({ speculative }) { loads++ })
        assertTrue(VoiceModelLoad.run({ committedEntry }) { loads++ })
        assertEquals(1, loads)
        committedEntry = false
        assertFalse(VoiceModelLoad.run({ committedEntry }) { loads++ })
        assertEquals(1, loads)
    }

    @Test
    fun activeNativeLoadFinishesEvenWhenDemandIsWithdrawn() {
        var needed = true
        var finished = false
        assertTrue(VoiceModelLoad.run({ needed }) {
            needed = false
            finished = true
        })
        assertTrue(finished)
    }

    @Test
    fun stoppingCaptureStillAllowsRequiredFinalRecognition() {
        val stopped = true
        val discarded = false
        var loads = 0
        assertFalse(VoiceModelLoad.run({ !stopped && !discarded }) { loads++ })
        assertTrue(VoiceModelLoad.run({ !discarded }) { loads++ })
        assertEquals(1, loads)
    }
}
