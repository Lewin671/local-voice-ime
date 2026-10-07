/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * A screen with nothing but a focused text field, so that scripts can bring up the keyboard and
 * read back what was typed/dictated with `uiautomator dump` (see `scripts/e2e-voice.sh`).
 *
 * Extras, for `scripts/e2e-scenarios.sh`: `--ez keep_recordings <bool>` sets the switch of
 * *Settings → Voice input → Keep what I dictate* without going through the settings screen;
 * `--ez keep_diagnostics <bool>` does the same for *Keep voice diagnostics*;
 * `--ez private true` marks the field as one whose content is not to be remembered.
 *
 * While it is open, the broadcast `<package>.RESTART_INPUT` makes it restart input in the field,
 * as apps do that rebuild their text while the keyboard is up.
 */
class TestInputActivity : Activity() {
    private lateinit var field: EditText

    private val restartInput = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            getSystemService(InputMethodManager::class.java).restartInput(field)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(restartInput)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(KEEP_RECORDINGS)) {
            AppPrefs.getInstance().voiceInput.keepRecordings
                .setValue(intent.getBooleanExtra(KEEP_RECORDINGS, false))
        }
        if (intent.hasExtra(KEEP_DIAGNOSTICS)) {
            AppPrefs.getInstance().voiceInput.keepDiagnostics
                .setValue(intent.getBooleanExtra(KEEP_DIAGNOSTICS, false))
        }
        field = EditText(this).apply {
            contentDescription = "test-input"
            gravity = Gravity.TOP
            fitsSystemWindows = true
            if (intent.getBooleanExtra(PRIVATE, false)) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }
        setContentView(field, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        field.requestFocus()
        ContextCompat.registerReceiver(
            this, restartInput, IntentFilter("$packageName.RESTART_INPUT"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private companion object {
        const val KEEP_RECORDINGS = "keep_recordings"
        const val KEEP_DIAGNOSTICS = "keep_diagnostics"
        const val PRIVATE = "private"
    }
}
