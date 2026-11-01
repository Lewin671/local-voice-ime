/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

/**
 * Commits text sent from a computer into the focused text field:
 *
 * ```
 * adb shell am broadcast -a io.github.lewin671.localvoiceime.COMMIT_TEXT --es base64 "$(printf '你好' | base64)"
 * ```
 *
 * Only a sender holding `android.permission.DUMP` gets through, which adb's shell does and no
 * app installed by the user can; and only while *Settings → Advanced → Accept text from adb*
 * is on (off by default). The text is never logged.
 */
class AdbTextReceiver(private val service: FcitxInputMethodService) : BroadcastReceiver() {

    private val enabled = AppPrefs.getInstance().advanced.acceptAdbText

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action(context)) return
        if (!enabled.getValue()) {
            reply(RESULT_DISABLED, "disabled: turn on Settings > Advanced > Accept text from adb")
            return
        }
        val parsed = AdbText.parse(
            intent.getStringExtra(AdbText.EXTRA_TEXT),
            intent.getStringExtra(AdbText.EXTRA_BASE64)
        )
        val text = when (parsed) {
            is AdbText.Parsed.Text -> parsed.text
            is AdbText.Parsed.Invalid -> {
                reply(RESULT_INVALID, parsed.reason)
                return
            }
        }
        if (service.currentInputConnection == null) {
            reply(RESULT_NO_FIELD, "no text field has the keyboard")
            return
        }
        // drop unfinished pinyin composition, so that it doesn't come back after the text
        service.postFcitxJob { reset() }
        service.commitText(text)
        Timber.d("Committed ${text.length} characters from adb")
        reply(RESULT_COMMITTED, "committed ${text.codePointCount(0, text.length)} characters")
    }

    private fun reply(code: Int, data: String) {
        // `am broadcast` waits for the result; other senders may not
        if (!isOrderedBroadcast) return
        resultCode = code
        resultData = data
    }

    fun register() {
        ContextCompat.registerReceiver(
            service,
            this,
            IntentFilter(action(service)),
            PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregister() {
        service.unregisterReceiver(this)
    }

    companion object {
        const val PERMISSION = "android.permission.DUMP"

        // like Activity.RESULT_OK; `am broadcast` reports 0 when no keyboard of this app received it
        const val RESULT_COMMITTED = -1
        const val RESULT_DISABLED = 1
        const val RESULT_INVALID = 2
        const val RESULT_NO_FIELD = 3

        /** `io.github.lewin671.localvoiceime.COMMIT_TEXT`; debug builds have their own. */
        fun action(context: Context) = "${context.packageName}.COMMIT_TEXT"
    }
}
