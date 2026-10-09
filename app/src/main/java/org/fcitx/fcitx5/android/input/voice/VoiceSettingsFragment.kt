/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.setup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Voice input: what stays on the phone, which speech models are on it, their
 * downloads, and the recordings a user chose to keep. See "Settings: voice input" in
 * docs/design/mockup.html and docs/PRIVACY.md.
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
            { VoiceEngine.uninstall(it, VoiceModels.SenseVoice) }
        ),
        Entry(
            VoiceModels.SenseVoiceTuned,
            R.string.voice_model_tuned, R.string.voice_model_tuned_summary,
            R.string.voice_model_tuned_delete_title, R.string.voice_model_delete_message,
            { VoiceEngine.uninstall(it, VoiceModels.SenseVoiceTuned) }
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

    private lateinit var tuned: Preference

    private lateinit var recordings: VoiceSamplesPreference

    private lateinit var diagnostics: VoiceDiagnosticsPreference

    /** Android's file dialog; the diagnostics go into the document it creates. */
    private val pickDiagnosticsTarget =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            val prefs = AppPrefs.getInstance()
            // what dictation depends on, so that the events can be read without asking
            val settings = arrayOf<Pair<String, Any?>>(
                "refine" to VoiceRefiner.isActive(ctx),
                "model" to VoiceEngine.modelId,
                "keep_recordings" to prefs.voiceInput.keepRecordings.getValue(),
                "long_press_ms" to prefs.keyboard.longPressDelay.getValue(),
                "space_swipe" to prefs.keyboard.spaceSwipeMoveCursor.getValue(),
                "vivo_workaround" to prefs.advanced.vivoKeypressWorkaround.getValue()
            )
            FcitxApplication.getInstance().coroutineScope.launch {
                VoiceDiagnostics.export(ctx, uri, *settings)
            }
        }

    /** Android's file dialog; the recordings go into the document it creates. */
    private val pickExportTarget =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            // not tied to this screen: it must finish even if the user leaves
            FcitxApplication.getInstance().coroutineScope.launch { VoiceSamples.export(ctx, uri) }
        }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(requireContext(), bytes)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        refine = screen.findPreference(AppPrefs.getInstance().voiceInput.voiceRefine.key)!!
        screen.removePreference(refine)
        tuned = screen.findPreference(AppPrefs.getInstance().voiceInput.voiceTuned.key)!!
        screen.removePreference(tuned)
        val keep = screen.findPreference<TwoStatePreference>(
            AppPrefs.getInstance().voiceInput.keepRecordings.key
        )!!
        screen.removePreference(keep)
        // turning it on asks first and says what is kept; turning it off needs no question
        keep.setOnPreferenceChangeListener { _, on ->
            if (on == false) return@setOnPreferenceChangeListener true
            AlertDialog.Builder(ctx)
                .setTitle(R.string.voice_samples_keep_title)
                .setMessage(R.string.voice_samples_keep_message)
                .setPositiveButton(R.string.voice_samples_keep_confirm) { _, _ -> keep.isChecked = true }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            false
        }
        val keepDiagnostics = screen.findPreference<TwoStatePreference>(
            AppPrefs.getInstance().voiceInput.keepDiagnostics.key
        )!!
        screen.removePreference(keepDiagnostics)
        diagnostics = VoiceDiagnosticsPreference(ctx).apply {
            setTitle(R.string.voice_samples_kept)
            setSummary(R.string.voice_diagnostics_kept_summary)
            onExport = {
                val time = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                pickDiagnosticsTarget.launch(getString(R.string.voice_diagnostics_export_name, time))
            }
            onDelete = {
                val app = ctx.applicationContext
                FcitxApplication.getInstance().coroutineScope.launch { VoiceDiagnostics.delete(app) }
            }
        }
        recordings = VoiceSamplesPreference(ctx).apply {
            setTitle(R.string.voice_samples_kept)
            setSummary(R.string.voice_samples_kept_summary)
            onExport = {
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                pickExportTarget.launch(getString(R.string.voice_samples_export_name, day))
            }
            onDelete = ::confirmDeleteRecordings
        }

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
            // each still carries its position in the list it was created in
            tuned.order = 0
            refine.order = 1
            addPreference(tuned)
            addPreference(refine)
        }
        screen.addCategory(R.string.voice_samples) {
            isIconSpaceReserved = false
            // the switch first; it still carries its position in the list it was created in
            keep.order = 0
            recordings.order = 1
            addPreference(keep)
            addPreference(recordings)
        }
        screen.addCategory(R.string.voice_diagnostics) {
            isIconSpaceReserved = false
            keepDiagnostics.order = 0
            diagnostics.order = 1
            addPreference(keepDiagnostics)
            addPreference(diagnostics)
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
                    if (entry.model === VoiceModels.SenseVoiceTuned) tuned.isEnabled = it == State.Installed
                }
            }
        }
        VoiceSamples.refresh(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            VoiceSamples.status.collect { recordings.status = it }
        }
        VoiceDiagnostics.refresh(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            VoiceDiagnostics.status.collect { diagnostics.status = it }
        }
    }

    private fun confirmDeleteRecordings() {
        val ctx = requireContext().applicationContext
        val status = VoiceSamples.status.value
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_samples_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.voice_samples_delete_message, status.recordings,
                    status.recordings, size(status.bytes)
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                FcitxApplication.getInstance().coroutineScope.launch { VoiceSamples.delete(ctx) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /** What deleting a recognition model does depends on whether the other one is there. */
    private fun deleteMessage(context: Context, entry: Entry): Int {
        fun installed(model: VoiceModel) = VoiceModels.isInstalled(context, model)
        return when (entry.model) {
            VoiceModels.SenseVoice ->
                if (installed(VoiceModels.SenseVoiceTuned)) R.string.voice_model_standard_delete_message_tuned
                else entry.deleteMessage
            VoiceModels.SenseVoiceTuned ->
                if (installed(VoiceModels.SenseVoice)) entry.deleteMessage
                else R.string.voice_model_standard_delete_message
            else -> entry.deleteMessage
        }
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
            .setMessage(getString(deleteMessage(ctx, entry), size(onDevice)))
            .setPositiveButton(R.string.delete) { _, _ ->
                // not tied to this screen: it must finish even if the user leaves
                FcitxApplication.getInstance().coroutineScope.launch { entry.uninstall(ctx) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
