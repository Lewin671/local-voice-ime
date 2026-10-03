#!/usr/bin/env bash
# Behaviour tests of the dictation UX on a connected device or emulator: the rules written down
# in docs/design/DESIGN.md that a transcript comparison (scripts/e2e-voice.sh) cannot catch.
#
#   scripts/e2e-scenarios.sh
#
# Requires a debug APK (scripts/build.sh); it is reinstalled from scratch, so app data of the
# debug build is reset. The emulator/device microphone must deliver silence for the idle-timeout
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
sentence="开饭时间早上9点至下午5点。"   # what the model hears in voice/test-wavs/zh.wav
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
adb shell ime enable "$ime" >/dev/null
adb shell ime set "$ime" >/dev/null
adb shell mkdir -p "$(dirname "$remote_wav")"

ui() { python3 scripts/e2e/ui.py "$@"; }
dump() {
    adb shell uiautomator dump --windows /sdcard/ime-e2e-ui.xml >/dev/null 2>&1
    adb pull /sdcard/ime-e2e-ui.xml "$work/ui.xml" >/dev/null 2>&1
}
field() { dump; ui text "$work/ui.xml" test-input; }
tap() { dump; adb shell input tap $(ui center "$work/ui.xml" "$1"); }
# the sample recording is shorter than some waits: the session may already have ended by itself
tap_if_present() { dump; ui center "$work/ui.xml" "$1" >/dev/null 2>&1 && adb shell input tap $(ui center "$work/ui.xml" "$1"); }
use_wav() { adb push voice/test-wavs/zh.wav "$remote_wav" >/dev/null 2>&1; }
use_microphone() { adb shell rm -f "$remote_wav"; }

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

# warm up: load the model once, so that the timing of the following scenarios is predictable
adb shell input motionevent DOWN "$sx" "$sy"; sleep 12; adb shell input motionevent UP "$sx" "$sy"; sleep 3

open_keyboard
adb shell input motionevent DOWN "$sx" "$sy"
sleep 3
preview=$(field)
expect "live preview appears in the text field while holding space" \
    '[[ -n $preview ]]' "field was empty 3 s into the utterance"
expect "live preview has no trailing punctuation" \
    '[[ -n $preview && $punctuation != *"${preview: -1}"* ]]' "preview: '$preview'"
sleep 5
adb shell input motionevent UP "$sx" "$sy"
sleep 2
# one dump for both checks: the undo offer only lasts a few seconds
text=$(field)
expect "releasing space inserts the utterance, punctuated" \
    '[[ $text == "$sentence" ]]' "field: '$text'"
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
sleep 4
adb shell input motionevent MOVE "$sx" "$cancel_y"
sleep 1
adb shell input motionevent UP "$sx" "$cancel_y"
sleep 3
text=$(field)
expect "sliding up and releasing inserts nothing and leaves no preview" \
    '[[ -z $text ]]' "field: '$text'"

# ---- dictation panel
open_keyboard
tap "Voice input"
sleep 9
tap_if_present "Stop listening"
sleep 3
text=$(field)
expect "the panel inserts the utterance" '[[ $text == "$sentence" ]]' "field: '$text'"
tap "Backspace"
sleep 1
text=$(field)
expect "backspace in the panel deletes exactly one character" \
    '[[ $text == "${sentence%?}" ]]' "field: '$text'"

# moving the cursor while a preview is showing must not write the words twice
open_keyboard
tap "Voice input"
sleep 9
tap_if_present "Stop listening"
sleep 2
tap "Start listening"
sleep 3
adb shell input tap 20 100          # cursor to the very beginning of the field
sleep 9
text=$(field)
rest=${text#"$sentence"}
expect "moving the cursor mid-utterance: nothing is written at the new position" \
    '[[ $text == "$sentence"* ]]' "field: '$text'"
expect "moving the cursor mid-utterance: the preview stays once, dictation stops" \
    '[[ -n $rest && ${#rest} -lt ${#sentence} && $sentence == "${rest:0:1}"* ]]' "after the first sentence: '$rest'"
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
# (uses so far: warm-up, preview, cancel)
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
