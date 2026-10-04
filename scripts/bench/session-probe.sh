#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
# On a dedicated device: compare final audio/text across builds without replacing VAD or ASR.
# Usage: session-probe.sh <output-directory> [wav-directory]
# CANCEL_BEFORE_START=1 verifies a cold cancelled session never loads the standard model.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/env.sh
out=${1:?Specify an output directory}
wavs=${2:-voice/test-wavs}
cancel=false
[[ ${CANCEL_BEFORE_START:-0} != 1 ]] || cancel=true
mkdir -p "$out"
apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk | head -1)
pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
activity=$pkg/org.fcitx.fcitx5.android.debug.VoiceSessionProbeActivity
remote=/sdcard/Android/data/$pkg/files/voice-probe.wav
trap 'adb shell rm -f "$remote" >/dev/null 2>&1 || true' EXIT
adb install -r -g "$apk" >/dev/null
./scripts/push-voice-model.sh "$pkg"
adb shell mkdir -p "$(dirname "$remote")"
for wav in "$wavs"/*.wav; do
    name=$(basename "$wav" .wav)
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" rm -f files/voice-probe-result.json
    adb push "$wav" "$remote" >/dev/null 2>&1
    adb shell am start -W -n "$activity" --ez cancelBeforeStart "$cancel" >/dev/null
    completed=false
    for _ in $(seq 120); do
        if adb shell run-as "$pkg" cat files/voice-probe-result.json >"$out/$name.json" 2>/dev/null; then
            completed=true
            break
        fi
        sleep 1
    done
    $completed || { echo "Timed out: $name" >&2; exit 1; }
    python3 - "$out/$name.json" "$cancel" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
assert 'error' not in d, d
assert d['sourceStopCalls'] == 1, d
if sys.argv[2] == 'true':
    assert not d['finals'] and d['capturedSamples'] == 0, d
    assert not d['standardModelLoadedAtEnd'], d
else:
    assert d['finals'], d
print(sys.argv[1], 'capture_stopped_before_finishing=', d['captureStoppedBeforeFinishing'])
PY
done
