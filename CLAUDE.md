# CLAUDE.md — RideLink

**This file is committed on purpose.** It is the project's development contract and must survive
a fresh clone on any machine. Keep it concise, keep it free of secrets, tokens, credentials and
machine-specific paths, and keep it in sync with the documents it points at.

## What this is

A **personal, local-only** rider–pillion app for exactly two people on one motorcycle:
full-duplex voice intercom + synchronized shared music. Rider on Android (OnePlus Nord 5,
helmet Bluetooth unit); pillion on iPhone 17 Pro Max (Bluetooth TWS).

No cloud, no accounts, no backend, no analytics, no subscription, no app-store release.

## Source of truth

Read these before changing anything. They are authoritative; this file is a summary.

| Question | File |
|---|---|
| What must it do? | `docs/REQUIREMENTS.md` — faithful transcription of the source DOCX. **Do not edit** to resolve a conflict; record the resolution in an ADR |
| How is it built? | `docs/ARCHITECTURE.md` |
| What's on the wire? | `docs/PROTOCOL.md` |
| How do we verify? | `docs/TEST_PLAN.md` |
| State now / exact next task | `docs/STATUS.md` |
| Why this way? | `docs/DECISIONS/` (ADR-001…020) |
| What was actually measured? | `docs/test-results/` — including the Phase 1b security spike |
| What did the hardware do? | `docs/PHASE0_RESULTS.md` (awaiting user input) |

`docs/RideLink_Requirements_and_Implementation_Plan.docx` is **read-only input**. Never modify it.

## Architecture rules

| # | Rule | Never instead |
|---|---|---|
| 1 | **Native Kotlin/Compose + Swift/SwiftUI.** Shared via protocol spec + golden vectors, not shared UI code | No Flutter/RN/Compose-Multiplatform |
| 2 | **Phone-to-phone = IP over shared Wi-Fi or a phone hotspot.** Discovery = mDNS/DNS-SD `_ridelink._tcp` (`NsdManager` / `NWBrowser`+`NWListener`) | No Multipeer Connectivity, no AWDL, no Wi-Fi Direct, no Bluetooth as the phone-to-phone link |
| 3 | **Three separate data planes.** Control = TCP+TLS 1.3 + JSON. Voice = WebRTC/DTLS-SRTP/Opus. Bulk files = a *second* TLS connection | Don't put control traffic on a WebRTC DataChannel; don't let a 40 MB transfer block a `PAUSE` |
| 4 | **Each phone plays its own local copy.** Transfer once, then schedule against a synced clock | Never restream music peer-to-peer during playback |
| 5 | **Monotonic clocks only** for anything timing-related | Never use wall-clock time for scheduling |
| 6 | **`content_hash` = SHA-256 of the whole file** is authoritative identity. `quick_id` is a cheap index-time tier | Never identify tracks by filename or metadata alone |
| 7 | **Internal leader = smaller `peer_id`**, assigns `command_seq`. Serialises commands only | Never expose a "master phone" in the UI; both users get full controls |
| 8 | **One `SessionCoordinator` owns session state** | Never scatter connection state across view models |
| 9 | **The domain layer is pure** — no platform types, no clock reads, no I/O. Android `core` is a `kotlin("jvm")` module; `RideLinkCore` imports only `Foundation`+`CryptoKit` | Don't put drift maths or FSM logic behind an Android/iOS type |
| 10 | **WebRTC is voice-only**, behind `network/voice` / `RideLinkPlatform.Voice` | Don't spread WebRTC types through the app |
| 11 | **Control frames cap at 256 KiB and the cap does not move.** Unbounded payloads get paginated | Never raise the frame cap to fit a manifest |
| 12 | **`identity_spki_sha256` is the only pinned identity.** SHA-256 of the DER SubjectPublicKeyInfo | Never pin a whole-certificate fingerprint; never call an SPKI hash `cert_fingerprint` |
| 13 | **One identity algorithm for both platforms: ECDSA P-256, `ecdsa-with-SHA256`** (ADR-017). Keys live in Android Keystore / the iOS Keychain and are never exported. RideLink encodes its own certificate with a shared DER encoder; the platform does the signing | Never choose the algorithm per platform; never use Android's `KeyGenParameterSpec` auto-issued certificate (it cannot be re-issued around an existing key, which breaks ADR-012) |
| 14 | **There is no plaintext control transport.** The only plaintext `ControlChannel` lives in a *test* source set, so it cannot be linked into an app at all | Never add a "debug-only" plaintext path to a production source set; never make security conditional on a build flag |
| 16 | **Voice media may never start before the trust gate.** `VOICE_*` is absent from the pre-authentication frame allowlist, and that absence *is* its access control (ADR-020). The ADR-010 leader is always the WebRTC offerer — never the TCP initiator. ICE is an empty server list and `VoiceEngineConfig` has no field that could carry a STUN/TURN server. `VoiceEngine.stop()` drops the peer connection; only `release()` closes the capture device, because reopening it renegotiates the Bluetooth profile and Android forbids reopening a microphone from the background | Never add a voice type to the pre-auth allowlist; never infer the offerer from who dialled; never add an ICE server "just for testing"; never let a link blip release capture |
| 17 | **The intercom transmission gate never touches the capture device.** PTT, VOX and mute gate the *outbound WebRTC audio track* (`AudioTrack.setEnabled` / `RTCAudioTrack.isEnabled`); the capture device and platform audio session are opened once, while foreground-visible, and stay open for the whole ride segment. Every decision lives in the pure, mirrored `IntercomTransmission` table (ADR-021), whose action vocabulary has **no** capture case — that absence *is* the enforcement, and `protocol/vectors/intercom/` pins it. `AUDIO_STATE` is absent from the pre-authentication allowlist for the same reason `VOICE_*` is | Never open or close capture per utterance; never route a PTT press to `VoiceAudioSession`; never rebuild the `PeerConnection` for a mute; never branch on a mode id; never flip `confidence` off `assumed` without A-12/A-13 |
| 15 | **`ControlEvent.Connected` means "the surviving connection passed the RideLink trust gate"** (ADR-019). `PAIRING -> CONNECTING` opens only on `PeerTrusted` (stored pin matched) or `PairingSucceeded` (both users confirmed and the pin was written). The gate table is `SessionGate` on both platforms, pinned by `vectors/session-gate/` | Never read "TLS and HELLO succeeded" as authentication; never let `Connected` imply pairing success; never start a task that presumes an authenticated peer just because a socket exists |

