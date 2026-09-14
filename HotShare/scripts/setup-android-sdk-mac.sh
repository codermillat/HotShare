#!/bin/bash
# setup-android-sdk-mac.sh — Install Android SDK cmdline-tools + platform-34 on macOS (arm64)
# Run once: bash scripts/setup-android-sdk-mac.sh
set -e
SDK_ROOT="$HOME/Library/Android/sdk"
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
mkdir -p "$SDK_ROOT/cmdline-tools"
cd /tmp
if [ ! -f cmdtools.zip ]; then
  echo "[1/5] Downloading cmdline-tools..."
  curl -L -o cmdtools.zip "$CMDTOOLS_URL"
fi
echo "[2/5] Unzipping..."
rm -rf "$SDK_ROOT/cmdline-tools/latest"
mkdir -p /tmp/cmdtools && unzip -q -o cmdtools.zip -d /tmp/cmdtools
mkdir -p "$SDK_ROOT/cmdline-tools/latest"
cp -R /tmp/cmdtools/cmdline-tools/* "$SDK_ROOT/cmdline-tools/latest/"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"
echo "[3/5] Accepting licenses..."
yes | sdkmanager --licenses || true
echo "[4/5] Installing platform-34, build-tools, platform-tools..."
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "[5/5] Done. Add to ~/.zshrc:"
echo "export ANDROID_HOME=$SDK_ROOT"
echo "export ANDROID_SDK_ROOT=$SDK_ROOT"
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH'
sdkmanager --list_installed
