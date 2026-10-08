#!/usr/bin/env bash
# Build the STABLE-layer dex (pancake-stable.dex) without Gradle: javac + d8, bundling Pine.
# Produces tools/pancake-stable.dex and stages tools/libpine.so for the patcher.
#
# Requires: JDK (javac/jar), Android SDK build-tools (d8), a platform android.jar,
#           tools/pine/classes.jar + tools/pine/jni/arm64-v8a/libpine.so (from the Pine AAR).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TOOLS="$ROOT/tools"

if [ "$(uname -s)" = "Linux" ]; then
  # Linux: JDK 'javac'/'java' from PATH (or $JDK_BIN); d8 runs from the SDK's d8.jar.
  SDK="${ANDROID_HOME:-/mnt/data/Android/sdk}"
  JAVAC="${JDK_BIN:+$JDK_BIN/}javac"; JAR="${JDK_BIN:+$JDK_BIN/}jar"; JAVA="${JDK_BIN:+$JDK_BIN/}java"
  ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
  D8_JAR="$(ls "$SDK"/build-tools/*/lib/d8.jar | sort -V | tail -1)"
  win() { echo "$1"; }; SEP=":"
  d8() { "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 "$@"; }
else
  JDK_BIN="${JDK_BIN:-/c/Program Files/Java/jdk-25.0.4.1/bin}"
  SDK="${ANDROID_HOME:-/d/Android/sdk}"
  JAVAC="$JDK_BIN/javac.exe"; JAR="$JDK_BIN/jar.exe"
  ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
  D8_BAT="$(ls "$SDK"/build-tools/*/d8.bat 2>/dev/null | sort -V | tail -1 || echo "$SDK/build-tools/36.0.0/d8")"
  win() { echo "$1" | sed -E 's#^/([a-zA-Z])/#\1:/#'; }; SEP=";"
  d8() { "$D8_BAT" "$@"; }
fi

AJ_W="$(win "$ANDROID_JAR")"
PINE_JAR_W="$(win "$TOOLS/pine/classes.jar")"

BUILD="$HERE/build"
rm -rf "$BUILD/classes" "$BUILD/dex"; mkdir -p "$BUILD/classes" "$BUILD/dex"

# Spicy Lyrics API key: read from spicy_lyrics_secret_key.txt (gitignored) and baked into a
# generated class. It ends up inside the APK -- do not share builds made with a real key.
GEN="$BUILD/gen/com/pancakeify/stable"
rm -rf "$BUILD/gen"; mkdir -p "$GEN"
SPICY_KEY=""
[ -f "$ROOT/spicy_lyrics_secret_key.txt" ] && SPICY_KEY="$(tr -d '[:space:]' < "$ROOT/spicy_lyrics_secret_key.txt")"
printf 'package com.pancakeify.stable;\n\nfinal class SpicyKey {\n    static final String KEY = "%s";\n    private SpicyKey() {}\n}\n' "$SPICY_KEY" > "$GEN/SpicyKey.java"

echo "[build_stable] javac"
"$JAVAC" --release 8 -cp "$AJ_W$SEP$PINE_JAR_W" -d "$(win "$BUILD/classes")" \
    "$HERE"/java/com/pancakeify/stable/*.java "$(win "$GEN/SpicyKey.java")"

echo "[build_stable] jar"
"$JAR" cf "$(win "$BUILD/ours.jar")" -C "$BUILD/classes" .

echo "[build_stable] d8 (+ Pine)"
d8 --release --min-api 21 --lib "$AJ_W" --output "$(win "$BUILD/dex")" \
    "$(win "$BUILD/ours.jar")" "$PINE_JAR_W"

cp "$BUILD/dex/classes.dex" "$TOOLS/pancake-stable.dex"
cp "$TOOLS/pine/jni/arm64-v8a/libpine.so" "$TOOLS/libpine.so"
echo "[build_stable] -> tools/pancake-stable.dex + tools/libpine.so"
