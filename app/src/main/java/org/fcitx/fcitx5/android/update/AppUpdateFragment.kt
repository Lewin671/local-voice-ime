/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.VoiceModels
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.update.AppUpdate.Install
import org.fcitx.fcitx5.android.update.AppUpdate.Refusal
import org.fcitx.fcitx5.android.update.AppUpdatePreference.Action
import org.fcitx.fcitx5.android.update.AppUpdatePreference.Content
import org.fcitx.fcitx5.android.update.AppUpdateStore.Check
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.setup

/**
 * Settings → App update: the installed version, a button that asks whether there is a newer
 * one, and that version's download and installation. Apart from the speech models in
 * `VoiceSettingsFragment`, the only screen from which the app goes online; see
 * "Settings: app update" in docs/design/mockup.html and docs/PRIVACY.md.
 */
class AppUpdateFragment : PaddingPreferenceFragment() {

    private val store by lazy { AppUpdate.store(requireContext()) }

    private lateinit var installed: AppUpdatePreference
    private lateinit var newVersion: PreferenceCategory
    private lateinit var update: AppUpdatePreference

    private val host = AppRelease.HOST

    private fun size(bytes: Long) = Formatter.formatShortFileSize(requireContext(), bytes)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(Preference(ctx).apply {
                setup(
                    getString(R.string.update_privacy_title),
                    getString(R.string.update_privacy_summary, host),
                    R.drawable.ic_voice_lock_24
                )
                isSelectable = false
            })
            addCategory(R.string.update_installed) {
                isIconSpaceReserved = false
                installed = AppUpdatePreference(ctx).apply {
                    val version = AppRelease.numbers(BuildConfig.VERSION_NAME)?.joinToString(".")
                    title = getString(R.string.update_version, version ?: BuildConfig.VERSION_NAME)
                }
                addPreference(installed)
            }
            addCategory(R.string.update_new_version) {
                isIconSpaceReserved = false
                newVersion = this
                update = AppUpdatePreference(ctx)
                addPreference(update)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // a row is updated several times per second while downloading; don't cross-fade it
        listView.itemAnimator = null
        val download = store.found.flatMapLatest { found ->
            (found?.download?.state ?: flowOf(null)).map { found to it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(store.check, download, AppUpdate.install) { check, (found, state), install ->
                installed.content = installed(check, found != null)
                newVersion.isVisible = found != null
                if (found != null) {
                    update.title = getString(R.string.update_version, found.release.version)
                    update.summary = found.release.notes
                    update.content = update(found, state, install)
                }
            }.collect {}
        }
    }

    private val viewOnGitHub = Action(R.string.update_view_on_github) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppRelease.PAGE)))
    }

    /** Asking is always the result of a tap on a button under the sentence that says whom. */
    private fun check(@StringRes label: Int) = Action(label) {
        AppUpdate.clearRefusal()
        store.check()
    }

    private fun installed(check: Check, found: Boolean) = when (check) {
        Check.Idle ->
            if (found) Content(secondary = check(R.string.update_check_again))
            else Content(primary = check(R.string.update_check))
        Check.Checking -> Content(
            progress = AppUpdatePreference.BUSY, status = getString(R.string.update_checking, host)
        )
        Check.UpToDate -> Content(
            status = getString(R.string.update_newest), secondary = check(R.string.update_check_again)
        )
        Check.Failed -> Content(
            status = getString(R.string.update_check_failed, host), error = true,
            primary = check(R.string.update_check_again), secondary = viewOnGitHub
        )
    }

    private fun update(found: AppUpdateStore.Found, state: State?, install: Install): Content {
        val apk = found.release.apk
        val download = found.download
        if (apk == null || download == null || state == null) return Content(
            source = getString(R.string.update_source_only, host),
            status = getString(R.string.update_no_package, Build.SUPPORTED_ABIS.first()),
            secondary = viewOnGitHub
        )
        val source = getString(R.string.update_source, size(apk.size), host)
        val delete = Action(R.string.delete) {
            AppUpdate.clearRefusal()
            // not tied to this screen: it must finish even if the user leaves
            FcitxApplication.getInstance().coroutineScope.launch { download.delete() }
        }
        fun partial(downloaded: Long) =
            getString(R.string.voice_model_progress, size(downloaded), size(apk.size))
        return when (state) {
            is State.Absent -> Content(
                source = source,
                status = when (state.error) {
                    VoiceModels.Error.Network -> getString(R.string.voice_model_error_network)
                    VoiceModels.Error.Storage ->
                        getString(R.string.voice_model_error_storage, size(apk.size - state.downloaded))
                    VoiceModels.Error.Content -> getString(R.string.voice_model_error_content)
                    null ->
                        if (state.downloaded > 0) getString(R.string.voice_model_paused, partial(state.downloaded))
                        else ""
                },
                error = state.error != null,
                primary =
                    if (state.downloaded > 0) Action(R.string.voice_model_resume, download::download)
                    else Action(R.string.voice_model_download) { confirmDownload(found) },
                secondary = if (state.downloaded > 0) delete else viewOnGitHub
            )
            is State.Downloading -> Content(
                source = source,
                progress = state.downloaded.toFloat() / apk.size,
                status = partial(state.downloaded),
                secondary = Action(R.string.voice_model_pause, download::pause)
            )
            State.Installed -> when (install) {
                Install.Starting -> Content(
                    source = source, progress = AppUpdatePreference.BUSY,
                    status = getString(R.string.update_installing)
                )
                else -> Content(
                    source = source,
                    status = when ((install as? Install.Refused)?.why) {
                        Refusal.Storage -> getString(R.string.update_refused_storage)
                        Refusal.Conflict -> getString(R.string.update_refused_conflict)
                        Refusal.Incompatible -> getString(R.string.update_refused_incompatible)
                        Refusal.Other -> getString(R.string.update_refused)
                        null -> getString(R.string.update_downloaded)
                    },
                    error = install is Install.Refused,
                    primary = Action(R.string.update_install) { AppUpdate.install(requireContext()) },
                    secondary = delete
                )
            }
        }
    }

    /** Going online is always the result of a tap that said what is fetched and from where. */
    private fun confirmDownload(found: AppUpdateStore.Found) {
        val apk = found.release.apk ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.voice_model_download_title, size(apk.size)))
            .setMessage(
                getString(
                    R.string.update_download_message,
                    getString(R.string.update_version, found.release.version), host
                )
            )
            .setPositiveButton(R.string.voice_model_download) { _, _ -> found.download?.download() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
