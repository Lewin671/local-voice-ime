#!/usr/bin/env bash
# Put the large speech model (FireRedASR2 AED) on a connected device or emulator without going
# through the download in the app's settings: copies the files fetched by
# `scripts/fetch-voice-assets.sh --refiner` into the private storage of an installed DEBUG build
# and marks them as installed, exactly as VoiceModels.kt would after a download.
#
#   scripts/push-voice-model.sh [package]     default: package of the newest debug APK
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

pkg=${1:-}
if [[ -z $pkg ]]; then
    apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
    [[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
    pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
fi

./scripts/fetch-voice-assets.sh --refiner >/dev/null

# keep in sync with VoiceModels.FireRedAsr2
id=fire-red-asr2-aed-int8
files=(encoder.int8.onnx decoder.int8.onnx tokens.txt)
src=voice/models/$id
dest=files/voice-models/$id
tmp=/data/local/tmp/voice-model-$id

adb shell am force-stop "$pkg"
adb shell run-as "$pkg" mkdir -p "$dest"
marker=
for f in "${files[@]}"; do
    echo "push     $f"
    adb push "$src/$f" "$tmp" >/dev/null
    # the app may not read /data/local/tmp, so the shell user pipes the file to it
    adb shell "cat $tmp | run-as $pkg sh -c 'cat > $dest/$f'"
    adb shell rm -f "$tmp"
    marker+=${marker:+\\n}$(shasum -a 256 "$src/$f" | cut -d' ' -f1)
done
adb shell "printf '$marker' | run-as $pkg sh -c 'cat > $dest/installed'"
echo "ok       $pkg: $dest"
