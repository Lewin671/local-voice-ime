#!/usr/bin/env bash
# Build the APK and print its path.
#
#   scripts/build.sh            debug build   (package id ends with .debug, includes test hooks)
#   scripts/build.sh release    release build (signed with the debug key unless configured,
#                               see docs/TESTING.md)
#
# Environment: ABI (default arm64-v8a) selects the CPU architecture.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

variant=${1:-debug}

# release signing key, see docs/TESTING.md
signing=${XDG_CONFIG_HOME:-$HOME/.config}/local-voice-ime/signing.env
if [[ $variant == release && -f $signing ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$signing"
    set +a
fi
abi=${ABI:-arm64-v8a}

task=":app:assemble$(tr '[:lower:]' '[:upper:]' <<<"${variant:0:1}")${variant:1}"

./scripts/fetch-voice-assets.sh
./gradlew "$task" -PbuildABI="$abi" --console=plain -q
apk=$(ls -t app/build/outputs/apk/"$variant"/*-"$abi"-"$variant".apk | head -1)
echo "$apk"
