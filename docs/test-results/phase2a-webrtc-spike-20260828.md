# Phase 2a.1 — WebRTC dependency and API spike

**Date:** 28 August 2026
**Machine:** macOS 26.5, Xcode 27.0 beta (build `27A5252f`), Swift 6.3.2, OpenJDK 21.0.12.1,
Android SDK 36 / build-tools 36.1.0, AGP 9.3.2, Gradle 9.7.1
**Decision it supports:** [ADR-020](../DECISIONS/ADR-020-webrtc-voice-foundation.md)
**Reason it exists:** ADR-003 named two candidate distributions and recorded "community-published
artifacts" as a Medium risk (ARCHITECTURE §12, STATUS §4 problem 5). Nothing had been pinned,
verified or built against. The Phase 2a brief required the dependency question to be *answered*
before implementation, and required stopping rather than substituting something unsafe if it could
not be.

**Verdict: both distributions are usable as-is. No architecture blocker. No unsafe substitution was
needed or taken.**

---

## 1. What was selected

| | Android | Apple |
|---|---|---|
| Coordinate | `io.github.webrtc-sdk:android` | `https://github.com/stasel/WebRTC.git` |
| Version | `144.7559.14` | `151.0.0` |
| Pin style | exact string in `gradle/libs.versions.toml` | `.package(url:exact:)` — **not** a range |
| Upstream | Chromium M144 | Chromium M151, WebRTC commit `f20ebb8adbf4fa781830e4384c61f732bd28a217` |
| Published | Maven Central, 2026-08-24 | GitHub release, 2026-08-07 |
| Maintainer | `webrtc-sdk/android` (357★, pushed 2026-08-24) | `stasel/WebRTC` (625★, pushed 2026-08-26) |
| License | BSD-3-Clause (artifact POM); packaging repo MIT | BSD-3-Clause (`LICENSE.md`) |
| Size | 48.7 MB AAR | 44.6 MB zipped, 96 MB expanded |

**Maven Central's Solr search index was stale** and reported `125.6422.07` (March 2025) as the
latest Android version. `maven-metadata.xml` — the authoritative listing — has `144.7559.14` with
`lastUpdated 20260824175412`. Recorded because the stale answer is the one a casual check gets, and
it would have led to pinning a version 16 milestones old.

**GitHub reported the Apple package's license as `NOASSERTION`** only because the file is named
`LICENSE.md` rather than `LICENSE`. Its contents are the standard BSD 3-Clause text.

## 2. Integrity

The Apple package is an SPM `binaryTarget` whose manifest carries a SHA-256. Downloaded and hashed
independently:

```
$ shasum -a 256 WebRTC-M151.xcframework.zip
64a218fad3d84a0d783321aa9a1eec58ca266ac7879123f86b0b44b703b7d8dc
expected (Package.swift + GitHub release notes):
64a218fad3d84a0d783321aa9a1eec58ca266ac7879123f86b0b44b703b7d8dc   ✅ match
```

So the bytes SPM will fetch are verified at resolve time against a checksum published in two places.
The Android AAR relies on Maven Central's own checksum and signature infrastructure.

## 3. Contents — what is actually in the binaries

### Android AAR

```
AndroidManifest.xml          <uses-sdk minSdkVersion="21" targetSdkVersion="23" />
classes.jar                  only  org/webrtc/**  and  org/jni_zero/**
jni/arm64-v8a                12 MB   libjingle_peerconnection_so.so
jni/armeabi-v7a             6.5 MB
jni/x86                      12 MB
jni/x86_64                   15 MB
```

- `minSdkVersion 21` is **below** the ADR-011 `minSdk 31`. No baseline change needed.
- **No permission, no service, no receiver, no provider** in its manifest.
- No analytics, crash-reporting or telemetry class anywhere in `classes.jar`.

### Apple XCFramework

```
AvailableLibraries:
  ios-arm64                      arm64                 minos 12.0
  ios-x86_64_arm64-simulator     arm64, x86_64         minos 14.0
  macos-x86_64_arm64             arm64, x86_64         minos 13.0     <-- see §5
  ios-x86_64_arm64-maccatalyst   arm64, x86_64
```

