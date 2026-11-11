/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LanguageKey
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextPickerSwitchKey
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    theme: Theme,
    val chinese: Boolean,
    val more: Boolean,
    private val spaceReturn: SymbolSpaceReturn
) : BaseKeyboard(context, theme, layout(chinese, more)) {
    companion object {
        const val Name = "Symbols123"
        const val MoreName = "SymbolsMore"

        fun name(chinese: Boolean, more: Boolean) =
            (if (more) MoreName else Name) + if (chinese) "Zh" else "En"

        fun usesChinese(ime: InputMethodEntry) = ime.languageCode.startsWith("zh")

        private fun literal(text: String, width: Float) = KeyDef(
            KeyDef.Appearance.Text(text, textSize = 23f, percentWidth = width),
            // Bypass fcitx punctuation conversion, including paired/alternating quotes.
            setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(text)))
        )

        private fun layout(chinese: Boolean, more: Boolean): List<List<KeyDef>> {
            val rows = SymbolLayout.rows(chinese, more)
            val width = 0.7f / rows[2].size
            return rows.take(2).map { row -> row.map { literal(it, 0.1f) } } + listOf(
                listOf(LayoutSwitchKey(if (more) "123" else "#+=", if (more) Name else MoreName)) +
                    rows[2].map {
                        if (it == "^_^") TextPickerSwitchKey(it, PickerWindow.Key.Emoticon, width)
                        else literal(it, width)
                    } + BackspaceKey(),
                listOf(
                    KeyDef(
                        KeyDef.Appearance.Text(
                            "ABC", textSize = 16f, textStyle = Typeface.BOLD, percentWidth = 0.15f,
                            variant = KeyDef.Appearance.Variant.Alternative
                        ),
                        setOf(
                            KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(TextKeyboard.Name)),
                            KeyDef.Behavior.LongPress(KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol))
                        )
                    ),
                    LanguageKey(), SpaceKey(), ReturnKey()
                )
            )
        }
    }

    private val space: TextKeyView = findViewById(R.id.button_space)
    private val lang: ImageKeyView = findViewById(R.id.button_lang)
    private val returnKey: ImageKeyView = findViewById(R.id.button_return)
    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val langListener = ManagedPreference.OnChangeListener<Boolean> { _, visible ->
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    init {
        lang.visibility = if (showLangSwitchKey.getValue()) View.VISIBLE else View.GONE
        showLangSwitchKey.registerOnChangeListener(langListener)
        space.setLeadingIcon(R.drawable.ic_baseline_keyboard_voice_24, theme.altKeyTextColor)
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        if (action is KeyAction.CommitAction) spaceReturn.onSymbol()
        if (action is KeyAction.SymAction && action.sym.sym == FcitxKeyMapping.FcitxKey_space) {
            val returnToLetters = spaceReturn.onSpace()
            super.onAction(KeyAction.CommitAction(" "), source)
            if (returnToLetters) {
                super.onAction(KeyAction.LayoutSwitchAction(TextKeyboard.Name), source)
            }
            return
        }
        super.onAction(action, source)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space.mainText.text = if (VoiceHints.shouldShowHoldToTalk(context)) {
            context.getString(R.string.voice_hold_to_talk)
        } else buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        returnKey.img.imageResource = returnDrawable
    }
}
