#!/usr/bin/env python3
"""Capture native fixture renders, not end-to-end session/audio evidence."""
import pathlib
import subprocess
import sys
import time

device, output = sys.argv[1], pathlib.Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
fixtures = ["idle", "intercom", "music", "combined", "ptt", "muted", "reconnecting", "disconnected", "sync-problem", "long-title", "waiting"]
fixtures += ["setup-" + name for name in ["IDLE", "DISCOVERING", "CONNECTING", "PAIRING", "CONNECTED", "ERROR", "RECONNECTING", "PAIR_CODE", "VOICE", "QUEUE", "MUSIC_EMPTY", "SECURITY", "LIBRARY", "TRANSFER", "MUSIC_PLAYING", "MUSIC_PAUSED"]]
if len(sys.argv) > 3:
    fixtures = sys.argv[3:]
for fixture in fixtures:
    subprocess.run(["xcrun", "simctl", "terminate", device, "com.ridelink.visualqa"], capture_output=True)
    subprocess.run(["xcrun", "simctl", "launch", device, "com.ridelink.visualqa", fixture], check=True, capture_output=True)
    # Allow the OS launch transition to settle for a photograph; no behavioral assertion uses time.
    time.sleep(3)
    subprocess.run(["xcrun", "simctl", "io", device, "screenshot", str(output / (fixture + ".png"))], check=True, capture_output=True)
print(f"Captured {len(fixtures)} native fixture screens in {output}")