- iOS `minos 12.0` is below the ADR-011 iOS 26.0 deployment target; macOS `minos 13.0` is below `RideLinkPlatform`'s `.macOS(.v14)`. No baseline change needed.
- 93 public headers on iOS, 85 on macOS. The macOS slice omits `RTCAudioSession.h`, `RTCAudioSessionConfiguration.h`, `RTCAudioDevice.h`, `RTCNetworkMonitor.h` and `UIDevice+RTCDevice.h` — all of which are iOS-only concerns, and all of which RideLink keeps behind `#if os(iOS)` anyway.
- Bundled `PrivacyInfo.xcprivacy`:

```
NSPrivacyTracking        = false
NSPrivacyTrackingDomains = []
NSPrivacyCollectedDataTypes = []
NSPrivacyAccessedAPITypes   = SystemBootTime (35F9.1, 8FFB.1), FileTimestamp (C617.1)
```

## 4. Telemetry: checked, not assumed

Every HTTP(S) string in both native binaries was extracted and read. The complete set, identical on
both platforms:

- 12 × `http://www.webrtc.org/experiments/rtp-hdrext/...` — RTP header-extension **URIs**. Identifiers that appear in SDP; nothing is fetched from them.
- `http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01`, `https://aomediacodec.github.io/av1-rtp-spec/` — the same, for two more extensions.
- 3 × `http://crl.comodo*.crl` — CRL distribution-point strings **inside the bundled root certificate store**. Data in a trust store, not a request RideLink makes.
- `https://webrtc.googlesource.com/src/`, `https://crbug.com/1053756` — source and bug references in comments/asserts.

**No upload endpoint, no metrics host, no crash reporter, no field-trial server.** WebRTC's own
`Metrics`/`RTCMetrics` histograms are local in-process counters with no network path in the library;
RideLink does not enable them. The Android engine passes no field trials
(`setFieldTrials` is deprecated upstream and an empty string is the default).

Linked system frameworks are what a media stack needs and nothing more: AVFoundation, CoreAudio,
CoreMedia, VideoToolbox, Metal, Network (iOS) / AppKit, AudioUnit, OpenGL (macOS).

## 5. The macOS slice, and what it buys

**This is the most consequential finding of the spike.** Because the XCFramework carries a macOS
slice, and `RideLinkPlatform` already builds and tests for macOS (ADR-014), `swift test` links the
*same WebRTC binary an iPhone build would* — same commit, same BoringSSL, same Opus.

That makes real media evidence available on a laptop. Measured, in-process, two real engines:

```
SPIKE-CANDIDATE-TYPES:            ["host"]
SPIKE-CANDIDATE-COUNT:            1, 1
SPIKE-CONNECTED:                  true   (both peer connections reached .connected)
SPIKE-TRANSPORT:                  dtlsState   = connected
                                  dtlsCipher  = TLS_AES_128_GCM_SHA256
                                  srtpCipher  = SRTP_AES128_CM_HMAC_SHA1_80
SPIKE-PAIR-SUCCEEDED:             yes
SPIKE-LOCAL-CANDIDATE-TYPE:       host   (protocol: udp)
SPIKE-CODEC:                      audio/opus  48000 Hz  ch=2  fmtp=minptime=10;useinbandfec=1
SPIKE-OFFER-HAS-OPUS:             true
SPIKE-DTLS-FINGERPRINT-IN-OFFER:  true
SPIKE-SRTP-PROFILE-IN-OFFER:      UDP/TLS/RTP/SAVPF
SPIKE-REMOTE-TRACK-RECEIVED:      yes
SPIKE-OUTBOUND-RTP:               kind=audio  packetsSent=0  bytesSent=0
```

Two numbers deserve reading carefully:

- **`packetsSent = 0` is expected and is not a failure.** There is no microphone in a headless test run. The transport is up; nothing is speaking into it. This is the exact line between "media path established" and "audio works", and it is why the latter is a device gate.
- **`ch=2` is the codec's *capability*, not the negotiated channel count.** The negotiated `fmtp` has no `stereo=1`, so Opus runs mono, which is what a voice link wants. No tuning was applied — ADR-003 says measure before tuning, and nobody has.

Determinism: the full loopback suite was run **5 consecutive times, 4/4 tests passing each time,
0 failures**.

## 6. Swift 6 strict concurrency

`RTCSessionDescription`, `RTCIceCandidate` and `RTCStatisticsReport` are **not `Sendable`**. Three
compile errors, reproduced deliberately before designing around them:

