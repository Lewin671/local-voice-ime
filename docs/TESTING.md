# Testing

## Where to test what

| What | Where | How long |
|---|---|---|
| Text post-processing, merging two transcripts, what may be edited in the text field, downloading a model (resume, verification), the files of kept recordings and finding a corrected passage in the field | JVM unit tests (`app/src/test/.../voice/`) | seconds |
| Which model, how accurate, how fast | desktop benchmark (`scripts/bench/`, results in `MODELS.md`) | minutes |
| That it is all wired together on Android | device scripts below | 5–10 minutes each |

## Verification policy

This section defines the verification requirements referenced by `AGENTS.md`,
`CONTRIBUTING.md`, and the PR template. Run checks after a coherent change, not after every edit.

| Change | Required checks |
|---|---|
| Documentation or comments only | Review the diff and local links; no build or device run |
| Pure Kotlin logic with no Android wiring changes | Relevant JVM regression tests and `./scripts/check.sh`; no device run required |
| Android wiring, recording, permissions, editor interaction, or speech runtime/model | `./scripts/check.sh` plus relevant device scenarios; run `e2e-voice.sh` for recognition and `e2e-scenarios.sh` for interaction behavior |
| Visible UI or gesture | Local checks plus `ui-shots.sh` and comparison with the updated design; run interaction scenarios when gestures or editor behavior change |
| Release | Local checks, `e2e-voice.sh`, `e2e-scenarios.sh`, `REFINER=1 ./scripts/e2e-scenarios.sh`, UI screenshots, and the real-phone/manual download checks below |

Report local tests, emulator checks, and real-phone checks separately. WAV-based device tests
exercise the recognition pipeline but replace the microphone; recording or microphone-permission
changes also need a real-phone recording/permission check. If required hardware is unavailable,
report the missing checks and keep device verification pending; do not claim the affected
behavior or release is fully verified. Serialize runs on the same test device.

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

At the end the script prints the CPU time the keyboard process used during the run. It is a proxy
for the energy dictation costs: when changing the recognition pipeline, run the script a few
times before and after (the first run after an install is noisy) and compare the numbers.

## Behaviour scenarios

```sh
./scripts/e2e-scenarios.sh
```

Checks the rules of `docs/design/DESIGN.md` on a device: the live preview appears and carries no
trailing punctuation, releasing inserts the punctuated text, the pill offers Undo and Undo
removes it, sliding up cancels cleanly, the microphone pill starts dictation in the toolbar
while the keyboard stays, Stop gives the toolbar back, a punctuation key pressed mid-utterance
leaves the microphone on and is written after the utterance, a letter turns the microphone off
and is typed after what was said, backspace deletes exactly one character,
moving the cursor mid-utterance does not duplicate text, an app restarting input while the space
bar is held does not end dictation or duplicate text, what dictation rewrites is not mistaken
for a cursor move, silence turns the microphone off with
the reason shown, and the "Hold to talk" hint goes away after three uses. It also checks the
recordings for fine-tuning: nothing is kept until the switch is on; then an utterance is kept as
a WAV file with its record, a correction made in the text field is recorded next to what was
dictated, Undo is recorded, and nothing is kept from a field marked private or after the switch
is turned off again. Last, the voice diagnostics: nothing is noted until the switch is on; then
a session is noted from start to end with what ended it, every value is a number or a fixed
word, and nothing is noted in a private field or after the switch is turned off.

With `REFINER=1`, a scenario that depends on the large model waits until it has transcribed
(a log line), not for a fixed time: loading it in a fresh process and transcribing one sentence
took 34 s on a warm Pixel 3. After a long utterance the script lets the model finish before the
next scenario starts.

