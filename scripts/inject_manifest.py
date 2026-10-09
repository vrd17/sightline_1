#!/usr/bin/env python3
"""Idempotently patch the generated AndroidManifest.xml:
  - camera / notification / internet / foreground-service / wake-lock permissions
  - large-screen support + resizeableActivity (tablets + split-screen)
  - the Sightline foreground service declaration
Targeted, guarded inserts — Capacitor's generated manifest is simple and stable."""
import sys, re, io

manifest_path = sys.argv[1]

PERMS = [
    '<uses-permission android:name="android.permission.INTERNET" />',
    '<uses-permission android:name="android.permission.CAMERA" />',
    '<uses-permission android:name="android.permission.VIBRATE" />',
    '<uses-permission android:name="android.permission.WAKE_LOCK" />',
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />',
    '<uses-feature android:name="android.hardware.camera" android:required="false" />',
    '<uses-feature android:name="android.hardware.camera.front" android:required="false" />',
]

SUPPORTS = ('<supports-screens android:smallScreens="true" android:normalScreens="true" '
            'android:largeScreens="true" android:xlargeScreens="true" android:anyDensity="true" />')

SERVICE = ('        <service\n'
           '            android:name=".SightlineForegroundService"\n'
           '            android:exported="false"\n'
           '            android:foregroundServiceType="dataSync" />\n')

with io.open(manifest_path, encoding="utf-8") as f:
    m = f.read()

# 1) permissions + features before <application>
to_add = [p for p in PERMS if p.split('android:name="')[1].split('"')[0] not in m]
if SUPPORTS not in m and 'supports-screens' not in m:
    to_add.append(SUPPORTS)
if to_add:
    block = "\n".join("    " + p for p in to_add) + "\n"
    m = re.sub(r'(\n\s*<application)', "\n" + block + r"\1", m, count=1)
    print(f"   + added {len(to_add)} manifest entr(ies) before <application>")
else:
    print("   = permissions/support already present")

# 2) resizeableActivity on <application> (enables tablet split-screen)
if 'android:resizeableActivity' not in m:
    m = m.replace('<application', '<application android:resizeableActivity="true"', 1)
    print("   + added resizeableActivity")
else:
    print("   = resizeableActivity already present")

# 3) foreground service inside <application>
if 'SightlineForegroundService' not in m:
    m = m.replace('</application>', SERVICE + '    </application>', 1)
    print("   + added foreground service")
else:
    print("   = foreground service already present")

with io.open(manifest_path, "w", encoding="utf-8") as f:
    f.write(m)
