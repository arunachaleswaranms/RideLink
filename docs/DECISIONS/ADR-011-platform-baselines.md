# ADR-011 — Platform baselines: Android API 31/36/36, iOS 26.0

**Status:** Accepted · 26 Aug 2026

## Context

The architecture baseline never fixed a supported-OS floor. Without one, every platform decision
that touches audio routing or TLS has to be written twice — once for the modern API and once for
a legacy path — and the second version is code that will never run on either of the two phones
this product exists for.

The actual deployment surface is exactly two devices:

| | Device | Ships with |
|---|---|---|
| Rider | OnePlus Nord 5 | Android 15 |
| Pillion | iPhone 17 Pro Max | iOS 26 |

There is no app-store release, no third user, and no legacy device in the picture (REQUIREMENTS
§1, §22). Compatibility breadth has no value here and a real cost: availability branches in the
highest-risk subsystem in the product.

**Local toolchain, inspected 26 Aug 2026:**

- Swift 6.3.2, macOS 26.5 SDK, via Command Line Tools.
- **Xcode is not installed**; no iOS SDK is present on the machine.
- No Android SDK, no Gradle installation.
- JDK: Temurin 25.0.3.

So the iOS baseline below is chosen from the device and toolchain generation, and must be
*confirmed* against the SDK that arrives with Xcode before Phase 1 iOS scaffolding is called
done.

## Decision

### Android

| Setting | Value |
|---|---|
| `minSdk` | **31** (Android 12) |
| `compileSdk` | **36** |
| `targetSdk` | **36** |
| JVM target | 21, via an explicitly pinned Gradle toolchain |

`minSdk 31` is not an arbitrary round number. It is the level at which:

- `AudioManager.setCommunicationDevice()` / `clearCommunicationDevice()` exist. Below it, routing the helmet unit means the deprecated `startBluetoothSco()` / `MODE_IN_COMMUNICATION` sequence — legacy workaround code in exactly the subsystem this product is most likely to break in.
- `BLUETOOTH_CONNECT` is the runtime permission model for enumerating and naming the helmet unit, rather than the older location-permission entanglement.
- The platform's public TLS surface is the generation we intend to build the SAS channel binding on. **This specific point is not yet verified** and is recorded as a Phase 1b spike item, not as a settled fact — see the risk register in ARCHITECTURE §12.

`targetSdk 36` means the app opts into current behaviour, including the foreground-service type
enforcement that ARCHITECTURE §6.4 is designed around. Targeting lower to dodge those rules is
explicitly rejected: the instruction is to work within platform background rules, not to defer
them.

**JDK pin.** The JDK on this machine is Temurin 25, ahead of what current AGP supports. Phase 1
must set the Gradle toolchain explicitly (`kotlin { jvmToolchain(21) }` and a matching
`compileOptions`) rather than inheriting `JAVA_HOME`, otherwise the first build fails with an
unhelpful toolchain error. If AGP at the pinned version turns out to require JDK 17, that is a
one-line change and an amendment note here.

### iOS

| Setting | Value |
|---|---|
| Deployment target | **iOS 26.0** |
| Compile against | latest installed iOS SDK (Xcode 26.x) |
| Swift | 6, strict concurrency |

iOS 26.0 because:

- The only pillion device ships with iOS 26 and runs it.
- `AVAudioSession.CategoryOptions.allowBluetoothHFP` is the current spelling; `allowBluetooth` is deprecated. At this target the audio-session code is written once with no `#available` branch — see ADR-016 and ARCHITECTURE §6.2.
- Swift 6 strict concurrency is used throughout, and pairing it with a current SDK keeps the concurrency annotations honest rather than back-deployed.
- There is no second iOS device and no store submission, so nothing is lost.

**Confirmation step, required before Phase 1 iOS scaffolding is complete:** install Xcode, run
`xcodebuild -showsdks`, and check the iOS SDK version. If it is older than 26, the deployment
target drops to that SDK's current release and this ADR gets a dated amendment. It does not get
changed silently, and the audio-session code then needs the availability branch that this
baseline was chosen to avoid.

## Consequences

- No availability branches in audio routing, TLS or background execution. Every line of platform code runs on the real device.
- Any future device with an older OS is out of scope by construction. Given the project's stated scope, that is a feature.
- Both baselines are now assertable: Phase 1 can add a build-configuration check to the static-analysis gate, so a drift in `minSdk` or deployment target fails a check instead of being noticed later.
- The `minSdk 31` TLS-exporter assumption is load-bearing for the SAS and is *unverified*. It is tracked as a high-severity Phase 1b risk, not as a decided fact. If the public exporter turns out to need a higher API level, raising `minSdk` is cheap (both devices are far above it); if it does not exist at all, ADR-007 Amendment A1 governs the response.

## Alternatives considered

| Option | Rejected because |
|---|---|
| `minSdk 26`–`30` | Buys nothing — no such device exists in this project — and costs a deprecated Bluetooth SCO routing path in the riskiest subsystem |
| `minSdk 34`/`35` | Tempting for the newest FGS APIs, but API 31 already provides everything the design needs, and a slightly wider floor costs nothing when there is no legacy branch to write |
| `targetSdk` below 36 | Would postpone foreground-service-type enforcement rather than design for it. ARCHITECTURE §6.4 exists precisely to design for it |
| iOS 18 minimum | Would require `#available(iOS 26)` branches around the audio-session options for a device that does not exist in this project |
| Defer the decision until Xcode is installed | The Android half is decidable now, and leaving the baseline unstated is what created the problem this ADR fixes. The iOS half is decided *with a named confirmation step*, which is stronger than silence |
