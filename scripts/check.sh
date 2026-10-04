#!/usr/bin/env bash
# Local checks for code changes; CI runs the same. See docs/TESTING.md#verification-policy.
#
#   1. unit tests of the voice package
#   2. debug build
#   3. network use is limited to downloading speech models (scripts/check-privacy.sh)
#
# Device checks depend on the affected behavior, not just the source directory.
# Documentation/comment-only changes need diff and link checks, not this script.
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
