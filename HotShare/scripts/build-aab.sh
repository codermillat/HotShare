#!/bin/bash
# build-aab.sh — signed Android App Bundle for Google Play upload
# Usage: bash scripts/build-aab.sh
# Requires keystore.properties (or HOTSHARE_* env vars) — see build-apk.sh header.
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
BT="${ANDROID_BUILD_TOOLS:-36.0.0}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BT:$PATH"

# ---- Signing: keystore.properties wins, env vars are the fallback -----------
KS="keystore/hotshare.jks"; STORE_PASS=""; KEY_PASS=""; ALIAS="hotshare"
if [ -f keystore.properties ]; then
  STORE_PASS=$(sed -n 's/^storePassword=//p' keystore.properties | head -n1 | tr -d '\r')
  KEY_PASS=$(sed -n 's/^keyPassword=//p' keystore.properties | head -n1 | tr -d '\r')
  ALIAS=$(sed -n 's/^keyAlias=//p' keystore.properties | head -n1 | tr -d '\r')
  KS=$(sed -n 's/^storeFile=//p' keystore.properties | head -n1 | tr -d '\r')
fi
KS="${HOTSHARE_STORE_FILE:-$KS}"
STORE_PASS="${HOTSHARE_STORE_PASS:-$STORE_PASS}"
KEY_PASS="${HOTSHARE_KEY_PASS:-${KEY_PASS:-$STORE_PASS}}"
ALIAS="${HOTSHARE_KEY_ALIAS:-$ALIAS}"

if [ ! -f "$KS" ] || [ -z "$STORE_PASS" ]; then
  echo "[!] Play upload key not configured (need $KS + password)."
  echo "    Create a dedicated UPLOAD key (never the sideload key):"
  echo "      keytool -genkeypair -keystore keystore/hotshare-upload.jks -alias hotshare \\"
  echo "        -keyalg RSA -keysize 4096 -validity 10000"
  echo "    Then: cp keystore.properties.example keystore.properties  # and fill it in"
  exit 1
fi

VN=$(grep -o "versionName '[^']*'" app/build.gradle | head -n1 | cut -d"'" -f2)
VC=$(grep -o "versionCode [0-9]*" app/build.gradle | head -n1 | awk '{print $2}')

echo "[*] Building signed App Bundle (targetSdk 36, R8 on)..."
./gradlew :app:bundleRelease

AAB=$(ls -t app/build/outputs/bundle/release/*.aab 2>/dev/null | head -n 1)
if [ -z "$AAB" ]; then echo "[!] No .aab produced — check the Gradle output above."; exit 1; fi
mkdir -p dist
OUT="dist/HotShare-${VN:-1.0.0}(${VC:-1}).aab"
cp "$AAB" "$OUT"
shasum -a 256 "$OUT" > "${OUT}.sha256"

echo "[*] Verifying bundle signature (jarsigner)..."
jarsigner -verify -certs "$OUT" >/dev/null && echo "    signature OK"

echo "[*] Native libs in bundle:"
unzip -l "$OUT" | grep -E 'lib/.*\.so' || echo "    [!] no native libs found — VPN will fall back to system-proxy mode"

echo "[*] Output:"
ls -lh "$OUT"
cat "${OUT}.sha256"
echo
echo "Next: Play Console -> Test and release -> Production -> Create new release -> upload $OUT"
echo "      Play App Signing will re-sign it with the app signing key; this key is only the UPLOAD key."
echo "      Reminder: targetSdk 36 is mandatory for submissions since 2026-08-31."
