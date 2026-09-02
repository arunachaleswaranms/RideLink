# RideLink

A private, local-only rider–pillion intercom and synchronized music system for **two people on
one motorcycle**.

- **Rider** — Android phone + helmet Bluetooth unit (speakers + mic)
- **Pillion** — iPhone + ordinary Bluetooth earbuds

The two phones talk to each other over local Wi-Fi (a LAN or a phone hotspot). Bluetooth is used
only between each phone and its own audio device. There is no server, no account, no cloud, no
subscription and no telemetry — the app is expected to work with mobile data switched off.

## What it does

| | |
|---|---|
| **Intercom** | Full-duplex voice between rider and pillion, with mute and selectable continuous / VOX / push-to-talk modes |
| **Shared music** | Both phones play their **own local copy** of the same track, started against a synchronized clock — music is never restreamed peer-to-peer |
| **Shared control** | Either person can play, pause, skip, seek and edit the shared queue. No "master phone" |
| **Shared catalogue** | Libraries are indexed and exchanged as paginated metadata manifests; a track missing on one phone transfers over the local link, SHA-256 verified before it's accepted |
| **Ride Mode** | A deliberately minimal screen you set up before moving and then put away |

## Status

**Phase 0 (hardware feasibility) is done. Phases 1a, 1b and 2a are implementation-complete; the
real-device gate is pending for all three.**

Phase 1b's two open security risks are closed with measurements rather than argument: a
hand-encoded self-signed X.509 certificate that Apple's parser, BoringSSL and OpenSSL all accept,
and a TLS 1.3 keying-material exporter that produces **byte-identical** output on an Apple endpoint
and a Conscrypt/BoringSSL endpoint for the same connection — which is what makes the six-digit
pairing code a real check. Evidence:
[`docs/test-results/phase1b-security-spike-20260827.md`](docs/test-results/phase1b-security-spike-20260827.md).

The control plane is now TLS 1.3 with mutual authentication, `identity_spki_sha256` pinning, and
first-meeting SAS pairing with persisted trust. **There is no plaintext transport left in any
production source set.**

**Phase 2a adds the voice transport foundation:** WebRTC behind pinned, checksum-verified,
telemetry-audited distributions on both platforms; SDP and ICE signalled over the control channel
that Phase 1b secured; host candidates only, with no STUN or TURN configured and no field that
could carry one; and DTLS-SRTP with Opus. `VOICE_*` frames are **absent from the
pre-authentication frame allowlist**, so an unauthenticated peer cannot start voice at all — proven
over real TLS on both platforms. Decisions:
[`ADR-020`](docs/DECISIONS/ADR-020-webrtc-voice-foundation.md); evidence:
[`docs/test-results/phase2a-webrtc-spike-20260828.md`](docs/test-results/phase2a-webrtc-spike-20260828.md).

Real WebRTC media *has* been established and measured on the build machine — two real engines,
host-only candidates, DTLS connected, `audio/opus` at 48 kHz — because the Apple WebRTC XCFramework
carries a macOS slice, so `swift test` links the same binary an iPhone build would.

**None of it has run on the two real phones, and no audio has been captured or played anywhere.**
This environment has no Android device or emulator and only an iOS simulator; the Android media path
has no test at all, and neither audio-session implementation has ever executed on a device. No
latency figure exists. See [`docs/STATUS.md`](docs/STATUS.md) for exactly what is verified and what
is not, and [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) §3.1a for the line drawn item by item.

## Repository layout

```
protocol/    Wire schemas and golden test vectors shared by both platforms' tests
docs/        Requirements, architecture, protocol, test plan, status, decision records
tools/       Local helper scripts (no dependencies, no network access)

android/     Kotlin + Jetpack Compose app — five Gradle modules, builds and tests green
ios/         Swift + SwiftUI app — RideLinkCore + RideLinkPlatform packages, RideLink.xcodeproj
             all build; the app runs on-simulator
```

## Platform baselines

Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`; iOS deployment target 26.0, Swift 6.
`minSdk 31` is also, measured rather than assumed, exactly the level at which Android's public TLS
keying-material exporter appears.
Rationale in [`docs/DECISIONS/ADR-011`](docs/DECISIONS/ADR-011-platform-baselines.md) — two known
modern devices, no store release, so legacy compatibility branches buy nothing.

## Documentation

| Read this for | File |
|---|---|
| What it must do | [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) — faithful transcription of the source DOCX |
| How it's built | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| What goes on the wire | [`docs/PROTOCOL.md`](docs/PROTOCOL.md) |
| How it's verified | [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) |
| Where it stands, what's next | [`docs/STATUS.md`](docs/STATUS.md) |
| Why decisions were made | [`docs/DECISIONS/`](docs/DECISIONS/) |
| What the security spike measured | [`docs/test-results/`](docs/test-results/) |
| The development contract | [`CLAUDE.md`](CLAUDE.md) — committed deliberately, so a fresh clone keeps it |

`docs/RideLink_Requirements_and_Implementation_Plan.docx` is the source of truth and is
read-only.

## Privacy

No analytics, advertising, telemetry or crash-reporting SDKs. No backend. Microphone audio is
never written to disk and never leaves the pair of phones. mDNS advertisements carry nothing
durable — no peer identity, no key fingerprint, no library information — so a passive observer on
the Wi-Fi cannot use them to recognise a device. Diagnostic logs redact file paths and peer
identifiers, and pairing codes, keys and tokens have no log path at all. Details in
[`docs/ARCHITECTURE.md` §11](docs/ARCHITECTURE.md#11-privacy-and-security-posture).

## Scope

Personal use, two participants, known hardware. Not published to any app store. Spotify /
YouTube Music / Apple Music integration, group riding, cloud sync and social features are
explicitly out of scope for V1.
