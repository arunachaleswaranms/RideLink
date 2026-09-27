#!/usr/bin/env bash
# RideLink Android sideload build (ADR-029 Amendment A2, docs/SIDELOAD.md).
#
#   tools/sideload/android.sh keystore                     create the local signing key, once
#   tools/sideload/android.sh build                        build + align + sign + verify HEAD
#   tools/sideload/android.sh verify  <apk>                print an APK's SHA-256 and signer
#   tools/sideload/android.sh install -s <serial> [--clean] <apk>
#                                                          install, then prove the installed bytes
#
# The signing key never enters the repository, the build, a log or this script's output: it lives
# in a PKCS12 keystore under $RIDELINK_SIDELOAD_HOME (default ~/.ridelink/sideload), and its random
# password lives in the macOS login Keychain and reaches keytool/apksigner without being printed or
# passed as an argument. No network access beyond what Gradle's own dependency cache already needs.
# -E: without it bash does not run the ERR trap inside functions, where all the work is.
set -Eeuo pipefail
# Never `set -x` here: it would echo the key password. Name the failing line instead of exiting mute.
trap 'echo "error: tools/sideload/android.sh failed at line $LINENO" >&2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PACKAGE="com.ridelink.app"
SIDELOAD_HOME="${RIDELINK_SIDELOAD_HOME:-$HOME/.ridelink/sideload}"
KEYSTORE="$SIDELOAD_HOME/android-sideload.p12"
KEY_ALIAS="ridelink-sideload"
KEYCHAIN_SERVICE="ridelink-android-sideload"
OUT_DIR="$ROOT/android/app/build/sideload"

die() { echo "error: $*" >&2; exit 1; }

java_home() {
    local candidate="${RIDELINK_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
    [ -x "$candidate/bin/java" ] || die "JDK 21 not found at $candidate; set RIDELINK_JAVA_HOME"
    "$candidate/bin/java" -version 2>&1 | awk 'NR==1' | grep -q '"21\.' || die "$candidate is not JDK 21"
    echo "$candidate"
}

build_tools() {
    local sdk="${ANDROID_HOME:-}"
    if [ -z "$sdk" ] && [ -f "$ROOT/android/local.properties" ]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/android/local.properties")"
    fi
    [ -d "$sdk/build-tools" ] || die "Android SDK not found; set ANDROID_HOME or android/local.properties"
    local latest
    latest="$(ls "$sdk/build-tools" | sort -V | tail -1)"
    echo "$sdk/build-tools/$latest"
}

keychain_password() {
    security find-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null \
        || die "no sideload key password in the Keychain; run: tools/sideload/android.sh keystore"
}

apk_sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

signer_sha256() {
    "$(build_tools)/apksigner" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}

cmd_keystore() {
    [ ! -e "$KEYSTORE" ] || die "$KEYSTORE already exists; refusing to replace a signing key (installs signed by it would stop updating)"
    command -v security >/dev/null || die "the macOS 'security' tool is required to hold the key password"
    mkdir -p "$SIDELOAD_HOME"
    chmod 700 "$SIDELOAD_HOME"
    local password
    # 192 random bits as 48 hex characters. Not `tr </dev/urandom | head`: under pipefail, head
    # closing the pipe kills tr with SIGPIPE and the whole step fails.
    password="$(openssl rand -hex 24)"
    [ "${#password}" -eq 48 ] || die "could not generate a key password"
    # `security -i` reads its command from stdin, so the password is never a process argument.
    printf 'add-generic-password -U -a "%s" -s "%s" -w "%s"\n' "$USER" "$KEYCHAIN_SERVICE" "$password" \
        | security -i >/dev/null || die "could not store the key password in the login Keychain"
    # keytool's stderr is kept: it reports failures and never prints the password.
    RIDELINK_KS_PASS="$password" "$(java_home)/bin/keytool" -genkeypair -noprompt \
        -storetype PKCS12 -keystore "$KEYSTORE" -storepass:env RIDELINK_KS_PASS \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
        -dname "CN=RideLink sideload" >/dev/null || die "keytool could not create the key"
    unset password
    chmod 600 "$KEYSTORE"
    echo "created the sideload signing key (outside the repository; password in the login Keychain)"
    echo "its certificate digest is printed by every 'build' as signer_certificate_sha256"
}