The test field (`TestInputActivity`) runs in the keyboard's own process. There the editor's
reports of the cursor arrive before the request that caused them returns, which no other app
does: a write to the editor must tell the cursor tracker what to expect before it is requested,
not after. Up to v0.8.0 the deletion of a preview that is taken back did it afterwards, and the
"restarting input while space is held" scenario left that preview in the field a second time,
with or without the large model; its check counted a piece of the sentence the leftover did
not always contain, so it passed or failed by the length of the first preview. With the test
field in a process of its own the order was right and nothing was left behind (four runs).

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
- Microphone pill → the toolbar becomes the dictation strip, the keyboard stays; pause between
  sentences; tap a comma, go on speaking; tap *Stop*.
- While dictating hands-free, type a pinyin syllable: the microphone goes off, the words said so
  far are written, the syllable follows them and candidates show as usual.
- Start speaking immediately after a cold start (model not loaded yet): the beginning of the
  sentence must not be lost.
- Password field: no microphone button, holding space does nothing.
- *Settings → Voice input → Keep what I dictate*: turning it on asks first; dictate a few
  sentences with the microphone, correct one of them, then *Export* to a folder, open the ZIP
  on a computer and listen to a recording; *Delete* empties the row.

Useful while debugging: `adb logcat | grep -i voice` shows model load time and the real-time
factor of every decode (debug builds).

## With the large model

```sh
./scripts/build.sh                        # debug APK
REFINER=1 ./scripts/e2e-scenarios.sh      # also checks refinement in Battery Saver
```

No device script downloads a model on the device: they run `scripts/push-voice-model.sh`, which
copies the standard model from `voice/models/` (fetched once with
`scripts/fetch-voice-assets.sh --models`) into the app's private storage, exactly as a finished
download would leave it. `REFINER=1` adds the large model (`--refiner`) the same way. This needs a debug build and about 1.5 GB of
free storage on the device.

The download itself is covered on the JVM: `VoiceModelFetchTest` (a local server that drops
connections, ignores ranges, and serves wrong content) and `VoiceModelStoreTest` (pause, resume
and delete in quick succession while a download hangs). Before a release, do it once for real:
*Settings → Voice input → Download*, pause and resume it, switch to another app while it runs,
and check that the row ends at "Installed" and that dictated text is refined afterwards.

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

Fails if the app could use the network for anything but what `docs/PRIVACY.md` lists (speech
models and app updates, both on request): a
network-capable permission other than `INTERNET`, cleartext traffic allowed, network code in a
source file other than `VoiceModelFetch.kt`, or a dependency that goes online. Run it for every
APK you hand to someone.

## Native thread-pool experiment

`CPU_SPIN=0 WAVS=<16-kHz-WAV-directory> scripts/bench/device-bench.sh <key> <model-dir> <kind>`
compares CPU worker sleeping against the default spinning policy. Use `CPU_SPIN=1` for the
explicit baseline. Requires the newest debug APK installed; `VoiceBenchActivity` reports both
wall time and process CPU time for each of its two decodes. The configuration is private to
the benchmark and does not change normal dictation. Compare raw transcripts before interpreting
performance. Repeating and alternating order reduces initialization and ordering bias.
`CPU_CONFIG=<local-file>` accepts a session-options file for bounded-spinning/backoff experiments;
it is mutually exclusive with `CPU_SPIN` and affects only the benchmark, never normal dictation.

## Performance and energy regression

`scripts/bench/session-probe.sh <output-directory> [wav-directory]` runs controlled WAV files
through the production `VoiceSession`, VAD and standard recognizer in a debug-only activity.
Use a dedicated device. It installs the newest debug APK, copies the standard model, and
force-stops the debug app between clips. For paired builds compare `finals`, `sentenceStops`,
`capturedSamples` and `capturedSha256` exactly; the capture-lifetime optimization should change
`captureStoppedBeforeFinishing` from false to true while `sourceStopCalls` remains one.
Do not interpret this WAV-source lifecycle check as physical AudioRecord or energy validation.
Run `python3 scripts/bench/compare-session-probes.py <baseline-directory> <optimized-directory>`
to enforce this comparison without a CER tolerance.
For paired editor runs use `python3 scripts/bench/compare-editor-transcripts.py <baseline-log>
<optimized-log>` with logs from `e2e-voice.sh` under the same installed-model configuration.
The script's ordinary 15% error threshold alone does not establish unchanged accuracy.
`CANCEL_BEFORE_START=1` with the probe verifies the cold cancelled-session wiring: no samples,
no final text, one source stop, and no standard model loaded by the native worker.
`PREPARING_STALL_MS=3000` blocks the initial main-thread state callback and asserts that audio
reading proceeds during the stall. Compare its audio fingerprints/text as well. This uses a
WAV source; it checks scheduling, not a physical phone's recording buffer or tail audio.

