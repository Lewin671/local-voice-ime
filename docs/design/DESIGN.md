# Design spec

The visual source of truth is [`mockup.html`](mockup.html) (open it in a browser): every screen
state in the light and dark theme, with tokens and interaction rules. This file adds what a
drawing cannot say: how the design maps onto the code, and the rules for changing it.

**UI work is design-first.** Change `mockup.html` and this file, get the change agreed, then
implement, then verify with `scripts/ui-shots.sh` (see [Accepting an implementation](#accepting-an-implementation)).
A UI change without a matching design change is a bug in one of the two.

## Accuracy and energy

Battery Saver and thermal throttling reduce preview frequency and disable speculative model
loading. Normal previews retain their 300 ms minimum during the first three seconds, then
progressively slow to a 900 ms minimum at nine seconds; Battery Saver doubles those intervals.
This changes preview latency, not final recognition. They do not disable the high-accuracy model when its refinement switch is enabled.
The settings summary states this explicitly; final recognition keeps the same models and audio.

## Principles

1. **Text goes where text lives.** Live dictation is written into the text field as composing
   (underlined) text, never into a bubble of its own.
2. **One gesture, one meaning.** Hold space = talk, release = insert, slide up = discard.
   Microphone pill = hands-free. Nothing else starts the microphone.
3. **Privacy is visible.** While the microphone is on, a lock and "On-device" are on screen.
   Hands-free listening stops by itself after 10 s without speech. Nothing that was said is
   kept unless the user turned that on in *Settings → Voice input*. The app goes online only
   after a tap on a button that says what is fetched and from where (a speech model, in
   *Settings → Voice input*; a new version of the app, in *Settings → App update*); it never
   does so by itself.
4. **Quiet surface, one accent.** Neutral keys; the primary colour only on things that act:
   enter, the microphone, the waveform, the first candidate.

## Tokens

Colours live in `ThemePreset.VoiceLight` / `VoiceDark` (the defaults; the keyboard follows the
system day/night setting). `Theme` has a fixed set of fields, so design tokens map as follows:

| Design token | Light | Dark | `Theme` field(s) |
|---|---|---|---|
| Keyboard | `#EEF1F0` | `#121615` | `backgroundColor`, `barColor`, `keyboardColor` |
| Key | `#FFFFFF` | `#2A302F` | `keyBackgroundColor`, `clipboardEntryColor` |
| Function key | `#DCE3E1` | `#1E2423` | `altKeyBackgroundColor`, `spaceBarColor`\* |
| Text | `#1B1F1E` | `#E6EAE9` | `keyTextColor`, `candidateTextColor`, `popupTextColor` |
| Secondary text | `#5F6B68` | `#9AA6A3` | `altKeyTextColor`, `candidateCommentColor`, `candidateLabelColor` |
| Primary | `#00695C` | `#7FD8C8` | `accentKeyBackgroundColor`, `genericActiveBackgroundColor` |
| On primary | `#FFFFFF` | `#00382F` | `accentKeyTextColor`, `genericActiveForegroundColor` |
| Primary container | `#CDE8E2` | `#1F4F47` | derived in `VoicePalette` (primary at 20 % over keyboard) |
| Error / error container | `#B3261E` / `#F9DEDC` | `#F2B8B5` / `#5C1D1A` | `VoicePalette` (constant per `isDark`) |
| Key shadow | 14 % black-green | 50 % black | `keyShadowColor` |

\* with key borders on, the space bar is drawn as a normal key (`keyBackgroundColor`).

`VoicePalette` derives the voice UI's colours from whatever `Theme` is active, so dictation
also looks right with the other built-in themes, Monet, and user themes.

| Shape / size | Value | Where it is set |
|---|---|---|
| Key radius | 9 dp | `ThemePrefs.keyRadius` default |
| Key gap | 6 dp × 8 dp | `ThemePrefs.keyHorizontalMargin` 3, `keyVerticalMargin` 4 |
| Key caps | on, shadow style | `ThemePrefs.keyBorder` default `true` |
| Hints | top right, digits only | `ThemePrefs.punctuationPosition` default (`NumbersTopRight`) |
| Toolbar height | 40 dp | `KawaiiBarComponent.HEIGHT` |
| Waveform | 27 bars, 3 dp wide, 3 dp gap, 4–56 dp tall; redrawn at about 30 fps, not at the display's refresh rate (energy) | `WaveformView` |
| Surface change | 140 ms fade | `VoiceInputComponent`, `InputWindowManager` |

## Components and states

| Component | Code | States |
|---|---|---|
| Microphone pill (toolbar) | `VoicePillButton` in `IdleUi` | Speak / Refining… (high-accuracy model installed, while inserted text is being re-checked; tap = undo) / Undo (8 s after push-to-talk inserted text, counted from the end of refinement) / hidden (password field) |
| Space bar | `TextKeyboard` (label), `BaseKeyboard` (gesture) | label = microphone glyph + input method name; "Hold to talk" until push-to-talk was used 3 times (`VoiceHints`) |
| Push-to-talk surface | `VoiceInputComponent` | listening, about to cancel, finishing |
| Dictation panel | `VoiceInputWindow` | listening, finishing, paused, needs the speech model, needs permission; utility row in every state: keyboard, ， space 。 ？, ⌫, ↵ |
| Waveform | `WaveformView` | live (follows level; at rest, without animation, while nobody speaks and the room is quiet), idle (dots), cancel (flat, error colour) |
| Status row | `VoiceStatusUi` | "Getting ready. Keep talking" / "Listening" / "Recognizing…" / "Refining…" / "Microphone off" / "Off after 10 s of silence" / an error naming its cause; always with lock + "On-device" |
| Inline preview | `FcitxInputMethodService.setVoicePreview` | composing text, replaced by the final text |
| Voice input settings | `VoiceSettingsFragment` | privacy statement, model list (standard: needed for voice input; high accuracy: optional), refinement switch (disabled until the large model is installed), recordings (switch, off by default; what is kept) |
| Recordings row (settings) | `VoiceSamplesPreference`, state from `VoiceSamples` | nothing kept / N recordings and their size / storage limit reached / exporting / exported / export failed |
| Model row (settings) | `VoiceModelPreference`, state from `VoiceModels` | not on the phone / downloading / paused / failed (network, storage, verification) / installed |
| App update settings | `AppUpdateFragment`, state from `AppUpdate` | statement of when the app asks github.com, installed version (not checked / checking / nothing newer / check failed), new version if one was found; entry in the main settings list, after *Advanced* |
| New version row (settings) | `AppUpdatePreference` | not downloaded / no package for this phone / downloading / paused / failed / downloaded / installing / not installed (cause) |

Behaviour rules that are easy to get wrong:

- **A pause is not the end of a sentence.** An utterance is inserted when the speaker pauses
  (0.7 s in the panel, 1.2 s while holding space), but without its closing full stop. If speech
  resumes within 4 s, the sentence goes on: both parts are transcribed again as one and the text
  is rewritten in place, so the pause gets the punctuation the whole sentence calls for (often
  none). The full stop appears once 4 s pass without speech, or when dictation stops. Question
  and exclamation marks are not held back. A key of the panel (punctuation, space, ⌫, ↵) ends
  the sentence where it stands, without a full stop: the user is punctuating by hand. Text that
  was edited meanwhile is never rewritten; only the new words are added. Nothing is decoded
  during the pause, and a sentence is extended up to 15 s of audio (`VoiceSentence`).
- ↵ in the panel does what the keyboard's enter key does in that field: send, search, or a new
  line.
- A punctuation key or space tapped while an utterance is still underlined is inserted after
  that utterance, once it is final: text can only be written at the cursor, where the preview is.
- The preview never ends in punctuation: the model closes every intermediate result with a full
  stop, which would flicker. Punctuation appears with the final text.
- If the user moves the cursor while a preview is showing, the editor keeps the preview as
  ordinary text and dictation stops; writing more would duplicate it at the new position.
- **Refinement** (high-accuracy model installed and switched on): the fast model's text is inserted immediately; the large
  model's transcript of the same audio is merged into it (`VoiceText.refine`: its words, the fast
  model's punctuation, digits and casing) and written over the inserted text. It only ever
  replaces text that is still exactly what dictation inserted, directly before the cursor
  (followed at most by later dictated text); otherwise it is dropped without a trace.
- The preview is composing text. A final result replaces it; cancelling or stopping with nothing
  recognized removes it. It must never be left behind in the field.
- Releasing space always ends push-to-talk, wherever the finger is. Above the cancel line
  (one key height above the space bar) it discards.
- ⌫ deletes exactly one character, everywhere. Removing a whole utterance is only ever done by
  an explicit Undo (a first version made ⌫ do it in the panel; users read that as a bug).
- Haptics: tick on start, tick on insert, double tick on cancel — through `InputFeedbacks`, so the
  user's haptic settings apply.
- **No speech model yet** (a fresh install, or the standard model was deleted): the microphone
  pill, the space bar's glyph and "Hold to talk" are shown as usual, so that voice input can be
  found. Both gestures open the dictation panel, which shows the "Speech model needed" card
  instead of listening; its button opens *Settings → Voice input*. The keyboard never starts a
  download itself. When the panel becomes visible again and the model is installed, it starts
  listening.
- **Downloads**: only *Download* and *Resume* in the model row start one; *Download* asks first
  and names size and source. Nothing is fetched twice: pause, a lost connection and a killed
  process all keep what has arrived. A model is used only once every file matched its pinned
  checksum; a file that does not match is discarded and reported as such.
- **Recordings**: nothing that was dictated is kept unless *Keep what I dictate* is on, and
  turning it on asks first and says what is kept, where, and that it is not sent anywhere.
  Dictation looks and behaves the same with it on: no indicator in the keyboard, no extra
  decoding, and a failure to save never disturbs dictation. Nothing is saved in a field marked
  private. Of the text field only the dictated passage is read back, to record how it was
  corrected (`VoiceFieldText`). Saving stops at 1 GB, or when the phone has less than 500 MB
  free, and the row says so. *Export* opens Android's file dialog and writes one ZIP file
  there; *Delete* asks first and names how much is removed. Turning the switch off keeps what
  is there. The export format is described in `docs/TRAINING_DATA.md`.
- **App update**: only *Check for updates* / *Check again* asks github.com, and only *Download*
  and *Resume* fetch the package; there is no automatic check, badge, notification or reminder.
  A version that was found is remembered until it is installed or a later check finds another.
  The package must match the checksum published with its release before *Install* is offered.
  *Install* hands it to Android's installer, which asks the user and verifies that the package
  is signed like the installed app; declining leaves the row at "Downloaded and verified", a
  refusal (no storage, another signature, wrong processor) is shown with its cause. Once the
  installed version is the downloaded one (or newer), the file is deleted. Versions compare by
  their numbers (`0.10.0` is newer than `0.9.2`); anything not newer than the installed version
  is "nothing newer". Release notes are shown as plain text, four lines at most.
- Every string on screen comes from `values/strings.xml`; wording in `mockup.html` is the source.

## Accepting an implementation

```sh
./scripts/build.sh && ./scripts/ui-shots.sh      # writes PNGs to build/ui-shots/
```

`ui-shots.sh` drives a debug build on a device or emulator through every state in the mockup, in
both themes. Compare each screenshot with its drawing: what is visible, where it sits, which
element carries the accent, and the wording. Offsets under 2 dp are not defects. Attach the
screenshots to the pull request.

## Out of scope for v1

The settings app other than *Voice input* and *App update*, and the setup wizard (still upstream's; the voice
input screen uses the same list components and theme), a notification for a running download, checking for updates without being asked,
landscape and tablet layouts for the voice
surfaces (they must work, but have no dedicated design), and theming of the candidate window for
physical keyboards.
