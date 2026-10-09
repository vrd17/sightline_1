# Get the APK automatically

One script does the whole cloud build and downloads the finished APK to your
computer. You only sign in to GitHub once (securely, in your browser).

## You need (free)
- A GitHub account — https://github.com/signup
- Git — https://git-scm.com/downloads
- GitHub CLI — https://cli.github.com

## Run it
1. Unzip this project. Open a terminal **inside the `sightline-native` folder**.
2. Run:

   **macOS / Linux**
   ```bash
   bash build-and-get-apk.sh
   ```

   **Windows** — install "Git for Windows", then open **Git Bash** in this
   folder and run the same line:
   ```bash
   bash build-and-get-apk.sh
   ```

3. The first time, it opens a GitHub sign-in (a one-time code in your browser —
   your password never leaves GitHub). After that it runs on its own:
   creates a private repo → pushes the app → waits for the cloud build →
   downloads **`apk/app-debug.apk`** into this folder.

4. Copy `apk/app-debug.apk` to your tablet/phone and tap to install
   (allow "install from this source" once). If an older Sightline is installed
   and the install is refused, uninstall it first.

## Re-running later (after more changes)
Just run the same command again — it reuses the same repo, pushes the update,
and downloads the new APK.

## If the build fails
The script prints the command to see the error:
```bash
gh run view <id> --log-failed
```
Send me that output and I'll fix it.
