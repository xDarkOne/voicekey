#!/usr/bin/env bash
# Builds VoiceKey without Gradle: aapt2 → javac → d8 → zipalign → apksigner.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-$HOME/.local/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-30/android.jar
OUT=build

rm -rf "$OUT" && mkdir -p assets "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/libs" "$OUT/native/lib/arm64-v8a"

# Offline speech: Vosk and the JNA it runs on, as Maven Central ships them (libs/*.aar).
# Their classes go into the dex, their arm64 libraries into the APK (the projector is arm64).
for aar in libs/*.aar; do
    name=$(basename "$aar" .aar)
    unzip -p "$aar" classes.jar > "$OUT/libs/$name.jar"
    unzip -qjo "$aar" 'jni/arm64-v8a/*.so' -d "$OUT/native/lib/arm64-v8a"
done
LIBCP=$(ls "$OUT"/libs/*.jar | paste -sd:)

"$BT/aapt2" compile --dir res -o "$OUT/res"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" -A assets \
    -o "$OUT/unsigned.apk" "$OUT"/res/*.flat

# A javac error must stop the build: an APK without its classes kills the service on the device.
"$JAVA_HOME/bin/javac" --release 8 -nowarn -Xlint:-options -classpath "$JAR:$LIBCP" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class') "$OUT"/libs/*.jar
(cd "$OUT" && zip -q -j unsigned.apk classes*.dex)
(cd "$OUT/native" && zip -q -r ../unsigned.apk lib)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# The key stays next to the project: updates must be signed with the same one.
KS=voicekey.keystore
[ -f "$KS" ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias voicekey \
    -keyalg RSA -keysize 2048 -validity 36500 -storepass voicekey -keypass voicekey \
    -dname "CN=VoiceKey" >/dev/null
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:voicekey --out VoiceKey.apk "$OUT/aligned.apk"
# Every class of src/ must be in the APK.
for d in "$OUT"/classes*.dex; do "$BT/dexdump" "$d"; done > "$OUT/dex.txt" 2>/dev/null
for c in $(find src -name '*.java' | sed 's|^src/||; s|\.java$||'); do
    grep -qF "L$c;" "$OUT/dex.txt" || { echo "missing class $c" >&2; exit 1; }
done
echo "built $(pwd)/VoiceKey.apk"
