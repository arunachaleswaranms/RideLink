#!/usr/bin/env bash
#
# Phase 8's cross-platform software integration gate.
#
# Runs the Swift/iOS half (`RideLinkPlatformTests.CrossPlatformInteropTests`) and the Kotlin/Android
# half (`com.ridelink.network.interop.CrossPlatformInteropTest`) as **two processes on one machine**,
# joined by a real TCP socket carrying the real RideLink protocol: real TLS 1.3 with mutual
# authentication, real ECDSA P-256 identities, PROTOCOL §4.5's six-digit pairing exchange, real
# PING/PONG clock sync, and the real Phase 5/Phase 7 relays and codecs on both sides.
#
# It then compares the two reports. The sharpest assertion is the six digits: each side derives them
# from its own TLS exporter, the protocol never compares them (two humans do), so this script is the
# only place that comparison can be made.
#
# What this gate is NOT: the interactive emulator <-> simulator UI journey. No UI is driven, no app
# is launched, and nothing here says anything about Bluetooth, audio, iPhone background behaviour or
# a physical device. The Android half also runs on the JVM against Conscrypt rather than on a device
# against Android's own TLS stack — the pre-existing limitation the Phase 1b security spike records.
#
# Usage: tools/crossplatform/run.sh [report-dir]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIR="${1:-$(mktemp -d "${TMPDIR:-/tmp}/ridelink-cross.XXXXXX")}"
mkdir -p "$DIR"
rm -f "$DIR"/ios-* "$DIR"/android-*
JDK="${RIDELINK_JDK21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

echo "cross-platform interop gate"
echo "  report directory: $DIR"

(
  cd "$ROOT"
  RIDELINK_CROSS_DIR="$DIR" swift test \
    --package-path ios/Packages/RideLinkPlatform \
    --filter CrossPlatformInteropTests
) >"$DIR/ios.log" 2>&1 &
IOS_PID=$!

(
  cd "$ROOT/android"
  ./gradlew --console=plain \
    -Dorg.gradle.java.home="$JDK" \
    -PrideLinkCrossDir="$DIR" \
    :network:testDebugUnitTest \
    --tests "com.ridelink.network.interop.CrossPlatformInteropTest"
) >"$DIR/android.log" 2>&1 &
ANDROID_PID=$!

wait "$IOS_PID"; IOS_STATUS=$?
wait "$ANDROID_PID"; ANDROID_STATUS=$?

echo "  swift test exit: $IOS_STATUS"
echo "  gradle test exit: $ANDROID_STATUS"

python3 "$ROOT/tools/crossplatform/compare.py" "$DIR"
VERDICT=$?

if [ "$IOS_STATUS" -ne 0 ] || [ "$ANDROID_STATUS" -ne 0 ] || [ "$VERDICT" -ne 0 ]; then
  echo "GATE FAILED — logs in $DIR"
  exit 1
fi
echo "GATE PASSED — reports in $DIR"
