/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.fcitx.fcitx5.android.R

/**
 * An input method service cannot show the runtime permission dialog by itself, so this
 * transparent activity does it on behalf of the keyboard.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceInput.hasPermission(this)) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (!VoiceInput.hasPermission(this)) {
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
