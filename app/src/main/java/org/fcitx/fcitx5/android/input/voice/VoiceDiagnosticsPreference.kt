/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.VoiceDiagnostics.Export
import splitties.resources.styledColor

/**
 * The row of the voice diagnostics in the voice input settings: how much is kept, and the two
 * things that can be done with it. Looks like the row of the recordings (the same layout); see
 * "Diagnostics" in docs/design/mockup.html.
 */
class VoiceDiagnosticsPreference(context: Context) : Preference(context) {

    var status = VoiceDiagnostics.Status()
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var onExport: () -> Unit = {}
    var onDelete: () -> Unit = {}

    init {
        layoutResource = R.layout.voice_model_preference
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val source = holder.findViewById(R.id.voice_model_source) as TextView
        val progress = holder.findViewById(R.id.voice_model_progress) as ProgressBar
        val text = holder.findViewById(R.id.voice_model_status) as TextView
        val export = holder.findViewById(R.id.voice_model_primary) as Button
        val delete = holder.findViewById(R.id.voice_model_secondary) as Button

        val status = status
        val some = status.bytes > 0
        source.visibility = View.GONE
        progress.visibility = View.GONE
        val failed = status.export == Export.Failed
        text.setTextColor(
            if (failed) context.styledColor(androidx.appcompat.R.attr.colorError)
            else source.currentTextColor
        )
        text.text = when {
            failed -> context.getString(R.string.voice_samples_export_failed)
            status.export == Export.Done -> context.getString(R.string.voice_diagnostics_exported)
            some -> context.getString(
                R.string.voice_diagnostics_size, Formatter.formatShortFileSize(context, status.bytes)
            )
            else -> context.getString(R.string.voice_samples_none)
        }
        val actions = if (some) View.VISIBLE else View.GONE
        export.visibility = actions
        export.setText(R.string.voice_samples_export)
        export.setOnClickListener { onExport() }
        delete.visibility = actions
        delete.setText(R.string.delete)
        delete.setOnClickListener { onDelete() }
    }
}
