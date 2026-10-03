/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService

/**
 * Whether the device asks apps to use less energy: Battery Saver is on, or the device is hot
 * enough for the system to throttle it.
 *
 * Dictation then does only what is needed to get the words into the text field: previews come
 * half as often, the large model of the high-accuracy build stays unloaded, and the speech model
 * is not loaded ahead of time.
 */
object VoicePower {

    fun isSaving(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        if (power.isPowerSaveMode) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
