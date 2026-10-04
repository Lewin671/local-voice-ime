/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceModelsTest {

    @Test
    fun everyFileIsPinnedAndFetchedOverHttps() {
        val ids = VoiceModels.all.map { it.id }
        assertEquals(ids.distinct(), ids)
        for (model in VoiceModels.all) for (file in model.files) {
            assertTrue("${model.id}/${file.name}: size", file.size > 0)
            assertTrue("${model.id}/${file.name}: sha256", Regex("[0-9a-f]{64}").matches(file.sha256))
            assertEquals("https", model.url(file).protocol)
        }
    }

    /** The device tests push the files that `scripts/fetch-voice-assets.sh` fetched: the same ones. */
    @Test
    fun testCopiesAreTheFilesTheAppDownloads() {
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "scripts/fetch-voice-assets.sh") }
            .first { it.exists() }
            .readText()
        for (model in VoiceModels.all) {
            assertTrue("voice/models/${model.id}", "voice/models/${model.id}" in script)
            for (file in model.files) {
                assertTrue("${model.id}/${file.name} is pinned differently", file.sha256 in script)
            }
        }
    }
}