`VoiceCaptureTest` covers lossless queued audio, the final partial read when stopping, empty
reads, cancellation/errors, EOF-before-release ordering and one-time cleanup. `VoiceModelLoadTest`
covers demand withdrawn while queued, independent required refinement and active-load completion.

`./scripts/bench/audio-buffer.sh` runs the old storage implementation and production buffer
against the same 10 minutes of synthetic audio, verifies matching window checksums, and reports
JVM allocations and median elapsed time. It also simulates preview pacing on a 20-second
utterance. Requires `kotlinc` and a JDK; no Android device or model is needed.

The high-accuracy scenario suite unplugs the simulated battery, enables Battery Saver, checks
that refinement still decodes and preserves the transcript, then restores the original power
mode and resets the simulated battery override. Use a dedicated test device: this suite resets the debug app.

These measurements do not measure battery consumption. See [PERFORMANCE.md](PERFORMANCE.md)
for interpretation, remaining bottlenecks, and the real-phone measurement procedure.

## System installer regression

On a dedicated arm64 emulator (`emulator-5554`), after building debug and signed release APKs:

```sh
python3 scripts/e2e-update-install.py <debug-apk> <signed-release-apk> build/update-install
```

The script seeds the debug app with the supplied signed APK and its exact checksum as a
verified update fixture; it does not test downloading from GitHub. It exercises denied
install-source permission, retry, permission granted in Android settings, foreground
confirmation, cancellation/retry, hiding the app before confirmation and resuming it, and
actual system installation. It requires both the APK's version code and a successful
PackageInstaller callback; pressing the confirmation button alone is not a passing result.
If Play Protect requests a scan, the script requests it and approves installation only
after an explicitly safe result. It does not disable or bypass system verification.
This changes the debug app's install-source permission and installs the supplied production
APK; use a dedicated emulator, not a daily-use device. Brand-specific permission pages and
physical phones remain manual checks.

The permission preflight follows Android's
[install-source permission documentation](https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html).
Pending confirmation is consumed only by a resumed update screen, following
[foreground activity-launch restrictions](https://developer.android.com/guide/components/activities/secure-bal);
the broadcast receiver never starts a UI. Android 8+ permission APIs and Android 12+ explicit
user-action APIs are guarded for the app's Android 6 minimum.

### Vendor file installer compatibility

On Xiaomi/Redmi/Poco, Install uses the system file installer with a verified APK content URI.
Other devices retain session installation and offer Open system installer as a compatibility
retry. Both paths require install-source permission. Check denied permission, grant and return,
confirmation, cancel and retry, and a signed production-to-production upgrade. Confirm that
models and settings survive; a debug-to-production installation alone does not verify self-update.
The file provider must not allow access to voice files or grant write access. A stopped session
now displays a cancelled/stopped message instead of silently resetting the Install button.

`AppUpdateInstallerTest` verifies denied/granted permission, the APK content URI, read-only
grants, provider isolation from voice files, and cancellation/retry. Set the debug app's
REQUEST_INSTALL_PACKAGES app-op from the host before instrumentation: changing it inside
the test kills the test process. Run once with deny and instrumentation argument
`installPermission=deny`, then with allow and `installPermission=allow`; restore the app-op
afterwards. Serialize access with other device tests.
