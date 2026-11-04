#!/usr/bin/env bash
# 把原生安卓工程 (android/) 打包成 APK。
# 工程是标准 Gradle 结构, 有网环境可直接用 Android Studio / ./gradlew 打开构建;
# 本脚本是无 Gradle 时的离线等价流程 (aapt2 -> javac -> d8 -> zipalign -> apksigner),
# 产物与 Gradle 构建一致 (仅框架 API, 无第三方依赖)。
# 前置: JDK 17、Android SDK (build-tools 34 / platform 34), 路径可用环境变量覆盖:
#   JAVA_HOME, ANDROID_SDK (默认 ~/tools/jdk-17.0.21+9 与 ~/tools/android-sdk)
# 签名 (口令只从环境变量读, 不要写进仓库):
#   WELLTRACK_KEYSTORE   keystore 路径, 不存在时按 WELLTRACK_KS_PASS 生成
#   WELLTRACK_KS_PASS    keystore 口令
# 用法: WELLTRACK_KEYSTORE=~/welltrack.keystore WELLTRACK_KS_PASS=*** bash scripts/build-apk.sh [输出APK路径]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/tools/android-sdk}}}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
MAIN="$ROOT/android/app/src/main"
OUT="${TMPDIR:-/tmp}/welltrack-apk-build"
APK_OUT="${1:-$ROOT/dist/welltrack.apk}"

: "${WELLTRACK_KEYSTORE:?请设置 WELLTRACK_KEYSTORE (keystore 路径)}"
: "${WELLTRACK_KS_PASS:?请设置 WELLTRACK_KS_PASS (keystore 口令)}"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$MAIN/res" -o "$OUT/compiled.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$JAR" \
  --manifest "$MAIN/AndroidManifest.xml" \
  -R "$OUT/compiled.zip" --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 10 --version-name 2.8

echo "[3/6] javac"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$JAR" -d "$OUT/classes" \
  $(find "$MAIN/java" "$OUT/gen" -name "*.java")

echo "[4/6] d8 dex"
"$BT/d8" --lib "$JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "[5/6] pack + align"
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-with-dex.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"

echo "[6/6] sign"
if [ ! -f "$WELLTRACK_KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$WELLTRACK_KEYSTORE" -alias welltrack \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass "$WELLTRACK_KS_PASS" -keypass "$WELLTRACK_KS_PASS" \
    -dname "CN=WellTrack, O=Personal, C=CN"
fi
mkdir -p "$(dirname "$APK_OUT")"
"$BT/apksigner" sign --ks "$WELLTRACK_KEYSTORE" --ks-pass "pass:$WELLTRACK_KS_PASS" \
  --key-pass "pass:$WELLTRACK_KS_PASS" --out "$APK_OUT" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$APK_OUT" | head -3
"$BT/aapt2" dump badging "$APK_OUT" | head -4
ls -lh "$APK_OUT"
