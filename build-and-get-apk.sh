#!/usr/bin/env bash
# =============================================================================
#  Sightline — one-shot APK builder
#  Run this ONCE from inside the unzipped "sightline-native" folder.
#  It pushes the app to your GitHub account, lets GitHub build the APK in the
#  cloud, and downloads the finished app-debug.apk next to this script.
#
#  You need (the script checks and tells you how to get them):
#    • a free GitHub account
#    • Git            https://git-scm.com/downloads
#    • GitHub CLI     https://cli.github.com
#  Nothing else — the Android build happens on GitHub's servers.
# =============================================================================
set -e
REPO_NAME="${1:-sightline}"     # optional: pass a different repo name as arg 1

say(){ printf "\n\033[1;36m==> %s\033[0m\n" "$1"; }
die(){ printf "\n\033[1;31m!! %s\033[0m\n" "$1"; exit 1; }

# --- 0. prerequisites -------------------------------------------------------
command -v git >/dev/null 2>&1 || die "Git is not installed. Get it: https://git-scm.com/downloads"
command -v gh  >/dev/null 2>&1 || die "GitHub CLI is not installed. Get it: https://cli.github.com  (then re-run this script)"
[ -f capacitor.config.json ] || die "Run this script from inside the unzipped 'sightline-native' folder (the one with capacitor.config.json)."

# --- 1. sign in to GitHub (secure browser login — your password never leaves GitHub) ---
if ! gh auth status >/dev/null 2>&1; then
  say "Signing you in to GitHub (a browser window / one-time code will appear)…"
  gh auth login
fi
GH_USER="$(gh api user -q .login)"
say "Signed in as: $GH_USER"

# --- 2. commit the project --------------------------------------------------
if [ ! -d .git ]; then
  git init -q
fi
git add -A
git -c user.email="build@sightline.local" -c user.name="Sightline Build" commit -q -m "Sightline build $(date '+%Y-%m-%d %H:%M')" || say "Nothing new to commit — continuing."
git branch -M main

# --- 3. create the repo (first run) or push to it (later runs) --------------
if git remote get-url origin >/dev/null 2>&1; then
  say "Pushing update to your existing repo…"
  git push -u origin main
else
  if gh repo view "$GH_USER/$REPO_NAME" >/dev/null 2>&1; then
    say "Repo $GH_USER/$REPO_NAME already exists — linking and pushing…"
    git remote add origin "https://github.com/$GH_USER/$REPO_NAME.git"
    git push -u origin main
  else
    say "Creating private repo $GH_USER/$REPO_NAME and pushing…"
    gh repo create "$REPO_NAME" --private --source=. --remote=origin --push
  fi
fi

# --- 4. wait for the GitHub Actions build ----------------------------------
say "Build triggered on GitHub Actions. Waiting for a run to appear…"
RUN_ID=""
for i in $(seq 1 20); do
  RUN_ID="$(gh run list --workflow build-apk.yml --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)"
  [ -n "$RUN_ID" ] && break
  sleep 3
done
[ -n "$RUN_ID" ] || die "Couldn't find the build run. Open your repo's Actions tab on github.com to check."

say "Watching build #$RUN_ID (this usually takes 4–6 minutes)…"
if ! gh run watch "$RUN_ID" --exit-status; then
  die "The build failed. Open it with:  gh run view $RUN_ID --log-failed   (or the Actions tab) and send me the error."
fi

# --- 5. download the APK ----------------------------------------------------
say "Build succeeded — downloading the APK…"
rm -rf ./apk && mkdir -p ./apk
gh run download "$RUN_ID" -n sightline-debug-apk -D ./apk

APK="$(find ./apk -name '*.apk' | head -1)"
[ -n "$APK" ] || die "APK not found in the downloaded artifact."

printf "\n\033[1;32m========================================================\n"
printf "  DONE.  Your installable APK is here:\n  %s\n" "$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
printf "========================================================\033[0m\n"
printf "\nNext: copy that file to your tablet/phone and tap it to install.\n"
printf "(If an older Sightline is installed and the install fails, uninstall it first.)\n\n"