cmd_build() {
    [ -f "$KEYSTORE" ] || die "no signing key; run: tools/sideload/android.sh keystore"
    cd "$ROOT"
    [ -z "$(git status --porcelain)" ] || die "the working tree is not clean; a sideload build must be exactly one commit"
    local revision
    revision="$(git rev-parse HEAD)"
    local jdk tools
    jdk="$(java_home)"
    tools="$(build_tools)"

    (cd android && ./gradlew -Dorg.gradle.java.home="$jdk" --quiet \
        "-Pridelink.sourceRevision=$revision" :app:assembleRelease)

    local unsigned="$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
    [ -f "$unsigned" ] || die "expected $unsigned"
    mkdir -p "$OUT_DIR"
    local name="ridelink-android-${revision:0:12}"
    local aligned="$OUT_DIR/$name-aligned-unsigned.apk"
    local apk="$OUT_DIR/$name.apk"
    rm -f "$aligned" "$apk"

    # 16 KiB page alignment for the WebRTC native libraries (-P 16), 4-byte for everything else.
    "$tools/zipalign" -f -P 16 4 "$unsigned" "$aligned"
    keychain_password | JAVA_HOME="$jdk" "$tools/apksigner" sign \
        --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass stdin \
        --out "$apk" "$aligned"
    rm -f "$aligned" "$apk.idsig"
    JAVA_HOME="$jdk" "$tools/apksigner" verify --verbose "$apk" >/dev/null || die "signature verification failed"
    "$tools/zipalign" -c -P 16 4 "$apk" || die "alignment verification failed"

    local badging version_name version_code
    badging="$("$tools/aapt2" dump badging "$apk" | awk 'NR==1')"
    version_name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
    version_code="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"

    local record="$OUT_DIR/$name.provenance.txt"
    {
        echo "source_revision: $revision"
        echo "working_tree: clean"
        echo "apk: $(basename "$apk")"
        echo "apk_sha256: $(apk_sha256 "$apk")"
        echo "signer_certificate_sha256: $(JAVA_HOME="$jdk" signer_sha256 "$apk")"
        echo "package: $PACKAGE"
        echo "version_name: $version_name"
        echo "version_code: $version_code"
        echo "build_type: release (not debuggable)"
        echo "build_tools: $(basename "$tools")"
        echo "jdk: $("$jdk/bin/java" -version 2>&1 | awk 'NR==1')"
        echo "built_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >"$record"
    cat "$record"
    echo "record: ${record#"$ROOT"/}"
}

cmd_verify() {
    local apk="${1:?usage: verify <apk>}"
    local jdk
    jdk="$(java_home)"
    JAVA_HOME="$jdk" "$(build_tools)/apksigner" verify --verbose "$apk" >/dev/null || die "signature verification failed"
    echo "apk_sha256: $(apk_sha256 "$apk")"
    echo "signer_certificate_sha256: $(JAVA_HOME="$jdk" signer_sha256 "$apk")"
}

cmd_install() {
    local serial="" clean=0 apk=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -s) serial="${2:?-s needs a serial}"; shift 2 ;;
            --clean) clean=1; shift ;;
            *) apk="$1"; shift ;;
        esac
    done
    [ -n "$serial" ] || die "an explicit -s <serial> is required; several adb transports may be attached"
    [ -f "$apk" ] || die "usage: install -s <serial> [--clean] <apk>"
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = "device" ] || die "$serial is not an attached device"
    [ -z "$(adb -s "$serial" shell getprop ro.boot.qemu | tr -d '\r')" ] \
        || die "$serial is an emulator; this procedure installs the qualification build on a physical phone"

    if [ "$clean" = 1 ]; then
        echo "clean install: removing $PACKAGE and all of its data from $serial"
        adb -s "$serial" uninstall "$PACKAGE" >/dev/null 2>&1 || true
    fi
    # Deliberately no -g: runtime permissions must be granted through the app's own prompts.
    adb -s "$serial" install -r "$apk" >/dev/null

    local installed pulled
    installed="$(adb -s "$serial" shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk$')"
    pulled="$(mktemp -d)/base.apk"
    adb -s "$serial" pull "$installed" "$pulled" >/dev/null 2>&1 || die "could not read the installed APK back from $serial"
    [ "$(apk_sha256 "$pulled")" = "$(apk_sha256 "$apk")" ] || die "the installed APK differs from $apk"
    rm -rf "$(dirname "$pulled")"

    local dumpsys
    dumpsys="$(adb -s "$serial" shell dumpsys package "$PACKAGE" | tr -d '\r')"
    echo "installed_apk_sha256: $(apk_sha256 "$apk") (read back from the device, identical)"
    echo "install_mode: $([ "$clean" = 1 ] && echo clean || echo update-or-first)"
    echo "installed_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # Only a prefix: a hardware serial or LAN address is a persistent identifier, and this record is
    # meant to be copied into committed evidence.
    echo "device_transport: ${serial:0:4}… (redacted)"
    echo "device: $(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r') $(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    echo "device_fingerprint: $(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
    grep -m1 -o 'versionName=[^ ]*' <<<"$dumpsys" || true
    grep -m1 -o 'versionCode=[0-9]*' <<<"$dumpsys" || true
    grep -m1 -o 'lastUpdateTime=.*' <<<"$dumpsys" || true
}

case "${1:-}" in
    keystore) shift; cmd_keystore "$@" ;;
    build) shift; cmd_build "$@" ;;
    verify) shift; cmd_verify "$@" ;;
    install) shift; cmd_install "$@" ;;
    *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
