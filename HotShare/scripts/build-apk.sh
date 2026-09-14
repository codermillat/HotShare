#!/bin/bash
# build-apk.sh — signed release APK for manual sideload dist
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ] || [ ! -f gradlew ]; then
  echo "[*] Bootstrapping gradle wrapper 8.7..."
  curl -sL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  rm -rf /tmp/gradle-w && mkdir -p /tmp/gradle-w && unzip -q /tmp/gradle.zip -d /tmp/gradle-w
  /tmp/gradle-w/gradle-8.7/bin/gradle wrapper --gradle-version 8.7
fi
KS="keystore/hotshare.jks"
# Secrets via env (never commit): HOTSHARE_STORE_PASS / HOTSHARE_KEY_PASS.
# Falls back to a local dev password + interactive prompt in CI.
STORE_PASS="${HOTSHARE_STORE_PASS:-}"
KEY_PASS="${HOTSHARE_KEY_PASS:-}"
if [ -z "$STORE_PASS" ]; then
  if [ -f "$KS" ]; then
    echo "[!] HOTSHARE_STORE_PASS not set — using local dev default. Set env vars for real releases."
    STORE_PASS="hotshare123"
    KEY_PASS="hotshare123"
  else
    echo "[*] Generating local keystore (sideload, keep $KS safe + backed up)..."
    mkdir -p keystore
    if [ -z "$KEY_PASS" ]; then KEY_PASS="$STORE_PASS"; fi
    if [ -z "$STORE_PASS" ]; then STORE_PASS="hotshare123"; KEY_PASS="hotshare123"; fi
    keytool -genkeypair -keystore "$KS" -alias hotshare -keyalg RSA -keysize 3072 -validity 9125 \
      -storepass "$STORE_PASS" -keypass "$KEY_PASS" -dname "CN=HotShare, OU=Dev, O=HotShare, C=US"
  fi
fi
if [ -z "$KEY_PASS" ]; then KEY_PASS="$STORE_PASS"; fi
echo "[*] Building release APK..."
./gradlew :app:assembleRelease -Pandroid.injected.signing.store.file="$PWD/$KS" \
  -Pandroid.injected.signing.store.password="$STORE_PASS" \
  -Pandroid.injected.signing.key.alias=hotshare \
  -Pandroid.injected.signing.key.password="$KEY_PASS"
APK=$(ls -t app/build/outputs/apk/release/*.apk | head -n 1)
mkdir -p dist
cp "$APK" dist/HotShare-v1.apk
shasum -a 256 dist/HotShare-v1.apk > dist/SHA256.txt
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs dist/HotShare-v1.apk | head -n 10
ls -lh dist/
VN=$(grep -o "versionName '[^']*'" app/build.gradle | head -n1 | cut -d"'" -f2)
VC=$(grep -o "versionCode [0-9]*" app/build.gradle | head -n1 | awk '{print $2}')
echo "{\"version\":\"${VN:-1.2.0}\",\"versionCode\":${VC:-3},\"apk\":\"HotShare-v1.apk\"}" > dist/version.json
cat dist/SHA256.txt
cat dist/version.json
