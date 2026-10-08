/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run on a dedicated device: changes only the debug app's update fixture and install app-op. */
class AppUpdateInstallerTest {
    @Test
    fun verifiedFileInstallerRequiresPermissionAndCannotShareVoiceFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.filesDir, "app-update")
        dir.deleteRecursively()
        val apk = File(dir, "v99.0.0/fixture-arm64-v8a.apk")
        apk.parentFile!!.mkdirs()
        File(context.applicationInfo.sourceDir).copyTo(apk)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        File(apk.parentFile, "installed").writeText(hash)
        File(dir, "release.json").writeText(
            """{"tag":"v99.0.0","notes":"Installer fixture","apk":{"name":"${apk.name}","size":${apk.length()},"sha256":"$hash"}}"""
        )
        fun permission(mode: String) {
            instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} REQUEST_INSTALL_PACKAGES $mode"
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        try {
            permission("deny")
            assertNull(AppUpdate.systemInstallerIntent(context))
            assertEquals(AppUpdate.Install.PermissionRequired, AppUpdate.install.value)
            permission("allow")
            val intent = AppUpdate.systemInstallerIntent(context)!!
            @Suppress("DEPRECATION")
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertNotNull(context.packageManager.resolveActivity(intent, 0))
            context.contentResolver.openInputStream(intent.data!!)!!.use {
                assertEquals('P'.code, it.read())
                assertEquals('K'.code, it.read())
            }
            assertNull(AppUpdate.systemInstallerIntent(context)) // no duplicate launch
            val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
            assertFalse(provider.exported)
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.updates",
                    File(context.filesDir, "voice/private.wav"))
                fail("Voice files must not be shareable through the update provider")
            } catch (_: IllegalArgumentException) {
                // The provider's root is restricted to app-update/.
            }
            AppUpdate.systemInstallerResult(Activity.RESULT_CANCELED)
            assertEquals(AppUpdate.Install.Refused(AppUpdate.Refusal.Aborted), AppUpdate.install.value)
            assertNotNull(AppUpdate.systemInstallerIntent(context)) // cancellation permits retry
        } finally {
            AppUpdate.systemInstallerResult(Activity.RESULT_OK)
            permission("default")
            dir.deleteRecursively()
        }
    }
}
