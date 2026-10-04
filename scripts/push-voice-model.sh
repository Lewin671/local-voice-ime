#!/usr/bin/env bash
# Put the speech models on a connected device or emulator without going through the download in
# the app's settings: copies the files fetched by `scripts/fetch-voice-assets.sh --models` into
# the private storage of an installed DEBUG build and marks them as installed, exactly as
# VoiceModels.kt would after a download. A model that is already installed is left alone.
#
#   scripts/push-voice-model.sh [--refiner] [package]
#
#   --refiner   also the large model (FireRedASR2 AED, 1.2 GB); without it only the standard
#               model (SenseVoice Small), which voice input cannot do without
#   package     default: package of the newest debug APK
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

refiner=false
[[ ${1:-} == --refiner ]] && { refiner=true; shift; }

pkg=${1:-}
if [[ -z $pkg ]]; then
    apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
    [[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
    pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
fi

./scripts/fetch-voice-assets.sh "$($refiner && echo --refiner || echo --models)" >/dev/null

# push <model id> <file>...      keep in sync with VoiceModels.kt
push() {
    local id=$1
    shift
    local src=voice/models/$id dest=files/voice-models/$id tmp=/data/local/tmp/voice-model-$id
    local marker= f
    for f in "$@"; do
        marker+=${marker:+\\n}$(shasum -a 256 "$src/$f" | cut -d' ' -f1)
    done
    if [[ $(adb shell "run-as $pkg cat $dest/installed 2>/dev/null") == "$(printf "$marker")" ]]; then
        echo "ok       $pkg: $dest"
        return
    fi
    adb shell run-as "$pkg" mkdir -p "$dest"
    for f in "$@"; do
        echo "push     $f"
        adb push "$src/$f" "$tmp" >/dev/null
        # the app may not read /data/local/tmp, so the shell user pipes the file to it
        adb shell "cat $tmp | run-as $pkg sh -c 'cat > $dest/$f'"
        adb shell rm -f "$tmp"
    done
    adb shell "printf '$marker' | run-as $pkg sh -c 'cat > $dest/installed'"
    echo "ok       $pkg: $dest"
}

# the app reads what is installed once per process
adb shell am force-stop "$pkg"
push sense-voice-small-int8 model.int8.onnx tokens.txt
if $refiner; then
    push fire-red-asr2-aed-int8 encoder.int8.onnx decoder.int8.onnx tokens.txt
fi
