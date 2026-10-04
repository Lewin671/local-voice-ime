/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * A screen with nothing but a focused text field, so that scripts can bring up the keyboard and
 * read back what was typed/dictated with `uiautomator dump` (see `scripts/e2e-voice.sh`).
 *
 * Extras, for `scripts/e2e-scenarios.sh`: `--ez keep_recordings <bool>` sets the switch of
 * *Settings → Voice input → Keep what I dictate* without going through the settings screen;
 * `--ez private true` marks the field as one whose content is not to be remembered.
 */
class TestInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(KEEP_RECORDINGS)) {
            AppPrefs.getInstance().voiceInput.keepRecordings
                .setValue(intent.getBooleanExtra(KEEP_RECORDINGS, false))
        }
        val field = EditText(this).apply {
            contentDescription = "test-input"
            gravity = Gravity.TOP
            fitsSystemWindows = true
            if (intent.getBooleanExtra(PRIVATE, false)) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }
        setContentView(field, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        field.requestFocus()
    }

    private companion object {
        const val KEEP_RECORDINGS = "keep_recordings"
        const val PRIVATE = "private"
    }
}
