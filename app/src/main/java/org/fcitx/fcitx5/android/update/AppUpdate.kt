/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import android.app.Activity
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

    enum class Refusal { Storage, Conflict, Incompatible, Blocked, Other }

    sealed interface Install {
        data object None : Install

        /** The package is on its way to the installer; then Android's own dialog takes over. */
        data object Starting : Install
        data object PermissionRequired : Install

        /** Kept until a resumed update screen can open Android's confirmation. */
        data class Confirmation(val intent: Intent) : Install
        data class Refused(val why: Refusal) : Install
    }

    private const val SESSION = "org.fcitx.fcitx5.android.update.SESSION"

    @Volatile
    private var activeSession = -1

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

    fun canInstall(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun permissionRequired() { _install.value = Install.PermissionRequired }

    /** Only a resumed Activity calls this; a receiver must never launch the installer UI. */
    fun confirmInstallation(activity: Activity) {
        val confirmation = synchronized(this) {
            val pending = _install.value as? Install.Confirmation ?: return
            _install.value = Install.None
            pending
        }
        runCatching { activity.startActivity(confirmation.intent) }
            .onFailure {
                Timber.w(it, "Could not show the installer")
                _install.value = Install.Refused(Refusal.Other)
            }
    }

    /** Hand the downloaded package to Android's installer. */
    fun install(context: Context) {
        if (!canInstall(context)) {
            permissionRequired()
            return
        }
        val app = context.applicationContext
        val apk = store(app).found.value?.apk ?: return
        synchronized(this) {
            if (_install.value == Install.Starting || _install.value is Install.Confirmation) return
            _install.value = Install.Starting
        }
        scope.launch {
            try {
                val installer = app.packageManager.packageInstaller
                // left over from an attempt the user neither confirmed nor declined
                activeSession = -1
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val id = installer.createSession(params)
                activeSession = id
                installer.openSession(id).use { session ->
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val result = PendingIntent.getBroadcast(
                        app, id, Intent(app, AppUpdateReceiver::class.java).putExtra(SESSION, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable
                    )
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                val failedSession = activeSession
                activeSession = -1
                if (failedSession >= 0) runCatching {
                    app.packageManager.packageInstaller.abandonSession(failedSession)
                }
                Timber.w(e, "Could not hand the update to the installer")
                val full = apk.parentFile.let { it == null || it.usableSpace < apk.length() }
                _install.value = Install.Refused(if (full) Refusal.Storage else Refusal.Other)
            }
        }
    }

    internal fun onInstallerResult(intent: Intent) {
        if (intent.getIntExtra(SESSION, -1) != activeSession || activeSession < 0) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Timber.i("Installer: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        _install.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { Install.Confirmation(it) } ?: Install.Refused(Refusal.Other)
            // declining is not an error
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Install.None
            PackageInstaller.STATUS_FAILURE_BLOCKED -> Install.Refused(Refusal.Blocked)
            PackageInstaller.STATUS_FAILURE_STORAGE -> Install.Refused(Refusal.Storage)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Install.Refused(Refusal.Conflict)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Install.Refused(Refusal.Incompatible)
            else -> Install.Refused(Refusal.Other)
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) activeSession = -1
    }
}

/** Receives what Android's installer says about the package [AppUpdate.install] gave it. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdate.onInstallerResult(intent)
}
