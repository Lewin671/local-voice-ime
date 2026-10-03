# Design spec

The visual source of truth is [`mockup.html`](mockup.html) (open it in a browser): every screen
state in the light and dark theme, with tokens and interaction rules. This file adds what a
drawing cannot say: how the design maps onto the code, and the rules for changing it.

**UI work is design-first.** Change `mockup.html` and this file, get the change agreed, then
implement, then verify with `scripts/ui-shots.sh` (see [Accepting an implementation](#accepting-an-implementation)).
A UI change without a matching design change is a bug in one of the two.

## Principles

1. **Text goes where text lives.** Live dictation is written into the text field as composing
   (underlined) text, never into a bubble of its own.
2. **One gesture, one meaning.** Hold space = talk, release = insert, slide up = discard.
   Microphone pill = hands-free. Nothing else starts the microphone.
3. **Privacy is visible.** While the microphone is on, a lock and "On-device" are on screen.
   Hands-free listening stops by itself after 10 s without speech. The app goes online only
   after a tap on a button that says what is fetched and from where (a speech model, in
   *Settings → Voice input*); it never does so by itself.
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
| Microphone pill (toolbar) | `VoicePillButton` in `IdleUi` | Speak / Refining… (high-accuracy model installed, while inserted text is being re-checked; tap = undo) / Undo (8 s after push-to-talk inserted text, counted from the end of refinement) / hidden (password field, model missing) |
| Space bar | `TextKeyboard` (label), `BaseKeyboard` (gesture) | label = microphone glyph + input method name; "Hold to talk" until push-to-talk was used 3 times (`VoiceHints`) |
| Push-to-talk surface | `VoiceInputComponent` | listening, about to cancel, finishing |
| Dictation panel | `VoiceInputWindow` | listening, finishing, paused, needs permission, unavailable |
| Waveform | `WaveformView` | live (follows level; at rest, without animation, while nobody speaks and the room is quiet), idle (dots), cancel (flat, error colour) |
| Status row | `VoiceStatusUi` | "Getting ready. Keep talking" / "Listening" / "Recognizing…" / "Refining…" / "Microphone off" / "Off after 10 s of silence" / an error naming its cause; always with lock + "On-device" |
| Inline preview | `FcitxInputMethodService.setVoicePreview` | composing text, replaced by the final text |
| Voice input settings | `VoiceSettingsFragment` | privacy statement, model list, refinement switch (disabled until the large model is installed) |
| Model row (settings) | `VoiceModelPreference`, state from `VoiceModels` | not on the phone / downloading / paused / failed (network, storage, verification) / installed |

Behaviour rules that are easy to get wrong:

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
- **Downloads**: only *Download* and *Resume* in the model row start one; *Download* asks first
  and names size and source. Nothing is fetched twice: pause, a lost connection and a killed
  process all keep what has arrived. A model is used only once every file matched its pinned
  checksum; a file that does not match is discarded and reported as such.
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

The settings app other than *Voice input*, and the setup wizard (still upstream's; the voice
input screen uses the same list components and theme), a notification for a running download,
landscape and tablet layouts for the voice
surfaces (they must work, but have no dedicated design), and theming of the candidate window for
physical keyboards.
