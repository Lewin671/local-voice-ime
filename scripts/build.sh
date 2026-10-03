#!/usr/bin/env bash
# Build the APK and print its path.
#
#   scripts/build.sh            debug build   (package id ends with .debug, includes test hooks)
#   scripts/build.sh release    release build (signed with the debug key unless configured,
#                               see docs/TESTING.md)
#
# Environment: ABI (default arm64-v8a) selects the CPU architecture;
#              REFINER=1 builds the high-accuracy variant, which also bundles the large model
#              that re-checks dictated text (about 1.5 GB instead of 0.3 GB).
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

if [[ ${REFINER:-} == 1 ]]; then
    # high-accuracy build: same app plus the large refinement model, in an APK marked "-hq"
    ./scripts/fetch-voice-assets.sh --refiner
    ./gradlew "$task" -PbuildABI="$abi" -PvoiceRefiner=true --console=plain -q
    built=$(ls -t app/build/outputs/apk/"$variant"/*-"$abi"-"$variant".apk | grep -v -- '-hq-' | head -1)
    apk=${built/-$abi-$variant.apk/-hq-$abi-$variant.apk}
    mv "$built" "$apk"
else
    ./scripts/fetch-voice-assets.sh
    ./gradlew "$task" -PbuildABI="$abi" --console=plain -q
    apk=$(ls -t app/build/outputs/apk/"$variant"/*-"$abi"-"$variant".apk | grep -v -- '-hq-' | head -1)
fi
echo "$apk"
