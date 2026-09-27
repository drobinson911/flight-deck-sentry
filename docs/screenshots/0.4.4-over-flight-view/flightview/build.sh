#!/usr/bin/env bash
# Build the Flight View stand-in APK without Gradle (aapt2 + javac + d8 + apksigner, debug key).
# usage: build.sh <python-with-pillow-and-numpy> <out-dir>
set -euo pipefail
PY=$1; OUT=$2; HERE=$(cd "$(dirname "$0")" && pwd)
BT=~/Android/Sdk/build-tools/36.0.0; JAR=~/Android/Sdk/platforms/android-34/android.jar
rm -rf "$OUT"; mkdir -p "$OUT/assets" "$OUT/classes" "$OUT/dex"
"$PY" "$HERE/render.py" "$OUT/assets/frame.png"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" -A "$OUT/assets"
javac -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" -d "$OUT/classes" $(find "$HERE/src" -name '*.java') 2>&1 | grep -v "warning" || true
"$BT/d8" --lib "$JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out "$OUT/flightview.apk" "$OUT/aligned.apk"
echo "$OUT/flightview.apk"
