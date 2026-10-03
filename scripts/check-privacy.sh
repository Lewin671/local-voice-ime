#!/usr/bin/env bash
# Assert that the built APK cannot talk to the network: the whole privacy promise of this
# project rests on the app not holding android.permission.INTERNET (or anything similar).
#
#   scripts/check-privacy.sh [path/to.apk]     default: newest APK under app/build/outputs
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

apk=${1:-$(ls -t app/build/outputs/apk/*/*.apk 2>/dev/null | head -1)}
[[ -f $apk ]] || { echo "No APK found; run scripts/build.sh first" >&2; exit 1; }

permissions=$("$BUILD_TOOLS/aapt2" dump permissions "$apk")
forbidden='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|BLUETOOTH[A-Z_]*|NFC)'

echo "$apk"
echo "$permissions" | sed 's/^/  /'
if echo "$permissions" | grep -Eq "$forbidden"; then
    echo "FAIL: network-capable permission found" >&2
    exit 1
fi
echo "PASS: no network permission"
