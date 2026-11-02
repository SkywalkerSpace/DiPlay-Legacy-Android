#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
elif [ -x /opt/homebrew/opt/openjdk/bin/javac ]; then
  JDK=/opt/homebrew/opt/openjdk
elif [ -x /usr/local/opt/openjdk/bin/javac ]; then
  JDK=/usr/local/opt/openjdk
else
  printf '%s\n' 'Set JAVA_HOME to a JDK.' >&2
  exit 1
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/dashboard-tests.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
"$JDK/bin/javac" --release 8 -encoding UTF-8 -d "$WORK" \
  "$ROOT/src/org/example/guangqidashboard/PlaylistCursor.java" \
  "$ROOT/src/org/example/guangqidashboard/GifSafety.java" \
  "$ROOT/src/org/example/guangqidashboard/PlaylistFiles.java" \
  "$ROOT/tests/PlaylistCursorTest.java" "$ROOT/tests/GifSafetyTest.java" "$ROOT/tests/PlaylistFilesTest.java"
"$JDK/bin/java" -cp "$WORK" org.example.guangqidashboard.PlaylistCursorTest
"$JDK/bin/java" -cp "$WORK" org.example.guangqidashboard.GifSafetyTest
"$JDK/bin/java" -cp "$WORK" org.example.guangqidashboard.PlaylistFilesTest "$WORK"
