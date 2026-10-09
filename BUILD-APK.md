# Getting an installable APK

The APK has to be compiled with the Android SDK. Pick whichever route fits.

---

## Route A — Cloud build, no local setup (recommended)

Uses the included GitHub Actions workflow. You need a free GitHub account.

1. Create a new **empty** GitHub repository.
2. Push this project to it:
   ```bash
   cd sightline-native
   git init && git add . && git commit -m "Sightline"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab. The "Build Android APK" workflow runs
   automatically on push (or click **Run workflow**).
4. When it finishes (~4–6 min), open the run and download the
   **sightline-debug-apk** artifact. Inside is `app-debug.apk`.
5. Copy it to your phone and install (you'll enable "Install unknown apps"
   for your file manager/browser the first time).

That's it — no Android Studio, no SDK on your machine.

---

## Route B — Local, with Android Studio (GUI)

1. Install Android Studio (bundles the SDK + JDK).
2. ```bash
   cd sightline-native
   npm install
   npx cap add android
   npm run patch:android
   npx cap sync android
   npx cap open android
   ```
3. In Android Studio: **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
4. Click **locate** in the popup → `app-debug.apk`. Send it to your phone.

---

## Route C — Local, command line

Needs the Android SDK installed and `ANDROID_HOME` set (JDK 17).

```bash
cd sightline-native
npm install
npx cap add android
npm run patch:android
npx cap sync android
cd android
./gradlew assembleDebug
# → android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Installing on the phone
- Transfer `app-debug.apk` (USB, Drive, email to yourself, etc.).
- Tap it; Android will prompt to allow installs from that source — allow it.
- On first launch, grant **camera** and **notifications** permissions.

## Notes
- This is a **debug** APK — signed with the auto-generated debug key, perfect
  for personal installs. For the Play Store you'd build a signed release
  (`assembleRelease` with a keystore); say the word and I'll add that config.
- First launch downloads the face model (~a few MB) once; needs internet that
  first time unless you bundle it offline.
