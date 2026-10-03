#!/usr/bin/env bash
# End-to-end test of voice dictation on a connected device or emulator.
#
# For every case in scripts/e2e/cases.tsv: the WAV file is played into the recognition pipeline
# *inside the keyboard* (debug builds read voice-test.wav instead of the microphone), the
# microphone button of the keyboard is tapped, and the text that ends up in a real text field is
# compared with the expected transcript.
#
#   scripts/e2e-voice.sh [--no-install] [cases.tsv]
#
# Requires a debug APK (scripts/build.sh). Exit code is non-zero if any case fails.
# The device's previous default keyboard is restored at the end.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

install=true
[[ ${1:-} == --no-install ]] && { install=false; shift; }
cases=${1:-scripts/e2e/cases.tsv}

# a case fails when more than this share of characters is wrong
MAX_ERROR_RATE=0.15

apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
[[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
ime=$pkg/org.fcitx.fcitx5.android.input.FcitxInputMethodService
activity=$pkg/org.fcitx.fcitx5.android.debug.TestInputActivity
remote_wav=/sdcard/Android/data/$pkg/files/voice-test.wav
work=$(mktemp -d)

adb get-state >/dev/null

if $install; then
    echo "Installing $apk"
    adb install -r -g "$apk" >/dev/null
fi

previous_ime=$(adb shell settings get secure default_input_method | tr -d '\r')
cleanup() {
    adb shell rm -f "$remote_wav" >/dev/null 2>&1 || true
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    if [[ -n $previous_ime && $previous_ime != null && $previous_ime != "$ime" ]]; then
        adb shell ime set "$previous_ime" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

adb shell ime enable "$ime" >/dev/null
adb shell ime set "$ime" >/dev/null
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell mkdir -p "$(dirname "$remote_wav")"

# note: every adb call inside the loop below must not read stdin (it would swallow the cases)
dump_ui() {
    adb shell uiautomator dump --windows /sdcard/ime-e2e-ui.xml >/dev/null </dev/null
    adb pull /sdcard/ime-e2e-ui.xml "$work/ui.xml" >/dev/null 2>&1 </dev/null
}

# wait until the keyboard is up, then print the position of its microphone button
find_mic() {
    for _ in $(seq 15); do
        sleep 1
        dump_ui
        python3 scripts/e2e/ui.py center "$work/ui.xml" "Voice input" 2>/dev/null && return
    done
    echo "Keyboard with a microphone button did not show up" >&2
    return 1
}

failed=0
while IFS=$'\t' read -r wav expected; do
    [[ -z $wav || $wav == \#* ]] && continue
    duration=$(python3 -c "import wave,sys; w=wave.open(sys.argv[1]); print(int(w.getnframes()/w.getframerate())+1)" "$wav")

    adb push "$wav" "$remote_wav" >/dev/null 2>&1 </dev/null
    # a fresh, empty text field with the keyboard showing
    adb shell am start -W --activity-clear-task -n "$activity" >/dev/null </dev/null
    read -r x y < <(find_mic)
    adb shell input tap "$x" "$y" </dev/null
    # cold model load + playback + trailing silence + final decode
    sleep $((duration + 12))
    dump_ui
    actual=$(python3 scripts/e2e/ui.py text "$work/ui.xml" test-input)
    rate=$(python3 scripts/e2e/ui.py score "$expected" "$actual")

    if python3 -c "import sys; sys.exit(float(sys.argv[1]) > float(sys.argv[2]))" "$rate" "$MAX_ERROR_RATE"; then
        status=PASS
    else
        status=FAIL
        failed=$((failed + 1))
    fi
    printf '%s  %s  (error rate %s)\n    expected: %s\n    actual:   %s\n' "$status" "$wav" "$rate" "$expected" "$actual"
done <"$cases"

if ((failed > 0)); then
    echo "$failed case(s) failed" >&2
    exit 1
fi
echo "All cases passed"
