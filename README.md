# Local Voice IME

A privacy-first Android keyboard with **on-device voice dictation**.

Speak Mandarin, English, or a mix of both; the text appears with punctuation, and **nothing ever
leaves your phone**: the app does not even hold the Android `INTERNET` permission, so the operating
system itself guarantees that audio and text cannot be uploaded.

It is also a complete everyday keyboard: 26-key pinyin with sentence-level prediction, English
with spell check, clipboard history, symbol/emoji pickers and themes — all inherited from
[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android), which this project is a fork of.

## Features

- **Hold the space bar to talk**, release to insert the text; slide up to cancel.
- **Hands-free dictation**: tap the microphone in the toolbar and keep talking; each sentence is
  inserted when you pause.
- What you say appears in the text field as you say it.
- The microphone turns itself off after 10 seconds of silence; a lock and "On-device" are on
  screen whenever it is on.
- Automatic punctuation and number formatting ("三点" → "3点", "fifty" → "50").
- Mandarin, English and Mandarin–English code-switching (Cantonese, Japanese and Korean are
  understood by the model as well).
- Fully offline. No account, no telemetry, no cloud API, no cost.
- Dictation is disabled on password fields.

## Two builds

| | Standard | High-accuracy (`-hq`) |
|---|---|---|
| APK size | about 300 MB | about 1.5 GB (plus 1.2 GB unpacked on first use) |
| Memory while dictating | about 0.4 GB | about 1.8 GB |
| Speech model | SenseVoice Small | SenseVoice Small, then FireRedASR2 re-checks every utterance |
| Errors (see [docs/MODELS.md](docs/MODELS.md)) | baseline | about 40–70 % fewer wrong words |

In the high-accuracy build the text still appears at once. A few seconds later, words the larger
model heard differently are corrected in place, keeping punctuation and numbers as they were.
Text you have edited in the meantime is never touched. It needs a phone with plenty of memory
(12 GB recommended) and can be switched off in *Virtual Keyboard → High-accuracy refinement*.

## How it stays private

| Guarantee | How it is enforced |
|---|---|
| No network access | The manifest has no `INTERNET` permission; `scripts/check-privacy.sh` verifies every APK |
| Speech is recognized on the device | [SenseVoice Small](https://github.com/FunAudioLLM/SenseVoice) (and [FireRedASR2](https://github.com/FireRedTeam/FireRedASR2S) in the high-accuracy build) run on the CPU via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx); the models ship inside the APK |
| Audio is not stored | Samples live in memory for the utterance in progress only |

Details: [docs/PRIVACY.md](docs/PRIVACY.md).

## Install

There is no store release yet. Build the APK (below) and install it with `adb install`, then:

1. Open **Local Voice IME** and follow the setup screen to enable and select the keyboard.
2. In the app, add **Pinyin** under *Input Methods* if it is not there already.
3. The first time you use voice input, grant the microphone permission.

Requirements: Android 6.0+, arm64. The model needs about 300 MB of RAM while dictating and is
unloaded after a few idle minutes.

## Build

Prerequisites:

- JDK 17 or 21
- Android SDK with Platform 36, Build-Tools 36.1.0, NDK 28.0.13004108 and CMake 3.31.6
  (`sdkmanager "platforms;android-36" "build-tools;36.1.0" "ndk;28.0.13004108" "cmake;3.31.6"`)
- `extra-cmake-modules` and `gettext`
  (macOS: `brew install extra-cmake-modules gettext`; Debian/Ubuntu: `apt install extra-cmake-modules gettext`)

```sh
git clone --recurse-submodules https://github.com/Lewin671/local-voice-ime.git
cd local-voice-ime
./scripts/build.sh            # downloads the speech model (~290 MB) on first run, prints the APK path
# REFINER=1 ./scripts/build.sh  # the high-accuracy build (downloads another 800 MB)
./scripts/check-privacy.sh    # verifies that the APK has no network permission
adb install -r <path printed by build.sh>
```

`./scripts/build.sh release` produces an optimized build; see [docs/TESTING.md](docs/TESTING.md)
for signing.

## Roadmap

- Benchmarks on real phones (load time, real-time factor, memory); see `docs/MODELS.md`.
- User-defined corrections / hot words for names and technical terms
  (the model tends to mis-spell English jargon inside Chinese sentences).
- Enable Pinyin by default regardless of the system language.

## Documentation

- [AGENTS.md](AGENTS.md) — start here if you (or your AI coding agent) want to work on the code
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how voice input is wired into the keyboard
- [docs/MODELS.md](docs/MODELS.md) — model choice, benchmark numbers, how to evaluate another model
- [CONTRIBUTING.md](CONTRIBUTING.md) — workflow and rules for changes
- [docs/design/](docs/design/DESIGN.md) — design spec and mockup of the keyboard and dictation UI
- [docs/TESTING.md](docs/TESTING.md) — unit tests, automated end-to-end test, release builds
- [docs/PRIVACY.md](docs/PRIVACY.md) — privacy policy
- [NOTICE.md](NOTICE.md) — relationship to upstream, changes, third-party licenses
- [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md) — the original fcitx5-android README

## Credits and license

Local Voice IME is a fork of fcitx5-android. It is an independent project, not affiliated with
or endorsed by the Fcitx project. What was changed, and the licenses of everything bundled, are
listed in [NOTICE.md](NOTICE.md).

- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) and the Fcitx5 project —
  the keyboard and input method engines
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) — speech inference runtime
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) — speech recognition model
- [FireRedASR2](https://github.com/FireRedTeam/FireRedASR2S) (Apache-2.0) — the large model of the high-accuracy build
- [Silero VAD](https://github.com/snakers4/silero-vad) (MIT) — voice activity detection

Licensed under [LGPL-2.1-or-later](LICENSE), like upstream. The bundled models are distributed
under their own licenses; see the links above.
