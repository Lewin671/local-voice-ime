#!/usr/bin/env bash
# Everything that can be verified without a device. Run it before every commit; CI runs the same.
#
#   1. unit tests of the voice package
#   2. debug build
#   3. the APK holds no network permission
#
# Changes under input/voice/ additionally need scripts/e2e-voice.sh, and UI changes
# scripts/ui-shots.sh; both require a device or emulator.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

./scripts/fetch-voice-assets.sh

echo "== unit tests"
# only our tests: upstream's ThemeSerializationTest.version2 fails on upstream's own main branch
./gradlew :app:testDebugUnitTest --tests 'org.fcitx.fcitx5.android.input.voice.*' \
    -PbuildABI="${ABI:-arm64-v8a}" --console=plain -q

echo "== build"
apk=$(./scripts/build.sh debug | tail -1)

echo "== privacy"
./scripts/check-privacy.sh "$apk"

echo "All checks passed"
