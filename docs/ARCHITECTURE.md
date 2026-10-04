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
| `VoiceEngine` | Process-wide singleton owning the sherpa-onnx `OfflineRecognizer`. Loads the model lazily from the files `VoiceModels` downloaded, confines all native calls to one thread, frees the model after 5 idle minutes. |
| `VoiceSession` | One dictation session. Reads audio, runs VAD, produces partial and final transcripts (see below). UI-agnostic; reports through `VoiceSession.Listener` on the main thread. |
| `AudioSource` | `MicrophoneSource` (16 kHz mono `AudioRecord`) and `WavFileSource` (debug-only test input). |
| `VoiceCapture` | Lossless reader loop and one-time source teardown before queued recognition finishes. Preserves the last completed read when stopping. Pure Kotlin, unit-tested. |
| `VoiceModelLoad` | Worker-side demand check for queued model loads. Withdrawn speculation never suppresses independently required recognition. Pure Kotlin, unit-tested. |
| `VoiceText` | Pure-Kotlin post-processing of recognizer output (spacing between CJK and Latin text, punctuation width, joining segments). Unit-tested. |
| `VoiceInput` | Glue: permission check, picks the audio source, guarantees a single live session, writes previews and final text into the editor, starts refinement. |
| `VoiceEdits` | Bookkeeping of what dictation wrote, so that undo and refinement only ever change text that is still exactly as dictated. Pure Kotlin, unit-tested. |
| `VoiceRefiner` | The optional large model (FireRedASR2 AED) on its own thread; loaded from the files `VoiceModels` downloaded, freed after 3 idle minutes. |
| `VoiceModels` | Catalogue of the speech models, all of them downloads (files pinned by size and SHA-256), and the entry points the rest of the app uses. |
| `VoiceModelStore` | One model on this device: its state (a `StateFlow`) and download, pause, resume, delete. Operations on the files run strictly one after the other, and only the newest one publishes state, so fast taps on a stalled connection cannot corrupt anything. Pure Kotlin, unit-tested. |
| `VoiceModelFetch` | Downloads one file, resumable, verified. **The only code in the app that opens a network connection.** Pure Kotlin, unit-tested against a local server. |
| `VoiceSettingsFragment`, `VoiceModelPreference` | *Settings → Voice input*: privacy statement, model list with download controls, refinement switch. |
| `VoiceRefine` | Merges the large model's words into the fast model's formatted text. Pure Kotlin, unit-tested. |
| `VoiceSentence` | Keeps a sentence together across a pause: holds back the full stop of an utterance and, when speech resumes within a few seconds, has both transcribed as one. Pure Kotlin, unit-tested. |
| `VoiceAudioBuffer` | Session-owned audio storage. Dropping prefixes advances an index; VAD windows reuse one array. Recognition snapshots remain independent copies. Pure Kotlin, unit-tested. |
| `VoicePacing` | `PartialPacer`: when the utterance in progress is decoded again for a preview. Pure Kotlin, unit-tested. |
| `VoiceRuntimeOptions` | Fixed ONNX worker-waiting options for background refinement, published atomically in private storage. Fallback to default CPU scheduling preserves recognition when storage is unavailable. Pure Kotlin, unit-tested. |
| `VoiceRefinementWork` | Worker-side eligibility checks before loading and decoding. Only permanently retired entries skip native work; valid text keeps the same refinement. Pure Kotlin, unit-tested. |
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
   slow, the text did not change, or the audio exceeds three seconds, see `PartialPacer`) and reports it as a **partial**, which `VoiceInput` shows in the editor as composing
   (underlined) text;
4. when the VAD sees enough trailing silence (0.7 s hands-free, 1.2 s push-to-talk) or the
   utterance reaches 20 s, decodes the segment once more and reports a **final**, which
   `VoiceInput` commits to the editor in place of the preview;
5. on stop, flushes the VAD so that speech in progress is not lost.

The reader coroutine never waits for decoding or waveform callbacks. Audio is queued losslessly;
only level notifications use a conflated channel. A stopped or exhausted source suppresses pending
previews, but its queued audio still goes through the VAD and full final recognition.

