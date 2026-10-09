/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URL

/**
 * A speech model. None is part of the APK: the user downloads them from the settings
 * (`VoiceSettingsFragment`). Every file is pinned by size and SHA-256.
 */
class VoiceModel(
    /** Name of its directory in the app's private storage; never reuse one for other content. */
    val id: String,
    /** Shown to the user; a model name is not translated. */
    val name: String,
    /** Shown to the user as the place the download comes from. */
    val host: String,
    private val baseUrl: String,
    val files: List<File>
) {
    class File(val name: String, val size: Long, val sha256: String)

    val size = files.sumOf { it.size }

    fun url(file: File) = URL("https://$host$baseUrl${file.name}")
}

/**
 * The catalogue of downloadable models and what is installed of it. To offer another model, add
 * it to [all] (after the steps in `docs/MODELS.md`); downloading, resuming, verifying and
 * deleting come with it ([VoiceModelStore]), as does the row in the settings.
 */
object VoiceModels {

    /**
     * SenseVoice Small, int8: the model that turns speech into text ([VoiceEngine]); without it
     * there is no voice input. The files are the ones in sherpa-onnx's release archive
     * `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` (same SHA-256), as mirrored on
     * ModelScope.
     */
    val SenseVoice = VoiceModel(
        id = "sense-voice-small-int8",
        name = "SenseVoice Small",
        host = "www.modelscope.cn",
        baseUrl = "/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/master/",
        files = listOf(
            VoiceModel.File(
                "model.int8.onnx", 239233841,
                "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
            ),
            VoiceModel.File(
                "tokens.txt", 315894,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
            )
        )
    )

    /**
     * FireRedASR2 AED, int8: the large model that re-checks every utterance ([VoiceRefiner]).
     * The files are the ones in sherpa-onnx's release archive
     * `sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26`, published file by file on ModelScope by
     * the same maintainer.
     */
    val FireRedAsr2 = VoiceModel(
        id = "fire-red-asr2-aed-int8",
        name = "FireRedASR2",
        host = "www.modelscope.cn",
        baseUrl = "/models/csukuangfj/FireRedASR2-AED-onnx/resolve/master/aed/",
        files = listOf(
            VoiceModel.File(
                "encoder.int8.onnx", 817286833,
                "54048d66b6e8f3c80ea7ce95efe794587b0fd81d7271651d0decd3803852ae82"
            ),
            VoiceModel.File(
                "decoder.int8.onnx", 417291928,
                "b840ce7196ae4a14d05ae84bbf56082b6b61ccec5610fda907dddbcea37354ff"
            ),
            VoiceModel.File(
                "tokens.txt", 79172,
                "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b"
            )
        )
    )

    /**
     * SenseVoice Small, fine-tuned on one speaker's dictation and exported in the same format:
     * an alternative to [SenseVoice] ([recognition]). The files are those of the release
     * `model-20261009` of https://github.com/Lewin671/sensevoice-finetune, whose README says
     * how it was made and measured; `tokens.txt` is that of [SenseVoice].
     */
    val SenseVoiceTuned = VoiceModel(
        id = "sense-voice-small-tuned-20261009-int8",
        name = "SenseVoice Small, fine-tuned",
        host = "github.com",
        baseUrl = "/Lewin671/sensevoice-finetune/releases/download/model-20261009/",
        files = listOf(
            VoiceModel.File(
                "model.int8.onnx", 239234129,
                "7e698cb387aee6cbf656762afe2c5bd9984fdeb3b8057db8c7c95151db90f047"
            ),
            VoiceModel.File(
                "tokens.txt", 315894,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
            )
        )
    )

    val all = listOf(SenseVoice, SenseVoiceTuned, FireRedAsr2)

    /**
     * The model that turns speech into text: the fine-tuned one if it is installed and wanted,
     * or if it is all there is; else the standard one; null if neither is installed.
     */
    fun recognition(standardInstalled: Boolean, tunedInstalled: Boolean, preferTuned: Boolean) = when {
        tunedInstalled && (preferTuned || !standardInstalled) -> SenseVoiceTuned
        standardInstalled -> SenseVoice
        else -> null
    }

    enum class Error { Network, Storage, Content }

    sealed interface State {
        /** Not usable. [downloaded] bytes of it are on the device and will not be fetched again. */
        data class Absent(val downloaded: Long, val error: Error? = null) : State
        data class Downloading(val downloaded: Long) : State
        data object Installed : State
    }

    private val stores = HashMap<String, VoiceModelStore>()

    fun dir(context: Context, model: VoiceModel) =
        File(context.applicationContext.filesDir, "voice-models/${model.id}")

    private fun store(context: Context, model: VoiceModel) = synchronized(stores) {
        stores.getOrPut(model.id) {
            // left behind by the builds that carried the large model inside the APK
            File(context.applicationContext.filesDir, "voice-refiner").deleteRecursively()
            // the fine-tuned model 0.9.0 offered, replaced by [SenseVoiceTuned]
            File(context.applicationContext.filesDir, "voice-models/sense-voice-small-tuned-20261008-int8")
                .deleteRecursively()
            VoiceModelStore(model, dir(context, model))
        }
    }

    fun state(context: Context, model: VoiceModel): StateFlow<State> = store(context, model).state

    fun isInstalled(context: Context, model: VoiceModel) =
        store(context, model).state.value == State.Installed

    /**
     * Start or continue downloading [model]. Runs until it is done, fails, or [pause] is called,
     * whether or not the settings are still open; a later call continues where this one stopped.
     */
    fun download(context: Context, model: VoiceModel) = store(context, model).download()

    /** Stop downloading and keep what has arrived. */
    fun pause(context: Context, model: VoiceModel) = store(context, model).pause()

    /** Remove [model] from the device, whether it is installed or partly downloaded. */
    suspend fun delete(context: Context, model: VoiceModel) = store(context, model).delete()
}
