#!/bin/bash
# build-apk.sh — signed release APK for manual sideload dist
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
BT="${ANDROID_BUILD_TOOLS:-36.0.0}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BT:$PATH"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ] || [ ! -f gradlew ]; then
  echo "[*] Bootstrapping gradle wrapper 8.13..."
  curl -sL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip
  rm -rf /tmp/gradle-w && mkdir -p /tmp/gradle-w && unzip -q /tmp/gradle.zip -d /tmp/gradle-w
  /tmp/gradle-w/gradle-8.13/bin/gradle wrapper --gradle-version 8.13
fi

# ---- Signing ----------------------------------------------------------------
# Credentials come from keystore.properties (project root, gitignored) or from
# HOTSHARE_* environment variables. There is deliberately NO default password:
# a shared fallback like "hotshare123" would ship a publicly-known signing key.
KS="keystore/hotshare.jks"; STORE_PASS=""; KEY_PASS=""; ALIAS="hotshare"
if [ -f keystore.properties ]; then
  KP_STORE=$(sed -n 's/^storePassword=//p' keystore.properties | head -n1 | tr -d '\r')
  KP_KEY=$(sed -n 's/^keyPassword=//p' keystore.properties | head -n1 | tr -d '\r')
  KP_ALIAS=$(sed -n 's/^keyAlias=//p' keystore.properties | head -n1 | tr -d '\r')
  KP_FILE=$(sed -n 's/^storeFile=//p' keystore.properties | head -n1 | tr -d '\r')
  STORE_PASS="$KP_STORE"; KEY_PASS="$KP_KEY"; ALIAS="${KP_ALIAS:-hotshare}"; KS="${KP_FILE:-$KS}"
fi
KS="${HOTSHARE_STORE_FILE:-$KS}"
STORE_PASS="${HOTSHARE_STORE_PASS:-$STORE_PASS}"
KEY_PASS="${HOTSHARE_KEY_PASS:-${KEY_PASS:-$STORE_PASS}}"
ALIAS="${HOTSHARE_KEY_ALIAS:-$ALIAS}"

if [ ! -f "$KS" ]; then
  if [ -n "$STORE_PASS" ]; then
    echo "[*] Keystore $KS missing — generating a SIDELOAD key with the password you supplied."
    echo "[!] Do NOT use a sideload key for Google Play. Create a separate upload key."
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
      -storepass "$STORE_PASS" -keypass "$KEY_PASS" -dname "CN=HotShare, OU=Dev, O=HotShare, C=US"
  else
    echo "[!] No signing key configured."
    echo "    1) keytool -genkeypair -keystore keystore/hotshare.jks -alias hotshare \\"
    echo "         -keyalg RSA -keysize 4096 -validity 10000"
    echo "    2) cp keystore.properties.example keystore.properties   # then fill it in"
    echo "    (keystore.properties is gitignored; never commit it)"
    exit 1
  fi
fi
if [ -z "$STORE_PASS" ] || [ -z "$KEY_PASS" ]; then
  echo "[!] No signing password provided for $KS."
  echo "    Set HOTSHARE_STORE_PASS / HOTSHARE_KEY_PASS, or add keystore.properties."
  exit 1
fi

echo "[*] Building release APK (targetSdk 36, R8 on)..."
./gradlew :app:assembleRelease
APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | grep -v unsigned | head -n 1)
if [ -z "$APK" ]; then echo "[!] No signed APK produced. Check the Gradle output above."; exit 1; fi
mkdir -p dist
cp "$APK" dist/HotShare-v1.apk
shasum -a 256 dist/HotShare-v1.apk > dist/SHA256.txt
"$ANDROID_HOME/build-tools/$BT/apksigner" verify --print-certs dist/HotShare-v1.apk | head -n 10
ls -lh dist/
VN=$(grep -o "versionName '[^']*'" app/build.gradle | head -n1 | cut -d"'" -f2)
VC=$(grep -o "versionCode [0-9]*" app/build.gradle | head -n1 | awk '{print $2}')
echo "{\"version\":\"${VN:-1.2.0}\",\"versionCode\":${VC:-3},\"apk\":\"HotShare-v1.apk\"}" > dist/version.json
cat dist/SHA256.txt
cat dist/version.json
