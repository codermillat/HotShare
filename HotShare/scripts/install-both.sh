#!/bin/bash
# install-both.sh — install sideload APK to two adb devices (host + client)
set -e
APK="${1:-dist/HotShare-v1.apk}"
adb devices -l
IDS=($(adb devices | awk 'NR>1 && $2=="device"{print $1}'))
if [ ${#IDS[@]} -eq 0 ]; then echo "No adb devices. Enable USB debugging + plug both phones."; exit 1; fi
for ID in "${IDS[@]}"; do
  echo "==> Installing to $ID"
  adb -s "$ID" install -r "$APK"
done
echo "Done: ${#IDS[@]} device(s)."
