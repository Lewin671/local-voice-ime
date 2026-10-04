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
import org.fcitx.fcitx5.android.input.voice.VoiceSamples.Export
import splitties.resources.styledColor

/**
 * The row of the kept recordings in the voice input settings: how many, how much, and the two
 * things that can be done with them. Looks like a model's row (the same layout); see
 * "Recordings" in docs/design/mockup.html.
 */
class VoiceSamplesPreference(context: Context) : Preference(context) {

    var status = VoiceSamples.Status()
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
        val running = status.export == Export.Running
        val some = status.recordings > 0
        // the line that names a model's origin: there is none here
        source.visibility = View.GONE
        progress.visibility = if (running) View.VISIBLE else View.GONE
        progress.isIndeterminate = running
        val failed = status.export == Export.Failed || status.full
        text.setTextColor(
            if (failed) context.styledColor(androidx.appcompat.R.attr.colorError)
            else source.currentTextColor
        )
        text.text = when {
            running -> context.getString(R.string.voice_samples_exporting)
            status.export == Export.Failed -> context.getString(R.string.voice_samples_export_failed)
            status.export == Export.Done -> context.resources.getQuantityString(
                R.plurals.voice_samples_exported, status.exported, status.exported
            )
            status.full -> context.getString(
                R.string.voice_samples_full,
                Formatter.formatShortFileSize(context, VoiceSampleStore.MAX_BYTES)
            )
            some -> context.resources.getQuantityString(
                R.plurals.voice_samples_count, status.recordings, status.recordings,
                Formatter.formatShortFileSize(context, status.bytes)
            )
            else -> context.getString(R.string.voice_samples_none)
        }
        val actions = if (some && !running) View.VISIBLE else View.GONE
        export.visibility = actions
        export.setText(R.string.voice_samples_export)
        export.setOnClickListener { onExport() }
        delete.visibility = actions
        delete.setText(R.string.delete)
        delete.setOnClickListener { onDelete() }
    }
}
