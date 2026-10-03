/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context

/**
 * One-time teaching hints. Until push-to-talk has been used a few times, the space bar says
 * "Hold to talk" instead of the name of the input method.
 */
object VoiceHints {

    private const val PREFS = "voice_hints"
    private const val KEY_PUSH_TO_TALK_USES = "push_to_talk_uses"
    private const val USES_UNTIL_LEARNED = 3

    // preferences are unavailable before the first unlock after boot; then just show the hint
    private fun prefs(context: Context) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    fun shouldShowHoldToTalk(context: Context): Boolean {
        if (!VoiceEngine.isAvailable(context)) return false
        val uses = runCatching { prefs(context)?.getInt(KEY_PUSH_TO_TALK_USES, 0) }.getOrNull()
        return (uses ?: 0) < USES_UNTIL_LEARNED
    }

    fun onPushToTalkUsed(context: Context) {
        runCatching {
            val p = prefs(context) ?: return
            val uses = p.getInt(KEY_PUSH_TO_TALK_USES, 0)
            if (uses < USES_UNTIL_LEARNED) p.edit().putInt(KEY_PUSH_TO_TALK_USES, uses + 1).apply()
        }
    }
}
