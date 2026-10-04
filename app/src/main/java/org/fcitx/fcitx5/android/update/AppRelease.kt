/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A published version of the app, as *Settings → App update* shows it. Pure Kotlin, see
 * `AppReleaseTest`.
 */
@Serializable
data class AppRelease(
    /** The release's tag, e.g. `v0.7.0`. */
    val tag: String,
    /** Release notes as plain text; may be empty. */
    val notes: String,
    /** The package for this device; `null` if the release has none for its processor. */
    val apk: Apk?
) {
    @Serializable
    data class Apk(val name: String, val size: Long, val sha256: String)

    /** For display: the tag without its `v`. */
    val version get() = tag.removePrefix("v")

    companion object {
        const val HOST = "github.com"
        private const val REPO = "/Lewin671/local-voice-ime"

        /** Answers with a description of the newest release; the one address a check asks. */
        const val LATEST = "https://api.github.com/repos$REPO/releases/latest"

        /** For the browser: every release, with notes and packages. */
        const val PAGE = "https://$HOST$REPO/releases"

        /** Where the packages of the release tagged [tag] are; an asset's name is appended. */
        fun downloadPath(tag: String) = "$REPO/releases/download/$tag/"

        // both become part of a URL and of a file name on the device
        private val TAG = Regex("""v?\d+(\.\d+){0,3}""")
        private val APK = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.apk""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.string(key: String) =
            get(key)?.jsonPrimitive?.takeIf { it.isString }?.content

        /**
         * The release described by [text], GitHub's answer to [LATEST]. Its package is the
         * `.apk` asset named for the first of [abis] that has one, provided it is where this
         * project's packages are published and comes with a SHA-256.
         *
         * @throws IllegalArgumentException if [text] does not describe a release
         */
        fun parse(text: String, abis: List<String>): AppRelease {
            val release = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a release description", e)
            }
            val tag = release.string("tag_name")?.takeIf(TAG::matches)
                ?: throw IllegalArgumentException("No usable tag_name")
            val assets = runCatching { release["assets"]!!.jsonArray.map { it.jsonObject } }
                .getOrDefault(emptyList())
            val apk = abis.firstNotNullOfOrNull { abi ->
                assets.firstNotNullOfOrNull { asset ->
                    val name = asset.string("name") ?: return@firstNotNullOfOrNull null
                    val size = runCatching { asset["size"]!!.jsonPrimitive.long }.getOrNull()
                    val sha256 = asset.string("digest")?.removePrefix("sha256:")
                    val url = asset.string("browser_download_url")
                    if (APK.matches(name) && name.endsWith("-$abi.apk") &&
                        size != null && size > 0 && sha256 != null && SHA256.matches(sha256) &&
                        url == "https://$HOST${downloadPath(tag)}$name"
                    ) Apk(name, size, sha256) else null
                }
            }
            return AppRelease(tag, plainText(release.string("body").orEmpty()), apk)
        }

        /** Release notes are written in Markdown; the screen shows text. */
        internal fun plainText(markdown: String) = markdown.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("""^#+\s*"""), "").replace(Regex("""^[-*]\s+"""), "• ") }
            .map { it.replace(Regex("""\*\*|`"""), "") }
            .joinToString("\n")
            .take(NOTES_LIMIT)

        private const val NOTES_LIMIT = 2000

        /**
         * The numbers of a version: `v0.7.0` and `0.7.0-3-gabc1234` (a build's version name,
         * from `git describe`) are both 0, 7, 0. `null` if [name] does not start with a version, as
         * the name of a build from a checkout without tags: a bare commit hash, which may well
         * consist of digits. Hence a version is a `v`, or has a dot.
         */
        fun numbers(name: String): List<Int>? =
            Regex("""^(?:v(\d+(?:\.\d+)*)|(\d+(?:\.\d+)+))(?:$|[-+])""").find(name)
                ?.groupValues?.let { it[1] + it[2] }
                ?.split('.')?.map { it.toIntOrNull() ?: return null }

        /** Whether the version [a] is newer than [b]; a missing number counts as 0. */
        fun isNewer(a: String, b: String): Boolean {
            val x = numbers(a) ?: return false
            val y = numbers(b) ?: return false
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
