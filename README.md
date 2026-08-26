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

**Documentation baseline complete and corrected. No application code yet.** Phase 0 (hardware
feasibility) is done; Phase 1 (peer session foundation) is next and has not started. A
pre-implementation correction pass on 26 Aug 2026 fixed several architecture, protocol and
platform-assumption issues found in review — see [`docs/STATUS.md`](docs/STATUS.md).

## Repository layout

```
protocol/    Wire schemas and golden test vectors shared by both platforms' tests
docs/        Requirements, architecture, protocol, test plan, status, decision records
tools/       Local helper scripts (no dependencies, no network access)

android/     Kotlin + Jetpack Compose app   — planned, created during Phase 1
ios/         Swift + SwiftUI app            — planned, created during Phase 1
```

`android/` and `ios/` do not exist yet. They are created when Phase 1 scaffolding begins;
no placeholder files are committed to pretend otherwise.

## Platform baselines

Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`; iOS deployment target 26.0, Swift 6.
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
