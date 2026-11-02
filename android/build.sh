#!/bin/bash
# 部落冲突升级管理 APK 构建脚本(无需 Gradle:aapt2 + javac + d8 + apksigner)
set -e
cd "$(dirname "$0")"

SDK="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KEYSTORE=debug.keystore

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 编译资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 链接生成基础 APK 与 R.java"
"$BT/aapt2" link -o build/unsigned.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml -R build/res.zip \
  --java build/gen --auto-add-overlay

echo "[3/6] javac 编译"
find src build/gen -name "*.java" > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM" -d build/classes @build/sources.txt

echo "[4/6] d8 转换 dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
  --lib "$PLATFORM" --min-api 24 --output build/dex \
  $(find build/classes -name "*.class")

echo "[5/6] 打包 dex 并对齐"
(cd build/dex && jar uf "$(cd .. && pwd)/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "[6/6] 签名"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out "../部落冲突升级管理.apk" build/aligned.apk

echo "BUILD_OK -> ../部落冲突升级管理.apk"
