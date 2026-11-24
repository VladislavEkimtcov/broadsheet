#!/usr/bin/env bash
# Builds android/build/broadsheet.apk (TV and phone in one APK) with the bare Android SDK tools:
# no Gradle, no libraries. Needs a JDK 17 and an Android SDK with platforms;android-25 and any
# build-tools (34 tested). The version comes from ../version.env, shared with the Windows port.
#   ./build.sh            build and sign with ../release.keystore (created on the first local run;
#                         keep it, updates must be signed with the same key)
#   KEYSTORE=... KEYPASS=... ./build.sh   sign with that key (it must exist)
# Source roots, one Java package: src/ (shared: relay client, controller, reader, options),
# tv/src/ (the TV's front page) and phone/src/ (the phone's).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
REPO=$(cd .. && pwd)

# shellcheck disable=SC1091
source "$REPO/version.env"
APK=build/broadsheet.apk
MIN_SDK=25

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK ]]; then
  for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" /opt/android-sdk; do
    if [[ -f $d/platforms/android-$MIN_SDK/android.jar ]]; then SDK=$d; break; fi
  done
fi
JAR=$SDK/platforms/android-$MIN_SDK/android.jar
# BUILD_TOOLS=34.0.0 picks a version; otherwise the newest installed.
if [[ -n ${BUILD_TOOLS:-} ]]; then BT=$SDK/build-tools/$BUILD_TOOLS
else BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true); fi
[[ -f $JAR ]] || { echo "error: $JAR missing - sdkmanager 'platforms;android-$MIN_SDK'" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: no build-tools in $SDK - sdkmanager 'build-tools;34.0.0'" >&2; exit 1; }

# d8 from build-tools 34 crashed on class files from JDK 21+; JDK 17 builds cleanly.
if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  for j in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk \
           "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [[ -n $j && -x $j/bin/javac ]]; then JAVA_HOME=$j; break; fi
  done
fi
[[ -x ${JAVA_HOME:-}/bin/javac ]] || { echo "error: no JDK 17 found - brew install openjdk@17" >&2; exit 1; }
export JAVA_HOME

KEYPASS=${KEYPASS:-broadsheet}
if [[ -n ${KEYSTORE:-} ]]; then
  # A key that was asked for and isn't there must not be replaced by a new one: an APK signed
  # with another key can't update the installed app.
  [[ -f $KEYSTORE ]] || { echo "error: KEYSTORE=$KEYSTORE does not exist" >&2; exit 1; }
else
  KEYSTORE=$REPO/release.keystore
  if [[ ! -f $KEYSTORE && -n ${CI:-} ]]; then
    echo "error: no signing key in CI (set KEYSTORE); refusing to make a new one" >&2
    exit 1
  fi
fi
if [[ ! -f $KEYSTORE ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias app -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=Broadsheet" >/dev/null
  echo "created signing key $KEYSTORE (back it up: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/broadsheet.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

ASSET_ARGS=()
[[ -d $REPO/assets ]] && ASSET_ARGS=(-A "$REPO/assets")
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" ${ASSET_ARGS[@]+"${ASSET_ARGS[@]}"} \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 30 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

ROOTS=(src)
for r in tv/src phone/src; do [[ -d $r ]] && ROOTS+=("$r"); done
find "${ROOTS[@]}" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out "$APK" "$WORK/aligned.apk"
"$BT/apksigner" verify "$APK"
printf 'built %s  %s bytes  version %s (%s)\n' "$APK" "$(wc -c < "$APK" | tr -d ' ')" "$VERSION_NAME" "$VERSION_CODE"
