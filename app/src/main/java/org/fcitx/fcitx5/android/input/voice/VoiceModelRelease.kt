/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A published version of the fine-tuned model: a release of
 * https://github.com/Lewin671/sensevoice-finetune tagged `model-<yyyymmdd>`, holding the two
 * files sherpa-onnx loads. Pure Kotlin, see `VoiceModelReleaseTest`.
 */
@Serializable
data class VoiceModelRelease(
    /** The release's tag, e.g. `model-20261009`. */
    val tag: String,
    val files: List<File>
) {
    @Serializable
    data class File(val name: String, val size: Long, val sha256: String)

    /** For display: the day it was made, `2026-10-09`. */
    val version get() = tag.removePrefix(PREFIX).let { "${it.take(4)}-${it.substring(4, 6)}-${it.drop(6)}" }

    /** Every version has a directory of its own on the device. */
    fun model() = VoiceModel(
        id = "$ID_PREFIX${tag.removePrefix(PREFIX)}-int8",
        name = "SenseVoice Small, fine-tuned $version",
        host = HOST,
        baseUrl = "$REPO/releases/download/$tag/",
        files = files.map { VoiceModel.File(it.name, it.size, it.sha256) }
    )

    /** Tags are dates in one format, so they sort as text. */
    fun isNewerThan(other: VoiceModelRelease) = tag > other.tag

    companion object {
        const val HOST = "github.com"
        private const val REPO = "/Lewin671/sensevoice-finetune"

        /** Answers with a description of the newest release; the one address a check asks. */
        const val LATEST = "https://api.github.com/repos$REPO/releases/latest"

        private const val PREFIX = "model-"

        /** Start of the directory name of every version, see [model]. */
        const val ID_PREFIX = "sense-voice-small-tuned-"

        // becomes part of a URL and of a directory name on the device
        private val TAG = Regex("""model-\d{8}""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")

        /** What a model for [VoiceEngine] consists of. */
        private val NAMES = listOf("model.int8.onnx", "tokens.txt")

        /** Several times the size of the model; anything larger is not one. */
        private const val SIZE_LIMIT = 1L shl 30

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.string(key: String) =
            get(key)?.jsonPrimitive?.takeIf { it.isString }?.content

        /**
         * The release described by [text], GitHub's answer to [LATEST]. It must hold both files
         * of a model, each where this project's models are published and with a SHA-256.
         *
         * @throws IllegalArgumentException if [text] does not describe such a release
         */
        fun parse(text: String): VoiceModelRelease {
            val release = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a release description", e)
            }
            val tag = release.string("tag_name")?.takeIf(TAG::matches)
                ?: throw IllegalArgumentException("No usable tag_name")
            val assets = runCatching { release["assets"]!!.jsonArray.map { it.jsonObject } }
                .getOrDefault(emptyList())
            val files = NAMES.map { name ->
                val asset = assets.singleOrNull { it.string("name") == name }
                    ?: throw IllegalArgumentException("$tag has no $name")
                val size = runCatching { asset["size"]!!.jsonPrimitive.long }.getOrNull()
                val sha256 = asset.string("digest")?.removePrefix("sha256:")
                val url = asset.string("browser_download_url")
                require(
                    size != null && size in 1..SIZE_LIMIT && sha256 != null && SHA256.matches(sha256) &&
                            url == "https://$HOST$REPO/releases/download/$tag/$name"
                ) { "$name of $tag is not usable" }
                File(name, size!!, sha256!!)
            }
            return VoiceModelRelease(tag, files)
        }
    }
}
