# Architecture

## Overview

```
┌──────────────────────────── FcitxInputMethodService (upstream) ───────────────────────────┐
│                                                                                            │
│  InputView                                                                                 │
│   ├─ KawaiiBar (toolbar)  ── mic button ──────────────┐                                    │
│   ├─ InputWindowManager                               ▼                                    │
│   │    ├─ KeyboardWindow ── hold space ──► VoiceInputComponent  (push-to-talk overlay)     │
│   │    └─ VoiceInputWindow  (hands-free dictation, replaces the keyboard)                  │
│   └─ …                                                │                                    │
│                                                       ▼                                    │
│                                   VoiceInput.start()  ── commits finals to the editor      │
│                                                       │                                    │
│                                                 VoiceSession                               │
│                              AudioSource ─► VAD (Silero) ─► VoiceEngine.transcribe()       │
│                              (microphone)                    (SenseVoice via sherpa-onnx)  │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

Everything runs inside the IME process. There is no service and no IPC, and dictation never
touches the network (the only network code downloads a model from the settings, see
[Privacy model](#privacy-model)).

## Voice package

`app/src/main/java/org/fcitx/fcitx5/android/input/voice/`

| File | Responsibility |
|---|---|
| `VoiceEngine` | Process-wide singleton owning the sherpa-onnx `OfflineRecognizer`. Loads the model lazily from APK assets, confines all native calls to one thread, frees the model after 5 idle minutes. |
| `VoiceSession` | One dictation session. Reads audio, runs VAD, produces partial and final transcripts (see below). UI-agnostic; reports through `VoiceSession.Listener` on the main thread. |
| `AudioSource` | `MicrophoneSource` (16 kHz mono `AudioRecord`) and `WavFileSource` (debug-only test input). |
| `VoiceText` | Pure-Kotlin post-processing of recognizer output (spacing between CJK and Latin text, punctuation width, joining segments). Unit-tested. |
| `VoiceInput` | Glue: permission check, picks the audio source, guarantees a single live session, writes previews and final text into the editor, starts refinement. |
| `VoiceEdits` | Bookkeeping of what dictation wrote, so that undo and refinement only ever change text that is still exactly as dictated. Pure Kotlin, unit-tested. |
| `VoiceRefiner` | The optional large model (FireRedASR2 AED) on its own thread; loaded from the files `VoiceModels` downloaded, freed after 3 idle minutes. |
| `VoiceModels` | Catalogue of downloadable models (files pinned by size and SHA-256), what is installed, and the download: start, pause, resume, delete. State is a `StateFlow` per model. |
| `VoiceModelFetch` | Downloads one file, resumable, verified. **The only code in the app that opens a network connection.** Pure Kotlin, unit-tested against a local server. |
| `VoiceSettingsFragment`, `VoiceModelPreference` | *Settings → Voice input*: privacy statement, model list with download controls, refinement switch. |
| `VoiceRefine` | Merges the large model's words into the fast model's formatted text. Pure Kotlin, unit-tested. |
| `VoicePacing` | `PartialPacer`: when the utterance in progress is decoded again for a preview. Pure Kotlin, unit-tested. |
| `VoicePower` | Whether the device asks for less energy use (Battery Saver, thermal throttling). |
| `VoiceInputWindow` | Hands-free dictation panel: an `InputWindow` that replaces the keyboard. |
| `VoiceInputComponent` | Push-to-talk surface: an overlay covering the keyboard while the space bar is held. Also the entry point other components use (`showWindow()`, `startPushToTalk()`). |
| `WaveformView`, `VoiceStatusUi`, `VoicePillButton`, `VoicePalette` | UI building blocks; their look is specified in `docs/design/`. |
| `VoiceHints` | One-time teaching hints ("Hold to talk" on the space bar). |
| `VoicePermissionActivity` | Transparent activity that shows the microphone permission dialog (a service cannot). |

### Simulated streaming

SenseVoice is a non-streaming (whole-utterance) model, but it is fast enough to be re-run several
times per second. `VoiceSession` therefore:

1. feeds audio to the VAD in 32 ms windows;
2. while the model is still loading (cold start), keeps recording and reports `Preparing`;
   everything said meanwhile is queued and transcribed as soon as the model is ready;
3. once speech starts, re-decodes the utterance so far every ≥300 ms (less often when decoding is
   slow or the text did not change, see `PartialPacer`) and reports it as a **partial**, which `VoiceInput` shows in the editor as composing
   (underlined) text;
4. when the VAD sees enough trailing silence (0.7 s hands-free, 1.2 s push-to-talk) or the
   utterance reaches 20 s, decodes the segment once more and reports a **final**, which
   `VoiceInput` commits to the editor in place of the preview;
5. on stop, flushes the VAD so that speech in progress is not lost.

The reader coroutine never waits for decoding, so audio is not dropped on slow devices.

Why not a true streaming model: the non-streaming model is markedly more accurate, already
includes punctuation and inverse text normalization, and only one model has to be kept in memory.
See `docs/MODELS.md` for the numbers.

### Refinement (optional large model)

As installed, the app is the pipeline above. Once the user has downloaded FireRedASR2 AED
(*Settings → Voice input*) and left refinement switched on, every final goes through a second
stage:

```
final (SenseVoice text + audio) ──► inserted immediately            (VoiceEdits.insert)
        │
        └─► VoiceRefiner.transcribe(audio)      seconds later, on its own thread
                 │
                 ▼
            VoiceRefine.refine(fast, accurate)  words of the large model,
                 │                              punctuation / digits / casing of the fast one
                 ▼
            VoiceEdits.refine + applyRefinements: replaces the inserted text if, and only if,
            it is still exactly as dictated, directly before the cursor
