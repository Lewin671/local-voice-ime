#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
# Requires JDK and kotlinc. Measures storage overhead, not phone battery consumption.
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
kotlinc app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceAudioBuffer.kt \
    app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoicePacing.kt \
    scripts/bench/AudioBufferBench.kt -include-runtime -d "$work/bench.jar"
java -jar "$work/bench.jar"
