# Testing

## Everything that needs no device

```sh
./scripts/check.sh      # unit tests of the voice package, debug build, privacy check
```

This is what CI runs. `./gradlew :app:testDebugUnitTest` runs upstream's tests as well; note
that `ThemeSerializationTest.version2` already fails on upstream's own main branch.

Voice-related tests live in `app/src/test/java/org/fcitx/fcitx5/android/input/voice/`.

## Automated end-to-end test

```sh
./scripts/build.sh          # debug APK
./scripts/e2e-voice.sh      # needs one device/emulator in `adb devices`
```

What it does, for each line of `scripts/e2e/cases.tsv` (`<wav>\t<expected text>`):

1. pushes the WAV to `/sdcard/Android/data/<package>/files/voice-test.wav`;
2. opens `TestInputActivity` (debug builds only), which is a single focused text field;
3. finds the keyboard's microphone button with `uiautomator dump --windows` and taps it;
4. waits, reads the text field back and compares it with the expected text (character error
   rate, ignoring punctuation and case; a case passes at ≤ 15 %).

Debug builds use `voice-test.wav`, when present, **instead of the microphone**
(`VoiceInput.createSource`). Everything downstream — VAD, recognition, post-processing, committing
to the editor — is the production path. The file is removed when the script exits; if a debug
build seems deaf, check that a stale `voice-test.wav` is not lying around.

WAV files must be 16 kHz, mono, 16-bit PCM. To add a case, append a line to `cases.tsv`
(or pass your own file: `scripts/e2e-voice.sh my-cases.tsv`).
On macOS, `say -o x.wav --data-format=LEI16@16000 "text"` generates suitable audio.

## Behaviour scenarios

```sh
./scripts/e2e-scenarios.sh
```

Checks the rules of `docs/design/DESIGN.md` on a device: the live preview appears and carries no
trailing punctuation, releasing inserts the punctuated text, the pill offers Undo and Undo
removes it, sliding up cancels cleanly, backspace in the panel deletes exactly one character,
moving the cursor mid-utterance does not duplicate text, silence turns the microphone off with
the reason shown, and the "Hold to talk" hint goes away after three uses.

It reinstalls the debug build from scratch (its data is reset) and needs a microphone that
delivers silence for the timeout scenario, e.g. an emulator started with `-no-audio`.
When you fix a behaviour bug or add a rule, add a scenario for it.

## UI screenshots

```sh
./scripts/ui-shots.sh   # PNGs of every state in docs/design/mockup.html, in build/ui-shots/
```

Compare them with the mockup as described in `docs/design/DESIGN.md`.

### Manual checks worth doing on a real phone

- Hold the space bar, speak, release: text is inserted; slide up before releasing: nothing is.
- Microphone button → hands-free window; pause between sentences; tap the button to stop.
- Start speaking immediately after a cold start (model not loaded yet): the beginning of the
  sentence must not be lost.
- Password field: no microphone button, holding space does nothing.

Useful while debugging: `adb logcat | grep -i voice` shows model load time and the real-time
factor of every decode (debug builds).

## Release builds

```sh
./scripts/build.sh release
```

Without configuration the release APK is unsigned-and-unusable, so set up a key once:

```sh
mkdir -p ~/.config/local-voice-ime
keytool -genkeypair -keystore ~/.config/local-voice-ime/release.jks -alias release \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Local Voice IME"
cat > ~/.config/local-voice-ime/signing.env <<EOF
SIGN_KEY_FILE=$HOME/.config/local-voice-ime/release.jks
SIGN_KEY_ALIAS=release
SIGN_KEY_PWD=<the password you chose>
EOF
```

`scripts/build.sh` loads `signing.env` automatically. Keep the keystore: Android only allows
updating an installed app with an APK signed by the same key. Never commit it.

## Privacy check

```sh
./scripts/check-privacy.sh [apk]
```

Fails if the APK declares `INTERNET` or any other network-capable permission. Run it for every
APK you hand to someone.
