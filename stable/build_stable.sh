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

JDK_BIN="${JDK_BIN:-/c/Program Files/Java/jdk-25.0.4.1/bin}"
SDK="${ANDROID_HOME:-/d/Android/sdk}"
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
D8="$(ls "$SDK"/build-tools/*/d8.bat 2>/dev/null | sort -V | tail -1 || echo "$SDK/build-tools/36.0.0/d8")"

# Windows-style paths for the Windows JDK/d8 executables.
win() { echo "$1" | sed -E 's#^/([a-zA-Z])/#\1:/#'; }
AJ_W="$(win "$ANDROID_JAR")"
PINE_JAR_W="$(win "$TOOLS/pine/classes.jar")"

BUILD="$HERE/build"
rm -rf "$BUILD/classes" "$BUILD/dex"; mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[build_stable] javac"
"$JDK_BIN/javac.exe" --release 8 -cp "$AJ_W;$PINE_JAR_W" -d "$(win "$BUILD/classes")" \
    "$HERE"/java/com/pancakeify/stable/*.java

echo "[build_stable] jar"
"$JDK_BIN/jar.exe" cf "$(win "$BUILD/ours.jar")" -C "$BUILD/classes" .

echo "[build_stable] d8 (+ Pine)"
"$D8" --release --min-api 21 --lib "$AJ_W" --output "$(win "$BUILD/dex")" \
    "$(win "$BUILD/ours.jar")" "$PINE_JAR_W"

cp "$BUILD/dex/classes.dex" "$TOOLS/pancake-stable.dex"
cp "$TOOLS/pine/jni/arm64-v8a/libpine.so" "$TOOLS/libpine.so"
echo "[build_stable] -> tools/pancake-stable.dex + tools/libpine.so"
