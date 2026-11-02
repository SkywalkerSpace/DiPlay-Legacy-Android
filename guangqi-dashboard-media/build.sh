#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
elif [ -x /opt/homebrew/opt/openjdk/bin/javac ]; then
  JDK=/opt/homebrew/opt/openjdk
elif [ -x /usr/local/opt/openjdk/bin/javac ]; then
  JDK=/usr/local/opt/openjdk
else
  printf '%s\n' 'JDK not found. Install openjdk or set JAVA_HOME.' >&2
  exit 1
fi
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
command -v apktool >/dev/null || { printf '%s\n' 'Install: brew install apktool' >&2; exit 1; }
# Reuse only the compile toolchain from ; no source, assets or key are packaged.
ANDROID_JAR="${ANDROID_JAR:-$ROOT/.tools/android-23/android.jar}"
D8_JAR="${D8_JAR:-$ROOT/.tools/r8-8.3.37/r8.jar}"
if [ ! -f "$ANDROID_JAR" ] || [ ! -f "$D8_JAR" ]; then
  printf '%s\n' 'Set ANDROID_JAR (API 23) and D8_JAR, or fetch tools in ./tools first.' >&2
  exit 1
fi
bash "$ROOT/tests/run.sh"
STAMP="$(date +%Y%m%d-%H%M%S)-$$"
WORK="$ROOT/build/$STAMP"
PROJECT="$WORK/apktool-project"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/framework" "$PROJECT" "$ROOT/dist"
find "$ROOT/src" -name '*.java' -print > "$WORK/sources.txt"
printf '%s\n' '[1/5] Compile Java against Android 6 / API 23'
javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
printf '%s\n' '[2/5] Compile DEX (min API 23, no native ABI dependency)'
java -cp "$D8_JAR" com.android.tools.r8.D8 --debug --min-api 23 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes.jar"
cp "$WORK/dex/classes.dex" "$PROJECT/classes.dex"
cp "$ROOT/AndroidManifest.xml" "$PROJECT/AndroidManifest.xml"
python3 -I "$ROOT/tools/write-apktool-config.py" "$PROJECT/apktool.yml"
printf '%s\n' '[3/5] Package with apktool'
apktool b "$PROJECT" --frame-path "$WORK/framework" -o "$WORK/guangqi-dashboard-media-unsigned.apk"
KEYSTORE="$ROOT/.keys/debug.p12"
mkdir -p "$ROOT/.keys"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass android -keypass android \
    -alias dashboard-debug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Dashboard Media Debug,OU=Development,O=Local Test,C=ZZ'
fi
APK="$ROOT/dist/guangqi-dashboard-media-0.1.0-debug-$STAMP.apk"
printf '%s\n' '[4/5] Sign with Android 6 compatible v1 signature'
jarsigner -keystore "$KEYSTORE" -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$APK" "$WORK/guangqi-dashboard-media-unsigned.apk" dashboard-debug
printf '%s\n' '[5/5] Verify signature, archive and ABI independence'
jarsigner -verify "$APK"
unzip -t "$APK"
python3 -I "$ROOT/tests/check-apk.py" "$APK"
apktool d --only-manifest -s "$APK" --frame-path "$WORK/framework" -o "$WORK/manifest-check"
python3 -I "$ROOT/tests/check-manifest.py" "$WORK/manifest-check"
shasum -a 256 "$APK"
printf '\nAPK: %s\nBuild intermediates: %s\n' "$APK" "$WORK"
