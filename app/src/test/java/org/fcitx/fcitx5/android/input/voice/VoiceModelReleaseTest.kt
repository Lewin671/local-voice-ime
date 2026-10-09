/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelReleaseTest {

    private val sha = "a".repeat(64)
    private val base = "https://github.com/Lewin671/sensevoice-finetune/releases/download"

    private fun asset(tag: String, name: String, size: Long = 10, digest: String? = "sha256:$sha", url: String = "$base/$tag/$name") =
        """{"name": "$name", "size": $size, ${digest?.let { "\"digest\": \"$it\"," }.orEmpty()}
           "browser_download_url": "$url"}"""

    private fun answer(tag: String, vararg assets: String) =
        """{"tag_name": "$tag", "body": "notes", "assets": [${assets.joinToString()}]}"""

    private fun usual(tag: String = "model-20261012") =
        answer(tag, asset(tag, "model.int8.onnx", 200), asset(tag, "tokens.txt"), asset(tag, "notes.txt"))

    @Test
    fun aReleaseBecomesAModelWithItsOwnDirectory() {
        val release = VoiceModelRelease.parse(usual())
        assertEquals("model-20261012", release.tag)
        assertEquals("2026-10-12", release.version)
        val model = release.model()
        assertEquals("sense-voice-small-tuned-20261012-int8", model.id)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), model.files.map { it.name })
        assertEquals(210L, model.size)
        assertEquals("$base/model-20261012/model.int8.onnx", model.url(model.files[0]).toString())
        assertEquals(sha, model.files[0].sha256)
    }

    @Test
    fun theVersionTheAppKnowsHasTheNameItAlwaysHad() {
        assertEquals("sense-voice-small-tuned-20261009-int8", VoiceModels.SenseVoiceTuned.model().id)
    }

    @Test
    fun laterDatesAreNewer() {
        val old = VoiceModelRelease.parse(usual("model-20261009"))
        val new = VoiceModelRelease.parse(usual("model-20270101"))
        assertTrue(new.isNewerThan(old))
        assertFalse(old.isNewerThan(new))
        assertFalse(old.isNewerThan(old))
    }

    @Test
    fun whatIsNotAModelReleaseIsRefused() {
        val tag = "model-20261012"
        val model = asset(tag, "model.int8.onnx")
        val tokens = asset(tag, "tokens.txt")
        val refused = listOf(
            "not json",
            "[]",
            answer("v1.0.0", model, tokens),
            answer("model-20261012/../x", model, tokens),
            answer(tag, model),
            answer(tag, model, model, tokens),
            answer(tag, model, asset(tag, "tokens.txt", digest = null)),
            answer(tag, model, asset(tag, "tokens.txt", digest = "sha256:abc")),
            answer(tag, model, asset(tag, "tokens.txt", size = 0)),
            answer(tag, asset(tag, "model.int8.onnx", size = 2L shl 30), tokens),
            answer(tag, model, asset(tag, "tokens.txt", url = "https://example.com/tokens.txt")),
            answer(tag, model, asset(tag, "tokens.txt", url = "$base/model-20261009/tokens.txt"))
        )
        for (text in refused) {
            assertThrows(text, IllegalArgumentException::class.java) { VoiceModelRelease.parse(text) }
        }
    }
}