Reasoning: `docs/DECISIONS/ADR-001…021`.

## Platform stack and baselines

**Binding baselines** (ADR-011): Android `minSdk 31`, `compileSdk 36`, `targetSdk 36`, Gradle
toolchain pinned to JDK 21. iOS deployment target **26.0**, Swift 6 strict concurrency.
Confirm the iOS target against the installed Xcode SDK before Phase 1 iOS scaffolding is done.

- **Android:** `AudioManager` focus/route (`setCommunicationDevice`, API 31+), Media3 `MediaSessionService`, one ride foreground service.
- **iOS:** `AVAudioSession` with **two** configurations — `.playback` for music-only, and `.playAndRecord`/`.voiceChat` with `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]` for the intercom. `.allowBluetooth` is the deprecated spelling. `UIBackgroundModes: audio`. Handle route change **and** interruption **and** media-services-reset.
- **Bluetooth is only phone↔its own audio device.** Never phone↔phone.
- **iOS library = app container only** (document-picker import). `MPMediaLibrary` is unusable (ADR-009). The pillion's catalogue starts empty and fills by peer transfer.
- **Ride Mode starts only from a visible app** (ARCHITECTURE §6.4). The microphone foreground service and the capture device are opened while foreground-visible, then the screen may be locked. Never start mic capture for the first time from the background. Work within platform background rules; never bypass them.
- **Highest product risk:** opening the mic forces most Bluetooth endpoints from media-quality output onto the duplex profile, degrading music. Output is **not** independent of input — that is modelled as `profile_coupling: "input_forces_output"`. Five intercom modes (A–E) are one policy object for exactly this. Default **Mode C (PTT)** until `docs/PHASE0_RESULTS.md` is filled in. The capture device stays open for a whole ride segment; PTT/VOX gate transmission, not the hardware.

## Directory structure

```
android/     Kotlin app — 5 Gradle modules: app, core, network, audio, data   (planned)
ios/         Swift app — thin Xcode target + RideLinkCore, RideLinkPlatform    (planned)
protocol/    Wire schemas + golden test vectors shared by BOTH platforms' tests
docs/        REQUIREMENTS · ARCHITECTURE · PROTOCOL · TEST_PLAN · STATUS · DECISIONS/ · test-results/
tools/       Local helper scripts (no deps, no network)
```

Android `core` (JVM) and `RideLinkCore` (pure Swift) are mirror images: `core.security` and
`RideLinkCore.Security` in particular are line-for-line ports, because a one-byte difference in
either would produce a different `identity_spki_sha256` on one phone than the other.

