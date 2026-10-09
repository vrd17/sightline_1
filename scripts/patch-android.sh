#!/usr/bin/env bash
# Copies native sources + notification icon into the generated Android project and
# patches the manifest (permissions, large-screen/resizeable, foreground service).
# Idempotent — safe to re-run. Run AFTER `npx cap add android`: npm run patch:android
set -e
PKG_DIR="android/app/src/main/java/com/sightline/app"
RES_DRAWABLE="android/app/src/main/res/drawable"
MANIFEST="android/app/src/main/AndroidManifest.xml"

if [ ! -d "android" ]; then
  echo "!! android/ not found. Run 'npx cap add android' first."; exit 1
fi

echo ">> Copying Java sources -> $PKG_DIR"
mkdir -p "$PKG_DIR" "$RES_DRAWABLE"
cp native/android/ForegroundPlugin.java            "$PKG_DIR/"
cp native/android/SightlineForegroundService.java  "$PKG_DIR/"
cp native/android/MainActivity.java                "$PKG_DIR/"
cp native/android/ic_stat_eye.xml                  "$RES_DRAWABLE/"

echo ">> Patching AndroidManifest.xml"
python3 scripts/inject_manifest.py "$MANIFEST"

echo ">> Done. Next:  npx cap sync android  &&  npx cap open android"
