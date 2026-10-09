# Sightline — native (Capacitor)

Android/iOS shell for the Sightline screen-distance & blink guardian. The UI and
foreground monitoring live in `www/index.html` (also runs standalone in a
browser). The native layer adds **true background monitoring** and the robustness
fixes needed on tablets.

## Build — Android

```bash
cd sightline-native
npm install
npx cap add android
npm run patch:android      # native files + manifest + gradle (CameraX, ML Kit)
npx cap sync android
npx cap open android        # Run in Android Studio
```

The cloud build (GitHub Actions) does all of this automatically, plus bundles the
face model for offline use. `patch:android` is idempotent.

## What's new in this version

### Background monitoring while another app is fullscreen
The hard part — watching distance/blink while a child watches a **fullscreen
video** — is solved by moving detection into native Android:

- A **camera-type foreground service** (`BackgroundMonitorService`) + **CameraX**
  (headless) + **ML Kit Face Detection** (bundled, on-device, offline).
- Seamless hand-off: the rich WebView UI runs while the app is open; the instant
  it's backgrounded during a session, the WebView releases the camera and the
  native service takes over, firing the same loud alerts. On return, the app
  takes the camera back and folds the background breaches into the session report.
- Self-calibrates at hand-off from the last on-screen distance, so no second
  calibration is needed.
- Works because video apps don't use the camera, so there's no contention.

Toggle: **Configure → Keep monitoring in background**. On first enable it asks to
exempt Sightline from battery optimisation (needed on Samsung).

Trade-offs (by design): a persistent **green camera dot**, higher battery, and the
one-time Samsung battery setting.

### Tablet reliability (Galaxy Tab S10 Lite)
The earlier "won't work on the tablet" problem had two deeper causes, now fixed:

1. **Offline / CDN failure killed the whole app.** The face engine was loaded
   with a *static* `import` from a CDN; if the tablet was offline or the CDN was
   blocked, that import threw and **every button went dead**. It's now a
   **dynamic, offline-first load**: the engine + model are **bundled into the APK**
   under `www/vendor` (done automatically in the cloud build) and loaded with no
   network; a plain browser falls back to the CDN. A failed load no longer breaks
   the UI — it shows a clear message.
2. **Camera permission wasn't requested natively.** Relying on the WebView's
   `getUserMedia` to prompt doesn't reliably show the OS dialog on Samsung.
   `MainActivity` now requests **CAMERA + notifications on launch**.

Also kept from before: MediaPipe **GPU→CPU fallback**, responsive
landscape/large-screen layout, permissive `getUserMedia` fallback, and
large-screen + `resizeableActivity` in the manifest.

### Session reports + history
Unchanged and still on-device: end-of-session report (grade, averages, trend
charts, breach timeline, export) and a History screen. Background breaches are
merged into the session so the report is complete.

## Offline bundle (how it works)
The GitHub Actions workflow runs, after `npm install`:
```
cp node_modules/@mediapipe/tasks-vision/{wasm,vision_bundle.mjs}  www/vendor/tasks-vision/
curl -L -o www/vendor/face_landmarker.task  <mediapipe model URL>
```
then `cap sync` packs `www/` (including `vendor/`) into the APK. To do it locally,
run those two lines before `npx cap sync android`.

## Notes
- The alarm tone in the background is played natively (ML Kit service) via the
  alarm ringtone + vibration + a high-importance notification.
- iOS cannot use the camera in the background (OS limit), so background monitoring
  is Android-only; iOS keeps foreground monitoring + reports.
