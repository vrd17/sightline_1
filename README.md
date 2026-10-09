# Sightline — native (Capacitor)

Native Android/iOS shell for the Sightline screen-distance guardian. The full UI
and eye-tracking logic live in `www/index.html` (the same file also works
standalone in a browser), wrapped by Capacitor so breach alerts can use a
**native high-importance notification channel with sound**, and so a session can
keep running via a **foreground service** while other apps are in front.

On every breach the app fires a loud alarm tone + strong vibration + banner, and
(natively) a sound-on notification:

- **Distance breach** — face closer than the threshold for the distance-alert delay.
- **Blink breach** — blink rate below the safe rate for the blink-alert delay.

```
sightline-native/
├── www/index.html            <- the app (camera + MediaPipe + all logic)
├── capacitor.config.json
├── package.json
├── scripts/                  <- patch-android.sh + inject_manifest.py
└── native/
    ├── android/
    │   ├── ForegroundPlugin.java            <- Capacitor.Plugins.Foreground
    │   ├── SightlineForegroundService.java  <- persistent "monitoring" service
    │   ├── MainActivity.java                <- registers the plugin
    │   └── ic_stat_eye.xml
    └── ios/README-ios.md
```

## Prerequisites
- Node.js 18+
- Android: Android Studio + JDK 17
- iOS: macOS + Xcode (+ CocoaPods)

## Build — Android

```bash
cd sightline-native
npm install
npx cap add android
npm run patch:android      # copies native files + patches the manifest
npx cap sync android
npx cap open android        # Run in Android Studio
```

`patch:android` is idempotent — safe to re-run after any `cap sync`. It adds the
camera / notification / **foreground-service** / wake-lock permissions, declares
the foreground service, and turns on **large-screen + resizeable** support
(tablets and split-screen). The cloud GitHub-Actions build runs it automatically.

## Build — iOS

```bash
npm install
npx cap add ios
npx cap sync ios
npx cap open ios
```

In Xcode add to `Info.plist`:
- `NSCameraUsageDescription` — "Sightline measures how far your face is from the screen. Video never leaves your device."

## What's new in this version

### 1. Background sessions + foreground service
A session now starts an Android **foreground service** (persistent
"Sightline is guarding your eyes" notification), so timers, state and breach
notifications survive the app being backgrounded. Toggle it under
**Configure → Keep running in background**.

**Important honest limit:** on Android the camera is suspended whenever the app is
not visible — this is an OS privacy rule, not a bug, and it applies to every
WebView app. So distance/blink **analysis** only runs while Sightline is on
screen. The supported way to "monitor while using another app" is **split-screen**:
put the learning app in one pane and Sightline in the other. Sightline stays
visible, the camera keeps working, and alerts keep firing. The large-screen /
resizeable manifest flags this version adds are what make that smooth on a tablet.
When Sightline is fully backgrounded it pauses analysis and posts a notification
telling the user to bring it back (or use split-screen). True always-on background
analysis would require moving the camera + ML pipeline to native CameraX — noted
as a future step in the project docs.

### 2. Session reports + history
Every session (≥5s) is scored and saved on-device. At the end you get a detailed
report — grade, averages, distance-over-time and blink-over-time trend charts, a
breach timeline, and a takeaway — plus an **Export** (share/download) button. The
clock icon opens **History**: all past sessions with sparklines, tap to reopen any
report. Stored in `localStorage` (`sightline_history`, capped at 50 sessions); no
data leaves the device.

### 3. Tablet compatibility (e.g. Galaxy Tab S10 Lite)
- **MediaPipe GPU→CPU fallback.** The most common reason the app failed to start
  on tablets was the GPU delegate failing to initialise in the WebView; it now
  retries on CPU automatically.
- **Responsive layout** — a two-column layout on wide/landscape screens, larger
  gauge, and no fixed phone width.
- **Camera constraint fallback** — if `facingMode:'user'` + resolution is rejected,
  it retries with a permissive `{ video: true }`.
- **Manifest** — large-screen support + `resizeableActivity` (also enables
  split-screen), front camera marked non-required so it installs broadly.

## What works where

| Capability | Web | Android (native) | iOS (native) |
|---|---|---|---|
| Distance calibration / config | yes | yes | yes |
| Blink-rate safe config | yes | yes | yes |
| Session duration + auto-end | yes | yes | yes |
| Loud alert (distance + blink) | tone+vibrate (foreground) | tone + sound notification | tone + sound notification |
| Session report + history | yes | yes | yes |
| Keep session alive in background | — (tab must stay open) | yes (foreground service) | limited (OS suspends) |
| Monitor while another app is in front | split-screen only | split-screen only | split-screen only |

## Notes
- **Offline MediaPipe:** the app loads the face model from a CDN on first run. To
  ship fully offline, download the `tasks-vision` wasm bundle +
  `face_landmarker.task` into `www/` and repoint the two URLs in `index.html`.
- **Background loudness:** the WebAudio tone needs the app foregrounded. When
  backgrounded, the native notification (`sound: 'default'` on a high-importance
  channel) is what alerts the user.
- **Foreground service type** is `dataSync` (declared in the manifest) — it keeps
  the process alive and shows the notification; it does not itself grant
  background camera frames (see the honest limit above).
