# Agent Guide

Read this before working in this repository. It is written for AI coding agents and applies to
humans just as well. `CLAUDE.md` is a symlink to this file.

## What this project is

**Local Voice IME** is a privacy-first Android keyboard: a full pinyin/English keyboard with
**on-device voice dictation** (Mandarin, English, and code-switching between the two).

- It is a fork of [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) (LGPL-2.1+).
  Upstream provides the keyboard, pinyin engine (libime), clipboard, themes, settings.
- This fork adds speech recognition with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
  running the SenseVoice Small model on the phone's CPU.

## Non-negotiable rules

1. **No network access, ever.** The app must not declare `android.permission.INTERNET`, and no
   dependency may add it. `scripts/check-privacy.sh` enforces this on the built APK; run it
   after touching the manifest or dependencies. Models ship inside the APK; nothing is downloaded
   at runtime.
2. **Everything in the repo is written in English**: code, comments, docs, commit messages, UI
   source strings (`values/strings.xml`). Translations go in `values-*/`.
3. **Keep the diff against upstream small.** New functionality goes into new files under
   `app/src/main/java/org/fcitx/fcitx5/android/input/voice/`; changes to upstream files should be
   minimal hooks. This keeps merging upstream releases cheap. `docs/ARCHITECTURE.md` lists every
   upstream file we touch.
4. **Don't commit large binaries.** The speech runtime and models live in `voice/` (git-ignored)
   and are fetched by `scripts/fetch-voice-assets.sh` with pinned checksums.

## Repository map

| Path | What |
|---|---|
| `app/` | The application (Kotlin). Upstream code plus our `input/voice/` package |
| `app/src/main/java/.../input/voice/` | **All voice input code** — start here |
| `app/src/debug/` | Debug-only test hooks (`TestInputActivity`) |
| `app/src/test/` | JVM unit tests |
| `lib/`, `plugin/`, `codegen/`, `build-logic/` | Upstream native libraries, plugins and build logic; rarely touched |
| `voice/` | Git-ignored: `libs/sherpa-onnx.aar` and `assets/voice/**` (models) |
| `scripts/` | Setup, build, privacy check, end-to-end test, model benchmark |
| `docs/` | `ARCHITECTURE.md`, `TESTING.md`, `MODELS.md` |

## Commands

All scripts are idempotent and safe to re-run. They locate the Android SDK via `ANDROID_HOME`
(default `~/Library/Android/sdk` or `~/Android/Sdk`) and need JDK 17 or 21.

```sh
git submodule update --init --recursive   # once after cloning
./scripts/fetch-voice-assets.sh           # once: speech runtime + models (~290 MB)
./scripts/build.sh                        # debug APK for arm64-v8a -> prints the APK path
./scripts/build.sh release                # release APK (see docs/TESTING.md for signing)
./gradlew :app:testDebugUnitTest          # unit tests
./scripts/check-privacy.sh                # assert the APK has no network permission
./scripts/e2e-voice.sh                    # install on a device/emulator and dictate test audio
```

First build compiles the native libraries and takes 10-20 minutes; later builds are incremental.
System prerequisites (macOS: `brew install extra-cmake-modules gettext`) and the SDK components
are listed in `README.md`.

## How to verify a change

1. `./gradlew :app:testDebugUnitTest` — pure logic (text post-processing).
2. `./scripts/build.sh && ./scripts/check-privacy.sh`.
3. `./scripts/e2e-voice.sh` with an emulator or phone attached. It feeds WAV files through the
   real recognition pipeline inside the IME and compares what lands in a text field. This is the
   test that matters for anything under `input/voice/`.

Do not claim a voice change works without step 3. Details and troubleshooting: `docs/TESTING.md`.

## Conventions

- Match the surrounding upstream style (Kotlin official style, splitties view DSL in upstream
  files; plain Android views are fine in `input/voice/`).
- New files carry the SPDX header used throughout the repo.
- Recognizer output post-processing must stay in `VoiceText` (pure Kotlin, unit-tested).
- Choosing or changing a model: follow `docs/MODELS.md` and record benchmark numbers there.
