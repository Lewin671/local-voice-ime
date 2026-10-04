#!/usr/bin/env bash
# Download the speech runtime and models that are too large to keep in git.
#
#   voice/libs/sherpa-onnx.aar                 inference runtime (sherpa-onnx + onnxruntime)
#   voice/assets/voice/silero_vad.onnx         voice activity detection model
#
# with --models (implied by --refiner):
#   voice/models/sense-voice-small-int8/       speech recognition model (SenseVoice Small, int8, 240 MB)
#   voice/test-wavs/                           sample recordings for the end-to-end test
#
# with --refiner:
#   voice/models/fire-red-asr2-aed-int8/       the large model that re-checks dictated text
#                                              (FireRedASR2 AED, 1.2 GB)
#
# voice/libs and voice/assets are bundled into the APK at build time, and are all a build needs.
# The speech models are not bundled: users download them in the app's settings (VoiceModels.kt
# pins the same files). --models and --refiner fetch them for scripts/push-voice-model.sh, which
# puts them on a test device without a download, and for the benchmarks in scripts/bench/.
#
# Idempotent: files that are already present with the right checksum are kept.
set -euo pipefail

cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
RELEASES=https://github.com/k2-fsa/sherpa-onnx/releases/download
ASR_MODEL=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

# fetch <url> <dest> <sha256>
fetch() {
    local url=$1 dest=$2 sum=$3
    if [[ -f $dest && $(sha256 "$dest") == "$sum" ]]; then
        echo "ok       $dest"
        return
    fi
    echo "download $dest"
    mkdir -p "$(dirname "$dest")"
    curl -fL --retry 3 --progress-bar -o "$dest.part" "$url"
    local actual
    actual=$(sha256 "$dest.part")
    if [[ $actual != "$sum" ]]; then
        echo "checksum mismatch for $url: expected $sum, got $actual" >&2
        exit 1
    fi
    mv "$dest.part" "$dest"
}

fetch "$RELEASES/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar" \
    voice/libs/sherpa-onnx.aar \
    633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96

fetch "$RELEASES/asr-models/silero_vad_v5.onnx" \
    voice/assets/voice/silero_vad.onnx \
    6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396

# ---- the speech models, for tests only
model_dir=voice/models/sense-voice-small-int8
wavs_dir=voice/test-wavs

# Until 0.4 the recognition model was bundled into the APK from voice/assets; it must not be
# any more, so move a copy that is still there to where the tests expect it.
old_model_dir=voice/assets/voice/sense-voice
if [[ -d $old_model_dir ]]; then
    mkdir -p "$model_dir"
    for f in model.int8.onnx tokens.txt; do
        [[ -f $old_model_dir/$f && ! -f $model_dir/$f ]] && mv "$old_model_dir/$f" "$model_dir/$f"
    done
    rm -rf "$old_model_dir"
    echo "moved    $old_model_dir -> $model_dir"
fi

[[ ${1:-} == --models || ${1:-} == --refiner ]] || exit 0

# the files and checksums of VoiceModels.SenseVoice, taken from sherpa-onnx's release archive
if [[ -f $model_dir/model.int8.onnx && $(sha256 "$model_dir/model.int8.onnx") == "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51" && -f $model_dir/tokens.txt && $(sha256 "$model_dir/tokens.txt") == "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc" && -f $wavs_dir/zh.wav && -f $wavs_dir/en.wav ]]; then
    echo "ok       $model_dir"
else
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    fetch "$RELEASES/asr-models/$ASR_MODEL.tar.bz2" "$tmp/model.tar.bz2" \
        7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e
    tar -xjf "$tmp/model.tar.bz2" -C "$tmp"
    mkdir -p "$model_dir"
    cp "$tmp/$ASR_MODEL/model.int8.onnx" "$tmp/$ASR_MODEL/tokens.txt" "$model_dir/"
    # sample recordings shipped with the model, used by scripts/e2e-voice.sh (not bundled into the APK)
    mkdir -p "$wavs_dir"
    cp "$tmp/$ASR_MODEL/test_wavs/zh.wav" "$tmp/$ASR_MODEL/test_wavs/en.wav" "$wavs_dir/"
    echo "ok       $model_dir"
fi

if [[ ${1:-} == --refiner ]]; then
    # the files and checksums of VoiceModels.FireRedAsr2
    REFINER=https://www.modelscope.cn/models/csukuangfj/FireRedASR2-AED-onnx/resolve/master/aed
    refiner_dir=voice/models/fire-red-asr2-aed-int8
    fetch "$REFINER/encoder.int8.onnx" "$refiner_dir/encoder.int8.onnx" \
        54048d66b6e8f3c80ea7ce95efe794587b0fd81d7271651d0decd3803852ae82
    fetch "$REFINER/decoder.int8.onnx" "$refiner_dir/decoder.int8.onnx" \
        b840ce7196ae4a14d05ae84bbf56082b6b61ccec5610fda907dddbcea37354ff
    fetch "$REFINER/tokens.txt" "$refiner_dir/tokens.txt" \
        1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b
fi
