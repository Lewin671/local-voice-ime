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
import org.fcitx.fcitx5.android.input.voice.VoiceModels.State
import splitties.resources.styledColor

/**
 * The row of a downloadable model in the voice input settings: what it is, where it comes from,
 * how much of it is on the device, and the one or two things that can be done about that.
 * See "Settings: voice input" in docs/design/mockup.html.
 */
class VoiceModelPreference(context: Context, private val model: VoiceModel) : Preference(context) {

    var state: State = State.Absent(0)
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var onDownload: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onDelete: () -> Unit = {}

    init {
        layoutResource = R.layout.voice_model_preference
        isSelectable = false
        isPersistent = false
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val source = holder.findViewById(R.id.voice_model_source) as TextView
        val progress = holder.findViewById(R.id.voice_model_progress) as ProgressBar
        val status = holder.findViewById(R.id.voice_model_status) as TextView
        val primary = holder.findViewById(R.id.voice_model_primary) as Button
        val secondary = holder.findViewById(R.id.voice_model_secondary) as Button

        fun Button.show(label: Int?, action: () -> Unit) {
            visibility = if (label == null) View.GONE else View.VISIBLE
            if (label != null) setText(label)
            setOnClickListener { action() }
        }

        source.text = context.getString(
            R.string.voice_model_source, model.name, size(model.size), model.host.removePrefix("www.")
        )
        val state = state
        val downloaded = when (state) {
            is State.Absent -> state.downloaded
            is State.Downloading -> state.downloaded
            State.Installed -> model.size
        }
        val partial = context.getString(R.string.voice_model_progress, size(downloaded), size(model.size))
        progress.visibility = if (state is State.Downloading) View.VISIBLE else View.GONE
        progress.progress = (downloaded * progress.max / model.size).toInt()
        status.setTextColor(
            if (state is State.Absent && state.error != null) context.styledColor(androidx.appcompat.R.attr.colorError)
            else source.currentTextColor
        )
        when (state) {
            is State.Absent -> {
                status.text = when (state.error) {
                    VoiceModels.Error.Network -> context.getString(R.string.voice_model_error_network)
                    VoiceModels.Error.Storage ->
                        context.getString(R.string.voice_model_error_storage, size(model.size - downloaded))
                    VoiceModels.Error.Content -> context.getString(R.string.voice_model_error_content)
                    null -> if (downloaded > 0) context.getString(R.string.voice_model_paused, partial) else ""
                }
                primary.show(
                    if (downloaded > 0) R.string.voice_model_resume else R.string.voice_model_download,
                    onDownload
                )
                secondary.show(if (downloaded > 0) R.string.delete else null, onDelete)
            }
            is State.Downloading -> {
                status.text = partial
                primary.show(null) {}
                secondary.show(R.string.voice_model_pause, onPause)
            }
            State.Installed -> {
                status.setText(R.string.voice_model_installed)
                primary.show(null) {}
                secondary.show(R.string.delete, onDelete)
            }
        }
    }
}
