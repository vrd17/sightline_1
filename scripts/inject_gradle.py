#!/usr/bin/env python3
"""Idempotently add CameraX + ML Kit + lifecycle-service dependencies to the
generated android/app/build.gradle so the native background monitor compiles."""
import sys, io, re

path = sys.argv[1]

DEPS = [
    'implementation "androidx.camera:camera-core:1.3.4"',
    'implementation "androidx.camera:camera-camera2:1.3.4"',
    'implementation "androidx.camera:camera-lifecycle:1.3.4"',
    'implementation "androidx.lifecycle:lifecycle-service:2.7.0"',
    'implementation "com.google.mlkit:face-detection:16.1.7"',
]

with io.open(path, encoding="utf-8") as f:
    g = f.read()

needed = [d for d in DEPS if d.split(':')[1] not in g]  # match by artifact id
if not needed:
    print("   = gradle dependencies already present")
    sys.exit(0)

block = "\n    // --- Sightline native background monitor ---\n" + "\n".join("    " + d for d in needed) + "\n"

# insert just after the opening of the LAST dependencies { block
m = list(re.finditer(r'dependencies\s*\{', g))
if not m:
    # no dependencies block (unexpected) — append one
    g = g + "\n\ndependencies {" + block + "}\n"
else:
    pos = m[-1].end()
    g = g[:pos] + block + g[pos:]
print(f"   + added {len(needed)} gradle dependency line(s)")

with io.open(path, "w", encoding="utf-8") as f:
    f.write(g)
