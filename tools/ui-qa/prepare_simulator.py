#!/usr/bin/env python3
"""Build-input copy for visual fixtures. Never changes production sources or authentication."""
import pathlib
import shutil
import sys

root = pathlib.Path(__file__).resolve().parents[2]
destination = pathlib.Path(sys.argv[1]).resolve()
if destination.exists():
    raise SystemExit("Choose a new temporary destination")
destination.mkdir(parents=True)
shutil.copytree(root / "ios/RideLink", destination / "RideLink")
shutil.copytree(root / "ios/RideLink.xcodeproj", destination / "RideLink.xcodeproj")
(destination / "Packages").symlink_to(root / "ios/Packages", target_is_directory=True)
shutil.copyfile(root / "tools/ui-qa/SimulatorApp.swift", destination / "RideLink/RideLinkApp.swift")
pbx = destination / "RideLink.xcodeproj/project.pbxproj"
pbx.write_text(pbx.read_text().replace("com.ridelink.app", "com.ridelink.visualqa"))
print(destination)
