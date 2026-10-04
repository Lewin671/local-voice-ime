#!/usr/bin/env bash
# Measure a speech model on a connected phone or emulator: load time, decode time for recordings
# of different lengths, memory. Requires an installed debug build (scripts/build.sh, adb install).
#
#   scripts/bench/device-bench.sh <key> <model dir> <kind> [punctuation model file]
#
#   <kind>   sensevoice | transducer | funasr_nano | firered_aed | qwen3
#   WAVS     directory with 16 kHz mono WAV files (default: voice/test-wavs)
#   THREADS  inference threads (default: 4)
#   CPU_SPIN  optional 0 or 1: configure ONNX worker spinning for CPU A/B tests
#   CPU_CONFIG optional local session-options file; mutually exclusive with CPU_SPIN
#
# The model is copied into the app's private storage, benchmarked by VoiceBenchActivity, and
# removed again. The result is printed and saved as build/device-bench/<key>.json.
set -euo pipefail

cd "$(dirname "$0")/../.."
source scripts/env.sh

key=$1; model=$2; kind=$3; punct=${4:-}
wavs=${WAVS:-voice/test-wavs}
threads=${THREADS:-4}
spin=${CPU_SPIN:-}
cpu_config=${CPU_CONFIG:-}
[[ -z $spin || $spin == 0 || $spin == 1 ]] || { echo "CPU_SPIN must be 0 or 1" >&2; exit 1; }
[[ -z $spin || -z $cpu_config ]] || { echo "Use CPU_SPIN or CPU_CONFIG, not both" >&2; exit 1; }
[[ -z $cpu_config || -f $cpu_config ]] || { echo "CPU_CONFIG does not exist" >&2; exit 1; }

apk=$(ls -t app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1 || true)
[[ -f $apk ]] || { echo "No debug APK; run scripts/build.sh first" >&2; exit 1; }
pkg=$("$BUILD_TOOLS/aapt2" dump packagename "$apk")
out=build/device-bench
mkdir -p "$out"

# Files pushed by adb belong to the shell user and are not readable by the app, so they are staged
# in /data/local/tmp and copied into the app's private storage with run-as (debug builds only).
stage=/data/local/tmp/lvi-bench
bench_home=$(adb shell run-as "$pkg" pwd | tr -d '\r')
remote=$bench_home/files/bench
as_app() { adb shell run-as "$pkg" "$@"; }

adb shell rm -rf "$stage"
adb shell mkdir -p "$stage/wavs"
adb push "$model" "$stage/model" >/dev/null 2>&1
adb push "$wavs"/*.wav "$stage/wavs/" >/dev/null 2>&1
[[ -z $cpu_config ]] || adb push "$cpu_config" "$stage/cpu.config" >/dev/null 2>&1
extra=()
if [[ -n $punct ]]; then
    adb push "$punct" "$stage/punct.onnx" >/dev/null 2>&1
    extra=(--es punct "$remote/punct.onnx")
fi
as_app rm -rf files/bench
as_app mkdir -p files/bench
as_app cp -r "$stage/." files/bench/
adb shell rm -rf "$stage"

if [[ -n $spin ]]; then
    adb shell "printf 'SessionConfig.session.intra_op.allow_spinning=$spin\\nSessionConfig.session.inter_op.allow_spinning=$spin\\n' | run-as $pkg sh -c 'cat > $remote/cpu.config'"
    extra+=(--es provider "cpu:$remote/cpu.config")
elif [[ -n $cpu_config ]]; then
    extra+=(--es provider "cpu:$remote/cpu.config")
fi

adb shell am force-stop "$pkg"      # start from a clean process: memory numbers are comparable
adb shell am start -W -n "$pkg/org.fcitx.fcitx5.android.debug.VoiceBenchActivity" \
    --es dir "$remote/model" --es kind "$kind" --es wavs "$remote/wavs" \
    --es out "$remote/result.json" --ei threads "$threads" ${extra[@]+"${extra[@]}"} >/dev/null

for _ in $(seq 600); do
    sleep 2
    as_app ls files/bench/result.json >/dev/null 2>&1 && break
    adb shell pidof "$pkg" >/dev/null || { echo "The app died (out of memory?)" >&2; break; }
done
as_app cat files/bench/result.json >"$out/$key.json" 2>/dev/null
[[ -s $out/$key.json ]] || echo '{"error": "no result: the app was killed"}' >"$out/$key.json"
as_app rm -rf files/bench
adb shell am force-stop "$pkg"

python3 - "$out/$key.json" "$key" <<'P'
import json, sys
r = json.load(open(sys.argv[1]))
if "error" in r:
    print(f"{sys.argv[2]}: {r['error']}"); sys.exit(1)
print(f"{sys.argv[2]}  on {r['device']} ({r['cores']} cores, {r['threads']} threads)")
print(f"  load {r['loadMs']} ms   memory {r['pssBeforeMb']} -> {r['pssLoadedMb']} MB loaded, {r['pssPeakMb']} MB peak")
for x in r["runs"]:
    p = f"  +punct {x['punctMs']} ms: {x['punctuated']}" if "punctMs" in x else ""
    print(f"  {x['wav']:14s} {x['audioMs']/1000:5.1f} s audio -> {x['secondMs']:6d} ms (first run {x['firstMs']} ms, RTF {x['rtf']:.2f})  {x['text']}{p}")
P