## Shared protocol vectors — not optional

Both platforms' unit suites run the **same** `protocol/vectors/*.json`. A wire mismatch must fail
a laptop unit test, not surface on a ride.

- Adding or changing a message shape means adding or updating vectors in the same change.
- A vector that passes on one platform only is a release blocker.
- Every protocol bug found on a device gets a vector added **before** the fix.
- `vectors/sas/` and `vectors/identity/` contain fabricated test values only. Never a real key, token, exporter output or pairing code. Five vector sets are **generated**, each deliberately an independent third implementation of what it pins — `identity/`, `session-gate/`, `voice-signal/`, `voice-fsm/`, `intercom/` and `audio-state/`. Edit the generator, not the JSON.

## Never change protocol or architecture silently

If a change touches the wire format, the security model, the state machine, module boundaries or
a platform baseline:

1. Say so explicitly in the response — do not fold it into an unrelated change.
2. Update `docs/PROTOCOL.md` / `docs/ARCHITECTURE.md` in the same change.
3. Add an ADR, or append a dated `## Amendment An` to the existing one. Never rewrite an accepted ADR in place; a superseded decision gets `Superseded by ADR-nnn`, not deletion.
4. Update the affected vectors and `docs/TEST_PLAN.md`.
5. Update `docs/STATUS.md`.
6. Leave no stale example behind — grep the repo for the old field name, message shape or module name.

Contradictions between documents are bugs. If two documents disagree, stop and resolve it rather
than picking one.

## Privacy rules (non-negotiable)

- No analytics / ads / telemetry / crash-reporter SDK. No backend. No account.
- **Never write microphone audio to disk.** Voice lives in RAM, only while a session is active.
- Logs go through `core.logging` / `RideLinkCore.Logging`, which redact by construction: paths → basename, `peer_id` → 6 chars, `identity_spki_sha256` → 6 hex, `conn_tiebreak` → 6 hex. **Pairing SAS codes, TLS secrets, exporter output and bulk tokens have no log path at all.**
- **mDNS TXT records carry only `{v, dh, plat}`** — `dh` is an ephemeral rotating handle. No `peer_id`, no SPKI or certificate fingerprint or prefix, no token, no library size, no device name. Anyone on the Wi-Fi can read them. Known-peer recognition happens *after* the TLS handshake.
- Never commit keystores, `.jks`, `.p12`, `.pfx`, `.mobileprovision`, private keys, or personal music. `.gitignore` covers these; keep it that way.
- No unnecessary network requests. No STUN/TURN — ICE uses host candidates only.

## Build / test commands

Both apps are scaffolded and these commands run for real. Toolchain: JDK 21, Android SDK 36
(`/opt/homebrew/share/android-commandlinetools`) and Xcode 27 are all installed. There is
deliberately **no global Gradle**: use the project's wrapper.

**On this machine, prefix every Gradle command with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`** (or set
`org.gradle.java.home` in `~/.gradle/gradle.properties`). Without it the daemon runs on the
machine's default Temurin 25, and `detekt` 1.23.8 fails on every module with a bare `25.0.3` for a
message. CI is unaffected — its daemon is JDK 21 — which is why this only bites locally. See
`docs/STATUS.md` §4 problem 17.

```sh
# Android  (from android/)
./gradlew assembleDebug                  # build
./gradlew :core:test                     # JVM unit tests — fast, no device, runs the vectors
./gradlew test                           # all unit tests
./gradlew connectedAndroidTest           # instrumented
./gradlew ktlintCheck detekt lint        # static analysis

# iOS  (from ios/)
swift test --package-path Packages/RideLinkCore          # pure logic, no simulator
xcodebuild -scheme RideLink -destination 'generic/platform=iOS' build
xcodebuild test -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
swiftlint && swiftformat --lint .

# Security spike — re-runnable evidence for the TLS exporter and self-signed X.509 decisions
./tools/spikes/phase1b-tls-exporter/run.sh

# Regenerate the identity vectors (an independent third implementation of the DER encodings)
python3 tools/generate_identity_vectors.py

# Regenerate the trust-gate vectors (ADR-019; an independent third transcription of the table)
python3 tools/generate_session_gate_vectors.py

# Regenerate the Phase 2a voice vectors (ADR-020; both independent third implementations)
python3 tools/generate_voice_signal_vectors.py
python3 tools/generate_voice_fsm_vectors.py

# Regenerate the Phase 2b intercom and AUDIO_STATE vectors (ADR-021; likewise independent)
python3 tools/generate_intercom_vectors.py
python3 tools/generate_audio_state_vectors.py