Why not a true streaming model: the non-streaming model is markedly more accurate, already
includes punctuation and inverse text normalization, and only one model has to be kept in memory.
See `docs/MODELS.md` for the numbers.

### Refinement (optional large model)

With the standard model, the app is the pipeline above. Once the user has also downloaded FireRedASR2 AED
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
| Previews: each one decodes the whole utterance so far | preview decode is followed by at least twice its cost in idle time; after 3 s of audio the minimum interval grows from 300 ms to 900 ms by 9 s; unchanged text waits twice as long; no previews once capture ends | `PartialPacer` |
| Large model (if installed): seconds of four cores per utterance, 1.2 GB to read on load | loaded when the first words are heard, not when a session starts; freed after 3 idle minutes | `VoiceInput.start`, `VoiceRefiner` |
| Native background worker spinning | FireRedASR2 workers sleep instead of spinning when waiting for work; 4 threads and model math stay unchanged; corrected text takes longer to arrive | `VoiceRuntimeOptions`, `VoiceRefiner` |
| Open microphone and VAD | hands-free listening turns itself off after 10 s without speech; the session stops when the keyboard is hidden | `VoiceSession`, `VoiceInput.stopCurrent` |
| Waveform animation | about 30 fps instead of the display's refresh rate; no frames at all while the microphone is off, or while nobody speaks and the room is quiet | `WaveformView` |
| Loading the model ahead of time | only within 30 minutes after dictation was used | `VoiceInput.warmUp` |

When Battery Saver is on or the device reports severe thermal throttling (`VoicePower`), previews
use twice the normal minimum intervals, and nothing is loaded ahead of time. The selected final
recognition models, including high-accuracy refinement, remain enabled. This preserves accuracy
but costs more than the previous policy that skipped refinement entirely.

There is no separate service, wake lock or alarm. After the keyboard is hidden, pending final
recognition and refinement can finish; both model threads then sleep until the next request or
their idle release. Cancelled release timers are removed immediately rather than retained until
their original deadline.

`scripts/e2e-voice.sh` prints the CPU time the keyboard process used for the run; compare it
before and after a change to the pipeline (`docs/TESTING.md`).

## Hooks in upstream files

Keep this list complete; it is what must be re-applied when merging upstream.

| Upstream file | Change |
|---|---|
| `app/build.gradle.kts` | sherpa-onnx AAR dependency, `voice/assets` as extra asset dir (the VAD model), `noCompress onnx`, own `applicationId` |
| `build-logic/convention/src/main/kotlin/Versions.kt` | release version codes are incremented so signed APKs upgrade previous versions |
| `app/proguard-rules.pro` | keep `com.k2fsa.sherpa.onnx.**` |
| `app/src/main/AndroidManifest.xml` | `RECORD_AUDIO`, `INTERNET` (model download only), `usesCleartextTraffic="false"`, `VoicePermissionActivity` |
| `app/src/main/res/xml/data_extraction_rules.xml`, `full_backup_content.xml` | downloaded models are excluded from backups |
| `ui/main/MainFragment.kt`, `ui/main/settings/SettingsRoute.kt`, `utils/AppUtil.kt` | entry and route for *Settings → Voice input*, and opening it from the keyboard |
| `app/src/main/res/values/strings.xml` | `voice_*` strings, `space_behavior_voice_input`, app name |
| `input/InputView.kt` | create `VoiceInputComponent`, add it to the scope and its overlay to the layout |
| `input/FcitxInputMethodService.kt` | `VoiceInput.stopCurrent()` in `onFinishInputView`; `setVoicePreview()`, `hasComposingText`; `handleReturnKey()` made public for the dictation panel |
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
(tracked by `descriptor.json`). The voice activity detection model must not go through that
mechanism (it is read directly from the APK), so it lives in a separate asset source directory,
`voice/assets/`, and is stored uncompressed. It is the only model in the APK.

The speech models are downloads: the standard one (SenseVoice Small, 240 MB), without which
voice input shows how to get it instead of listening (`VoiceInputWindow`), and the optional
large one. Downloaded models live in the app's private storage, `files/voice-models/<model id>/`. A file
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

Performance findings and measurement limits: [PERFORMANCE.md](PERFORMANCE.md).