```
error: sending 'd' risks causing data races    (RTCSessionDescription out of an offer callback)
error: sending 'r' risks causing data races    (RTCStatisticsReport out of a stats callback)
error: instance method 'lock' is unavailable from asynchronous contexts
```

The compiler refuses to let a WebRTC object leave its callback. The fix is not a suppression: every
value is reduced to `String`/`Int`/enum **inside** the callback — which is exactly the boundary
PROTOCOL §7.4 already defines, since SDP crosses the wire as a string and a candidate as a string
plus two scalars. `@unchecked Sendable` is confined to two observer classes holding immutable values.

Recorded in ADR-020 §7, because it shaped the `VoiceEngine` seam on **both** platforms — Kotlin has
no such constraint, and the interface is primitive-only there too so the two stay mirrors.

## 7. Build integration

| Check | Result |
|---|---|
| Android: AAR resolves and `org.webrtc` API compiles (AGP 9.3.2, Kotlin 2.4.10, compileSdk 36, minSdk 31) | ✅ |
| Android: `assembleDebug` + `assembleRelease` with the dependency | ✅ |
| Android: full gate — `test ktlintCheck detekt lint assembleDebug assembleRelease` | ✅ |
| Apple: `swift build`/`swift test` for `RideLinkPlatform` **on macOS** with the dependency | ✅ |
| Apple: the hand-authored `RideLink.xcodeproj` resolves the transitive SPM binary dependency with **no `project.pbxproj` change** | ✅ |
| Apple: `WebRTC.framework` is embedded in `RideLink.app/Frameworks/` (checked, not assumed — a missing embed is a launch-time crash on device) | ✅ |
| Apple: `xcodebuild` Debug **and** Release for the simulator | ✅ |
| Apple: the 99 pre-existing `RideLinkPlatform` tests still pass with the dependency added | ✅ |

## 8. What this spike does **not** establish

Stated plainly, because the whole point of the file is to be usable as evidence and not as
reassurance:

- **Nothing ran on a phone.** No physical OnePlus Nord 5, no physical iPhone 17 Pro Max, no emulator, no simulator run of the voice path.
- **No audio was captured or played anywhere.** No microphone, no speaker, no Bluetooth endpoint, no helmet unit, no TWS earbuds.
- **The Android media path is untested.** `PeerConnectionFactory.initialize` requires an Android `Context`, so `WebRtcVoiceEngine` on Android cannot be exercised by a JVM unit test at all. It compiles and is wired; that is the entire claim.
- **`AVAudioSession` was not exercised.** It is unavailable on macOS, so `IosVoiceAudioSession` has no test coverage. Its pure mapper does.
- **No latency was measured.** The <200 ms end-to-end target involves a phone's audio stack and a Bluetooth link, neither of which is present here. Nothing in this file may be read as progress against it.
- **The milestone skew (Android M144, Apple M151) was not interop-tested against each other.** WebRTC interoperates across milestones by design and both negotiate the same Opus and DTLS-SRTP profiles, but the two real stacks have never spoken to each other.

All of the above is tracked as **REAL-DEVICE AUDIO GATE PENDING** in `docs/STATUS.md` §7.

## 9. Reproducing this

```sh
# Android: resolve, compile and run the full gate
cd android && ./gradlew -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    test ktlintCheck detekt lint assembleDebug assembleRelease

# Inspect the AAR that Gradle cached
unzip -l ~/.gradle/caches/modules-2/files-2.1/io.github.webrtc-sdk/android/144.7559.14/*/android-144.7559.14.aar

# Apple: the real two-engine media test (host-only ICE, DTLS-SRTP, Opus)
swift test --package-path ios/Packages/RideLinkPlatform --filter VoiceEngineLoopbackTests

# Verify the XCFramework checksum independently
curl -sSL -o /tmp/w.zip https://github.com/stasel/WebRTC/releases/download/151.0.0/WebRTC-M151.xcframework.zip
shasum -a 256 /tmp/w.zip   # expect 64a218fad3d84a0d783321aa9a1eec58ca266ac7879123f86b0b44b703b7d8dc

# Inspect the slices
unzip -q /tmp/w.zip -d /tmp/w && plutil -p /tmp/w/WebRTC.xcframework/Info.plist
```
