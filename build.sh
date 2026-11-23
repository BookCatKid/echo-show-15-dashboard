#!/bin/bash
set -euo pipefail
TASK_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$SDK_DIR/build-tools/34.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-30/android.jar"
JAVA_DIR="/opt/homebrew/opt/openjdk@21"
R8_JAR="/Users/simon/security-audit-skill/echo-show-15/run-4/tools/r8-8.3.37.jar"
LIBS=$(printf ":%s" "$TASK_DIR"/lib/*.jar)
mkdir -p "$TASK_DIR/build/classes" "$TASK_DIR/build/dex" "$TASK_DIR/signing"
if [ ! -f "$TASK_DIR/signing/dashboard.jks" ]; then
 "$JAVA_DIR/bin/keytool" -genkeypair -keystore "$TASK_DIR/signing/dashboard.jks" -storepass android -keypass android -alias dashboard -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Echo Dashboard' >/dev/null 2>&1
 chmod 600 "$TASK_DIR/signing/dashboard.jks"
fi
find "$TASK_DIR/build/classes" -name '*.class' -delete
find "$TASK_DIR/src" -name '*.java' -print0 | xargs -0 "$JAVA_DIR/bin/javac" --release 8 -classpath "$ANDROID_JAR$LIBS" -d "$TASK_DIR/build/classes"
find "$TASK_DIR/build/classes" -name '*.class' -print0 | xargs -0 "$JAVA_DIR/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 28 --lib "$ANDROID_JAR" --output "$TASK_DIR/build/dex" "$TASK_DIR"/lib/*.jar
"$BUILD_TOOLS/aapt2" compile --dir "$TASK_DIR/res" -o "$TASK_DIR/build/resources.zip"
"$BUILD_TOOLS/aapt2" link -R "$TASK_DIR/build/resources.zip" -I "$ANDROID_JAR" --manifest "$TASK_DIR/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 28 --version-code 2 --version-name 2.0 -o "$TASK_DIR/build/unsigned.apk"
(cd "$TASK_DIR/build/dex" && zip -q "$TASK_DIR/build/unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$TASK_DIR/build/unsigned.apk" "$TASK_DIR/build/aligned.apk"
JAVA_HOME="$JAVA_DIR" "$BUILD_TOOLS/apksigner" sign --ks "$TASK_DIR/signing/dashboard.jks" --ks-pass pass:android --key-pass pass:android --out "$TASK_DIR/build/dashboard.apk" "$TASK_DIR/build/aligned.apk"
JAVA_HOME="$JAVA_DIR" "$BUILD_TOOLS/apksigner" verify "$TASK_DIR/build/dashboard.apk"
echo "Built $TASK_DIR/build/dashboard.apk"
