#!/usr/bin/env bash
# Copies native sources + notification icon into the generated Android project and
# patches the manifest (permissions, camera foreground service, large-screen) and
# app/build.gradle (CameraX + ML Kit). Idempotent — safe to re-run.
# Run AFTER `npx cap add android`:  npm run patch:android
set -e
PKG_DIR="android/app/src/main/java/com/sightline/app"
RES_DRAWABLE="android/app/src/main/res/drawable"
MANIFEST="android/app/src/main/AndroidManifest.xml"
APP_GRADLE="android/app/build.gradle"

if [ ! -d "android" ]; then
  echo "!! android/ not found. Run 'npx cap add android' first."; exit 1
fi

echo ">> Copying Java sources -> $PKG_DIR"
mkdir -p "$PKG_DIR" "$RES_DRAWABLE"
# remove any stale service from a previous version
rm -f "$PKG_DIR/ForegroundPlugin.java" "$PKG_DIR/SightlineForegroundService.java"
cp native/android/BackgroundMonitorService.java "$PKG_DIR/"
cp native/android/BackgroundMonitorPlugin.java  "$PKG_DIR/"
cp native/android/MainActivity.java             "$PKG_DIR/"
cp native/android/ic_stat_eye.xml               "$RES_DRAWABLE/"

echo ">> Patching AndroidManifest.xml"
python3 scripts/inject_manifest.py "$MANIFEST"

echo ">> Patching app/build.gradle (CameraX + ML Kit)"
python3 scripts/inject_gradle.py "$APP_GRADLE"

echo ">> Done. Next:  npx cap sync android  &&  npx cap open android"
