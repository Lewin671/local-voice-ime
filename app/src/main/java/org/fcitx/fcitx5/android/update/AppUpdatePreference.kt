/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R
import splitties.resources.styledColor

/**
 * A version of the app in the update settings, the installed one or a new one: a row laid out
 * like a speech model's (`VoiceModelPreference`), showing whatever [content] says.
 * See "Settings: app update" in docs/design/mockup.html.
 */
class AppUpdatePreference(context: Context) : Preference(context) {

    class Action(@StringRes val label: Int, val run: () -> Unit)

    class Content(
        /** Size and origin; `null` for the installed version. */
        val source: String? = null,
        /** 0 to 1, or [BUSY] for work without a measure; `null` for no bar. */
        val progress: Float? = null,
        val status: String = "",
        val error: Boolean = false,
        val primary: Action? = null,
        val secondary: Action? = null
    )

    var content = Content()
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.voice_model_preference
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val source = holder.findViewById(R.id.voice_model_source) as TextView
        val notes = holder.findViewById(android.R.id.summary) as TextView
        val progress = holder.findViewById(R.id.voice_model_progress) as ProgressBar
        val status = holder.findViewById(R.id.voice_model_status) as TextView
        val primary = holder.findViewById(R.id.voice_model_primary) as Button
        val secondary = holder.findViewById(R.id.voice_model_secondary) as Button

        fun Button.show(action: Action?) {
            visibility = if (action == null) View.GONE else View.VISIBLE
            if (action != null) setText(action.label)
            setOnClickListener { action?.run?.invoke() }
        }

        val content = content
        source.visibility = if (content.source == null) View.GONE else View.VISIBLE
        source.text = content.source
        // release notes: the first lines here, all of them on the release's page
        notes.maxLines = NOTES_LINES
        notes.ellipsize = TextUtils.TruncateAt.END
        progress.visibility = if (content.progress == null) View.GONE else View.VISIBLE
        progress.isIndeterminate = content.progress == BUSY
        if (content.progress != null && content.progress != BUSY) {
            progress.progress = (content.progress * progress.max).toInt()
        }
        status.text = content.status
        status.setTextColor(
            if (content.error) context.styledColor(androidx.appcompat.R.attr.colorError)
            else notes.currentTextColor
        )
        primary.show(content.primary)
        secondary.show(content.secondary)
    }

    companion object {
        const val BUSY = -1f
        private const val NOTES_LINES = 4
    }
}
