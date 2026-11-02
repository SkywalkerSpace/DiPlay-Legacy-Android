#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-debug}"
if [ "$#" -gt 1 ]; then
  printf '%s\n' 'Only one build mode is accepted. Use --help for usage.' >&2
  exit 2
fi
case "$MODE" in
  -h|--help)
    printf '%s\n' \
      'Usage: bash build-diplayduo.sh [debug|release|standalone-debug|standalone-release]' \
      '' \
      'debug (default): installable APK signed with the local Android debug key.' \
      'release: requires the existing project release identity and all ANDROID_KEY* signing variables.' \
      'standalone-*: also validates the explicitly provisioned local offline-mfi identity and certificate.' \
      '' \
      'Authentication: prefer DIPLAY_AUTH_ASSETS_DIR; Gradle retains its existing local directory fallbacks.' \
      'SDK: ANDROID_HOME or ANDROID_SDK_ROOT, then local.properties, then the standard local SDK directory.' \
      'JDK: set JAVA_HOME if needed. Gradle uses the project-pinned JDK 25 daemon.' \
      'Debug and release share com.shihab.diplay.duo; different signatures cannot update one another.' \
      'Output: dist/DiPlayDuo-<version>-<mode>-<timestamp>.apk and its SHA-256 checksum.'
    exit 0
    ;;
  debug) TASK=assembleDebug; VARIANT=debug ;;
  release) TASK=assembleRelease; VARIANT=release ;;
  standalone-debug) TASK=assembleStandaloneDebug; VARIANT=debug ;;
  standalone-release) TASK=assembleStandaloneRelease; VARIANT=release ;;
  *) printf 'Unknown build mode: %s\nUse --help for usage.\n' "$MODE" >&2; exit 2 ;;
esac

# macOS /usr/bin/java may be a shim even when Gradle or Homebrew has a working JDK.
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$HOME"/.gradle/jdks/*/Contents/Home \
    "$HOME"/.gradle/jdks/*/jdk-*/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  [ -x "$JAVA_HOME/bin/java" ] || { printf 'Invalid JAVA_HOME: %s\n' "$JAVA_HOME" >&2; exit 1; }
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || { printf '%s\n' 'Install a JDK or set JAVA_HOME.' >&2; exit 1; }

if [ -n "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ "$ANDROID_HOME" != "$ANDROID_SDK_ROOT" ]; then
  printf '%s\n' 'ANDROID_HOME and ANDROID_SDK_ROOT must point to the same SDK.' >&2
  exit 1
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
  SDK="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$ROOT/local.properties" | tail -n 1)"
  # Java properties commonly escape macOS/Linux path spaces and colons.
  SDK="${SDK//\\ / }"
  SDK="${SDK//\\:/:}"
fi
if [ -z "$SDK" ]; then
  case "$(uname -s)" in
    Darwin) SDK="$HOME/Library/Android/sdk" ;;
    *) SDK="$HOME/Android/Sdk" ;;
  esac
fi
if [ ! -d "$SDK/platforms/android-37" ] && [ ! -d "$SDK/platforms/android-37.0" ]; then
  printf 'Android API 37 SDK not found in %s\n' "$SDK" >&2
  printf '%s\n' 'Install Android Command-line Tools, then run (review and accept the SDK license when prompted):' >&2
  printf 'sdkmanager --sdk_root="%s" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;25.2.9519653"\n' "$SDK" >&2
  exit 1
fi
TOOLS="$SDK/build-tools/37.0.0"
[ -x "$TOOLS/aapt" ] && [ -x "$TOOLS/apksigner" ] || {
  printf '%s\n' 'Install build-tools;37.0.0 to verify the APK.' >&2
  exit 1
}
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

cd "$ROOT"
printf 'Building DiPlayDuo (%s), SDK: %s\n' "$MODE" "$SDK"
bash "$ROOT/gradlew" --console=plain ":mobile:$TASK"

SOURCE="$ROOT/mobile/build/outputs/apk/$VARIANT/mobile-$VARIANT.apk"
[ -s "$SOURCE" ] || { printf 'APK not found: %s\n' "$SOURCE" >&2; exit 1; }
BADGING="$("$TOOLS/aapt" dump badging "$SOURCE")"
printf '%s\n' "$BADGING" | grep "^package: name='com.shihab.diplay.duo'" >/dev/null || {
  printf '%s\n' 'Wrong application ID: refusing to export an APK that might replace the original app.' >&2
  exit 1
}
printf '%s\n' "$BADGING" | grep "^application-label:'DiPlayDuo'" >/dev/null || {
  printf '%s\n' 'Wrong application label: expected DiPlayDuo.' >&2
  exit 1
}
"$TOOLS/apksigner" verify --verbose "$SOURCE"
VERSION="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
[ -n "$VERSION" ] || { printf '%s\n' 'Could not read APK version.' >&2; exit 1; }
mkdir -p "$ROOT/dist"
OUTPUT="$ROOT/dist/DiPlayDuo-$VERSION-$MODE-$(date +%Y%m%d-%H%M%S)-$$.apk"
cp "$SOURCE" "$OUTPUT"
if command -v shasum >/dev/null; then
  (cd "$ROOT/dist" && shasum -a 256 "$(basename "$OUTPUT")") > "$OUTPUT.sha256"
else
  (cd "$ROOT/dist" && sha256sum "$(basename "$OUTPUT")") > "$OUTPUT.sha256"
fi
printf '\nAPK: %s\nChecksum: %s.sha256\nPackage: com.shihab.diplay.duo\n' "$OUTPUT" "$OUTPUT"
case "$MODE" in
  debug|release)
    printf '%s\n' 'Note: ordinary builds do not require CarPlay authentication assets. Without valid assets, standalone CarPlay authentication is unavailable.'
    ;;
esac
