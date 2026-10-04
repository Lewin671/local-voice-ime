/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * Updating the app from *Settings → App update*: the process-wide [AppUpdateStore], and handing
 * a downloaded package to Android's installer. Android asks the user, and installs the package
 * only if it is signed like the installed app. See "Settings: app update" in
 * docs/design/mockup.html.
 */
object AppUpdate {

    enum class Refusal { Storage, Conflict, Incompatible, Other }

    sealed interface Install {
        data object None : Install

        /** The package is on its way to the installer; then Android's own dialog takes over. */
        data object Starting : Install
        data class Refused(val why: Refusal) : Install
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _install = MutableStateFlow<Install>(Install.None)
    val install: StateFlow<Install> = _install

    @Volatile
    private var store: AppUpdateStore? = null

    fun store(context: Context) = store ?: synchronized(this) {
        store ?: AppUpdateStore(
            File(context.applicationContext.filesDir, "app-update"),
            BuildConfig.VERSION_NAME,
            Build.SUPPORTED_ABIS.toList()
        ).also { store = it }
    }

    /** Forget a refusal, e.g. because the package it was about is gone. */
    fun clearRefusal() {
        if (_install.value is Install.Refused) _install.value = Install.None
    }

    /** Hand the downloaded package to Android's installer. */
    fun install(context: Context) {
        val app = context.applicationContext
        val apk = store(app).found.value?.apk ?: return
        synchronized(this) {
            if (_install.value == Install.Starting) return
            _install.value = Install.Starting
        }
        scope.launch {
            try {
                val installer = app.packageManager.packageInstaller
                // left over from an attempt the user neither confirmed nor declined
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(apk.length())
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val result = PendingIntent.getBroadcast(
                        app, id, Intent(app, AppUpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable
                    )
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not hand the update to the installer")
                val full = apk.parentFile.let { it == null || it.usableSpace < apk.length() }
                _install.value = Install.Refused(if (full) Refusal.Storage else Refusal.Other)
            }
        }
    }

    internal fun onInstallerResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Timber.i("Installer: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        _install.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Update this app?"; from here on the row offers Install again, so a
                // dialog that is left without an answer does not leave it stuck
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { e -> Timber.w(e, "Could not show the installer") }
                }
                Install.None
            }
            // declining is not an error
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Install.None
            PackageInstaller.STATUS_FAILURE_STORAGE -> Install.Refused(Refusal.Storage)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Install.Refused(Refusal.Conflict)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Install.Refused(Refusal.Incompatible)
            else -> Install.Refused(Refusal.Other)
        }
    }
}

/** Receives what Android's installer says about the package [AppUpdate.install] gave it. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdate.onInstallerResult(context, intent)
}
