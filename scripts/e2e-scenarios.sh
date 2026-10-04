#!/usr/bin/env bash
# Behaviour tests of the dictation UX on a connected device or emulator: the rules written down
# in docs/design/DESIGN.md that a transcript comparison (scripts/e2e-voice.sh) cannot catch.
#
#   scripts/e2e-scenarios.sh
#   REFINER=1 scripts/e2e-scenarios.sh     also installs the large model
#                                          and runs the refinement scenarios
#
# Requires a debug APK (scripts/build.sh); it is reinstalled from scratch, so app data of the
# debug build is reset. The speech models are not downloaded on the device but copied to it
# (scripts/push-voice-model.sh). The emulator/device microphone must deliver silence for the idle-timeout
# scenario (an emulator started with -no-audio does). Exit code is non-zero if a scenario fails.
set -uo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
[[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
ime=$pkg/org.fcitx.fcitx5.android.input.FcitxInputMethodService
activity=$pkg/org.fcitx.fcitx5.android.debug.TestInputActivity
remote_wav=/sdcard/Android/data/$pkg/files/voice-test.wav
work=$(mktemp -d)
# What is said in voice/test-wavs/zh.wav. The fast model sometimes hears 开饭 instead of 开放,
# depending on how the utterance was cut; the large model corrects it.
refined="开放时间早上9点至下午5点。"
fast_alt="开饭时间早上9点至下午5点。"
if [[ ${REFINER:-} == 1 ]]; then
    # with the large model: inserted text is corrected in place a few seconds later
    hq=true
    settle() { sleep 10; }
else
    hq=false
    settle() { :; }
fi
# true if $1 is the sample sentence (without the large model either reading of it)
is_sentence() { [[ $1 == "$refined" ]] || { ! $hq && [[ $1 == "$fast_alt" ]]; }; }
punctuation='。．.，,、；;：:？?！!…'

adb get-state >/dev/null || exit 1
previous_ime=$(adb shell settings get secure default_input_method | tr -d '\r')
cleanup() {
    adb shell rm -f "$remote_wav" >/dev/null 2>&1
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    if [[ -n $previous_ime && $previous_ime != null && $previous_ime != "$ime" ]]; then
        adb shell ime set "$previous_ime" >/dev/null 2>&1
    fi
    rm -rf "$work"
}
trap cleanup EXIT

echo "Installing $apk (fresh)"
adb uninstall "$pkg" >/dev/null 2>&1
adb install -g "$apk" >/dev/null || exit 1
sleep 2
./scripts/push-voice-model.sh $($hq && echo --refiner) "$pkg" || exit 1
adb shell ime enable "$ime" >/dev/null
adb shell ime set "$ime" >/dev/null
adb shell mkdir -p "$(dirname "$remote_wav")"

ui() { python3 scripts/e2e/ui.py "$@"; }
dump() {
    # the dump fails now and then while something on screen is animating (the waveform)
    for _ in 1 2 3 4; do
        adb shell rm -f /sdcard/ime-e2e-ui.xml
        adb shell uiautomator dump --windows /sdcard/ime-e2e-ui.xml >/dev/null 2>&1
        adb pull /sdcard/ime-e2e-ui.xml "$work/ui.xml" >/dev/null 2>&1 && return
    done
    echo "could not read the screen" >&2
}
field() { dump; ui text "$work/ui.xml" test-input; }
tap() { dump; adb shell input tap $(ui center "$work/ui.xml" "$1"); }
# the sample recording is shorter than some waits: the session may already have ended by itself
tap_if_present() { dump; ui center "$work/ui.xml" "$1" >/dev/null 2>&1 && adb shell input tap $(ui center "$work/ui.xml" "$1"); }
use_wav() { adb push voice/test-wavs/zh.wav "$remote_wav" >/dev/null 2>&1; }
use_microphone() { adb shell rm -f "$remote_wav"; }

# The sample said three times in a row without pauses: one 14 s utterance, long enough for states
# that only last while the large model works to be observed (reading the screen takes seconds).
use_long_wav() {
    python3 - voice/test-wavs/zh.wav "$work/long.wav" <<'P'
import sys, wave, array
src = wave.open(sys.argv[1]); a = array.array("h", src.readframes(src.getnframes()))
loud = [i for i, v in enumerate(a) if abs(v) > 600]
speech = a[max(0, loud[0] - 3200):loud[-1] + 3200]          # keep 0.2 s around the speech
out = wave.open(sys.argv[2], "w"); out.setparams(src.getparams()); out.writeframes((speech * 3).tobytes())
P
    adb push "$work/long.wav" "$remote_wav" >/dev/null 2>&1
}

# The sample with 2.5 s of silence put into its quietest spot near the middle: somebody who stops
# to think in the middle of a sentence.
use_paused_wav() {
    python3 - voice/test-wavs/zh.wav "$work/paused.wav" <<'P'
import sys, wave, array
src = wave.open(sys.argv[1]); a = array.array("h", src.readframes(src.getnframes()))
loud = [i for i, v in enumerate(a) if abs(v) > 600]
first, last = loud[0], loud[-1]
lo, hi = first + (last - first) * 2 // 5, first + (last - first) * 3 // 5
cut = min(range(lo, hi, 160), key=lambda i: sum(abs(v) for v in a[i:i + 800])) + 400
out = wave.open(sys.argv[2], "w"); out.setparams(src.getparams())
out.writeframes((a[:cut] + array.array("h", [0] * 40000) + a[cut:]).tobytes())
P
    adb push "$work/paused.wav" "$remote_wav" >/dev/null 2>&1
}

# Wait until the app has logged <pattern> (debug builds log what dictation does). Reading the
# screen is too slow and too irregular to catch states that last a second or two.
wait_log() {
    for _ in $(seq $((${2:-30} * 3))); do
        # via a file: with pipefail, `adb | grep -q` fails when grep exits before adb is done
        adb logcat -d >"$work/log.txt" 2>/dev/null
        grep -q "$1" "$work/log.txt" && return
        sleep 0.3
    done
    echo "timed out waiting for log: $1" >&2
    return 1
}

# fresh, empty text field with the keyboard up; sets $sx $sy (space bar) and $cancel_y
open_keyboard() {
    for i in $(seq 20); do
        ((i % 7 == 1)) && adb shell am start -W --activity-clear-task -n "$activity" >/dev/null
        sleep 1
        dump
        if ui center "$work/ui.xml" button_space >/dev/null 2>&1; then
            read -r sx sy < <(ui center "$work/ui.xml" button_space)
            read -r _ top _ bottom < <(ui bounds "$work/ui.xml" button_space)
            cancel_y=$((top - 2 * (bottom - top)))
            return
        fi
    done
    echo "Keyboard did not show up" >&2
    exit 1
}

failed=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; echo "      $2"; failed=$((failed + 1)); }
# check <name> <condition as a shell test string> <detail on failure>
expect() { if eval "$2"; then pass "$1"; else fail "$1" "$3"; fi; }

# ------------------------------------------------------------------------------------------
use_wav
open_keyboard
dump
expect "space bar teaches 'Hold to talk' on a fresh install" \
    'ui has-text "$work/ui.xml" "Hold to talk"' "label not found on the space bar"

# warm up: load the model(s) once, so that the timing of the following scenarios is predictable
adb shell input motionevent DOWN "$sx" "$sy"; sleep 12; adb shell input motionevent UP "$sx" "$sy"; sleep 3
$hq && sleep 15   # the large model takes a few seconds to load

# a long utterance, so that it is still being spoken while the screen is read
use_long_wav
open_keyboard
adb logcat -c
adb shell input motionevent DOWN "$sx" "$sy"
wait_log "updateComposingText: '"
preview=$(field)
adb shell input motionevent UP "$sx" "$sy"
expect "live preview appears in the text field while holding space" \
    '[[ -n $preview ]]' "field was empty while speaking"
expect "live preview has no trailing punctuation" \
    '[[ -n $preview && $punctuation != *"${preview: -1}"* ]]' "preview: '$preview'"
sleep 3
$hq && sleep 8

use_wav
open_keyboard
adb logcat -c
adb shell input motionevent DOWN "$sx" "$sy"
wait_log "Voice final inserted"
adb shell input motionevent UP "$sx" "$sy"
# one dump for both checks: the undo offer only lasts a few seconds
text=$(field)
expect "releasing space inserts the utterance at once, punctuated" \
    'is_sentence "$text" || [[ $text == "$fast_alt" ]]' "field: '$text'"
expect "the pill offers Undo after push-to-talk" \
    'ui center "$work/ui.xml" "Undo dictation" >/dev/null 2>&1' "no 'Undo dictation' button"
adb shell input tap $(ui center "$work/ui.xml" "Undo dictation" 2>/dev/null)
sleep 1
text=$(field)
expect "Undo removes what push-to-talk inserted" '[[ -z $text ]]' "field: '$text'"
dump
expect "the pill returns to Speak after undoing" \
    'ui center "$work/ui.xml" "Voice input" >/dev/null 2>&1' "no 'Voice input' button"

open_keyboard
adb shell input motionevent DOWN "$sx" "$sy"
sleep 2
adb shell input motionevent MOVE "$sx" "$cancel_y"
sleep 1
adb shell input motionevent UP "$sx" "$cancel_y"
sleep 3
text=$(field)
expect "sliding up and releasing inserts nothing and leaves no preview" \
    '[[ -z $text ]]' "field: '$text'"

if $hq; then
    # ---- refinement
    open_keyboard
    adb shell input motionevent DOWN "$sx" "$sy"; sleep 8; adb shell input motionevent UP "$sx" "$sy"
    settle
    text=$(field)
    expect "the inserted text is corrected in place, punctuation and digits kept" \
        '[[ $text == "$refined" ]]' "field: '$text'"
    adb shell input motionevent DOWN "$sx" "$sy"; sleep 8; adb shell input motionevent UP "$sx" "$sy"
    settle
    text=$(field)
    expect "a second utterance is refined without disturbing the first" \
        '[[ $text == "$refined$refined" ]]' "field: '$text'"

    use_long_wav
    open_keyboard
    read -r bx by < <(ui center "$work/ui.xml" button_backspace)
    adb logcat -c
    adb shell input motionevent DOWN "$sx" "$sy"
    wait_log "Voice final inserted"
    adb shell input motionevent UP "$sx" "$sy"
    dump
    expect "the pill says the text is being refined" \
        'ui has-text "$work/ui.xml" "Refining…"' "no 'Refining…' on the pill"
    sleep 8
    # (that refinement never touches text which was edited in the meantime is covered by
    # VoiceEditsTest: on a device it cannot be timed reliably)
    use_wav
fi

# ---- dictation panel
open_keyboard
tap "Voice input"
sleep 9
tap_if_present "Stop listening"
sleep 3
settle
text=$(field)
expect "the panel inserts the utterance" 'is_sentence "$text"' "field: '$text'"
sentence=$text
tap "Backspace"
sleep 1
text=$(field)
expect "backspace in the panel deletes exactly one character" \
    '[[ $text == "${sentence%?}" ]]' "field: '$text'"

# a pause in the middle of a sentence must not split it in two
use_paused_wav
open_keyboard
tap "Voice input"
sleep 5
text=$(field)
expect "after a pause, what was said is inserted without a full stop yet" \
    '[[ -n $text && $text != *。* ]]' "field: '$text'"
sleep 8
tap_if_present "Stop listening"
sleep 3
settle
text=$(field)
stops=${text//[^。]/}
expect "speech that resumes after a pause continues the sentence" \
    '[[ $text == *9点*5点。 && ${#stops} -eq 1 ]]' "field: '$text'"
use_wav

# moving the cursor while a preview is showing must not write the words twice
open_keyboard
tap "Voice input"
sleep 9
tap_if_present "Stop listening"
sleep 2
settle
tap "Start listening"
sleep 3
adb shell input tap 20 100          # cursor to the very beginning of the field
sleep 9
text=$(field)
rest=${text#"$refined"}; rest=${rest#"$fast_alt"}
expect "moving the cursor mid-utterance: nothing is written at the new position" \
    'is_sentence "${text:0:${#refined}}"' "field: '$text'"
expect "moving the cursor mid-utterance: the preview stays once, dictation stops" \
    '[[ -n $rest && ${#rest} -lt ${#refined} && $refined == "${rest:0:1}"* ]]' "after the first sentence: '$rest'"
dump
expect "moving the cursor mid-utterance turns the microphone off" \
    'ui center "$work/ui.xml" "Start listening" >/dev/null 2>&1' "still listening"

# silence: the microphone goes off by itself and says why
use_microphone
open_keyboard
tap "Voice input"
sleep 16
dump
expect "10 s of silence turn the microphone off, with the reason shown" \
    'ui has-text "$work/ui.xml" "Off after 10 s of silence"' "status text not found"
text=$(field)
expect "silence inserts nothing" '[[ -z $text ]]' "field: '$text'"

# after three uses of push-to-talk the space bar shows the input method again
# (push-to-talk has been used more than three times by now)
adb shell am force-stop "$pkg"
adb shell ime set "$ime" >/dev/null
open_keyboard
dump
expect "'Hold to talk' is gone after three uses" \
    '! ui has-text "$work/ui.xml" "Hold to talk"' "label still on the space bar"

echo
if ((failed > 0)); then
    echo "$failed scenario check(s) failed" >&2
    exit 1
fi
echo "All scenario checks passed"
