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
5. **License hygiene.** The project is LGPL-2.1-or-later, like upstream. Keep upstream copyright
   headers; when you add a dependency, model or asset, check that its license allows
   redistribution, then record it in `NOTICE.md` and `app/licenses/libraries/`. When you change
   an upstream file that is not yet listed in `docs/ARCHITECTURE.md`, add it there.

## Repository map

| Path | What |
|---|---|
| `app/` | The application (Kotlin). Upstream code plus our `input/voice/` package |
| `app/src/main/java/.../input/voice/` | **All voice input code** — start here |
| `app/src/debug/` | Debug-only test hooks (`TestInputActivity`) |
| `app/src/test/` | JVM unit tests |
| `lib/`, `plugin/`, `codegen/`, `build-logic/` | Upstream native libraries, plugins and build logic; rarely touched |
| `voice/` | Git-ignored: `libs/sherpa-onnx.aar` and `assets/voice/**` (models) |
| `scripts/` | Setup, build, checks, end-to-end test, UI screenshots, model benchmark |
| `docs/` | `ARCHITECTURE.md`, `TESTING.md`, `MODELS.md`, `design/` (design spec and mockup) |

## Commands

All scripts are idempotent and safe to re-run. They locate the Android SDK via `ANDROID_HOME`
(default `~/Library/Android/sdk` or `~/Android/Sdk`) and need JDK 17 or 21.

```sh
git submodule update --init --recursive   # once after cloning
./scripts/fetch-voice-assets.sh           # once: speech runtime + models (~290 MB)
./scripts/build.sh                        # debug APK for arm64-v8a -> prints the APK path
./scripts/build.sh release                # release APK (see docs/TESTING.md for signing)
./scripts/check.sh                        # unit tests + debug build + privacy check (what CI runs)
./scripts/e2e-voice.sh                    # install on a device/emulator and dictate test audio
./scripts/e2e-scenarios.sh                # behaviour rules: preview, undo, cancel, cursor moves, timeout
./scripts/ui-shots.sh                     # screenshot every UI state, light and dark
```

First build compiles the native libraries and takes 10-20 minutes; later builds are incremental.
System prerequisites (macOS: `brew install extra-cmake-modules gettext`) and the SDK components
are listed in `README.md`.

## How to verify a change

1. `./scripts/check.sh` — unit tests of the voice package, debug build, privacy check.
2. `./scripts/e2e-voice.sh` and `./scripts/e2e-scenarios.sh` with an emulator or phone attached.
   The first compares transcripts; the second checks the behaviour rules of the design
   (add a scenario there whenever you fix or add a behaviour). It feeds WAV files through the
   real recognition pipeline inside the IME and compares what lands in a text field. This is the
   test that matters for anything under `input/voice/`.

3. For anything visible: `./scripts/ui-shots.sh`, then look at the PNGs in `build/ui-shots/`
   and compare them with `docs/design/mockup.html`. UI work is design-first: change the design
   documents before the code (`docs/design/DESIGN.md`).

Do not claim a voice change works without step 2, or a UI change without step 3.
Details and troubleshooting: `docs/TESTING.md`. The full workflow is in `CONTRIBUTING.md`.

## Conventions

- Match the surrounding upstream style (Kotlin official style, splitties view DSL in upstream
  files; plain Android views are fine in `input/voice/`).
- New files carry the SPDX header used throughout the repo.
- Recognizer output post-processing must stay in `VoiceText` (pure Kotlin, unit-tested).
- Choosing or changing a model: follow `docs/MODELS.md` and record benchmark numbers there.