# Requirements doc (DOCX is read-only input; never modify it)
python3 tools/extract_docx.py docs/RideLink_Requirements_and_Implementation_Plan.docx
```

## Definition of done (per phase)

1. Inspect existing code before changing it. 2. Update docs when architecture moves.
3. Smallest coherent increment. 4. Android builds. 5. iOS builds (when relevant).
6. Unit tests pass. 7. Shared vectors pass on **both** platforms. 8. Integration tests pass where
possible. 9. Static analysis clean. 10. Fix what it found. 11. **Update `docs/STATUS.md`** —
current phase, what was verified (not what was written), tests pending, known problems, exact
next task. 12. Summarize exactly what changed.

**Never call a phase done because the code looks right.** If a step can't be automated, write the
exact manual procedure and record the measured result in `docs/test-results/`.
For latency/drift, collect numbers — not impressions. Report failures and skipped steps plainly.

## Debugging

Reproduce → instrument → isolate → hypothesise → **change one variable** → reproduce →
add a regression vector. Don't change several subsystems at once. Every session state
transition is logged with a monotonic timestamp, prior state, trigger and reason — that log is
the primary debugging artefact.

## Out of scope for V1

Spotify / YouTube Music / Apple Music, cloud sync, >2 peers, group riding, social, messaging,
store publishing, payments, analytics, backend. Resume-able file transfer and partial-manifest
resume are deferred, but the chunk and page framing keep both possible.

## Current phase

**Phase 2b — intercom integration and audio lifecycle. Implementation complete; the real-device
intercom gate is open.**

Phase 0 (hardware feasibility) is complete; do **not** repeat it. Phases 1a, 1b, 2a and 2b are all
implementation-complete and green on both platforms. **The overall "2 Intercom" milestone is not
complete** — its hardware gates (TEST_PLAN A-01, A-02, A-04, A-09 and V-01…V-11) have not run.

**Phase 1b** gave the secure control channel: TLS 1.3 with mutual authentication,
`identity_spki_sha256` pinning, first-meeting SAS pairing and persisted trust, on top of the
Phase 1a discovery/framing/clock-sync layer. The security-state integration bug found after that —
`Connected` being read as implicit pairing success, which let an unknown peer reach `CONNECTED`
before the six digits were shown — is fixed and pinned by shared vectors on both platforms
(rule 15 above, ADR-019, `docs/STATUS.md` §2g).

Both of ADR-007 Amendment A1's open risks are closed with measurements — see
`docs/test-results/phase1b-security-spike-20260827.md`, ADR-017 and ADR-018. **Do not re-open or
re-litigate those two decisions without new measurements.**

**Phase 2a** built the voice plane on top of that channel (ADR-020,
`docs/test-results/phase2a-webrtc-spike-20260828.md`, `docs/STATUS.md` §2i):

- WebRTC pinned **exactly** — `io.github.webrtc-sdk:android:144.7559.14` and `stasel/WebRTC` `exact: "152.0.0"`. Both BSD-3-Clause, Apple's XCFramework SHA-256 verified independently, both binaries read for telemetry (none: no upload endpoint anywhere). **Never widen these to a version range** — a WebRTC minor bump changes a media stack. The Apple pin was `151.0.0` until upstream **deleted that release** and CI 404ed on the binary (ADR-020 Amendment A1, STATUS §4 problem 27): a checksum protects integrity, not availability, so **expect to re-pin this again** and re-verify the macOS slice when you do — losing it would silently take the real media test with it.
- PROTOCOL §7 is now a full specification: `VOICE_OFFER`/`VOICE_ANSWER`/`VOICE_ICE`/`VOICE_STATE`, exact schemas, bounds, and the authentication gate. There is deliberately **no `VOICE_END`** — `VOICE_STATE { state: "closed" }` is the teardown signal.
- **`VOICE_*` is absent from the pre-authentication frame allowlist**, which is the whole of its access control (rule 15's corollary). Adding a voice type to that list is a security change, and a test fails if anyone does.
- The **ADR-010 leader is always the WebRTC offerer**, never the TCP initiator. A follower sends intent; the offerer's response to intent is idempotent, so two simultaneous Start Voice presses produce one negotiation.
- **Host candidates only.** The ICE server list is empty and `VoiceEngineConfig` has *no field* that could carry a STUN or TURN server. Adding one would take a protocol and ADR change, which is the point.
- `VoiceEngine.stop()` and `release()` are **two separate calls**: a control-link blip drops the peer connection but must never close the capture device, because reopening it renegotiates the Bluetooth profile (ARCHITECTURE §6.2/§6.3) and because Android forbids reopening a microphone from the background (§6.4).
- Every decision lives in the pure, mirrored `VoiceNegotiation` table, pinned by `protocol/vectors/voice-fsm/` — not in `VoiceController`. That is the direct lesson of ADR-019 and of STATUS §4 problem 20.

**Real WebRTC media is proven on this machine** — two real engines, host-only candidates, DTLS
connected, `audio/opus` at 48 kHz, deterministic over five runs. It works under `swift test` because
the Apple WebRTC XCFramework carries a macOS slice.

**What is *not* done is anything on a real phone, and no audio has been captured or played
anywhere.** In particular: the Android WebRTC media path has **no test of any kind**
(`PeerConnectionFactory.initialize` needs an Android `Context`); neither `AndroidVoiceAudioSession`
nor `IosVoiceAudioSession` has ever executed on a device, only their pure route mappers are tested;
`RideForegroundService` has never started; every `assumed` value in the two route mappers is a
reasoned guess about unmeasured hardware; and **no latency figure exists**, so the <200 ms target
must not be described as approached, let alone met. The Phase 1b device gate is also still open, and
the Android half of the exporter-equality result was measured against Conscrypt on a laptop rather
than the phone's own TLS stack.

**Phase 2b** turned that transport into an intercom (ADR-021, `docs/STATUS.md` §2m):

- **The transmission gate never touches the capture device** (rule 17 above). PTT, VOX and mute gate the outbound WebRTC audio track; the device opens once, foreground-visible, and stays open for the ride segment. `IntercomTransmission` is the pure mirrored table that decides it, and its action vocabulary has **no** capture case — that absence is the enforcement. `VoiceControllerIntercomTest[s]` counts 50 presses against 1 open and 0 closes; TEST_PLAN **A-10** is the same assertion with a real helmet unit and is **pending**.
- **Full duplex stays primary.** `gate: none` (Modes A and D) is the no-gate policy; PTT and VOX are fallbacks over the *same* live capture path and the *same* WebRTC session.
- **VOX's gate is implemented; its level source is not.** Neither pinned WebRTC distribution exposes a fast per-frame input level, and ADR-021 §6 declines to hand-write a detector to fill the gap. Selecting Mode B today means the gate cannot open, `voxLevelSourceAvailable` is `false`, and the UI says so: **PENDING REAL AUDIO INPUT / LATER HARDENING**.
- **The default is Mode C by architecture, not by measurement.** `docs/PHASE0_RESULTS.md` is still empty, so nothing selected it but ARCHITECTURE §6.3 and ADR-008 §4. Do not present it as validated.
- **`AUDIO_STATE` is implemented, with no wire change**: bounds, the monotonic `revision` on both sides, and the same pre-authentication absence that gates `VOICE_*`. PROTOCOL §4.4's "`intercom_mode` mirrors `VOICE_STATE.mode`" was a contradiction (four values against three) and ADR-021 §3 resolves it — `intercom_mode` is a **superset**, because it describes local audio state rather than a live session.
- **Every `AVAudioSession`/`AudioManager` decision is now in `AudioSessionLifecycle`**, a pure mirrored reducer: `stable -> transitioning -> stable` with a **measured** duration, settled by the platform's own callback and never by a sleep; `shouldResume` read rather than assumed; and a strict generation guard so a callback from before a media-services reset is inert.
- ARCHITECTURE §6.4's readiness sequence is `RideStartPolicy`, pure and mirrored, and its 2^7 cross-product asserts that **no** decision ever opens capture from the background.

**Nothing in Phase 2b ran on a phone, and no audio has been captured or played anywhere.** The
Android WebRTC media path still has **no test of any kind**; `AndroidVoiceAudioSession`,
`IosVoiceAudioSession` and `RideForegroundService` have still never executed on a device; every
`assumed` value in the two route mappers is still a reasoned guess about unmeasured hardware; and
**no latency figure exists.** The setup timings this phase adds (`VoiceSetupTimeline`) measure how
long the *app* took to bring voice up — mouth-to-ear latency (A-09/V-11) includes two Bluetooth hops
and **cannot** be inferred from them or from network RTT, so the <200 ms target must not be described
as approached, let alone met.

Read `docs/STATUS.md` §4 and §7 for exactly what is verified versus pending and for the exact next
task, and `docs/TEST_PLAN.md` §3.1a / §3.1b / §5.1 / §6.1 for the line drawn item by item.
