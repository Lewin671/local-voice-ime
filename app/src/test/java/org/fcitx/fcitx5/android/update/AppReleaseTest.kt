/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {

    private val sha = "6adfd40d055c2b968eec03622e5804baf3fd8d82012c9aa61a56617d30aa16f2"

    private fun asset(
        name: String,
        url: String = "https://github.com/Lewin671/local-voice-ime/releases/download/v0.7.0/$name",
        digest: String? = "sha256:$sha",
        size: Long = 68918398
    ) = """{"name": "$name", "size": $size, "digest": ${digest?.let { "\"$it\"" }}, "browser_download_url": "$url", "state": "uploaded"}"""

    /** GitHub's answer, reduced to a few of the fields it has. */
    private fun answer(
        vararg assets: String = arrayOf(asset("local-voice-ime-v0.7.0-arm64-v8a.apk"), asset("SHA256SUMS.txt")),
        tag: String = "v0.7.0",
        body: String? = "Faster."
    ) = """{"url": "https://api.github.com/repos/x", "tag_name": "$tag", "draft": false,
        "author": {"login": "someone"}, "assets": [${assets.joinToString()}],
        "body": ${body?.let { "\"$it\"" }}}"""

    private val arm = listOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun readsTheNewestRelease() {
        val release = AppRelease.parse(answer(), arm)
        assertEquals("v0.7.0", release.tag)
        assertEquals("0.7.0", release.version)
        assertEquals("Faster.", release.notes)
        assertEquals(AppRelease.Apk("local-voice-ime-v0.7.0-arm64-v8a.apk", 68918398, sha), release.apk)
    }

    @Test
    fun picksThePackageForThePreferredProcessor() {
        val both = answer(asset("app-v0.7.0-armeabi-v7a.apk"), asset("app-v0.7.0-arm64-v8a.apk"))
        assertEquals("app-v0.7.0-arm64-v8a.apk", AppRelease.parse(both, arm).apk?.name)
        assertEquals("app-v0.7.0-armeabi-v7a.apk", AppRelease.parse(both, listOf("armeabi-v7a")).apk?.name)
    }

    @Test
    fun aReleaseWithoutAPackageForThisDeviceHasNone() {
        assertNull(AppRelease.parse(answer(), listOf("x86_64")).apk)
        assertNull(AppRelease.parse(answer(assets = emptyArray()), arm).apk)
        assertNull(AppRelease.parse("""{"tag_name": "v0.7.0"}""", arm).apk)
    }

    @Test
    fun aPackageIsOnlyTakenFromThisProjectsReleaseAndWithItsChecksum() {
        val name = "app-v0.7.0-arm64-v8a.apk"
        fun apk(asset: String) = AppRelease.parse(answer(asset), arm).apk
        assertEquals(name, apk(asset(name))?.name)
        assertNull(apk(asset(name, url = "https://example.com/$name")))
        assertNull(apk(asset(name, url = "https://github.com/someone/else/releases/download/v0.7.0/$name")))
        // the address must be the one the app will build from tag and name
        assertNull(apk(asset(name, url = "https://github.com/Lewin671/local-voice-ime/releases/download/v0.6.0/$name")))
        assertNull(apk(asset(name, digest = null)))
        assertNull(apk(asset(name, digest = "sha256:1234")))
        assertNull(apk(asset(name, digest = "md5:$sha")))
        assertNull(apk(asset(name, size = 0)))
        assertNull(apk(asset("a b-arm64-v8a.apk")))
    }

    @Test
    fun whatIsNotAReleaseIsRejected() {
        for (text in listOf(
            "", "<html>", "[]", """{"message": "API rate limit exceeded"}""",
            answer(tag = "../../etc"), answer(tag = "v1/../.."), answer(tag = "latest")
        )) {
            assertThrows(text, IllegalArgumentException::class.java) { AppRelease.parse(text, arm) }
        }
    }

    @Test
    fun notesBecomePlainText() {
        assertEquals("", AppRelease.parse(answer(body = null), arm).notes)
        assertEquals(
            "What's new\n• Faster start\n• The check.sh script\nThanks.",
            AppRelease.plainText("## What's new\r\n\r\n- **Faster** start\n* The `check.sh` script\n\n\nThanks.\n")
        )
        assertEquals(2000, AppRelease.plainText("x".repeat(5000)).length)
    }

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(AppRelease.isNewer("v0.7.0", "v0.6.2"))
        assertTrue(AppRelease.isNewer("v0.10.0", "v0.9.2"))
        assertTrue(AppRelease.isNewer("v1.0", "0.99.99"))
        assertTrue(AppRelease.isNewer("v0.6.2.1", "v0.6.2"))
        assertFalse(AppRelease.isNewer("v0.6.2", "v0.6.2"))
        assertFalse(AppRelease.isNewer("v0.6", "v0.6.0"))
        assertFalse(AppRelease.isNewer("v0.6.1", "v0.6.2"))
    }

    @Test
    fun aBuildsVersionNameIsUnderstood() {
        // git describe --tags --long --always
        assertEquals(listOf(0, 6, 2), AppRelease.numbers("v0.6.2-0-g59f996d8"))
        assertFalse(AppRelease.isNewer("v0.6.2", "v0.6.2-3-g59f996d8"))
        assertTrue(AppRelease.isNewer("v0.6.3", "v0.6.2-3-g59f996d8"))
        // without tags the name is a commit hash, which may start with digits: no version
        assertNull(AppRelease.numbers("59f996d8"))
        assertNull(AppRelease.numbers("1234567"))
        assertFalse(AppRelease.isNewer("v0.7.0", "59f996d8"))
    }
}