```

The large model is roughly twice as accurate but returns bare text and takes seconds; merging
keeps its accuracy and SenseVoice's formatting (numbers in `docs/MODELS.md`). Utterances are
transcribed with 0.3 s of audio before and after what the VAD cut out: the large model in
particular mishears a clipped first syllable.

## Energy

Recognition runs on the CPU, so dictation is by far the most expensive thing the keyboard does.
What keeps it in check, and what to preserve when changing the pipeline:

| Cost | Measure | Where |
|---|---|---|
| Previews: each one decodes the whole utterance so far | at most a third of the time is spent decoding, however long the utterance; half as often during a pause (unchanged text) | `PartialPacer` |
| Large model (if installed): seconds of four cores per utterance, 1.2 GB to read on load | loaded when the first words are heard, not when a session starts; freed after 3 idle minutes | `VoiceInput.start`, `VoiceRefiner` |
| Open microphone and VAD | hands-free listening turns itself off after 10 s without speech; the session stops when the keyboard is hidden | `VoiceSession`, `VoiceInput.stopCurrent` |
| Waveform animation | about 30 fps instead of the display's refresh rate; no frames at all while the microphone is off, or while nobody speaks and the room is quiet | `WaveformView` |
| Loading the model ahead of time | only within 30 minutes after dictation was used | `VoiceInput.warmUp` |

When Battery Saver is on or the device reports severe thermal throttling (`VoicePower`), previews
come half as often, the large model is not used and nothing is loaded ahead of time. Accuracy
of the inserted text is that of the built-in model then.

Nothing runs while the keyboard is hidden: there is no service, wake lock, alarm or background
work, and both model threads sleep until the next request.

`scripts/e2e-voice.sh` prints the CPU time the keyboard process used for the run; compare it
before and after a change to the pipeline (`docs/TESTING.md`).

## Hooks in upstream files

Keep this list complete; it is what must be re-applied when merging upstream.

| Upstream file | Change |
|---|---|
| `app/build.gradle.kts` | sherpa-onnx AAR dependency, `voice/assets` as extra asset dir, `noCompress onnx`, own `applicationId` |
| `app/proguard-rules.pro` | keep `com.k2fsa.sherpa.onnx.**` |
| `app/src/main/AndroidManifest.xml` | `RECORD_AUDIO`, `INTERNET` (model download only), `usesCleartextTraffic="false"`, `VoicePermissionActivity` |
| `app/src/main/res/xml/data_extraction_rules.xml`, `full_backup_content.xml` | downloaded models are excluded from backups |
| `ui/main/MainFragment.kt`, `ui/main/settings/SettingsRoute.kt` | entry and route for *Settings → Voice input* |
| `app/src/main/res/values/strings.xml` | `voice_*` strings, `space_behavior_voice_input`, app name |
| `input/InputView.kt` | create `VoiceInputComponent`, add it to the scope and its overlay to the layout |
| `input/FcitxInputMethodService.kt` | `VoiceInput.stopCurrent()` in `onFinishInputView`; `setVoicePreview()`, `hasComposingText` |
| `input/bar/ui/IdleUi.kt`, `input/bar/KawaiiBarComponent.kt` | microphone pill in the toolbar |
| `input/keyboard/KeyAction.kt` | `SpaceHoldMoveAction`, `SpaceReleaseAction` |
| `input/keyboard/BaseKeyboard.kt` | space bar emits `SpaceHoldMoveAction` / `SpaceReleaseAction` |
| `input/keyboard/CustomGestureView.kt` | `onHoldMoveListener`: follow the finger after a long press |
| `input/keyboard/KeyView.kt`, `input/keyboard/TextKeyboard.kt` | microphone glyph and "Hold to talk" hint on the space bar; `NumbersTopRight` hint position |
| `data/theme/ThemePreset.kt`, `ThemeManager.kt`, `ThemePrefs.kt` | `VoiceLight` / `VoiceDark` themes and the default look (key caps, radius, margins, hint position) |
| `input/keyboard/SpaceLongPressBehavior.kt`, `data/prefs/AppPrefs.kt` | `VoiceInput` behavior, made the default; `VoiceInput` preference category with the `voiceRefine` switch |
| `input/keyboard/CommonKeyActionListener.kt` | route long-press / release to `VoiceInputComponent` |
| `.gitignore` | ignore `voice/` |
| `.github/` | upstream's workflows and issue templates replaced by ours |
| `app/src/main/java/.../utils/Const.kt` | repository and privacy policy URLs |
| `app/src/main/res/drawable/ic_launcher_*`, `mipmap-*/ic_launcher*` | own launcher icon |
| `app/src/main/res/values-*/strings.xml` | removed translated app names; `values-zh-rCN` has ours |
| `app/licenses/libraries/` | entries for sherpa-onnx, ONNX Runtime, SenseVoice, Silero VAD |
| `README.md` | replaced; the original is `docs/UPSTREAM_README.md` |
| `app/src/main/play/`, `app/org.fcitx.fcitx5.android.yml` | removed (upstream's store listings) |

## Assets

Upstream copies everything under `app/src/main/assets/` to the app's data directory on first run
(tracked by `descriptor.json`). Speech models must not go through that mechanism (they are large
and are read directly from the APK), so they live in a separate asset source directory, `voice/assets/`,
and are stored uncompressed.

Downloaded models live in the app's private storage, `files/voice-models/<model id>/`. A file
named `installed` is written last, so a directory that has it holds every file complete and
verified; anything else in there is an unfinished download (`*.part`) that the next attempt
continues. The directory is excluded from backups.

## Privacy model

- The network is used for one thing: `VoiceModelFetch` downloads the files of a model listed in
  `VoiceModels` when the user taps *Download* (or *Resume*) in the settings. It sends a GET for a
  fixed HTTPS URL and nothing else, and what arrives is used only if it matches the pinned size
  and SHA-256. Nothing starts a download by itself, and the dictation pipeline has no reference
  to this code.
- `scripts/check-privacy.sh` fails the build check if another source file opens a connection,
  if a network-capable permission other than `INTERNET` appears, if cleartext traffic becomes
  possible, or if a dependency that goes online is added. This replaces the guarantee the app
  had up to 0.3, when it held no `INTERNET` permission at all; `docs/PRIVACY.md` explains the
  trade to users.
- Audio is held in memory only for the utterance in progress and is never written to disk.
- Dictated text is committed to the focused editor and is not logged or stored by the voice code.
- The microphone button and push-to-talk are disabled on password fields.
