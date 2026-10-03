# Sourced by the other scripts: locate the Android SDK and a suitable JDK.
# shellcheck shell=bash

if [[ -z ${ANDROID_HOME:-} ]]; then
    for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -d $d ]] && export ANDROID_HOME=$d && break
    done
fi
[[ -d ${ANDROID_HOME:-} ]] || { echo "Android SDK not found; set ANDROID_HOME" >&2; exit 1; }

# Gradle needs JDK 17 or 21; on macOS pick one explicitly if the default is something else.
if [[ -z ${JAVA_HOME:-} && -x /usr/libexec/java_home ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)
    [[ -n $JAVA_HOME ]] && export JAVA_HOME
fi

export PATH="$ANDROID_HOME/platform-tools:$PATH"

# newest installed build-tools, for aapt2/apksigner
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
export BUILD_TOOLS
