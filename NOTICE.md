# Notices

## This is a modified version of fcitx5-android

Local Voice IME is a fork of [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android),
Copyright 2021-2026 Fcitx5 for Android Contributors, licensed under the GNU Lesser General Public
License, version 2.1 or (at your option) any later version. The full license text is in
[LICENSE](LICENSE).

Local Voice IME is distributed under the same license (LGPL-2.1-or-later). It is an independent
project: it is **not affiliated with, endorsed by, or supported by** the Fcitx project. Please do
not report problems with this fork to upstream.

The complete corresponding source code of every released APK is this repository at the tag of
the release, including its git submodules. Build instructions are in [README.md](README.md).

### Changes made to upstream (since 2026-10-02, based on upstream commit `e6199a28`)

- Added on-device voice dictation: everything under
  `app/src/main/java/org/fcitx/fcitx5/android/input/voice/`, and the hooks into upstream files
  listed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#hooks-in-upstream-files).
- Changed the application id (`io.github.lewin671.localvoiceime`), the application name and the
  launcher icon, so that the fork cannot be mistaken for, and can be installed next to, the
  original app. As a consequence, plugins built for the original app do not load in this fork.
- Added the `RECORD_AUDIO` permission, and the `INTERNET` permission, used only to download a
  speech model on the user's request ([docs/PRIVACY.md](docs/PRIVACY.md)).
- Changed the default long-press action of the space bar to voice dictation.
- Removed upstream's Google Play and F-Droid store metadata.
- Added `scripts/`, `docs/`, `AGENTS.md`; moved the upstream README to `docs/UPSTREAM_README.md`.

The exact changes, with dates, are recorded in the git history: `git log e6199a28..HEAD`.

## Third-party components added by this fork

The runtime and the voice activity detection model are downloaded by
`scripts/fetch-voice-assets.sh` and bundled into the APK. The speech recognition models
(SenseVoice Small, a fine-tuned SenseVoice Small, FireRedASR2) are not distributed with the
app: the app downloads them from ModelScope (the fine-tuned one from GitHub) when the user
asks for it. They are separate works under their own licenses.

| Component | Author | License |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 — speech inference runtime | Xiaomi Corporation / k2-fsa contributors | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) — bundled in sherpa-onnx | Microsoft Corporation | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| [SenseVoice Small](https://github.com/FunAudioLLM/SenseVoice) (model name: `SenseVoiceSmall`) — speech recognition model, **downloaded by the app on request, not bundled**; ONNX conversion from the sherpa-onnx project ([ModelScope mirror](https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue)) | FunAudioLLM, Alibaba Group | [FunASR Model Open Source License Agreement 1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) |
| [SenseVoice Small, fine-tuned](https://github.com/Lewin671/sensevoice-finetune) (model name: `SenseVoiceSmall`, fine-tuned 2026-10-08) — optional alternative to the model above, **downloaded by the app on request, not bundled**; fine-tuned and exported with [sensevoice-finetune](https://github.com/Lewin671/sensevoice-finetune) ([release `model-20261008`](https://github.com/Lewin671/sensevoice-finetune/releases/tag/model-20261008)) | FunAudioLLM, Alibaba Group; fine-tuned by the Local Voice IME contributors | [FunASR Model Open Source License Agreement 1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) |
| [FireRedASR2](https://github.com/FireRedTeam/FireRedASR2S) AED (model name: `FireRedASR2-AED`) — optional second speech recognition model, **downloaded by the app on request, not bundled**; ONNX conversion from the sherpa-onnx project ([ModelScope](https://www.modelscope.cn/models/csukuangfj/FireRedASR2-AED-onnx)) | FireRedTeam | [Apache-2.0](https://github.com/FireRedTeam/FireRedASR2S/blob/main/LICENSE) |
| [Silero VAD](https://github.com/snakers4/silero-vad) v5 — voice activity detection model | Silero Team | [MIT](https://github.com/snakers4/silero-vad/blob/master/LICENSE) |

The launcher icon uses the "keyboard voice" glyph from
[Material Icons](https://github.com/google/material-design-icons) (Apache-2.0).

Components inherited from upstream (Fcitx5, libime, fcitx5-chinese-addons, Lua, OpenCC, Boost, …)
keep their licenses; they are listed in the app under *About → Open source licenses*, together
with the components above.
