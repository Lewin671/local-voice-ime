#!/usr/bin/env bash
# Screenshot every UI state of docs/design/mockup.html on a connected device or emulator, in the
# light and dark theme, for comparing an implementation with the design.
#
#   scripts/ui-shots.sh [output dir]        default: build/ui-shots
#
# Requires a debug APK (scripts/build.sh); it is (re)installed first, and the speech model is
# copied to the device (scripts/push-voice-model.sh). Dictation states are driven with a sample
# recording instead of the microphone.
# The device's previous keyboard and day/night setting are restored at the end.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

out=${1:-build/ui-shots}
mkdir -p "$out"

apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
[[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
ime=$pkg/org.fcitx.fcitx5.android.input.FcitxInputMethodService
activity=$pkg/org.fcitx.fcitx5.android.debug.TestInputActivity
remote_wav=/sdcard/Android/data/$pkg/files/voice-test.wav
work=$(mktemp -d)

adb get-state >/dev/null
echo "Installing $apk"
adb install -r -g "$apk" >/dev/null
./scripts/push-voice-model.sh "$pkg"
model=files/voice-models/sense-voice-small-int8

previous_ime=$(adb shell settings get secure default_input_method | tr -d '\r')
previous_night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
cleanup() {
    adb shell rm -f "$remote_wav" >/dev/null 2>&1 || true
    adb shell run-as "$pkg" mv "$model.aside" "$model" >/dev/null 2>&1 || true
    adb shell pm grant "$pkg" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
    adb shell cmd uimode night "$previous_night" >/dev/null 2>&1 || true
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    if [[ -n $previous_ime && $previous_ime != null && $previous_ime != "$ime" ]]; then
        adb shell ime set "$previous_ime" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

adb shell ime enable "$ime" >/dev/null
adb shell ime set "$ime" >/dev/null
adb shell mkdir -p "$(dirname "$remote_wav")"
adb push voice/test-wavs/zh.wav "$remote_wav" >/dev/null 2>&1

dump_ui() {
    adb shell uiautomator dump --windows /sdcard/ime-ui-shots.xml >/dev/null
    adb pull /sdcard/ime-ui-shots.xml "$work/ui.xml" >/dev/null 2>&1
}

# open a fresh text field and wait for the keyboard
open_keyboard() {
    for i in $(seq 20); do
        if ((i % 7 == 1)); then
            adb shell am start -W --activity-clear-task -n "$activity" >/dev/null
        fi
        sleep 1
        dump_ui
        python3 scripts/e2e/ui.py center "$work/ui.xml" button_space >/dev/null 2>&1 && return
    done
    echo "Keyboard did not show up" >&2
    return 1
}

center() { python3 scripts/e2e/ui.py center "$work/ui.xml" "$1"; }

shot() {
    adb exec-out screencap -p >"$out/$theme-$1.png"
    echo "$out/$theme-$1.png"
}

for theme in light dark; do
    adb shell cmd uimode night "$([[ $theme == dark ]] && echo yes || echo no)" >/dev/null
    adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
    sleep 2

    open_keyboard
    shot 1-keyboard

    # hold space: listening, then above the cancel line, then release there (nothing inserted)
    read -r sx sy < <(center button_space)
    read -r _ top _ bottom < <(python3 scripts/e2e/ui.py bounds "$work/ui.xml" button_space)
    adb shell input motionevent DOWN "$sx" "$sy"
    sleep 4
    shot 2-hold-listening
    adb shell input motionevent MOVE "$sx" $((top - 2 * (bottom - top)))
    sleep 1
    shot 3-hold-cancel
    adb shell input motionevent UP "$sx" $((top - 2 * (bottom - top)))
    sleep 2

    # hands-free panel: listening, then paused
    dump_ui
    adb shell input tap $(center "Voice input")
    sleep 4
    shot 4-panel-listening
    dump_ui
    adb shell input tap $(center "Stop listening")
    sleep 3
    shot 5-panel-paused

    # without microphone access (revoking the permission restarts the app)
    adb shell pm revoke "$pkg" android.permission.RECORD_AUDIO
    sleep 2
    adb shell ime set "$ime" >/dev/null
    open_keyboard
    adb shell input tap $(center "Voice input")
    sleep 2
    shot 6-panel-permission

    # without the speech model, as after a fresh install (the app reads what is installed once
    # per process, hence the restart)
    adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" mv "$model" "$model.aside"
    adb shell ime set "$ime" >/dev/null
    open_keyboard
    adb shell input tap $(center "Voice input")
    sleep 2
    shot 7-panel-model-needed
    dump_ui
    adb shell input tap $(center "Open settings")
    sleep 3
    shot 8-settings-voice-input
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" mv "$model.aside" "$model"
    adb shell ime set "$ime" >/dev/null
done
