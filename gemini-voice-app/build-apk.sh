#!/bin/bash
# Gemini 9420 语音 APK (与 AirPlay/DLNA 独立)
set -euo pipefail
APP="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$APP")"
OUT="$APP/build"
ANDROID_JAR=/usr/share/java/com.android.android-23.jar
DX_JAR=/usr/share/java/com.android.dx.jar

rm -rf "$OUT"; mkdir -p "$OUT/classes"

javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$APP/src" -name '*.java') 2> "$OUT/javac.log" || { cat "$OUT/javac.log"; exit 1; }
( cd "$OUT" && java -cp "$DX_JAR" com.android.dx.command.Main --dex --output=classes.dex classes )

aapt package -f -M "$APP/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
( cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null )
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$PROJ/autostart-app/debug.keystore"
[ -f "$KS" ] || KS="$APP/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=gemini9420,O=local,C=CN"
mkdir -p "$PROJ/dist"
java -jar /usr/lib/android-sdk/build-tools/debian/apksigner.jar sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$PROJ/dist/GeminiVoice9420.apk" "$OUT/aligned.apk"
ls -l "$PROJ/dist/GeminiVoice9420.apk"
