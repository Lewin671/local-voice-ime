#!/usr/bin/env bash
# Keep the app's network use to what docs/PRIVACY.md promises: downloading a speech model, and
# looking for and downloading a new version of the app or of the fine-tuned model, each when the
# user asks for it, and nothing else.
#
#   1. the APK holds INTERNET and no other network-capable permission, and refuses cleartext
#   2. VoiceModelFetch.kt is the only source file that opens a connection
#   3. no dependency that talks to the network (HTTP clients, analytics, crash reporting, ads)
#      has been added
#
#   scripts/check-privacy.sh [path/to.apk]     default: newest APK under app/build/outputs
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/env.sh

apk=${1:-$(ls -t app/build/outputs/apk/*/*.apk 2>/dev/null | head -1)}
[[ -f $apk ]] || { echo "No APK found; run scripts/build.sh first" >&2; exit 1; }

fail() { echo "FAIL: $*" >&2; exit 1; }

permissions=$("$BUILD_TOOLS/aapt2" dump permissions "$apk")
forbidden='android\.permission\.(ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE|BLUETOOTH[A-Z_]*|NFC|[A-Z_]*LOCATION|READ_PHONE_STATE|FOREGROUND_SERVICE[A-Z_]*|RECEIVE_BOOT_COMPLETED)'

echo "$apk"
echo "$permissions" | sed 's/^/  /'
if echo "$permissions" | grep -Eq "$forbidden"; then
    fail "permission beyond what downloading a model or an update needs"
fi
manifest=$("$BUILD_TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
grep -q 'usesCleartextTraffic.*=false' <<<"$manifest" || fail "cleartext traffic is not disabled"

# anything that can open a connection, outside of the one file that is allowed to
network_api='openConnection|HttpURLConnection|HttpsURLConnection|java\.net\.Socket|SocketFactory|DatagramSocket|okhttp|WebView|DownloadManager|android\.net\.http|ConnectivityManager'
offenders=$(grep -rlE "$network_api" app/src/main lib/*/src/main plugin/*/src/main \
    --include='*.kt' --include='*.java' 2>/dev/null |
    grep -v 'input/voice/VoiceModelFetch.kt$' || true)
[[ -z $offenders ]] || fail "network code outside VoiceModelFetch.kt:"$'\n'"$offenders"

network_deps='okhttp|retrofit|ktor|volley|firebase|play-services|crashlytics|sentry|bugsnag|analytics|appcenter|admob|facebook'
offenders=$(grep -nEi "$network_deps" gradle/libs.versions.toml app/build.gradle.kts || true)
[[ -z $offenders ]] || fail "dependency that talks to the network:"$'\n'"$offenders"

echo "PASS: the network is only reachable from VoiceModelFetch.kt"
