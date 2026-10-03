/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText

/**
 * A screen with nothing but a focused text field, so that scripts can bring up the keyboard and
 * read back what was typed/dictated with `uiautomator dump` (see `scripts/e2e-voice.sh`).
 */
class TestInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            contentDescription = "test-input"
            gravity = Gravity.TOP
            fitsSystemWindows = true
        }
        setContentView(field, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        field.requestFocus()
    }
}
