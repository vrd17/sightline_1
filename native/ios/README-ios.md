# iOS notes

The same `www/index.html` runs inside the iOS WKWebView. Camera + MediaPipe +
all monitoring logic work unchanged.

## Alerts
- In-app: a WebAudio alarm tone + vibration fire on every breach while the app
  is foregrounded.
- Background: `@capacitor/local-notifications` delivers a sound-on notification.
  On first session the app calls `requestPermissions()`.

Add to Info.plist:
- `NSCameraUsageDescription` (required for the front camera)

There is no screen-locking on any platform in this build — breaches simply
alert loudly.
