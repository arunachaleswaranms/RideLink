# RideLink — Status

**Updated:** 3 September 2026 (Phase 2a mailbox hardening — conflated iOS doorbell and non-coalescible terminal peer state, eighth session)
**Current milestone:** M1 (Private voice link) — in progress
**Current phase:** Phase 2a — voice transport foundation.
**Phase 2a status: IMPLEMENTATION COMPLETE — REAL-DEVICE AUDIO GATE PENDING.**
**Phase 1b status: IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING (unchanged).**

> **What is genuinely new evidence this session, and what is not.** Real WebRTC media *was*
> established and measured on this machine: two real `WebRtcVoiceEngine`s, host candidates only,
> DTLS `connected`, `SRTP_AES128_CM_HMAC_SHA1_80`, `audio/opus` at 48 kHz, deterministic over 5 runs.
> That is possible because `stasel/WebRTC`'s XCFramework carries a **macOS** slice, so `swift test`
> links the same binary an iPhone build would ([ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md),
> [evidence](test-results/phase2a-webrtc-spike-20260828.md)).
>
> **No audio was captured or played anywhere, on either platform.** No microphone, no speaker, no
> Bluetooth, no phone. The **Android** media path is untested even locally — `PeerConnectionFactory.initialize`
> needs an Android `Context`. `AVAudioSession` and `AudioManager` have no test coverage at all; only
> their pure route mappers do. See §4 and §7, and TEST_PLAN §3.1a for the line item by item.

> **Fixed this session (§2g), and it was a real security bug, not a tidy-up.** An unknown peer
> could reach `CONNECTED` **before** the six-digit SAS was displayed, let alone confirmed:
> `ControlEvent.Connected` was emitted as soon as duplicate resolution picked a survivor, and
> `SessionCoordinator` — needing *something* to carry `PAIRING -> CONNECTING` — read it as pairing
> success. Both platforms. Every individual mechanism (TLS, the pin, the exporter, the exchange,
> the trust store) was correct and tested; the sentence joining them was wrong, and no test looked
> at the join. `Connected` now means "the trust gate has passed", the gate is a pure shared table
> ([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md)) pinned by
> `protocol/vectors/session-gate/` on both platforms, and the invariant is covered by real-TLS
> integration suites. **Do not treat "CI is green" as evidence about a join no test crosses.**

