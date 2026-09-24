# Native visual fixtures

These small hosts render production view components with immutable sample values and no-op
callbacks. They do not authenticate a peer, transmit audio, or prove an end-to-end ride.
Never ship the simulator fixture host as the production application.

Android: build `:app:assembleDebug :app:assembleDebugAndroidTest`, install both APKs, then run
`adb shell am instrument -w -e class com.ridelink.app.ui.RideVisualTest,com.ridelink.app.ui.SetupVisualTest com.ridelink.app.test/androidx.test.runner.AndroidJUnitRunner`.
Pull `/sdcard/Android/data/com.ridelink.app/files/ui-qa` before Gradle uninstalls the test app.
The independent `PresentationActionsTest` exercises accessible PTT and duplicate queue identity.
Run instrumentation serially and inspect screenshots; a successful capture is not a visual verdict.

For iOS, run `python3 tools/ui-qa/prepare_simulator.py /tmp/ridelink-visual-copy` with a new path.
Build that copy's `RideLink.xcodeproj` / `RideLink` scheme for a simulator, install its app, then
run `python3 tools/ui-qa/capture_simulator.py DEVICE_UUID OUTPUT_DIRECTORY`.
Optional trailing fixture names restrict captures. The copy uses `com.ridelink.visualqa` and
symlinks the real packages. The repository's application entry point and trust gates are untouched.

Use native appearance and text-size settings. Restore emulator size/density/font scale and
simulator text size after the audit. The photograph delay only settles OS transitions and is not
an assertion of asynchronous behavior. Review for blank/transition frames and system dialogs;
recapture them rather than treating them as evidence of the app.
