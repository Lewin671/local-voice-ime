/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.setup

/**
 * Settings → Voice input: what stays on the phone, which speech models are on it, and their
 * downloads. The only screen from which the app goes online; see
 * "Settings: voice input" in docs/design/mockup.html and docs/PRIVACY.md.
 */
class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voiceInput) {

    /** A model as this screen presents it. */
    private class Entry(
        val model: VoiceModel,
        @StringRes val title: Int,
        @StringRes val summary: Int,
        @StringRes val deleteTitle: Int,
        /** Takes the size that is freed; says what stops working. */
        @StringRes val deleteMessage: Int,
        /** Deletes the model once nothing uses it any more. */
        val uninstall: suspend (Context) -> Unit
    )

    private val entries = listOf(
        Entry(
            VoiceModels.SenseVoice,
            R.string.voice_model_standard, R.string.voice_model_standard_summary,
            R.string.voice_model_standard_delete_title, R.string.voice_model_standard_delete_message,
            VoiceEngine::uninstall
        ),
        Entry(
            VoiceModels.FireRedAsr2,
            R.string.voice_model_accurate, R.string.voice_model_accurate_summary,
            R.string.voice_model_delete_title, R.string.voice_model_delete_message,
            VoiceRefiner::uninstall
        )
    )

    private lateinit var rows: List<VoiceModelPreference>

    private lateinit var refine: Preference

    private fun size(bytes: Long) = Formatter.formatShortFileSize(requireContext(), bytes)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        refine = screen.findPreference(AppPrefs.getInstance().voiceInput.voiceRefine.key)!!
        screen.removePreference(refine)

        screen.addPreference(Preference(ctx).apply {
            setup(
                getString(R.string.voice_privacy_title),
                getString(R.string.voice_privacy_summary),
                R.drawable.ic_voice_lock_24
            )
            isSelectable = false
        })
        screen.addCategory(R.string.voice_models) {
            isIconSpaceReserved = false
            rows = entries.map { entry ->
                VoiceModelPreference(ctx, entry.model).apply {
                    setTitle(entry.title)
                    setSummary(entry.summary)
                    onDownload = { download(entry.model) }
                    onPause = { VoiceModels.pause(ctx, entry.model) }
                    onDelete = { confirmDelete(entry) }
                }
            }
            rows.forEach(::addPreference)
        }
        screen.addCategory(R.string.voice_dictation) {
            isIconSpaceReserved = false
            addPreference(refine)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // a row is updated several times per second while downloading; don't cross-fade it
        listView.itemAnimator = null
        entries.zip(rows).forEach { (entry, row) ->
            viewLifecycleOwner.lifecycleScope.launch {
                VoiceModels.state(requireContext(), entry.model).collect {
                    row.state = it
                    if (entry.model === VoiceModels.FireRedAsr2) refine.isEnabled = it == State.Installed
                }
            }
        }
    }

    /** Going online is always the result of a tap that said what is fetched and from where. */
    private fun download(model: VoiceModel) {
        val ctx = requireContext()
        val state = VoiceModels.state(ctx, model).value
        if (state is State.Absent && state.downloaded > 0) return VoiceModels.download(ctx, model)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.voice_model_download_title, size(model.size)))
            .setMessage(
                getString(
                    R.string.voice_model_download_message, model.name, model.host.removePrefix("www.")
                )
            )
            .setPositiveButton(R.string.voice_model_download) { _, _ ->
                VoiceModels.download(ctx, model)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(entry: Entry) {
        val ctx = requireContext().applicationContext
        val onDevice = when (val state = VoiceModels.state(ctx, entry.model).value) {
            is State.Absent -> state.downloaded
            is State.Downloading -> state.downloaded
            State.Installed -> entry.model.size
        }
        AlertDialog.Builder(requireContext())
            .setTitle(entry.deleteTitle)
            .setMessage(getString(entry.deleteMessage, size(onDevice)))
            .setPositiveButton(R.string.delete) { _, _ ->
                // not tied to this screen: it must finish even if the user leaves
                FcitxApplication.getInstance().coroutineScope.launch { entry.uninstall(ctx) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