The two open risks that governed this phase are **closed with measurements, not argument**
([ADR-007 Amendment A1](DECISIONS/ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
required both to be spiked before anything was built on them):

1. **Self-signed X.509 on iOS.** A ~150-line DER encoder plus `SecKeyCreateSignature` produces a certificate that Apple's own parser, BoringSSL **and** OpenSSL all accept, and `SecIdentityCreate` turns it into a `Network.framework` TLS identity with **no PKCS#12 and no key export**.
2. **TLS keying-material exporter.** Both platforms expose one from public API — `android.net.ssl.SSLSockets.exportKeyingMaterial` (API **31**, exactly the ADR-011 `minSdk`, verified against `api-versions.xml`) and `sec_protocol_metadata_create_secret` (iOS 12). For the **same TLS 1.3 connection** an Apple endpoint and a Conscrypt/BoringSSL endpoint produce **byte-identical** exporter output, cross-checked against OpenSSL 3.6.3 as a third stack.

Evidence: [`docs/test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md),
re-runnable via [`tools/spikes/phase1b-tls-exporter/run.sh`](../tools/spikes/phase1b-tls-exporter/).
Decisions: [ADR-017](DECISIONS/ADR-017-identity-key-and-certificate.md) (P-256 identity key, shared
certificate encoder) and [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md) (the SAS
channel binding). **No design review was triggered and nothing weaker was substituted.**

On top of that, the whole secure control channel is implemented and tested on both platforms:
per-device identity in Android Keystore / the iOS Keychain, self-signed X.509 identity
certificates with ADR-012 re-issuance semantics, TLS 1.3 with mutual authentication,
`identity_spki_sha256` pinning that fails closed on mismatch, PROTOCOL §4.5 first-pair SAS
verification with persisted trust, and a pairing/security UI on both. **The Phase 1a plaintext
transport is gone from every production source set** — not gated, deleted — and a mechanical test
fails if a raw socket reappears there.

**What is still not done is running any of it on the two real phones.** That gate was already
open for Phase 1a and this phase does not close it: this machine has no Android device or
emulator, and only the iOS *simulator*. Everything below is a laptop measurement. See §4 and §7.

**Repository state:** Android — **335** unit tests across five modules (was 324), `test ktlintCheck
detekt lint assembleDebug assembleRelease` all green. iOS — `RideLinkCore` **69** tests (was 61),
`RideLinkPlatform` **150** tests (was 139), `RideLink.xcodeproj` builds in **both** Debug and Release
for the simulator with zero warnings. Shared vectors: `protocol/vectors/identity/`,
`protocol/vectors/session-gate/` (120 rows), `protocol/vectors/voice-signal/` (70 rows) and
`protocol/vectors/voice-fsm/` (52 rows) — every one run by **both** platforms from the same file.
Neither the Phase 2a hardening pass (§2j) nor its follow-up (§2k) added a new vector file: their
mailbox and doorbell fixes are pinned by ordinary unit tests, not shared wire vectors, since none
of them has a wire shape of its own — `VoiceNegotiation`'s reducer, which does, is unchanged by
either pass.

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §6 |
| Docs baseline | ✅ Complete (earlier session) | Requirements transcribed, architecture/protocol/test plan/ADR-001…010 written |
| **Architecture correction pass** | ✅ Complete | 15 corrections applied before implementation. Details in §2 |
| **ADR-015/ADR-010 leadership-independence correction** | ✅ **Complete this session** | See §2b |
| **Phase 1a — control-plane skeleton** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING** | Protocol vectors, Android + iOS discovery, plaintext control transport, clock sync, diagnostics UI, hardening pass (§2e). Real-device gate still open — see §7. Its plaintext transport has since been **deleted** (§2f) |
| **Phase 1b — secure control channel** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING** | Both ADR-007 A1 spikes closed with measurements; identity, TLS 1.3, pinning, SAS pairing, trust persistence and UI on both platforms (§2f). The trust-gate security bug found afterwards is fixed and vector-pinned (§2g, ADR-019) |
| **Phase 2a — voice transport foundation** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE AUDIO GATE PENDING** | WebRTC pinned and reviewed on both platforms, PROTOCOL §7 specified in full, the negotiation table shared and vector-pinned, the pre-authentication `VOICE_*` refusal proven over real TLS on both platforms, and **real DTLS-SRTP/Opus media measured on this machine** (§2i, [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md)). No audio captured or played anywhere; the Android media path is untested even locally |
| Phases 2b–8 | ⬜ Not started | The earlier commits named "init phase 2a" and "phase 2a" (`d709c45`, `90cbe12`) were Phase 1b work under a misleading name. Phase 2a proper is the sixth session, §2i |

`protocol/schema/` and `protocol/vectors/` now exist (§2c). `android/` is a real five-module
Gradle project that builds. `ios/` now has all three pieces ARCHITECTURE §9.2 describes:
`Packages/RideLinkCore`, `Packages/RideLinkPlatform`, and `RideLink.xcodeproj` — all three build,
and the app target runs on-simulator with the correct UI.

The build commands in `CLAUDE.md` now run for real on both platforms: `./gradlew` from
`android/`; `swift build`/`swift test` from each package under `ios/Packages/`; and
`xcodebuild -project RideLink.xcodeproj -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build`
from `ios/`.

**Toolchain state (installed and verified 26 Aug 2026):**

| Tool | State | Path |
|---|---|---|
| OpenJDK 21 | ✅ 21.0.12.1, Homebrew `openjdk@21` formula | `/opt/homebrew/opt/openjdk@21` |
| Android SDK platform 36 | ✅ rev 2, `ApiLevel=36`, licences accepted | `/opt/homebrew/share/android-commandlinetools` |
| Android build-tools | ✅ 36.1.0 | …/`build-tools/36.1.0` |
| Android platform-tools | ✅ 37.0.1 (adb 1.0.41) | …/`platform-tools` |
| Gradle | ❌ **not installed globally, on purpose** | the project uses its own committed wrapper |
| Swift / macOS SDK | ✅ Swift 6.3.2, macOS 26.5 SDK | Command Line Tools |
| **Xcode / iOS SDK** | ✅ Xcode 27.0 beta, iOS SDK 27.0 (user-supplied) | `/Applications/Xcode-beta.app` |

**Two toolchain corrections made in the Phase 1b session, both pre-existing and both local-only:**

- `android/gradle.properties`'s `org.gradle.java.installations.paths` pointed at Homebrew's **keg root** (`/opt/homebrew/opt/openjdk@21`) rather than the JDK *home* (`…/libexec/openjdk.jdk/Contents/Home`). The keg root has `bin/java`, so Gradle's toolchain detection accepted it, but it has no `lib/modules`, so the Kotlin compiler failed with `No class roots are found in the JDK path` **the moment it actually had to compile something**. Up-to-date and cached builds never resolve the JDK home at all, which is why it survived earlier sessions as an intermittent failure. Now corrected in the committed file.
- **`detekt` cannot run on this machine without an explicit daemon JVM.** The Gradle daemon inherits the machine's default `java` (Temurin 25); detekt 1.23.8 is handed `25.0.3` as a JVM target, cannot parse it, and every detekt task fails with a bare version string for a message. CI is unaffected — `actions/setup-java` makes the daemon JDK 21 — which is why this was never seen before. `jvmTarget`/`jdkHome` on the task do **not** fix it (verified). The workaround, and the way every detekt result in §3 was produced, is to run Gradle with `-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Recorded in §4 as an open, low-severity problem rather than papered over.

JDK 21 is keg-only and deliberately *not* symlinked into the system JVM directory, so the
machine's default `java` remains Temurin 25 and the build reaches JDK 21 by explicit path. Set
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (or record it as `sdk.dir` in
`android/local.properties`, which is gitignored) and pin the Gradle toolchain to the JDK 21 path.
Note that `/usr/libexec/java_home -v 21` reports the JDK 25 install — it means "at least 21", so
it is not a valid presence check for JDK 21.

**Resolved mid-session:** the user supplied Xcode 27.0 (beta, build `27A5252f`, Apple-signed,
verified genuine) and installed it to `/Applications/Xcode-beta.app`, ran `xcode-select -s`,
accepted the license and ran `-runFirstLaunch`. `swift test` for `RideLinkCore` now runs and
passes 16/16 — see §3 and ADR-011 Amendment A2. §4 problem 10 (below) is resolved; kept in the
table with its resolution noted rather than deleted, per this file's own discipline of recording
what changed rather than erasing history.

**Android Gradle toolchain versions, pinned this session** (all verified by real builds, not
assumed): AGP `9.3.2` (requires Gradle ≥ 9.5.0 — the wrapper targets Gradle `9.7.1`, downloaded
and SHA-256-verified against the official checksum), Kotlin `2.4.10`. AGP 9.x no longer needs
(and rejects) the separate `org.jetbrains.kotlin.android` Gradle plugin — Kotlin support is
built into AGP now; do not re-add that plugin. Compose BOM pinned to `2026.04.01`, `androidx.core`
to `1.18.0`, and `androidx.lifecycle:lifecycle-runtime-ktx` to `2.10.0` — one release newer of any
of these three currently requires `compileSdk 37`, which conflicts with the ADR-011 `compileSdk
36` baseline. **Do not bump these three without also revisiting ADR-011.**

All toolchain prerequisites are installed and verified (table above) — nothing here still blocks
either platform's scaffolding. The only remaining gate for Phase 1a is the two-real-phones test
pass described in §7, which is hardware, not toolchain.

---

## 2. What changed in the correction pass

Documentation, ADRs and repository hygiene only — **no feature implementation**, as instructed.
Fifteen corrections from an independent review of the completed baseline.

| # | Correction | Files touched |
|---|---|---|
| 1 | `CLAUDE.md` removed from `.gitignore` and rewritten to be worth committing | `.gitignore`, `CLAUDE.md` |
| 2 | Minimal 2-line `.gitignore` replaced with a full one: macOS, Gradle/Android Studio, Xcode/SPM, signing material, personal music, transfer temporaries, diagnostic logs | `.gitignore` |
| 3 | **Manifest pagination.** Single-frame `MANIFEST` replaced by `MANIFEST_BEGIN` / `MANIFEST_PAGE` × n / `MANIFEST_END` / `MANIFEST_ABORT`, sized by encoded bytes, with a deterministic digest. 256 KiB frame cap left untouched | `PROTOCOL` §1, §3, §8.1, §9, §10, §11 · `ARCHITECTURE` §1.1, §8.2 · `TEST_PLAN` §2, §3, §4, §5 · `ADR-013` (new), `ADR-006` (amended) · `protocol/README.md` |
| 4 | **Six-digit SAS fixed.** The old "decimal of the first 20 bits" could produce **seven** digits. Now: exporter → first 4 bytes big-endian → `mod 1 000 000` → zero-padded to exactly 6. Ten boundary vectors tabulated with expected values | `PROTOCOL` §4.5.1, §4.5.2 · `ARCHITECTURE` §4.3 · `TEST_PLAN` §2, §3 · `protocol/README.md` |
| 5 | **Identity standardised on SPKI.** `cert_fingerprint` retired everywhere in favour of `identity_spki_sha256`; certificate re-issuance vs key rotation semantics defined | `PROTOCOL` §4.1, §4.5, §4.5.3, §4.6, §8.2 · `ARCHITECTURE` §4.3, §11 · `ADR-012` (new), `ADR-007` (amended) |
| 6 | **`fp6` removed from mDNS.** TXT records are now `{v, dh, plat}` with `dh` an ephemeral rotating handle. Known-peer recognition moved after the TLS handshake | `ARCHITECTURE` §4.1, §11 · `ADR-002` Amendment A1 · `TEST_PLAN` §4, §5 (I-22) |
| 7 | **Simultaneous-connection deduplication defined.** New `conn_tiebreak` field; larger tiebreak's outbound connection survives; deliberately not keyed on `peer_id` | `PROTOCOL` §4.1, §4.2, §4.6, §10 · `ARCHITECTURE` §3, §4.2, §5 · `ADR-015` (new), `ADR-010` Amendment A1 · `TEST_PLAN` §2, §5 (I-15…I-18) |
| 8 | **Platform baselines fixed:** Android 31/36/36 + JDK 21 toolchain; iOS 26.0 | `ARCHITECTURE` §1.2, §10 · `ADR-011` (new) · `README.md`, `CLAUDE.md` · `TEST_PLAN` §4, §8 |
| 9 | **Android background-microphone rules architected.** Foreground-visible start sequence, service types, full permission list, seven failure modes | `ARCHITECTURE` §6.1, §6.4 · `TEST_PLAN` §4.1 (AF-01…AF-10) |
| 10 | **`.allowBluetooth` → `.allowBluetoothHFP`**, plus two distinct audio-session configurations instead of one option set | `ARCHITECTURE` §6.2 · `ADR-016` · `TEST_PLAN` §4.2 |
| 11 | **Bluetooth capability model corrected.** Independent `output_route`/`input_route` replaced by declared `CAPABILITIES.audio` + runtime `AUDIO_STATE`, with `profile_coupling: "input_forces_output"` as the load-bearing field | `PROTOCOL` §4.3, §4.3.1, §4.4, §7 · `ARCHITECTURE` §6.5, §7.3 · `ADR-016` (new) · `TEST_PLAN` §2, §4.2, §5, §6 |
| 12 | **TLS-PSK withdrawn as a claimed fallback.** Contingency is now "stop and run a focused secure-transport design review"; status recorded as *contingency unresolved pending implementation spike* | `ADR-007` Amendment A1 · `ARCHITECTURE` §12 · `PROTOCOL` §4.5.1 |
| 13 | **Module count reduced** from ~20 Gradle modules to 5 (`app`, `core`, `network`, `audio`, `data`) and 4 SPM packages/17 targets to 2 packages. Boundaries preserved and now *compiler-enforced* | `ARCHITECTURE` §2, §9 · `ADR-014` (new) · `TEST_PLAN` §2, §8 · `CLAUDE.md` |
| 14 | **Manual constructor DI** instead of Hilt, with a concrete revisit trigger | `ARCHITECTURE` §10.1, §10.2, §10.3 · `ADR-014` §2 |
| 15 | **Repository-state wording corrected.** `android/`/`ios/` described as planned, not existing. The claim that `.DS_Store` files were tracked was also wrong — they are on disk but were never committed | `STATUS.md`, `README.md` |

**ADRs created:** 011 (platform baselines), 012 (SPKI identity), 013 (manifest pagination),
014 (module structure + DI), 015 (connection dedup), 016 (audio capability model).
**ADRs amended, dated and labelled:** 002 (A1 — no stable identity in TXT), 007 (A1 — secure
transport contingency), 010 (A1 — leadership ≠ connection ownership). 006 updated to point at
ADR-013. No ADR was rewritten in place and none was deleted.

**Verification performed this session:** repository-wide search for the retired terms `fp6`,
`cert_fingerprint`, `.allowBluetooth` (bare), `TLS-PSK`, `Hilt`, the old module names and the old
`MANIFEST` shape; `git diff` and `git status` reviewed; internal Markdown links checked;
requirements DOCX confirmed unmodified; `.gitignore` confirmed no longer ignoring `CLAUDE.md`; no
application code added.

**No builds or tests were run — there is no code to build.** Stated plainly rather than implied.

---

## 2b. ADR-015 / ADR-010 correction (26 August 2026 session)

Before any implementation, corrected an inaccurate rationale in ADR-015 that claimed the
`conn_tiebreak` comparison direction was "chosen so that on the surviving connection the leader is
the acceptor, not the initiator." That claim is **false**: `conn_tiebreak` (ADR-015) and `peer_id`
(ADR-010) are independent random values with no relationship, so leadership lands on either side
of the surviving connection by chance, never as a guaranteed consequence of the dedup rule.

- The dedup **algorithm** is unchanged — only the rationale text was wrong.
- Fixed via append-only amendments, not in-place rewrites: [ADR-015 Amendment
  A2](DECISIONS/ADR-015-duplicate-connection-resolution.md#amendment-a2--26-august-2026--correction-connection-ownership-does-not-determine-leadership),
  [ADR-010 Amendment
  A2](DECISIONS/ADR-010-internal-leader-election.md#amendment-a2--26-august-2026--correction-to-amendment-a1),
  and a corrected paragraph in [ARCHITECTURE §4.2](ARCHITECTURE.md#42-duplicate-and-simultaneous-connections).
- `protocol/vectors/dedup/dedup_vectors.json` now encodes the corrected property directly:
  `initiator-not-assumed-leader` and `acceptor-not-assumed-leader` are two vectors with identical
  dedup mechanics but opposite leader assignment, so an implementation cannot pass both while
  assuming either correlation.

## 2c. Phase 1a scaffolding (26 August 2026 session)

**Protocol (`protocol/`):**
`schema/envelope.schema.json` (JSON Schema, informational/normative reference) and four vector
files: `vectors/envelope/` (11 cases: round-trip, unknown-field/type tolerance, missing-field/
null-payload/malformed-value/malformed-JSON rejection, version mismatch, the 262144-byte cap
accepted and 262144+1 rejected), `vectors/sas/` (the 10 PROTOCOL §4.5.2 values transcribed
verbatim + 2 property vectors), `vectors/dedup/` (6 vectors, see §2b), `vectors/session-fsm/`
(27 legal transitions, 10 illegal, 3 non-fault non-transition cases for duplicate-connection
close). The frame-size vectors use a padding recipe (documented in `protocol/README.md`) instead
of committing literal 256 KiB fixtures.

**Android (`android/`):** five Gradle modules (`app`, `core`, `network`, `audio`, `data`), Gradle
wrapper committed (`gradlew`, verified against the pinned distribution's published SHA-256).
`core` (pure `kotlin("jvm")`, no `android.*` on its classpath) implements: `model` (7 REQUIREMENTS
§16 entities + `PeerId`/`SessionId`/`SpkiHash`/`ConnTiebreak`/`ContentHash`, each redacting its own
`toString()`), `protocol` (`Envelope` + `EnvelopeCodec` via kotlinx.serialization, `Sas`, `Dedup`,
`Leadership`), `sessionfsm` (the 10-state pure FSM, `FsmState` carrying `returnTo` for
RECONNECTING per ARCHITECTURE §3 rule 1), `logging` (`Redactor` + `StructuredLogger` +
`InMemoryLogSink`). `network.discovery` implements `NsdDiscoveryController` (advertise + browse,
TXT limited to `{v, dh, plat}`, `DiscoveryHandle` via `SecureRandom`). `app` wires a
`SessionCoordinator` (owns FSM state + discovered-peer list) through manual DI
(`AppContainer`) into a minimal Compose screen matching CLAUDE.md's Phase 1a UI spec exactly.
`audio` and `data` are placeholder modules (compilable, empty) establishing the module boundary
only — no Phase 2+ logic added early.

**iOS (`ios/`):** all three ARCHITECTURE §9.2 pieces now exist.

- `Packages/RideLinkCore` — a Swift Package porting the same domain logic 1:1:
  `Model/Identifiers.swift` + `Entities.swift`, `Protocol/{JSONValue,Envelope,EnvelopeCodec,Sas,Dedup}.swift`,
  `SessionFSM/SessionFsm.swift`, `Logging/Logging.swift`. Vector-driven tests exist for all four
  protocol vector files plus the same redaction regression tests as Android, using **XCTest**
  rather than Swift Testing — see §4 problem 10 (resolved) for why.
- `Packages/RideLinkPlatform` — a second Swift Package, depending on `RideLinkCore`, implementing
  `Discovery/BonjourDiscovery.swift` (`NWListener`+`NWBrowser`, TXT limited to `{v, dh, plat}` via
  `NWTXTRecord`, `DiscoveryHandle` via `SecRandomCopyBytes`) — the iOS mirror of Android's
  `NsdDiscoveryController`. Builds and tests (2, pure `DiscoveryHandle` format checks) pass; the
  live `NWListener`/`NWBrowser` wiring is unverified for the same reason as Android's — no second
  peer to discover in this environment.
- `RideLink.xcodeproj` — a hand-authored project (there is no Apple CLI for scaffolding a fresh
  Xcode project; `xcodegen`/`tuist` weren't installed without asking first, so this was written
  directly and verified by building, the same way the Gradle/AGP version issues were resolved
  earlier this session) with one app target, `RideLink/{RideLinkApp,SessionCoordinator,MainScreen}.swift`
  + `Info.plist`, local Swift package dependencies on both packages above, iOS 26.0 deployment
  target, Swift 6. **Builds and runs** on the iPhone 17 Pro Max simulator — confirmed with an
  actual screenshot showing "RideLink / Device: iPhone 17 Pro Max / Connection: Idle /
  [Start Discovery]", matching CLAUDE.md's Phase 1a UI spec exactly. Device builds need a
  development team (CLAUDE.md "Apple Signing" — a personal choice, not made here).

---

## 2d. Phase 1a control transport, discovery lifecycle and diagnostics UI (27 August 2026 session)

Completes steps 8–10 of §7's ordered list from the previous session, plus fixes to step 7
(discovery) that the previous session had left unverified. No Phase 1b work (TLS, identity,
pairing) was started — see the explicit non-goals at the end of this section.

**`core.sync` / `RideLinkCore.Sync` (step 9):** a pure clock-offset estimator matching
ARCHITECTURE §7.1 — `rtt`/`offset` from the four PING/PONG timestamps, outlier rejection (discard
any sample whose rtt exceeds 2× the window minimum), minimum-RTT sample selection, EWMA smoothing
(α = 0.2), and the 30 ms step-rejection-with-two-window-confirmation rule. All arithmetic is
exact-rational integer math (no floating point) so Kotlin `Long` and Swift `Int64` divide
identically. Two implementation parameters ARCHITECTURE §7.1 leaves as prose — the jitter formula
and the step-confirmation tolerance — are pinned by `protocol/vectors/clock/clock_vectors.json`
(16 vectors: ideal symmetric RTT, low-RTT sample selection, a high-latency outlier, jitter under
varying RTT, an asymmetric-path example documenting the known NTP-style limitation, positive/
negative offsets, an integer-overflow-boundary sanity check, two "no valid samples" cases, a
rejected-then-confirmed 50 ms step, and four EWMA convergence steps). **Both platforms pass all 16
byte-for-byte** (`./gradlew :core:test`, `swift test --package-path ios/Packages/RideLinkCore`).

**`network.control` / `RideLinkPlatform.Control` (step 8) — `PlainControlTransportPhase1a`,
explicitly named and documented as plaintext/debug-only, never to be mistaken for the Phase 1b
transport:**

- Framing: `uint32` BE length prefix + JSON body, 262144-byte cap. The length is validated
  **before** any payload buffer/receive is requested — proven by a test that declares an
  oversized length with no body and asserts the read returns `frameTooLarge` promptly rather than
  hanging or allocating (both platforms).
- HELLO/HELLO_ACK per PROTOCOL §4.1, using the existing `EnvelopeCodec`/`Envelope` — no second
  JSON protocol. Phase 1a has no real identity yet: `identity_spki_sha256` is a fixed,
  documented, non-security-bearing sentinel (`ProvisionalIdentity` / ADR-012's field populated
  with a structurally-valid placeholder), and `peer_id` is a random value generated once per
  process start, not persisted, not a Phase 1b durable identity.
- **Real-socket duplicate-connection resolution** (PROTOCOL §4.2 / ADR-015), wired end to end:
  `DuplicateConnectionArbiter` holds candidate sockets until both `conn_tiebreak` values are
  known, applies `core.protocol.Dedup`, and — because a rival can complete its handshake a moment
  after this one does — a lone candidate is held 300 ms (documented, tunable implementation
  constant, not a protocol value) before being declared the survivor. Tested with **two
  independent `ControlSessionManager`/`ControlSessionManager`(actor) instances dialling each
  other over real loopback TCP at once** on both platforms: exactly one survivor, the loser
  closes cleanly with `BYE{duplicate_connection}`, `reconnect_count` is untouched, and both sides
  independently agree on the leader (ADR-010) with no correlation to which side's connection
  survived (ADR-015 Amendment A2) — asserted directly in the test.
- `TCP_NODELAY` set on every socket; OS-level `SO_KEEPALIVE` best-effort; the PROTOCOL §1
  application PING/PONG (2 s / 6 s-lost) remains authoritative for session health, as specified.
- Reconnect: the exact PROTOCOL §10 ladder (0.5, 1, 2, 4, 8, 8, 8… s, ±20 % jitter, 120 s budget),
  pure and tested with an injected delay recorder — no `Thread.sleep`/real `Task.sleep` in the
  test, and a separate test proves the 120 s budget is honoured before `DISCONNECTED`.
- Clock sync wired to the wire: an 11-sample, ~50 ms-spaced burst runs at `CONNECTED` and every
  10 s thereafter (ARCHITECTURE §7.1's two cadences), each burst run through `ClockSync` to update
  `offset_us`/`jitter_us`/`rtt_ms` in the diagnostics snapshot.

**Discovery lifecycle fixes (step 7, both platforms):**

- Android: **`NsdManager.ServiceInfoCallback` (API 34+)** used for resolution/live-update
  tracking; **API 31–33** falls back to legacy `resolveService`, now with a **fresh
  `ResolveListener` per call** (the previous session's implementation reused one listener across
  concurrent resolutions, which is unsafe). iOS: `NWBrowser.Result.Change` (`.added`/`.changed`/
  `.removed`) drives Found/Updated/Lost directly — `.removed` recovers the discovery handle from
  the browser's own cached TXT metadata, no extra resolve needed.
- Platform-neutral `Found`/`Updated`/`Lost` event model on both platforms (`DiscoveryEvent`),
  extracted into pure, unit-tested logic (`DiscoveryLifecycleTracker` on Android; `NWBrowser`'s
  own change set on iOS) — repeated discovery of the same peer never re-emits `Found`, losing a
  peer removes it, and a subsequent rediscovery is `Found` again.
- Self-filtering by discovery handle, not IP/hostname/peer_id, on both platforms.
- `dh` rotation: regenerated on advertise start and at least every 15 minutes thereafter
  (`DiscoveryHandleRotationPolicy`, identical constant/logic on both platforms, unit-tested
  against an injected clock — no real 15-minute wait in any test). Rotation re-registers the
  Android `NsdServiceInfo` / re-assigns the iOS `NWListener.service` in place; neither touches the
  TCP listener socket or any live control connection.
- **Advertising now shares the control listener's socket** on both platforms — no second, unused
  TCP port. Android: `NsdDiscoveryController.advertise(name, port)` takes the real port from
  `ControlSessionManager.startListening()`. iOS: `BonjourDiscovery.startAdvertising(on: listener)`
  takes the already-bound `NWListener` from the control layer directly and attaches the Bonjour
  service registration + TXT rotation to it in place.
- TXT record content is exactly `{v, dh, plat}` on both platforms, asserted by a dedicated
  privacy test run against the **same function** (`buildTxtRecord` / `buildTxtRecord`) the real
  advertise path calls — an accidental future field addition fails this test, not a manual review.

**Diagnostics UI (step 10, both platforms):** device identity, FSM connection state, discovered-
peer count (current + cumulative), and a Phase 1a diagnostics card — control state, peer,
`is_local_leader`, RTT, clock offset, clock jitter, reconnect count — plus a highly visible
`TRANSPORT: PLAIN / PHASE 1A / NOT SECURE` banner (yellow/amber on both platforms). Verified on
iOS by installing and screenshotting on the iPhone 17 Pro Max **simulator** (not a physical
device) — renders correctly, matches the spec. Android has no emulator/device available in this
environment (no `adb`, no AVD configured) — verified by `./gradlew assembleDebug` only, **not**
run or screenshotted. See §7 for exactly what that leaves pending.

**`SessionCoordinator` end-to-end wiring (both platforms):** `Start Discovery` → bind the control
listener → advertise + browse concurrently → first discovered peer auto-selected (`PeerSelected`
then `PairingSucceeded` applied immediately — Phase 1a has no pairing UI to tap into yet, see the
explicit non-goal below; this is a Phase 1a simplification of *when* those two already-legal FSM
transitions fire, not a new transition) → outbound `connectTo` → duplicate resolution → HELLO
exchange → `ConnectionEstablished`/`ReconnectSucceeded` on `ControlEvent.Connected`,
`ConnectionFailed`/`LinkLost(NETWORK)` (state-dependent) on a failed/lost link,
`DuplicateConnectionClosed` and `ReconnectBudgetExhausted` mapped straight through → `CONNECTED`.
`ENDING`'s existing `ReleaseAudioAndStopForegroundService` effect now also tears down the control
session and discovery (no separate rule needed — one owner, one teardown path, per ARCHITECTURE
§3 rule 4).

**Explicitly not started, per CLAUDE.md rule 28 / this session's brief §28:** production TLS,
self-signed X.509, Android Keystore / iOS Keychain identity, SPKI pin *runtime* checking, TLS
exporter, real pairing SAS, pairing trust persistence, QR fallback, WebRTC, audio, music. The
`ProvisionalIdentity` sentinel values exist solely to satisfy Phase 1a's wire shape and are never
treated as security-bearing anywhere in the new code.

---

## 2e. Phase 1a cleanup / hardening pass (27 August 2026 session, second)

An independent review of the §2d implementation found ten real defects — not stylistic issues —
each confirmed by reproducing it in a test that failed before the fix and passes after. No Phase
1b work (TLS, identity, pairing) was touched; no protocol/wire shape changed. All fixes are code-
and test-only.

1. **iOS PING/PONG race.** `ControlSessionManager.sendPingAndAwait` wrote the PING frame *before*
   registering the waiter in `pendingPings`. Because `writeFrame` suspends, a fast (e.g. loopback)
   peer's PONG could be processed by this same actor before the waiter existed, silently dropping
   it until the 3 s timeout. Fixed by registering the waiter synchronously inside
   `withCheckedThrowingContinuation`'s body, before any suspension. Android's ordering was already
   correct (reviewed, not changed) — but its write-failure path leaked the pending entry, fixed
   alongside.
2. **Reconnect re-entrancy, both platforms.** `ReconnectController`'s own attempts called the
   *public* `connectTo`, which emits `.linkLost`/`LinkLost` on failure. `SessionCoordinator` reacts
   to that event by calling `beginReconnect` again, starting a second ladder on top of the first.
   Fixed by splitting `connectTo` (top-level, may emit) from a new internal `attemptConnection` /
   `attemptConnection` (used only by the reconnect ladder, never emits). **While validating this
   fix, found a second, independent bug it depends on**: iOS's `ReconnectController.start()`
   wraps `Task.sleep` in `try?`, which swallows the `CancellationError` that would otherwise stop
   the loop — so `cancel()` merely dropped the caller's reference while the loop kept running
   *detached*, still calling `onAttempt` on schedule. Fixed by checking `Task.isCancelled`
   explicitly at each loop step. **A third, separate bug surfaced testing this on iOS**:
   `ControlConnection.connect` had no timeout — `NWConnection` can sit in `.waiting` (e.g. on
   `ECONNREFUSED`) indefinitely rather than surfacing `.failed`, so a reconnect attempt against an
   unreachable peer could hang forever. Fixed with a `connectTimeoutMs` race (default 5000 ms,
   matching Android's existing `ControlSocket.connect` timeout) using the `SingleResumeContinuation`
   pattern — not `withThrowingTaskGroup`, which does not actually cancel a sibling task blocked on
   a raw continuation (confirmed by reproducing the hang with a minimal repro before writing the
   fix).
3. **Plaintext transport not enforced as debug-only.** Documentation said `PlainControlTransportPhase1a`
   is debug-only; nothing enforced it. Android: `AppContainer.sessionCoordinator` is now `SessionCoordinator?`,
   gated by a pure `gatedByPlaintextTransport(allowed = BuildConfig.DEBUG) { ... }` helper —
   `NsdDiscoveryController`/`ControlSessionManager` are never *constructed* in a release build, not
   just unused; `MainActivity` shows `SecureTransportUnavailableScreen` when `null`.
   `buildConfig = true` added to `app/build.gradle.kts`. iOS: `PlaintextTransportGate.makeSessionCoordinator()`
   wraps `SessionCoordinator()` in `#if DEBUG`/`#else nil#endif` — a **compile-time** exclusion
   (confirmed `SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG` is set only for the Debug build
   configuration in the checked-in `project.pbxproj`); `RideLinkApp` shows `SecureTransportUnavailableView`
   when `nil`.
4. **Android NSD `ServiceInfoCallback` leak (API 34+).** `resolveModern` registered a callback per
   `onServiceFound` with nothing ever unregistering it. Added `ServiceInfoCallbackRegistry` (pure,
   no `android.*`) tracking registrations by service name; wired to unregister on service-lost,
   browse-stop, teardown and registration-failure, and to safely replace (not leak) a duplicate
   registration for the same service name.
5. **mDNS instance-name privacy leak, both platforms.** Android's advertised service name was
   `"RideLink-${Build.MANUFACTURER} ${Build.MODEL}"`. iOS's `NWListener.Service` had no explicit
   `name:`, which falls back to the device's Bonjour name (tied to `UIDevice.current.name`) — a
   leak that existed even though the TXT record itself was already privacy-clean. Both now use a
   neutral `"RideLink-" + dh.take(8)` derived from the rotating discovery handle
   (`instanceServiceName`, mirrored on both platforms), rotating exactly when `dh` rotates.
6. **Unsafe PING/PONG field extraction, both platforms.** Android used `payload[key]!!.jsonPrimitive.long`,
   which throws (killing the read-loop coroutine) on a missing/wrong-typed/non-numeric field.
   Replaced with `requiredLongField`/`requiredBooleanField`, which validate presence, JSON type
   (rejecting quoted-string numbers) and representability, returning `null` instead of throwing.
   iOS's `guard let ... else { return }` shape was already safe against missing/wrong-typed
   fields, but `JSONValue.int64Value` used the non-failable `Int64(_:)` on a `Double`, which
   **traps** (crashes the process, not a thrown `Error`) on NaN/infinite/out-of-range input —
   fixed with `Int64(exactly:)`.
7. **Discovery-handle rotation self-race, both platforms.** Rotating `dh` flips the "self" handle
   synchronously, but the old advertisement can still be resolvable for a short window afterward
   (Android: `unregisterService` is async; iOS: `NWListener.service` reassignment has no completion
   callback at all) — during that window a stale self-resolution could be misread as a newly
   discovered peer. Added `SelfDiscoveryHandles` (pure, mirrored on both platforms) tracking
   current + previous handle; Android clears "previous" on the old registration's confirmed
   `onServiceUnregistered`, iOS on a bounded 1 s grace period (no equivalent OS callback exists).
8. **iOS `@unchecked Sendable` review — `BonjourDiscovery`.** Confirmed the documented invariant
   ("every access confined to `queue`") was **violated** in three places: `startBrowsing`/
   `stopBrowsing` mutated `browser` directly on the caller's thread; `stopAdvertising` cancelled
   `rotationTask` directly on the caller's thread; and `listener.serviceRegistrationUpdateHandler`
   (which fires on `ControlSessionManager`'s listener queue, not `BonjourDiscovery`'s own) read
   `selfHandles` without hopping onto `queue` first. All three fixed; no new `@unchecked Sendable`
   added anywhere.
9. **Control-task teardown, both platforms.** `shutdown()` cancelled the read/keepalive/clock-sync/
   reconnect tasks and closed the active socket, but did **not** close candidate sockets the
   `DuplicateConnectionArbiter` was still holding mid-resolution — added `arbiter.drainAll()`,
   closed on shutdown. Also added a dedicated `isShutDown` flag (distinct from `endedDeliberately`,
   which also becomes true after an ordinary BYE) so a handshake/dedup resolution already in
   flight when `shutdown()` was called cannot "resurrect" a connection afterward; reset on the
   next `startListening`, since `ControlSessionManager` is reused across sessions. Fixing this
   surfaced Android's `failAllPendingPings` iterating a live `ConcurrentHashMap` with
   `.keys.toList()`, which threw `NoSuchElementException` under concurrent modification from an
   in-flight keepalive/clock-sync coroutine — fixed using `ConcurrentHashMap.forEach`, the JDK's
   documented concurrent-safe traversal.
10. **Clock-sync live-wire input validation, both platforms.** PONG's `t2`/`t3` are peer-controlled.
    `ClockSync.Sample.rttUs`/`offsetUs` use plain `Int64`/`Long` subtraction on both platforms —
    trapping (crashing) on overflow in Swift, silently wrapping to a wrong-but-plausible value in
    Kotlin. Added `isPlausibleClockSample` (both platforms) using overflow-*reporting* arithmetic
    to reject a sample before it is ever constructed. Reproduced and confirmed the iOS crash with
    a concrete wire-reachable input (`t2 = 9223372036854774784`, `t3 = -9223372036854775808` —
    both individually exact `Double`→`Int64` round-trips, so both pass the field-type check from
    fix 6, but their difference overflows `Int64`) before writing the fix; the regression test
    uses that exact input. `ClockSync`'s own algorithm and `protocol/vectors/clock/` are
    **untouched** — its existing `rttUs > 0` outlier filter already rejects the *result* of a
    non-overflowing bad sample; this fix only prevents the arithmetic itself from overflowing.

**Verification:** every fix above has a dedicated regression test (new files:
`PingRaceAndReconnectTest[s]`, `MalformedPingPongTest[s]`, `ServiceInfoCallbackRegistryTest`,
`SelfDiscoveryHandlesTest[s]`, `TeardownTest[s]`, plus additions to `DiscoveryPrivacyTest[s]` and
`TransportGateTest`). `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease`
and `swift test` for both packages plus `xcodebuild` Debug **and** Release simulator builds all
pass — see §3 for the exact commands and results. Static analysis thresholds touched: `detekt.yml`
`TooManyFunctions.thresholdInClasses` raised 20 → 24 for `ControlSessionManager`, documented in
the config file itself with the same style-calibration precedent as prior sessions' adjustments.

---

## 2f. Phase 1b — secure control channel (27 August 2026 session, third)

### The two spikes, run first

ADR-007 Amendment A1 required both to be answered before anything depended on them, and named the
response to either failing: stop and run a focused design review, never substitute something
weaker. **Both passed.** Full method, raw numbers and caveats:
[`test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md);
harness: [`tools/spikes/phase1b-tls-exporter/`](../tools/spikes/phase1b-tls-exporter/).

Findings that changed what got built:

1. **Apple cannot supply a zero-length exporter context.** `sec_protocol_metadata_create_secret_with_context(…, context_len: 0, …)` returns nil — tested with a valid pointer, so the nil could not be blamed on a bad one. PROTOCOL §4.5.1 said `context = zero-length, but PRESENT`, which is an RFC 5705 distinction that **does not exist in TLS 1.3** (RFC 8446 §7.5 always hashes a context value). Measured `null == empty` on the one stack that can express both. §4.5.1 is reworded and each platform's concrete call is now named in the spec; **`protocol/vectors/sas/` is untouched**, because it starts from exporter *output*. [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md).
2. **Conscrypt's server-side `SSLSession.getProtocol()` misreports TLS 1.3 as `TLSv1.2`** on a connection where only TLS 1.3 was ever enabled and a TLS-1.3-only cipher suite was negotiated. So the "no silent 1.2 fallback" check is **not** an assertion on `getProtocol()` — that would reject good sessions. It is enforced structurally (`setEnabledProtocols(["TLSv1.3"])` / `set_{min,max}_tls_protocol_version(.TLSv13)` on both ends) plus an assertion on the negotiated **cipher suite**.
3. **Android does not use `KeyGenParameterSpec`'s auto-issued certificate** — the expected path. It issues that certificate *at key-generation time* and offers no way to issue a new one around an existing Keystore key, which makes ADR-012's whole re-issuance model unreachable. Both platforms therefore encode their own certificate with a shared DER encoder. [ADR-017 §3](DECISIONS/ADR-017-identity-key-and-certificate.md).
4. **`SecIdentityCreateWithCertificate` is macOS-only** (`SEC_OS_OSX` / `__IPHONE_NA`) and is the function most iOS examples reach for. `SecIdentityCreate(nil, cert, key)` is the iOS-available one, takes the key directly, and needs no PKCS#12 — so the private key is never exported.

### What was built

**Shared, pure, vector-pinned** (`core.security` / `RideLinkCore.Security`, mirrored line for line):
`Der` (a minimal DER **encoder**, no parser, no ASN.1 framework), `IdentityCertificate` (P-256
SubjectPublicKeyInfo, `identity_spki_sha256`, the TBSCertificate, the ADR-012 validity window),
`UtcTime` (**the project's only wall-clock type**, with Howard Hinnant's exact civil-date
algorithm rather than `java.time`/`DateFormatter`, so the two platforms cannot drift over a locale
or a calendar), `PeerTrust` (the pin decision as a pure function) and `TrustedPeerStore`.

New shared vectors: **`protocol/vectors/identity/`** — DER length and INTEGER encodings at their
boundaries, the 91-byte P-256 SPKI, `identity_spki_sha256` formatting (uppercase rejected), an
exact TBSCertificate, the eight pin decisions, and certificate-validity boundaries. Generated by
`tools/generate_identity_vectors.py`, an independent third implementation, and cross-checked: the
generated certificate parses correctly under `openssl x509`, and the generated SPKI hash matches
what OpenSSL computes for the same key.

**Android** (`network.security`, `data.trustedpeers`): `AndroidKeystoreIdentityStore` (P-256 in
Android Keystore, non-exportable, `AfterFirstUnlock`-equivalent so it works with the screen
locked), `IdentityIssuer` (kept free of `android.*` so the encoding, signing and point extraction
are JVM-testable), `TlsControlChannel` (TLS 1.3 only, `needClientAuth`, a deferring trust manager
because trust is the pin one layer up), `FileTrustedPeerStore` and `LocalPeerIdStore` (atomic
writes, corrupt-file tolerance, pin-replacement refusal).

**iOS** (`RideLinkPlatform.Security`): `DeviceIdentityStore` (Keychain P-256; an `.ephemeral`
storage mode exists **only** so an unsigned `swift test` binary can exercise the rest),
`PeerCertificateInspector` (SPKI via `SecCertificateCopyKey`, structural validity via
`SecTrustEvaluateWithError` against the certificate as its own anchor — not a chain or hostname
check), `TlsControlChannel`, `FileTrustedPeerStore`, `LocalPeerIdStore`.

**Both:** a `ControlChannel`/`ChannelSecurity` seam so `ControlSessionManager` never imports a TLS
type and `PeerTrust` never imports a socket type; the SPKI pin check wired into
`ControlHandshake`; `PairingExchange` (PROTOCOL §4.5, with the SAS never leaving the device);
persisted trust; and a pairing card + security-warning card in both UIs. The transport banner is
now **green when the link really is TLS 1.3** — a banner that keeps crying wolf after the
transport is secure trains the user to ignore it.

### The plaintext transport is deleted, not gated

Phase 1a shipped `PlainControlTransportPhase1a` in the production source set and used
`BuildConfig.DEBUG` / `#if DEBUG` to avoid *constructing* it. Phase 1b removes it from `main`
entirely: the only plaintext `ControlChannel` in the repository is a fixture in
`network/src/test` / `RideLinkPlatformTests`, so it is not compiled into either library and no app
build — debug or release — contains those bytes. The old gate (`TransportGate.kt`,
`PlaintextTransportGate.swift`) is gone, replaced by `SecureTransportPolicy` (a composition-root
assertion) and by **`PlaintextTransportAbsenceTest`**, which reads `network/src/main` and fails if
a raw socket, a reference to the fixture, or a second `ControlChannel` implementation ever appears
there. That is the part that keeps being true after this session.

### Bugs found and fixed while building it

Each was reproduced before being fixed, and each has a regression test.

1. **`ControlHandshake` could be crashed by a malformed HELLO, on both platforms.** It built `PeerId`/`ConnTiebreak` straight from wire strings with constructors that `require`/`precondition` — so `"peer_id": "NOTHEX"` threw out of the handshake coroutine (Android) or trapped the process (iOS). This is the same class the §2e hardening pass fixed for PING/PONG; HELLO was not covered then because Phase 1a had no security-bearing field in it. Fixed with non-throwing `parse` constructors on `PeerId`, `ConnTiebreak` and `SpkiHash`, used for every wire-sourced value.
2. **A handshake write racing a close threw instead of reporting a closed connection.** When one side refuses a certificate before replying, production closes the socket — and the peer's in-flight `writeFrame` then threw an `IOException` out of `performAsInitiator`, leaving the socket unclosed and producing no outcome at all. Surfaced by the expired-certificate test. Fixed by reporting a failed write as `ConnectionClosed`, exactly as a failed read already was.
3. **`gradle.properties` pointed at a non-JDK** (§1). Intermittent by nature; now correct.
4. **`detekt` has never actually run on this machine** (§1, §4 problem 17). CI was green throughout, which is precisely why nobody noticed.

### Explicitly not started

WebRTC, microphone capture, the Opus pipeline, Bluetooth routing, intercom UX, music playback,
music sync, local music transfer, manifest transfer, Ride Mode, navigation announcements, group
sessions, cloud/backend, accounts, streaming services. Also deliberately **not** built: the manual
`host:port` / QR fallback for blocked mDNS — it is Phase 1b scope in ARCHITECTURE §4.4 but is a
*discovery* feature with no security content, and it is listed in §7 as the first follow-up.

---

## 2g. Phase 1b — security-state integration fix (27 August 2026 session, fourth)

Scope was deliberately one bug. No new feature, no Phase 2, no redesign of the crypto.

### The bug

An **unknown** peer could drive the application FSM to `CONNECTED` before SAS pairing had even
been offered. On both platforms:

```
TLS 1.3 handshake succeeds
  -> certificate / SPKI checked         (correct)
  -> candidate wins duplicate resolution (correct)
  -> ControlSessionManager emits Connected     <-- too early
  -> SessionCoordinator sees Connected while the FSM is in PAIRING
  -> applies PairingSucceeded                  <-- a lie
  -> PAIRING -> CONNECTING -> CONNECTED
  -> ...and only now beginPairing(), PairingRequired, the six-digit code
```

Reproduced before anything was changed, as a `network`-module test against the real TLS channel
with two unpaired peers:

```
A announced Connected before SAS pairing completed:
  [Connected(remotePeerId=peer:bbbbbb…, sessionId=…, isLocalLeader=true),
   PairingRequired(remotePeerId=peer:bbbbbb…)]
```

and confirmed on iOS by temporarily restoring the old emit order, which fails the new suite with
`["Connected", "PairingRequired"]`.

**Why nothing caught it.** Every mechanism had tests and they all passed: `PairingExchangeTest[s]`
proved no pin is written until both sides confirm; `TlsControlChannelTest[s]` proved an unknown
peer produces `pairing_required` and a known one connects silently; the FSM vectors proved
`PAIRING -> CONNECTING` needs `PairingSucceeded`. What had no test was the *join* — which control
event the coordinator turns into which FSM event — because it lived as a `when`/`switch` inside a
platform class that no suite could construct (`SessionCoordinator` needs `NsdDiscoveryController`
on Android and is in the untested app target on iOS). CI run 33098708512 was fully green over this
bug.

### The fix

[ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md). Same shape on both platforms.

1. **`ControlEvent.Connected` has one meaning:** *the surviving secure connection has passed the RideLink trust gate and may be treated as authenticated.* Emitted from exactly one function, `activateAuthenticatedSession`, and never from the handshake or from promotion.
2. **New `ControlEvent.PeerTrusted`** carries the silent path (stored pin matched), so `Connected` no longer has to double as pairing success. Trusted: `PeerTrusted` → `Connected`. Unknown: `PairingRequired` → *(two humans)* → `PairingSucceeded` → `Connected`.
3. **`promote()` no longer announces a connection.** It records the survivor's facts, starts the *transport* tasks (read loop, keepalive — pairing frames arrive on that same socket, and a link that dies mid-pairing has to be noticed), and then either pairs or activates. Diagnostics show `CONNECTING`, not `CONNECTED`, while a code is on screen.
4. **`SessionCoordinator` no longer decides.** The `(ControlEvent, status) -> SessionEvent?` table is now `SessionGate` — pure, mirrored, and pinned by `protocol/vectors/session-gate/gate_vectors.json`, the complete 120-row cross-product, run by **both** platforms. The coordinator still owns the `FsmState` (CLAUDE.md rule 8) and does the side effects.
5. **The FSM is untouched.** No new state, no new `SessionEvent`, no changed transition; `protocol/vectors/session-fsm/` passes unchanged. `PAIRING` and `CONNECTING` stay distinct.
6. **Pairing completes on the socket that is already open.** A second handshake would produce a second exporter, so the code the users compared would no longer bind the session in use (ADR-018). A test counts the transport's dials: one per side across the whole flow.
7. **The clock-sync burst moved behind the gate.** ARCHITECTURE §7.1 places it at `CONNECTING`, which is now genuinely post-authentication.
8. **A pre-authentication frame allowlist** — `PING`, `PONG`, `PAIR_*`, `BYE`, `ERROR` — so a Phase 2 message type is inert before authentication unless added deliberately. `PING`/`PONG` can never mark authentication complete.
9. **Failure closes deliberately.** A refusal writes no pin, clears both codes, sends `ERROR{fatal}` and ends the connection as `user_ended`, so the reconnect ladder cannot silently re-offer a pairing someone refused.

### Two smaller bugs found and fixed on the way

| # | Bug | Fix |
|---|---|---|
| 1 | **A link lost in `PAIRING` wedged the session.** Neither `LinkLost` variant is legal in `PAIRING`, so the FSM rejected it and the session sat there forever with no prompt and no way forward. Pre-existing (a failed *first* dial did it too), but the fix makes `PAIRING` last much longer, so it went from rare to routine | `SessionGate` maps both `LinkLost` reasons in `PAIRING` to `PairingRejectedOrTimeout` → `DISCOVERING`. `connect_attempted` is deliberately **not** re-armed, so a refusal is not re-offered by the next mDNS `Found` |
| 2 | **A peer's rejection left the other side showing a dead code.** The rejecter's `ERROR{fatal}` was handled as a plain link loss, so the receiving side kept its `PairingExchange` and its six digits on screen for a socket that was gone | A fatal `ERROR` arriving mid-pairing is treated as a pairing failure carrying the peer's code — and the code is surfaced **only** if it is one of PROTOCOL §4.6's defined codes, so a remote peer cannot choose the text of a security message |

### What was added, and where

| | Android | iOS |
|---|---|---|
| Trust-gate table | `network/control/SessionGate.kt` | `RideLinkPlatform/Control/SessionGate.swift` |
| Event semantics | `ControlSessionManager.kt` — `PeerTrusted`, `activateAuthenticatedSession`, reworked `promote`/`succeedPairing`/`failPairing`, pre-auth frame allowlist | `ControlSessionManager.swift`, the same |
| Coordinator | `app/session/SessionCoordinator.kt` — now applies what the gate returns | `RideLink/SessionCoordinator.swift`, the same |
| Shared vectors | `protocol/vectors/session-gate/gate_vectors.json` + `tools/generate_session_gate_vectors.py` (an independent third transcription of the rules) | same file |
| Tests | `SessionGateTest`, `SessionGateVectorTest`, `PairingSessionIntegrationTest` (+ `PairingSessionSupport`) | `SessionGateTests`, `SessionGateVectorTests`, `PairingSessionIntegrationTests` (+ `TestSupport/PairingSessionSupport.swift`, `TestSupport/Vectors.swift`) |

### Explicitly not done

No Phase 2 work of any kind — no WebRTC, voice, microphone, Opus, audio routing, Bluetooth, music
or Ride Mode — despite the two preceding commits being named "init phase 2a" and "phase 2a". Those
names are misleading; nothing in this repository is Phase 2. No pairing *timeout* was invented
either: PROTOCOL §4.5 specifies a rate limit and no timeout, so implementing one would have been
inventing protocol.

---

## 2h. Phase 1b — iOS control-event ordering fix (28 August 2026 session, fifth)

Delivery-only. The security model, `SessionGate`'s table, `SessionFsm` and every crypto primitive
are unchanged; no ADR was needed because no design changed.

### The bug

`SessionCoordinator.startDiscovery()` subscribed to `ControlSessionManager`'s events with
`setOnEvent { event in Task { @MainActor in self?.handleControlEvent(event) } }` — a **new**
unstructured `Task` per event. `ControlSessionManager` deliberately emits ordered pairs
(`.pairingSucceeded` then `.connected`; `.peerTrusted` then `.connected`, both synchronously,
back to back, inside `succeedPairing`/`promote`) and `SessionGate` (ADR-019) depends on that order
surviving delivery. A `Task` per event preserves the order events were *created* in, not the order
they *run* in — Swift gives no ordering guarantee between independently created tasks on the same
executor. Nothing in this repository proved otherwise; it was found by inspection, matching the
same class of gap ADR-019 itself closed (a join no test crossed), not a device failure.
`pairingPromptChanged` had the identical shape, with a real consequence if it lost ordering: a
stale six-digit code reappearing after pairing had already settled.

### The fix

`OrderedEventChannel<Element>` (`RideLinkPlatform/Control/OrderedEventChannel.swift`) — a minimal
`AsyncStream` wrapper: `send` enqueues synchronously from any isolation context, `finish` ends the
stream. `SessionCoordinator` now creates one `OrderedEventChannel<ControlEvent>` and one
`OrderedEventChannel<PairingPrompt?>` per `startDiscovery()`, each drained by exactly one
long-lived `Task` running a single `for await` loop — so event *N+1* is structurally unable to be
handled before event *N*. `teardownSession()` cancels both consumer tasks and finishes both
channels before the next `startDiscovery()` creates a fresh pair, so a callback still in flight
from a torn-down `ControlSessionManager` session lands as a no-op `send` on an already-finished
channel rather than mutating the next session's state. `onDiagnosticsChanged` (cosmetic UI only,
no security content) was deliberately left on its prior per-callback `Task` — out of scope per this
session's brief.

Android already collects `controlSessionManager.events` with a single `Flow.collect` inside one
`launch {}` — Kotlin `Flow` collection is inherently sequential on one coroutine, so the equivalent
defect does not exist there. Confirmed by inspection; **no Android change was made.**

### Verification

`OrderedEventChannelTests` (5 tests) exercise the abstraction directly: FIFO order over 200 sends,
two synchronous back-to-back sends (the exact production shape) repeated 50×, `finish()` ending an
in-progress consumer loop, a `send` after `finish()` being silently dropped, and a cancelled
consumer leaving nothing running. `PairingSessionOrderingTests` (3 tests) reproduce the real
`SessionCoordinator` wiring — `ControlSessionManager.emit` → `channel.send` → one consumer `Task`
— over real TLS 1.3 handshakes between two real `ControlSessionManager`s, proving the unknown-peer
`PairingRequired → PairingSucceeded → Connected` order, the known-peer `PeerTrusted → Connected`
order with no SAS prompt, and that a stale `send` issued after `detach()` cannot move the FSM. All
8 pass; the full ordering + pairing suite (`OrderedEventChannelTests` +
`PairingSessionOrderingTests` + `PairingSessionIntegrationTests`, 21 tests) was run **20/20
consecutive times with 0 failures**. `swift test` for `RideLinkPlatform` is 99/99 (up from 91);
`RideLinkCore` is unchanged at 27/27. `xcodebuild` Debug and Release both succeed for the
simulator, zero warnings beyond the pre-existing benign "no AppIntents.framework dependency"
notice. Android's full `test ktlintCheck detekt lint assembleDebug assembleRelease` is unchanged
at 253/253 with 0 failures, confirming no regression from a session that touched no Android file.

---

## 2i. Phase 2a — voice transport foundation (28 August 2026 session, sixth)

Two steps, in order, as the brief required: a dependency/API spike first, then implementation only
after the spike answered the open questions. Decisions:
[ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md). Evidence:
[`test-results/phase2a-webrtc-spike-20260828.md`](test-results/phase2a-webrtc-spike-20260828.md).

### The spike, run first (Phase 2a.1)

ADR-003 named two candidate WebRTC distributions in June and left four things open. All four now
have answers, and none required a weaker substitute:

1. **Distributions pinned exactly, and reviewed rather than trusted.** Android
   `io.github.webrtc-sdk:android:144.7559.14` (Chromium M144, BSD-3-Clause, 48.7 MB AAR, four ABIs,
   `minSdkVersion 21`); Apple `stasel/WebRTC` `exact: "152.0.0"` (Chromium M152, BSD-3-Clause, SPM
   `binaryTarget` whose SHA-256 was verified byte-for-byte against the published release — pinned at
   `151.0.0` until upstream deleted that release, §4 problem 27). Neither
   declares a permission, service or analytics class; **every** HTTP string in both native binaries
   was extracted and read, and all of them are RTP header-extension URIs, a CRL string inside the
   bundled root store, or source references — **no upload endpoint of any kind**. Apple's bundled
   `PrivacyInfo.xcprivacy` states `NSPrivacyTracking: false` with no collected data types.
2. **Maven Central's search index was stale**, reporting `125.6422.07` (March 2025) as the newest
   Android version. `maven-metadata.xml` has `144.7559.14`. Worth knowing: the stale answer is the
   one a casual check returns, and it would have pinned a version 16 milestones old.
3. **The macOS slice is the find that mattered.** It is what makes real DTLS-SRTP/Opus media
   testable on a laptop at all — see the box at the top of this file.
4. **Swift 6 refuses to let WebRTC objects leave a callback.** `RTCSessionDescription`,
   `RTCIceCandidate` and `RTCStatisticsReport` are not `Sendable`; three compile errors were
   reproduced deliberately before designing around them. The fix is not a suppression: every value
   is reduced to a primitive *inside* the callback — which coincides exactly with the boundary
   PROTOCOL §7.4 already defines. It shaped the `VoiceEngine` seam on **both** platforms.

**Milestone skew accepted knowingly:** Android M144, Apple M152, because neither distribution
publishes the other's. WebRTC interoperates across milestones by design; recorded so a future
interop problem is investigated against a known difference rather than met as a surprise.

### What was built (Phase 2a.2)

**Shared, pure, vector-pinned** (`core.protocol`/`core.voice`/`core.audiopolicy` mirrored by
`RideLinkCore.Protocol`/`.Voice`/`.AudioPolicy`):

- `VoiceSignal` + `VoiceSignalCodec` — the four `VOICE_*` messages, total and non-throwing, with every PROTOCOL §7.5 bound enforced **before** a peer-supplied string reaches the media stack.
- `VoiceNegotiation` — the complete PROTOCOL §7 negotiation table as a pure `(state, input) -> (state, actions)` reducer. This is where the offerer rule, glare, the generation guard and "a link loss must not close the capture device" live, so a laptop can exhaust them.
- `VoiceSessionId` — PROTOCOL §7.2's generation guard, a distinct type from `ConnTiebreak`.
- `PendingCandidates` — the bounded trickle-ICE queue; at capacity the **oldest** is dropped and every drop is **counted**.
- `VoiceEngine`/`VoiceAudioSession`/`VoiceSignalTransport`/`VoiceSignalSink` — primitive-only seams, which is what makes `VoiceController` testable with no WebRTC at all.
- `VoiceStatsMapping` — one shared `webrtc-stats` mapping, so both platforms report the same numbers.
- `audiopolicy` — ADR-016's vocabulary, now **implemented** rather than a shell (see the consolidation note below).

**New shared vectors:** `protocol/vectors/voice-signal/` (70 rows) and
`protocol/vectors/voice-fsm/` (52 rows), generated by `tools/generate_voice_signal_vectors.py` and
`tools/generate_voice_fsm_vectors.py` — independent third implementations written from PROTOCOL, not
ported from either platform. **Both generators disagreed with the Kotlin implementation on first
run, and both disagreements were real findings** (see below).

**Android:** `network/voice/{VoiceController, WebRtcVoiceEngine, VoiceSignalRelay}`,
`audio/route/{AndroidVoiceAudioSession, AndroidAudioRouteMapper}`,
`app/service/RideForegroundService`, a voice card in the UI, and the ARCHITECTURE §6.4 manifest
surface (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, the two FGS permissions, one
service declaring `microphone|mediaPlayback`).

**iOS:** `RideLinkPlatform/Voice/{VoiceController, WebRtcVoiceEngine, VoiceSignalRelay}`,
`RideLinkPlatform/Route/{IosVoiceAudioSession, IosAudioRouteMapper}`, a voice card, and
`NSMicrophoneUsageDescription` + `UIBackgroundModes: audio`.

**PROTOCOL §7 grew from a 28-line sketch to a full specification** — schemas, bounds, the
authentication gate, the generation guard, the offerer rule, logging rules and lifecycle.

### The security property, and how it is enforced

> A peer that has completed TLS but has not passed the ADR-019 trust gate cannot start voice.

Enforced structurally, not by a check that could be forgotten: `VOICE_*` is **absent** from
`ControlSessionManager`'s pre-authentication frame allowlist, so the read loop's dispatch never
reaches the voice branch, and `VoiceController` is not constructed at all until `Connected` fires.
Proven over **real TLS on both platforms** by `VoiceAuthenticationGateTest[s]`: two real unpaired
peers reach `PAIRING` with an unanswered six-digit code, one sends every `VOICE_*` frame there is,
none arrives — and the refusals are **counted**, so the test cannot be satisfied by the frames never
being sent. The same frames from the same peer *are* delivered once both users confirm.

### Three real findings, none of them cosmetic

1. **A contradiction inside ADR-016.** Its prose said `media_quality` is `reduced` for "a duplex
   profile other than `duplex_wide_stereo`"; its own representable-states table said `builtin` is
   `full`. `builtin` satisfies the prose and contradicts the table. The table is right — a phone's
   own speaker and microphone do not degrade each other — and the prose had generalised a
   coincidence that holds only for Bluetooth. Corrected to "a **narrowed** duplex profile" and
   recorded as [ADR-016 Amendment A1](DECISIONS/ADR-016-effective-audio-capability-model.md#amendment-a1--28-august-2026--correction-media_quality-is-about-narrowed-duplex-not-duplex).
   Found by a unit test, before any of it shipped.
2. **A duplicated audio vocabulary.** `EndpointClass`, `AudioProfile`, `ProfileCoupling` and
   `AudioRoute` already existed as unimplemented Phase 1a shells in `model/Entities`, referenced by
   nothing but each other. Phase 2a's first draft added a parallel set in `audiopolicy` — and
   **Kotlin did not complain, because the packages differ**, which is worse than a compile error.
   Consolidated: the enums moved to `audiopolicy` (where ADR-016 says the vocabulary lives) and the
   `AudioRoute` shell is replaced by the implemented `AudioRouteSnapshot`. Two types for one concept,
   differing only in which one a call site reached for, is exactly the drift the shared vectors exist
   to prevent — in a place no vector could see.
3. **A missed candidate-type inspection.** PROTOCOL §7.6 inspects the `typ` of every candidate this
   side *gathers* as well as every one it *receives*. The first controller draft only checked the
   receive direction — and the gathering direction is the one that would reveal a STUN server had
   been contacted. Caught by the test written for it, fixed on both platforms.

Plus two the independent generators caught, which is what they are for: the Python transcription
mispredicted the rejection reason for a present-but-non-string `voice_session_id` (the codec
correctly distinguishes "absent" from "wrong type"), and one property test over-specified a drop
reason (a stale *engine callback* and a foreign *wire* generation are deliberately diagnosed
differently). In both cases the implementation was right and the third implementation was wrong,
which is a useful direction for a disagreement to point.

### `ControlSessionManager` got bigger, and the extraction happened

STATUS §4 problem 18 predicted this class would get worse and named the fix. detekt's `LargeClass`
fired the **first time** the voice wiring went in inline — the tool doing exactly its job. The whole
voice half was extracted to `VoiceSignalRelay` on both platforms, leaving about twenty lines of
wiring; there is no smaller way to attach a subsystem to it at all. The residual overflow is
pre-existing (the class was already at 608 counted lines before this phase touched it), so
`config/detekt/detekt.yml` now documents a `LargeClass` threshold with the reason, and **problem 18
is escalated below**. The prescribed `PairingController` extraction is deliberately still not done
here: it touches the pairing and trust-gate paths, and STATUS §4 says it belongs in a change that is
*only* that refactor.

### Explicitly not done

Music playback, music sync, local music transfer, manifest transfer, the drift ladder, Ride Mode as
a product screen, navigation announcements, PTT/VOX gating (Phase 2a sends `mode: continuous`
always), in-place WebRTC renegotiation (V1 tears down and negotiates afresh), the manual
`host:port`/QR discovery fallback, and the `PairingController` refactor. No Phase 2b work of any
kind.

---

## 2j. Phase 2a hardening — bounded voice input mailbox and strict generation guard (2 September 2026 session, seventh)

A focused hardening pass on Phase 2a's implementation, requested explicitly as hardening rather than
Phase 2b or Bluetooth tuning. Two real defects found in an independent review, both fixed with
regression coverage on both platforms. Neither touches `VoiceNegotiation`'s table, the offerer rule,
glare handling, `voice_session_id` generation, host-only ICE, Opus/DTLS-SRTP, mute behaviour, or the
ADR-019 pre-authentication gate — all confirmed unchanged. Full account:
[ADR-020 Amendment A2](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a2--2-september-2026--a-bounded-input-mailbox-and-the-generation-guard-made-strict).

**Finding 1 — the per-negotiation input channel was unbounded.** `VoiceController.submit` (and
`start`/`stop`/`setMicrophoneMuted`/`onControlLinkLost`, and the engine's own event sink) fed an
unbounded `Channel`/`AsyncStream` ahead of the pure reducer. PROTOCOL §7.5's bounds — SDP size,
candidate size, `MAX_QUEUED_VOICE_CANDIDATES` — all apply only *after* a frame is already sitting in
that queue, so an authenticated peer past the ADR-019 trust gate could grow this controller's
memory without limit just by sending `VOICE_*` frames faster than the single consumer drained them.

Fixed with `VoiceInputMailbox` (`com.ridelink.core.voice.VoiceInputMailbox` /
`RideLinkCore.VoiceInputMailbox`) — pure, mirrored, exhaustively unit-tested, sitting between the
wire/engine-callback boundary and the reducer. Four lanes, priority `teardown > critical > ice >
coalesced`:

- **critical** (start, engine offer/answer/connectivity callbacks, a peer's offer/answer): bounded
  FIFO, capacity 32. A new input arriving at capacity is refused and forces `ControlLinkLost`
  through the teardown lane — reusing that input's already-correct, already-tested effect (media
  stops; local capture and the TLS control session both survive) rather than inventing a new
  failure path.
- **ice** (a peer's `VOICE_ICE`, a locally gathered candidate): bounded ring at
  `MAX_QUEUED_VOICE_CANDIDATES` — the same constant `PendingCandidates` already enforces one layer
  later, so the two bounds are one policy rather than two that could disagree. Oldest evicted and
  counted at capacity.
- **coalesced** (`VOICE_STATE`, mute, remote-track-present): one slot per kind, latest value wins.
- **teardown** (a deliberate stop, a control-link loss): one slot, always accepted, drained first.

A critical-lane refusal is counted as the new `INPUT_MAILBOX_OVERFLOW` `VoiceSignalDropReason` —
never produced by `VoiceNegotiation` itself, since the reducer never sees a refused input; the
controller counts it directly. PROTOCOL §7.5 now documents all four lanes.

**Finding 2 — the generation guard's `nil` case was backwards.** Both engines' callback-forwarding
function was, in effect, `if generation != null && generation != expected: drop` — which reads as
"reject a mismatch" but actually *accepts* the moment `generation` is `null`, exactly the state
right after `stop()`. A stale callback from an already-torn-down peer connection could reach
`VoiceController` after all, in precisely the window the generation guard exists to close.

Fixed to the strict form the prose always implied, extracted as a pure, independently
unit-tested rule rather than re-inlined a second time on each platform:
`com.ridelink.core.voice.VoiceEngineGeneration` / `RideLinkCore.VoiceEngineGeneration`. Neither real
`WebRtcVoiceEngine` can be constructed in a host unit test (§4 problems 22/23), so extracting the
rule is what makes it testable at all — before this fix it was inline logic no test suite on either
platform could reach. Fixing it surfaced a second, adjacent gap the strict check would otherwise
have broken: a media engine reporting that `start()` itself failed is not a peer-connection
callback (no peer connection exists yet to name one), so both engines now report a start failure
directly and unconditionally through the event sink, bypassing the generation check entirely —
PROTOCOL §7.8 records the distinction. On iOS this closed a genuine pre-existing gap rather than
only fixing the guard: `WebRtcVoiceEngine.start()`'s failure path previously reported nothing to the
controller at all.

**Verification.** 28 new Android tests (`VoiceInputMailboxTest` 18, `VoiceEngineGenerationTest` 4,
`VoiceControllerMailboxTest` 6) and 27 new iOS tests (`VoiceInputMailboxTests` 18,
`VoiceEngineGenerationTests` 4, `VoiceControllerMailboxTests` 5), covering: the mailbox never grows
past either bound under a simulated flood; a critical-lane overflow forces a safe degrade that never
releases capture and never kills the control session; `stop`/`onControlLinkLost` remain processable
under a fully saturated mailbox; a stale-generation callback is rejected and a callback from
generation N cannot affect generation N+1; and a fresh Start Voice after an overflow-induced degrade
begins a genuinely clean negotiation. The new mailbox/generation test classes were run **20
consecutive times on both platforms with 0 failures** (see §3). All prior Phase 1b and Phase 2a
suites remain green, including the real two-engine WebRTC loopback test.

**§4 problem 28 (the intermittent `PairingSessionIntegrationTest` CI failure) is unrelated and
still open** — not reproduced, not touched, and not claimed fixed. It is an existing Phase 1b issue
this pass did not investigate further because it did not recur.

---

## 2k. Phase 2a mailbox hardening, second pass — conflated iOS doorbell and non-coalescible
terminal peer state (3 September 2026 session, eighth)

A second, explicitly scoped hardening pass on §2j's mailbox, requested as exactly two remaining
mailbox issues — not Phase 2b, not a redesign. Both found in review of §2j's own implementation,
both fixed with regression coverage on both platforms where applicable. Full account:
[ADR-020 Amendment A3](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a3--3-september-2026--the-doorbell-is-conflated-and-a-peers-terminal-state-gets-its-own-lane).

**Finding 1 — the iOS voice doorbell was still unbounded.** `VoiceInputMailbox` itself was already
bounded by §2j, but the wake-up `VoiceController` rings on every `offer` to notify its single
consumer was, on iOS only, an `OrderedEventChannel<Void>` — an `AsyncStream` with the default
**unbounded** buffering policy. Android's equivalent (`Channel<Unit>(Channel.CONFLATED)`) was
already correct, so this was a single-platform gap. Every lane's `offer` unconditionally rang that
unbounded doorbell regardless of which lane accepted the input, so a flood of authenticated
`VOICE_*` traffic could still grow an unbounded backlog of pending `Void` wake-ups sitting *behind*
the already-bounded mailbox.

`OrderedEventChannel` itself is untouched and was not the fix: it exists specifically because
`ControlSessionManager` emits `.pairingSucceeded`/`.peerTrusted` immediately followed by
`.connected` as ordered pairs that `SessionGate` (ADR-019) depends on arriving in that order. A
doorbell has no such requirement, so making `OrderedEventChannel` conflated globally would have
risked reintroducing exactly the event-ordering problem §2h fixed. Instead, a dedicated new type —
`RideLinkPlatform.ConflatedSignal`, an `AsyncStream<Void>` built with `.bufferingNewest(1)` —
gives the same `signal()`/`stream`/`finish()` contract with at most one pending wake-up buffered
between drains, matching Android's `Channel.CONFLATED` doorbell exactly. Each `VoiceController`
owns exactly one, created fresh in its initializer.

**Finding 2 — a peer's terminal `VOICE_STATE` could be silently coalesced away by an ordinary
one.** `VoiceInputMailbox`'s classification put every `VoiceSignal.State` value — `negotiating`,
`connecting`, `active`, `idle`, `closed`, `failed`, `unknown` — into the same one-slot coalesced
lane, latest-value-wins. `closed` and `failed` are not ordinary: `VoiceNegotiation`'s reducer gives
them teardown semantics (`teardownFromPeer`, tearing down to `idle`/`failed` respectively) that no
other value in the enum gets. Coalescing put them in the same slot as everything else, so a peer's
`closed` queued ahead of a later `active` update — a perfectly ordinary sequence a reconnecting peer
could produce — could be silently replaced before the mailbox's consumer ever drained it, and the
remote teardown signal would simply vanish with this side never learning the peer had ended its
side of the call.

Fixed with a fifth mailbox lane, `terminal_peer_state`, holding only `closed`/`failed` peer signals;
every other `VOICE_STATE` value keeps coalescing exactly as before. Bounded FIFO, capacity **8** on
both platforms (`VoiceInputMailbox.TERMINAL_PEER_STATE_CAPACITY`/`terminalPeerStateCapacity`) —
sized the same way as the critical lane: one negotiation produces at most one terminal peer state
naturally, so 8 absorbs several rapid teardown/rebuild cycles while staying far below anything a
real ride would approach. Draining priority is now `teardown > terminal_peer_state > critical > ice
> coalesced` — a peer's own teardown is never delayed behind an offer/answer or ICE flood, and it
sits strictly above `coalesced` so it can never be classified alongside, and therefore overwritten
by, an ordinary update. An overflow at this lane refuses the new input outright (not evicting an
*earlier* terminal event) and forces `ControlLinkLost` through the always-accepting teardown lane —
the same already-proven safe degrade a critical-lane overflow produces, applied one layer earlier.
`VoiceNegotiation` itself needed no change: the reducer's handling of `closed`/`failed` was already
correct whenever it actually saw them; the bug was entirely in the mailbox deciding, ahead of the
reducer, that a terminal signal and an ordinary one were interchangeable.

**Verification.** 8 new Android tests (`VoiceInputMailboxTest`, terminal-lane classification,
priority, overflow) and 3 new Android tests (`VoiceControllerMailboxTest`, terminal state through a
live controller) — Android's doorbell needed no change and so has no new doorbell tests. 8 new iOS
tests (`VoiceInputMailboxTests`, mirroring Android's) and 3 new iOS tests
(`VoiceControllerMailboxTests`, mirroring Android's), plus 8 new `ConflatedSignalTests` proving the
doorbell semantics directly: 100,000 signals before one consume buffer at most one wake-up, one
wake-up is enough to drain everything queued behind it, a signal after `finish()` is harmless,
teardown leaves no pending consumer, and a fresh instance is independently functional. All of it
proves the semantics directly rather than by measuring memory. Every new/changed suite was run **20
consecutive times on both platforms with 0 failures** (see §3). All prior Phase 1b/2a suites remain
green, including the real two-engine WebRTC loopback test and the pre-authentication `VOICE_*`
refusal over real TLS.

**§4 problem 28 (the intermittent `PairingSessionIntegrationTest` CI failure) remains open,
unrelated, and untouched by this pass** — this session did not attempt to reproduce it and makes no
claim about its status either way.

---

## 3. Tests passed / pending

**Passed and verified in the second Phase 2a mailbox hardening session (3 September 2026, eighth),
by actually running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **335 tests** (was 324): `core` 184 (was 176 — +8 `VoiceInputMailboxTest`:
  terminal-lane classification, priority over ICE/coalesced/critical, drained-unchanged, flood
  overflow), `network` 129 (was 126 — +3 `VoiceControllerMailboxTest`: remote CLOSED/FAILED through
  the live reducer, terminal-lane overflow degrade), `audio` 11, `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **69/69** (was 61 — +8
  `VoiceInputMailboxTests`, mirroring Android's terminal-lane additions).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **150/150** (was 139 — +3
  `VoiceControllerMailboxTests` mirroring Android's, +8 new `ConflatedSignalTests` proving the
  doorbell's conflation semantics directly), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings**.
- Every new/changed test class was run **20 consecutive times, 0 failures**: Android
  `VoiceInputMailboxTest` and `VoiceControllerMailboxTest` (the latter via `:network:testDebugUnitTest
  --tests`, not the `:network:test` lifecycle task, which does not accept a `--tests` filter on an
  AGP library module — a CLI quirk worth recording since it looks like a real failure the first
  time), and iOS `VoiceInputMailboxTests`, `VoiceControllerMailboxTests` and `ConflatedSignalTests`.
  All prior Phase 1b/2a suites remain green, including the real two-engine WebRTC loopback test and
  the pre-authentication `VOICE_*` refusal over real TLS on both platforms.
- SwiftLint/SwiftFormat: still not installed on this machine (§4 problem 14, unchanged).

---

**Passed and verified in the Phase 2a hardening session (2 September 2026, seventh), by actually
running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **324 tests** (was 296): `core` 176 (was 154 — +18 `VoiceInputMailboxTest`, +4
  `VoiceEngineGenerationTest`), `network` 126 (was 120 — +6 `VoiceControllerMailboxTest`), `audio`
  11, `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **61/61** (was 39 — +18
  `VoiceInputMailboxTests`, +4 `VoiceEngineGenerationTests`).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **139/139** (was 134 — +5
  `VoiceControllerMailboxTests`), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings**.
- The three new test classes per platform (`VoiceInputMailboxTest[s]`, `VoiceEngineGenerationTest[s]`,
  `VoiceControllerMailboxTest[s]`) were each run **20 consecutive times, 0 failures**, since several
  of them concern flooding/overflow behaviour whose determinism matters more than usual — one of
  them (an "authenticated flood of offers" scenario) was reworked mid-session specifically because
  a real, unconstrained coroutine/actor dispatcher let the consumer occasionally keep pace with a
  200-item flood and never actually overflow, which would have made the test's proof accidental.
  The fix — flood before the consumer exists (a `ManualDispatcher` on Android; deferring
  `attach()` on iOS), not a bigger flood count — is what makes the overflow scenario deterministic
  rather than probabilistic. All other Phase 1b/2a suites remain green, including the real
  two-engine WebRTC loopback test and the pre-authentication `VOICE_*` refusal over real TLS.
- SwiftLint/SwiftFormat: still not installed on this machine (§4 problem 14, unchanged).

---

**Passed and verified in the security-state fix session (27 August 2026, fourth), by actually
running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4
problem 17):

- `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five modules. **253 unit tests, 0 failures**: `core` 145, `network` 97 (up from 74 — `SessionGateTest` 10, `SessionGateVectorTest` 2 running the shared 120-row table, `PairingSessionIntegrationTest` 11 over real TLS), `data` 9, `app` 2. **No `detekt` threshold was raised for this change** — `LargeClass`/`TooManyFunctions`/`CyclomaticComplexMethod` were satisfied by moving the pure payload readers out of `ControlSessionManager` (where they never belonged) and by splitting `SessionGate`'s table into three small functions.
- `swift test --package-path ios/Packages/RideLinkCore` — **27/27 pass** (unchanged; the FSM itself did not move).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **91/91 pass** (up from 68: `SessionGateTests` 10, `SessionGateVectorTests` 2, `PairingSessionIntegrationTests` 11), zero Swift 6 strict-concurrency warnings.
- `xcodebuild … -configuration Debug` and `-configuration Release`, simulator, `CODE_SIGNING_ALLOWED=NO` — both **succeed**.
- `python3 tools/generate_session_gate_vectors.py` — regenerates the 120-row table; both platforms run the regenerated file.

**The bug was reproduced before it was fixed, on both platforms** (§2g): the Android repro failed
with `[Connected(...), PairingRequired(...)]`, and the iOS suite was verified to fail the same way
by temporarily restoring the old emit order. Neither test could pass against the old code.

**Not run this session:** SwiftLint/SwiftFormat (not installed, §4 problem 14), the spike harness
(unchanged this session), and anything on a physical device — see below.

### Phase 1b implementation session (27 August 2026, third)

**Passed and verified then, by actually running the commands.** Every Gradle command below was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, without
which `detekt` cannot run on this machine at all (§1, §4 problem 17):

- `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five modules. **230 unit tests, 0 failures**: `core` 145 (up from 93 — 52 new `identity/` vector assertions), `network` 74 (up from 52 — the TLS channel, pairing and plaintext-absence suites), `data` 9 (new — trusted-peer and peer-id persistence), `app` 2 (`SecureTransportPolicyTest`, replacing `TransportGateTest`). `detekt` thresholds touched and documented in `config/detekt/detekt.yml`: `thresholdInObjects` 11 → 16, and `thresholdInClasses` 24 → 34 — the second raise for `ControlSessionManager`, recorded as tech debt in §4 rather than pretended away.
- `swift test --package-path ios/Packages/RideLinkCore` — **27/27 pass** (up from 17; +10 `IdentityVectorTests` running the same `identity/` file as Android).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **68/68 pass** (up from 40; +10 `TlsControlChannelTests`, +9 `PairingExchangeTests`, plus the existing suites re-pointed at the real TLS channel), zero Swift 6 strict-concurrency warnings.
- `xcodebuild … -configuration Debug` and `-configuration Release`, simulator, `CODE_SIGNING_ALLOWED=NO` — both **succeed**, zero warnings beyond the pre-existing benign "no AppIntents.framework dependency" notice.
- `./tools/spikes/phase1b-tls-exporter/run.sh` — **10/10 PASS**, including the cross-stack Apple ↔ Conscrypt exporter equality that this whole phase rests on.

**Passed and verified in the Phase 2a session (28 August 2026, sixth), by actually running the
commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4
problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **296 tests** (was 253): `core` 154 (was 121 — plus `voice-signal/`'s 70 rows and
  `voice-fsm/`'s 52 rows), `network` 120, `audio` 11 (new module suite), `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **39/39** (was 27).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **134/134** (was 99), zero Swift 6
  strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings** beyond the
  pre-existing benign "no AppIntents.framework dependency" notice.

**Real WebRTC media, measured — this is the one genuinely new class of evidence.**
`VoiceEngineLoopbackTests` runs two real `WebRtcVoiceEngine`s against each other under `swift test`
on macOS, linking the same WebRTC binary an iPhone build would (ADR-020). Asserted from the stack's
own statistics: gathered candidate types exactly `{host}` (no `srflx`, no `prflx`, no `relay`),
`dtlsState = connected`, `dtlsCipher = TLS_AES_128_GCM_SHA256`,
`srtpCipher = SRTP_AES128_CM_HMAC_SHA1_80`, `audio/opus` at 48 000 Hz, remote track present on both
sides. **Deterministic: the suite was run 5 consecutive times with 0 failures.** `packetsSent = 0`,
which is expected and is not a failure — there is no microphone in a headless run, so the transport
is up and nothing is speaking into it. That is exactly the line between "media path established" and
"audio works".

**CI is green on both platforms: run `33654431951`, commit `4d55089`, every step of both jobs.**
Android: core tests, all unit tests, ktlint, detekt, lint, `assembleDebug`, `assembleRelease`.
iOS: `RideLinkCore` 39/39, `RideLinkPlatform` 134/134, Debug and Release simulator builds. It took
three runs to get there and the two failures in between were both real — see §4 problems 27, 28
and 29; neither was retried until it passed.

**The real media test also passes on a hosted CI runner, not only on this machine.** Run
`33654431951`'s iOS job ran `VoiceEngineLoopbackTests` on a GitHub `macos-26` runner and all four
passed — so the host-only-ICE, DTLS-SRTP and Opus assertions are reproducible on a machine nobody
here configured, which is a materially stronger claim than a laptop result. It is still not a phone.

**The Phase 2a security invariant is proven over real TLS on both platforms.**
`VoiceAuthenticationGateTest` / `VoiceAuthenticationGateTests`: two real unpaired
`ControlSessionManager`s complete a real TLS 1.3 handshake, reach `PAIRING` with an unanswered
six-digit code, and one sends every `VOICE_*` frame there is. None reaches the voice subsystem; the
refusals are **counted**, so the test cannot be satisfied by the frames never being sent; and the
same frames from the same peer *are* delivered once both users confirm. Malformed and oversize
`VOICE_*` frames are dropped **without ending the control connection**, verified by a well-formed
frame still arriving afterwards.

**Not run this session, stated plainly:** nothing on a physical device, on either platform, and no
audio anywhere. The Android WebRTC engine has **no test of any kind** (§4 problem 22). Neither
audio-session implementation has ever run (§4 problem 23). `RideForegroundService` has never started
(§4 problem 25). No latency was measured and none may be claimed. See §7 and TEST_PLAN §3.1a.

---

### Earlier sessions, kept for history

**The security tests are real handshakes, not mocks.** `TlsControlChannelTest[s]` on both platforms
open real loopback TCP, complete a real mutually authenticated TLS 1.3 handshake with certificates
this codebase encoded and signed, and assert that both ends derive the *same* six-digit SAS. The
two substitutions that make that possible on a laptop — where the private key lives, and which
call frame reaches the exporter — are named in TEST_PLAN §3.1 and in
`test-results/phase1b-security-spike-20260827.md` §5.

**Not run this session, stated plainly:** nothing on a physical device, on either platform. The
Phase 1a real-device gate remains exactly as open as it was, and Phase 1b adds its own device-only
items (Android Keystore, the iOS Keychain, and the assumption that device-Conscrypt behaves like
Conscrypt-on-JVM). See §4 and §7.

---

### Earlier sessions, kept for history

**Passed and verified 27 August 2026 session (first), by actually running the commands:**

- `./gradlew clean test ktlintCheck detekt assembleDebug` — **all green**, all five Android
  modules. `:core:test` still runs `protocol/vectors/{envelope,sas,dedup,session-fsm}/*.json`
  directly; `:network:test` now additionally runs 56 tests including `protocol/vectors/clock/`
  (16 vectors), the socket-level dedup/reconnect/framing/discovery-lifecycle/privacy suites
  described in §2d, all against real JVM sockets — no Robolectric, no emulator needed for any of
  it. `ktlintCheck`/`detekt` clean across all five modules (two small `config/detekt/detekt.yml`
  threshold adjustments made and documented in the config file itself, same style-calibration
  precedent as the existing table-driven-test adjustments).
- `swift test --package-path ios/Packages/RideLinkCore` — **17/17 tests pass** (16 previous +
  `ClockSyncVectorTests`, same `clock_vectors.json` as Android, byte-identical results).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **17/17 tests pass** (2 previous +
  15 new: framing cap enforcement, real-socket simultaneous-connect dedup ×2, reconnect policy
  ×4, discovery privacy ×3, discovery-handle rotation ×4), **zero Swift 6 strict-concurrency
  warnings**. Run 4+ times consecutively with no flakes.
- `xcodebuild -project ios/RideLink.xcodeproj -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build`
  — succeeds, zero warnings. Installed and launched on the iPhone 17 Pro Max **simulator**; a
  screenshot confirms the Phase 1a diagnostics UI (§2d) renders correctly, including the
  `TRANSPORT: PLAIN / PHASE 1A / NOT SECURE` banner.

**Passed and verified 27 August 2026 session (cleanup/hardening pass, §2e), by actually running
the commands:**

- `./gradlew clean` then `:core:test`, `test`, `ktlintCheck`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` — **all green**, all five Android modules. `network` module: 52 tests (up from
  the prior session's suite; net new this session: NSD callback lifecycle, mDNS instance-name
  privacy, reconnect re-entrancy, PING-race regression, malformed PING/PONG, dh-rotation
  self-race, teardown). `app` module: 3 new tests for the release-transport gate. `lint` run
  explicitly this session (not run to completion as such in the prior session — only
  `assembleDebug`, which does not run full `lint`) and found + fixed 4 genuine pre-existing
  `NewApi` errors in `NsdDiscoveryController` unrelated to this session's new code (guarded with
  `@RequiresApi`, matching the existing SDK-tiered pattern the file already used elsewhere).
- `swift test --package-path ios/Packages/RideLinkCore` — **17/17 tests pass**, unchanged.
- `swift test --package-path ios/Packages/RideLinkPlatform` — **40/40 tests pass** (17 prior + 23
  new this session across `PingRaceAndReconnectTests`, `MalformedPingPongTests`,
  `SelfDiscoveryHandlesTests`, `TeardownTests`, plus additions to `DiscoveryPrivacyTests`), zero
  Swift 6 strict-concurrency warnings. Run multiple times consecutively with no flakes.
- `xcodebuild ... -configuration Debug -sdk iphonesimulator ... build` — succeeds, zero warnings
  (checked explicitly with a clean build + grep for `warning:`, only the pre-existing benign
  "no AppIntents.framework dependency" notice present).
- `xcodebuild ... -configuration Release -sdk iphonesimulator ... build` — **succeeds, new this
  session.** This is the direct proof the release-transport guard works: `PlaintextTransportGate`
  compiles `SessionCoordinator()` out entirely under Release (`#if DEBUG`), so a successful Release
  build is evidence the plaintext transport was never reachable, not merely unused.
- Not re-run this session, since no UI changed: the on-simulator screenshot verification from
  §2d/§3 above.

**Not passed / not run this session, stated plainly — this is the entire real-device gate:**

- **No physical Android device or emulator was available in this environment** — no `adb`, no
  AVD. `NsdDiscoveryController` and `PlainControlTransportPhase1a` on Android are therefore
  verified only by unit/integration tests against real *local* sockets, never against Android's
  actual `NsdManager`/`ConnectivityManager` stack or a real Wi-Fi radio. This is the single
  biggest gap before Phase 1a can be called complete rather than implementation-complete.
- **No physical iPhone was available** — only the iOS 17 Pro Max *simulator*. The simulator run
  confirms the UI and build; it does not exercise real mDNS multicast on a physical Wi-Fi radio,
  and `Network.framework` Bonjour behaviour between a simulator and a real device is not the same
  as between two real phones.
- **No two-device (L4) test was run at all** — I-01 through I-25, all of them, remain exactly as
  pending as last session. In particular I-08 (5-minute clock-offset-stability observation) and
  I-15…I-17 (real simultaneous-connect trials, not the loopback-simulated version this session's
  tests cover) need the real phones. §16's TCP-jitter question is therefore still **unmeasured**,
  not resolved — no UDP path was added pre-emptively, per instruction.
- AF-01…AF-10 (Android foreground-service/microphone lifecycle) and IA-01…IA-09 (iOS audio
  session/route) remain untouched — correctly out of scope for Phase 1a (they are Phase 2/6).
- The "Start Discovery" button was not interactively tapped on either platform's UI this session
  either (same synthetic-tap limitation as last session) — the FSM transitions it triggers are
  vector-tested, and this session additionally exercises the *entire* `startDiscovery()` →
  `CONNECTED` path via the loopback dedup tests, but the SwiftUI/Compose button tap itself is
  build/render-verified only.
- SwiftLint / SwiftFormat remain not installed (unchanged from last session — still not installed
  without asking first).

Test debt remaining, all specified in `docs/PROTOCOL.md` §11 / `docs/TEST_PLAN.md` but not yet
written (Phase 1b/4/5/6 concerns, not Phase 1a's — `vectors/clock/` is now done, moved out of
this list):

| Vector set | Covers |
|---|---|
| `vectors/manifest-paging/` | 1 / 1 000 / 5 000 entries, pathological metadata, digest determinism |
| `vectors/manifest-paging-errors/` | 12 failure cases; each asserts the live manifest is unchanged |
| `vectors/identity/` | SPKI formatting, pin match/mismatch, certificate re-issue with unchanged key |
| `vectors/audio-state/` | enum vocabulary, `revision` monotonicity, derived `media_quality` |
| `vectors/drift/`, `vectors/queue/`, `vectors/manifest/`, `vectors/ordering/` | Phases 5/8 |

Plus: Android AF-01…AF-10 (foreground service / microphone lifecycle), iOS IA-01…IA-09 (audio
session and route), and integration tests I-01…I-25. Full list in `docs/TEST_PLAN.md`.

---

## 4. Known problems

| # | Problem | Severity | Action |
|---|---|---|---|
| 1 | ~~**iOS self-signed X.509 generation has no first-party API**~~ **Resolved 27 Aug 2026 (Phase 1b spike).** A minimal DER encoder + `SecKeyCreateSignature` + `SecCertificateCreateWithData` + `SecIdentityCreate` works, with no PKCS#12 and no key export; the result is accepted by Apple's parser, BoringSSL and OpenSSL. [ADR-017](DECISIONS/ADR-017-identity-key-and-certificate.md) | ~~High~~ — | **Residual:** none of it has run against the iOS *Keychain* on a device — the tests use a transient key. Folded into problem 16 |
| 2 | ~~**TLS keying-material exporter availability is unconfirmed**~~ **Resolved 27 Aug 2026 (Phase 1b spike).** Public on both (`SSLSockets.exportKeyingMaterial` API 31; `sec_protocol_metadata_create_secret` iOS 12), byte-identical across Apple ↔ Conscrypt for one TLS 1.3 connection, cross-checked against OpenSSL. [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md) | ~~High~~ — | **Residual:** the Android side was measured on Conscrypt-on-JVM, not on the phone. Folded into problem 15 |
| 3 | Phase 0 measured results not recorded (mode, helmet model, topology, latency) | Medium | Blocks **Phase 6 only**. Template ready at `docs/PHASE0_RESULTS.md`. Until filled, `AUDIO_STATE.confidence` stays `assumed` and Phase 6 defaults to Mode C |
| 4 | ~~Xcode not installed~~ **Resolved 26 Aug 2026 (this session).** User installed Xcode 27.0 beta; SDK confirmed newer than baseline (ADR-011 Amendment A2), deployment target unchanged; `RideLink.xcodeproj` and `RideLinkPlatform` now built and verified (§7) | — | Xcode 27 being a beta remains a residual, lower risk — see the amendment |
| 5 | ~~WebRTC artifacts are community-published on both platforms~~ **Resolved 28 Aug 2026 (Phase 2a spike).** Both pinned exactly (`144.7559.14` / `152.0.0`), licences confirmed BSD-3-Clause, Apple's XCFramework SHA-256 verified independently, both binaries read for telemetry (none — no upload endpoint, `NSPrivacyTracking: false`), release builds proven, isolated behind `network/voice` / `RideLinkPlatform.Voice`. [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md) | ~~Medium~~ — | **Residual:** Android is Chromium M144 and Apple M152 (neither distribution publishes the other's milestone). Interop-safe by WebRTC's design; the two real stacks have never spoken to each other. Closed by V-01. A second residual became problem **27** five days later: the Apple artifact's *availability* is not guaranteed by its checksum |
| 6 | TCP jitter may floor clock-offset precision | Low | Measure in Phase 1 (I-08). Only if it exceeds ~5 ms, add a UDP `PING`/`PONG` path. Do not pre-build it |
| 7 | mDNS may be blocked on hotspots / enterprise APs | Medium | Manual `host:port` + QR fallback in Phase 1b |
| 8 | Removing `fp6` means a discovered peer cannot be labelled "known" before connecting | Low | Accepted trade (ADR-002 A1). Mitigated by an auto-attempt silent connect when exactly one trusted peer exists. Watch whether the pre-ride UX suffers in real use |
| 9 | ~~`minSdk 31` is assumed to be the level where a public TLS exporter is available~~ **Resolved 27 Aug 2026.** Measured against `android.jar`'s `api-versions.xml`: `SSLSockets.exportKeyingMaterial` is `since="31"` — exactly the baseline. The assumption was correct and `minSdk` does not move | — | — |
| 10 | ~~`swift test` cannot execute on this machine~~ **Resolved 26 Aug 2026 (this session).** Root cause was Command Line Tools alone not carrying a runnable `XCTest.framework`/Swift Testing runtime. User installed full Xcode 27.0 beta; `swift test` now runs, 16/16 pass | — | Tests use XCTest (not Swift Testing) — this was a deliberate Phase 1a choice made while blocked and is fine to keep, but revisit if the team later wants Swift Testing's nicer parameterization |
| 11 | ~~Neither discovery controller has run against a real second peer~~ **Partially resolved 27 Aug 2026 (this session).** Discovery lifecycle logic (Found/Updated/Lost, self-filtering, dh rotation, TXT privacy) is now unit/integration-tested against real local sockets/`NWBrowser` change sets on both platforms — see §2d. **Still open:** neither has run against `NsdManager`/`Network.framework`'s real mDNS stack on a real Wi-Fi radio, because no second device was available (problems 15/16). Whether `NEARBY_WIFI_DEVICES` is required on API 33+ is still unverified — ARCHITECTURE §6.4 already flags this as "settle on-device, don't assume" | Medium | Needs the real-device gate (§7) |
| 12 | AGP 9.x dropped the separate `org.jetbrains.kotlin.android` Gradle plugin; Compose BOM / `androidx.core` / `androidx.lifecycle` versions newer than the ones pinned this session require `compileSdk 37` | Low, but easy to regress | Documented in §1. Don't bump these three dependency versions without checking the compileSdk requirement first |
| 13 | `RideLink.xcodeproj`'s `project.pbxproj` was hand-authored (no Apple CLI creates one, and `xcodegen`/`tuist` weren't installed without asking). It resolves, builds, and runs on-simulator, but has not been opened in the Xcode GUI to confirm it looks/behaves like a normal project (no Assets.xcassets/app icon, minimal build settings) | Low | Open it in Xcode once to sanity-check; add an app icon when one exists. Not urgent — sideloaded personal builds don't need a store-quality icon |
| 14 | SwiftLint / SwiftFormat (ARCHITECTURE §10.2) are not installed on this machine | Low | Install when convenient; not blocking — ktlint/detekt (Android) are clean, Swift Xcode builds show zero compiler warnings |
| 15 | **No Android device or emulator available in this development environment** — no `adb` on `PATH`, no AVD configured. `PlainControlTransportPhase1a` and `NsdDiscoveryController` are therefore unverified against Android's real network stack | **High (blocks the Phase 1a gate)** | Needs either a physical OnePlus Nord 5 with USB debugging, or an AVD image + emulator installed via `sdkmanager`. Neither was set up this session — not attempted without asking, since it changes the toolchain |
| 16 | **No physical iPhone available** — only the simulator, which does not exercise real mDNS multicast or real `Network.framework` Bonjour behaviour between two independent radios. Phase 1b adds to this: the iOS **Keychain** path (a permanent key, `SecIdentityCreate` over a Keychain-resident key, survival across restart/upgrade) is exercised only with a *transient* key, because an unsigned `swift test` binary has no keychain entitlement | **High (blocks the Phase 1a and 1b gates)** | Needs a physical iPhone 17 Pro Max with a Personal Team signing identity (CLAUDE.md "Apple Signing") — a user decision, not made here |
| 17 | **`detekt` cannot run on this machine without `-Dorg.gradle.java.home=…`** — the Gradle daemon inherits Temurin 25, detekt 1.23.8 is handed `25.0.3` as a JVM target and fails with a bare version string. Pre-existing, local-only (CI's daemon is JDK 21, so CI has always been green), and **not** fixable by setting `jvmTarget`/`jdkHome` on the task — both were tried and neither helped | Low | Use the flag (it is in every §3 command), or set `org.gradle.java.home` in `~/.gradle/gradle.properties`. A committed daemon-JVM criterion (`gradle/gradle-daemon-jvm.properties`) would fix it portably but risks breaking CI if the criterion cannot be satisfied there, so it was not done blind |
| 18 | **`ControlSessionManager` is the largest class in the codebase, and Phase 2a made it larger.** detekt's `LargeClass` fired the first time the voice wiring went in inline; the whole voice half was extracted to `VoiceSignalRelay` on both platforms in response, leaving ~20 lines of wiring, and there is no smaller way to attach a subsystem to it. The class was **already at 608 counted lines before Phase 2a touched it**, so `config/detekt/detekt.yml` now documents a `LargeClass` threshold with the reason. That headroom is the last of it | **Medium** (was Low) | Unchanged and now overdue: extract a `PairingController` owning the socket-facing half (`beginPairing`, `sendPairRequest`, `applyPairingStep`, `succeedPairing`, `failPairing`, `handlePairingFrame`, `activateAuthenticatedSession`), as a change that is **only** that refactor. It touches the pairing and trust-gate paths, which is precisely why it must not ride along with a feature |
| ~~18-old~~ | **(previous wording, kept for history)** `ControlSessionManager` is the largest class in the codebase and has now absorbed the trust-gate wiring on top of the PROTOCOL §4.5 pairing wiring. The 27 Aug (fourth) session pushed it back under the existing `detekt` thresholds *without raising them*, by moving the pure payload readers (`requiredLongField`, `requiredBooleanField`, `requiredSpkiField`, `knownErrorCode`, `isPlausibleClockSample`) out of the class — they never touched a session — but that is headroom, not a fix | Low, but it will get worse | Unchanged: extract a `PairingController` owning the socket-facing half (`beginPairing`, `sendPairRequest`, `applyPairingStep`, `succeedPairing`, `failPairing`, `handlePairingFrame`, `activateAuthenticatedSession`). Deliberately not done alongside a security fix; it belongs in a change that is *only* that refactor |
| 19 | **The manual `host:port` / QR fallback for blocked mDNS is not implemented.** ARCHITECTURE §4.4 scopes it to Phase 1b | Medium | It is a *discovery* feature with no security content, so it was deliberately left until after the security work. First item in §7. Problem 7 is the reason it matters |
| 20 | **`SessionCoordinator` itself is still not directly unit-testable on either platform** — Android's needs a concrete `NsdDiscoveryController` (an Android type), and iOS's lives in the app target, which has no test target. That is precisely the gap the §2g bug hid in: a `when`/`switch` no suite could reach. It is now *mostly* closed by moving the decision into `SessionGate` (pure, mirrored, vector-pinned), leaving the coordinator a thin applier — but "thin" is a code reading, not a test | Medium | Either (a) give `NsdDiscoveryController` an interface and add an `app`-module test, or (b) add a test target to `RideLink.xcodeproj`. Do **not** let logic drift back into the coordinator in the meantime: anything with a decision in it belongs behind `SessionGate` or another pure, mirrored type |
| 22 | **The Android WebRTC media path has no test of any kind.** `PeerConnectionFactory.initialize` requires an Android `Context`, so `WebRtcVoiceEngine` on Android cannot be exercised by a JVM unit test, and no emulator or device is available here. It compiles and is wired; that is the entire claim. The iOS side has a real two-engine media test because the XCFramework carries a macOS slice — Android has no equivalent | **High (blocks the Phase 2a gate)** | An emulator would give a first signal (`sdkmanager` + an AVD, which changes the toolchain and was not attempted without asking); the real answer is V-01…V-11 on the phones |
| 23 | **Neither audio-session implementation has ever run.** `AndroidVoiceAudioSession` (`AudioManager`, `MODE_IN_COMMUNICATION`, `setCommunicationDevice`) and `IosVoiceAudioSession` (`AVAudioSession` two-configuration switch, all three notifications) are untestable off-device — `AVAudioSession` does not exist on macOS. Only their **pure mappers** are unit-tested | **High (blocks the Phase 2a gate)** | V-01…V-11 and A-12…A-15 |
| 24 | **Every value in both route mappers marked `assumed` is a reasoned guess.** `TYPE_BLUETOOTH_SCO`/`.bluetoothHFP` → `duplex_wideband` assumes mSBC rather than CVSD; `input_forces_output` for all Bluetooth is ADR-016's central claim asserted, not measured; LE Audio is deliberately *not* claimed to preserve music quality. Both mappers report `confidence: assumed` and their tests **assert** that, so the tests are what will change when the measurement exists | Medium | A-12/A-13, then A-15 flips `confidence` and fills `docs/PHASE0_RESULTS.md` |
| 25 | **`RideForegroundService` has never started.** Whether the `microphone` foreground-service type is accepted, whether capture survives a screen lock, and whether `ForegroundServiceStartNotAllowedException` fires in practice are all device facts. The code follows ARCHITECTURE §6.4's sequence and is started only from a resumed Activity | **High (blocks the Phase 2a gate)** | V-08 |
| 27 | **The Apple WebRTC dependency has a single point of failure outside this project's control, and it fired within five days.** Phase 2a pinned `stasel/WebRTC` `151.0.0` with its SHA-256 verified byte-for-byte; on 2 Sep upstream **deleted that release** ("accidentally", their words) and the phase's first CI run failed with a hard 404 on the binary. `151.0.1` is not a usable replacement — its manifest points at the deleted `151.0.0` URL while carrying the new checksum, so it fails with either a 404 (cold cache) or a checksum mismatch (warm). Re-pinned to `152.0.0` and re-validated from scratch. **A checksum protects integrity, not availability**: integrity held perfectly — the mismatch was *detected* — and the build broke anyway ([ADR-020 Amendment A1](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a1--2-september-2026--the-apple-pin-moves-to-m152-because-upstream-deleted-the-m151-release)) | **High** | It will happen again. Re-pinning is the cheap response and is what was done; **vendoring the ~45 MB XCFramework is the only option that removes the failure mode** and should be reconsidered on the next occurrence. Android is unaffected — Maven Central does not permit deleting a published artifact, and that asymmetry between the two distributions is now a recorded property rather than an assumption |
| 28 | **A CI-only test failure was not diagnosable from the CI log.** `PairingSessionIntegrationTest > an unknown peer holds the session in PAIRING…` (a **Phase 1b** test) failed on the same run, and Gradle's default `SHORT` exception format printed only `AssertionFailedError at PairingSessionIntegrationTest.kt:314` — a line that is the body lambda's call site and therefore names no assertion. It does not reproduce locally: attempted under 8-way CPU load, and with the duplicate-connection grace period forced from 300 ms to 1 ms (which disproved the first hypothesis, that asymmetric grace-period expiry lets the two sides promote different connections). **Root cause not yet established.** It did **not** recur on runs 33607112656 or 33654431951, both of which had Android fully green — which is evidence that it is intermittent, not evidence that it is fixed | **High** | Fixed the *diagnosability* first, which was itself a real gap: all five modules now use `exceptionFormat = FULL` with stack traces and causes, and CI uploads the test reports as an artifact `if: failure()`. The next occurrence will name its assertion. Do **not** re-run until green — that is the flaky-skip the brief forbids |
| 29 | **A Phase 1b timing test tripped its ceiling in CI because Phase 2a changed what shares its process.** `PingRaceAndReconnectTests.testRepeatedClockBurstsAllCompleteQuickly` asserts an 11-sample clock burst converges within a fixed budget. That budget (4.0s) was measured when the `RideLinkPlatform` test binary held control-plane code only; it now also links a ~96 MB WebRTC framework and, a few tests earlier in the same process, stands up two real `RTCPeerConnectionFactory` instances with their own worker threads. CI run 33607112656 tripped it with the signature the test's own comment predicts — `elapsed 4.129s`, `pendingPings=1`, `rttMs=3.0` (three **milliseconds**: the wire was healthy and a PONG was measured; one waiter was not resumed before its own 3s `pingTimeoutMs` fired). Actor-scheduling starvation on a three-core hosted runner, not a protocol or lifecycle bug — `PingRequestRegistry`'s own tests cover the bookkeeping | Low | Ceiling raised to 8.0s with the arithmetic written down: a single dropped PONG costs the full 3.0s timeout on top of a ~0.6s healthy burst, so ~3.6s is the floor before contention. 8.0s clears it with margin and stays below the 10s resync interval, so a genuinely stuck burst still fails. **The underlying fragility is not removed:** a wall-clock assertion sharing a process with a real media stack will always be environment-sensitive. The durable fix is to assert the invariant (every ping resolves, no stale waiter) and measure the timing separately — a Phase 1b test-design change, not a Phase 2a one |
| 26 | **APK/IPA size.** The Android AAR adds ~48 MB of native code across four ABIs; the Apple XCFramework is ~96 MB expanded and embedded in the app bundle. No ABI filtering or slice stripping is applied — the default is the safe configuration and a sideloaded personal build has no size gate | Low | Revisit if install time becomes annoying. Recorded rather than forgotten |
| 21 | **Diagnostics now show `CONNECTING` while a six-digit code is on screen**, where they previously showed `CONNECTED`. This is deliberate and more honest (ADR-019 §5), but it is a user-visible change that has never been looked at on a real screen | Low | Confirm it reads sensibly during I-02 on the two phones; the FR-023 diagnostics screen is one of the things I-02 exercises anyway |

Resolved 26 Aug 2026 session: `CLAUDE.md` in `.gitignore` (was problem 1); `.DS_Store` tracking
(was problem 7 — the claim was incorrect; the files are untracked and now ignored); the ADR-015/
ADR-010 leadership-independence rationale error (§2b).

---

## 5. Architectural risks that remain genuinely open

Only these. Everything else is a known task with a known shape.

1. **Secure transport — mostly closed, one thread left.** Both platform capabilities are now demonstrated working *together* (ADR-017, ADR-018), so the "stop and review" trigger did not fire. What is left is narrower and specific: the Android half of the exporter equality was measured against **Conscrypt-on-a-laptop**, not against the phone's own TLS stack, and neither Android Keystore nor the iOS Keychain has been exercised on a device. Integration tests I-02/I-19/I-20/I-21 close it. Until they run, "the two phones show the same six digits" is a well-supported expectation, not a measurement.
2. **Bluetooth duplex-profile coupling.** Now modelled honestly rather than wrongly, *and implemented* — each platform's route mapper is the single place ADR-016 vocabulary is produced, and both currently report `confidence: assumed` because that is the truth. Modelling it still does not fix it. Whether *any* of Modes A–E is genuinely pleasant with the real helmet unit is unknown until TEST_PLAN §6.1's A-12…A-15 run. **The product's viability sits here**, and Phase 2a moved it not at all.

2a. **Voice media on a phone.** Real WebRTC media is now proven *locally* — host-only ICE, DTLS-SRTP, Opus, two real engines, deterministic. What that does not touch: any microphone, any speaker, `AVAudioSession`, `AudioManager`, a foreground service, a screen lock, and the Android media path at all. Closed by TEST_PLAN §5.1's V-01…V-11, not by more unit tests.
3. **iOS `AVAudioEngine` scheduling precision** against the <100 ms sync target on real hardware. Measured in Phase 5, not assumable.
4. **Hotspot behaviour on a moving motorcycle** — an idle iPhone hotspot may sleep its interface; Android hotspot behaviour is vendor-dependent. Phase 1 test I-07 is the first real data.

---

## 6. Open questions for the user

Not blocking Phase 1. Answers needed before Phase 6.

1. **Phase 0 results** — which intercom mode (A–E) was validated? Helmet unit make/model? Most stable network topology (common Wi-Fi / Android hotspot / iPhone hotspot)? Measured end-to-end voice latency? Any surprises?
2. **Library size** — roughly how many tracks, and which formats? Determines whether FLAC matters and how hard to push on index performance. It also sets the realistic manifest page count.
3. **iPhone cache cap** default (DOCX §24)? Suggest 8 GB with a user control.

---

## 7. Next exact task

**Phase 2a — voice transport foundation. IMPLEMENTATION COMPLETE, REAL-DEVICE AUDIO GATE PENDING.**
Everything below is done and verified by the automatable tests this machine can run. What remains is
hardware.

1. ✅ **WebRTC dependency spike, run before any implementation** — both distributions pinned exactly, licences confirmed, Apple's checksum verified independently, both binaries read for telemetry, release builds proven (§2i, [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md)).
2. ✅ **PROTOCOL §7 specified in full** — schemas, bounds, the authentication gate, the generation guard, the offerer rule, logging rules, lifecycle. `VOICE_OFFER.ice_ufrag_hint` removed as a duplicate of a value the SDP already carries.
3. ✅ **Signalling rides the authenticated TLS control channel** — no second socket, and `VOICE_*` is absent from the pre-authentication frame allowlist, so it is inert before the trust gate by construction rather than by a check.
4. ✅ **The pre-authentication refusal is proven over real TLS on both platforms**, with the refusals counted so the test cannot pass vacuously.
5. ✅ **Deterministic offerer and glare** — the ADR-010 leader always offers; a follower sends intent; the offerer's response to intent is idempotent. Asserted as a *property* over every arrival order, not one case.
6. ✅ **Host-only ICE** — empty server list, no field that could carry a STUN/TURN server, and the gathered set asserted to be exactly `{host}` against a real stack.
7. ✅ **Opus and DTLS-SRTP established and measured** on this machine, deterministically.
8. ✅ **The voice state model, start/end, mute/unmute, bounds and validation, teardown, and the stale-callback guard** — all in the shared `VoiceNegotiation` table, pinned by 52 vector rows on both platforms.
9. ✅ **Control-reconnect integration** — media drops, capture survives (ARCHITECTURE §6.3/§6.4), voice rebuilds as a fresh generation. Asserted over the whole role × status cross-product.
10. ✅ **Audio-session and route foundations on both platforms**, with the platform→ADR-016 mapping in exactly one place per platform and both reporting `confidence: assumed`, which is the truth.
11. ✅ **No sensitive logging** — no SDP, no candidate string, no address or port, no keying material has a log *or display* path; candidate **types** only.
12. ✅ **Both platforms' full CI gates green** locally.

**Immediately actionable next steps, in order:**

1. **Get two real devices into this loop.** Unchanged since Phase 1a and now blocking three gates:
   (a) enable USB debugging on the OnePlus Nord 5 so `adb devices` sees it; (b) set up a
   development-team signing identity for the iPhone 17 Pro Max (Xcode → Signing & Capabilities →
   Personal Team — the user's call, not made here).
2. **Run the Phase 1a gate**: I-01, I-05, I-06, I-07, I-08, I-14, I-15, I-17, I-22.
3. **Run the Phase 1b gate**: I-02, I-03, I-04, I-16, I-19, I-20, I-21. I-02 is still the single most
   important one in the repository — it is what turns "the two phones show the same six digits" from
   a well-supported expectation into a measurement.
4. **Run the Phase 2a voice gate**: TEST_PLAN §5.1's **V-01…V-11**. V-04 is the on-device check that
   host-only ICE held on a real Wi-Fi radio; V-07 is the one that proves a link blip does not
   renegotiate the Bluetooth profile; V-08 is the screen-lock/background question, which nothing
   about a compiling foreground service answers; V-11 is the first latency number this project will
   ever have.
5. **Run the audio-hardware gate**: TEST_PLAN §6.1's **A-12…A-15**, with the helmet unit and the TWS
   earbuds. A-13 is the measurement ADR-016 exists to make checkable — whether
   `profile_coupling: input_forces_output` is actually true of the real hardware. **The product's
   viability sits here.**
6. **Record every result** — pass, fail and measured numbers — in `docs/test-results/`, per the
   "measurements, not impressions" rule. A-15 then flips `confidence` to `measured` in both route
   mappers and fills in `docs/PHASE0_RESULTS.md`; the mapper tests currently **assert** `assumed`, so
   they are what changes.
7. **Then the recorded tech debt**, now overdue: extract a `PairingController` from
   `ControlSessionManager` (§4 problem 18 — escalated to Medium this session, and the documented
   detekt threshold is the last headroom available), and close the `SessionCoordinator` testability
   gap (§4 problem 20). Also still outstanding from Phase 1b: the manual `host:port`/QR fallback for
   blocked mDNS (§4 problem 19).

**Gate for 2a:** V-01…V-11 pass on the two real phones; A-12…A-15 recorded with numbers;
`voice-signal/` and `voice-fsm/` pass on both platforms (✅ **already true**); the pre-authentication
`VOICE_*` refusal holds (✅ **already true, over real TLS on both platforms**).

> **Do not treat Phase 2a as complete because the laptop tests are green — including the WebRTC
> ones.** The loopback test is real media and it is genuinely useful evidence, but it ran with no
> microphone, no speaker, no Bluetooth and no phone, and `packetsSent = 0` says so honestly. The
> Android media path has no test at all. Neither audio-session implementation has ever executed a
> single line on a device. The single highest product risk — whether opening a helmet unit's
> microphone collapses the pillion's music — is exactly as unmeasured as it was before this session.
>
> **And do not treat green CI as evidence about anything no test crosses.** That warning earned its
> place in Phase 1b (CI run 33098708512 was green over the trust-gate bug) and it earned it again
> here: three of this session's findings — ADR-016's self-contradiction, the duplicated audio
> vocabulary that compiled cleanly because the packages differed, and the un-inspected candidate
> gathering direction — were all in places the existing suites did not look. When reviewing this
> codebase, look for decisions in places the test suite cannot reach.

**Then Phase 2b — the intercom as a product.** PTT/VOX gating (Phase 2a always sends
`mode: continuous`), the five REQUIREMENTS §8 modes as one policy object, ducking, and the
`AUDIO_STATE` message that carries the route to the peer. Not before the device gate has numbers in
it: Phase 2b's decisions depend on what A-12…A-15 measure.

---

## 8. How to update this file

Every session, revise: current milestone/phase, completed work, tests passed, tests pending,
known problems, architecture changes, next exact task. A new session must be able to read this
file and continue **without guessing**. Record what was *verified*, not what was written.
