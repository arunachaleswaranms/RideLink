# RideLink — Status

**Updated:** 27 August 2026 (Phase 1a cleanup/hardening session)
**Current milestone:** M1 (Private voice link) — not started
**Current phase:** Phase 1a — control-plane skeleton, no crypto.
**Phase 1a status: IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING.** Every Phase 1a
deliverable in `docs/PROTOCOL.md` / `docs/ARCHITECTURE.md` §1–§7 is implemented and unit/
integration-tested on **both** platforms — discovery lifecycle (advertise+browse, Found/Updated/
Lost, self-filtering, dh rotation), `PlainControlTransportPhase1a` (framing, HELLO/HELLO_ACK,
socket-level duplicate-connection dedup, TCP_NODELAY+keepalive, reconnect/backoff), `core.sync` /
`RideLinkCore.Sync` clock estimation (shared vectors, byte-identical on both platforms), and a
Phase 1a diagnostics UI. This session (§2e) reviewed the whole control/discovery lifecycle
end-to-end, found and fixed ten real implementation defects (an iOS PING/PONG race, a reconnect
re-entrancy bug on both platforms plus a genuine "the ladder never actually stops" bug the fix for
that exposed, an unbounded-hang bug in iOS's TCP connect path, a release-build plaintext-transport
guard, an Android NSD callback leak, mDNS instance-name privacy leaks on both platforms, unsafe
malformed-message parsing on both platforms (including a wire-triggerable crash on iOS), a
discovery-handle rotation self-discovery race on both platforms, real `@unchecked Sendable`
invariant violations on iOS, control-task teardown leaks on both platforms, and a live-wire
clock-sample overflow/crash risk on both platforms), each with regression tests. What is **still
not** done is running any of this on the two real phones — this machine has no Android
device/emulator (no `adb`, no AVD) and only the iOS *simulator* (not a physical iPhone) was
available this session. See §2d, §2e and §7 for exactly what that gate still needs.

**Repository state:** protocol vectors + schema exist (`vectors/clock/` since the prior session).
Android's five modules all build/test/lint/detekt clean, **including `assembleRelease`, new this
session** — `network` now has 52 tests (measured, not the prior session's claimed count carried
forward), including this session's new regression coverage: NSD callback lifecycle, mDNS
instance-name privacy, reconnect re-entrancy, the iOS PING race, malformed PING/PONG handling,
dh-rotation self-race, and teardown. `app` has 3 tests for the new release-transport gate. iOS
mirrors this: `RideLinkCore` (17/17 tests, unchanged) and `RideLinkPlatform` (40/40 tests, up from
17 — 23 new regression tests this session) both build and test clean under Swift 6 strict
concurrency with zero warnings; `RideLink.xcodeproj` builds in
**both Debug and Release** configurations (Release build now new/required, this session — proves
the plaintext transport compiles out) and the Phase 1a diagnostics UI renders correctly
on-simulator (screenshot-verified in the prior session; not re-screenshotted this session, since
no UI changed). See §2d, §2e.

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §6 |
| Docs baseline | ✅ Complete (earlier session) | Requirements transcribed, architecture/protocol/test plan/ADR-001…010 written |
| **Architecture correction pass** | ✅ Complete | 15 corrections applied before implementation. Details in §2 |
| **ADR-015/ADR-010 leadership-independence correction** | ✅ **Complete this session** | See §2b |
| **Phase 1a — control-plane skeleton** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING** | Protocol vectors, Android + iOS discovery, plaintext control transport, clock sync, diagnostics UI, and this session's hardening pass (§2e). Real-device gate is the only remaining work — see §7 |
| Phases 1b–8 | ⬜ Not started | |

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

## 3. Tests passed / pending

**Passed and verified 27 August 2026 session, by actually running the commands:**

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
| 1 | **iOS self-signed X.509 generation has no first-party API** (`SecKey` cannot build certificates) | **High** | Highest-risk Phase 1b item. Needs a small hand-written DER encoder. **There is no validated fallback** — ADR-007 Amendment A1 governs the response. Spike it early |
| 2 | **TLS keying-material exporter availability is unconfirmed on both platforms** | **High** | The six-digit SAS depends on it. Second Phase 1b spike, same governing amendment. If absent, redesign the channel binding deliberately — do not weaken it |
| 3 | Phase 0 measured results not recorded (mode, helmet model, topology, latency) | Medium | Blocks **Phase 6 only**. Template ready at `docs/PHASE0_RESULTS.md`. Until filled, `AUDIO_STATE.confidence` stays `assumed` and Phase 6 defaults to Mode C |
| 4 | ~~Xcode not installed~~ **Resolved 26 Aug 2026 (this session).** User installed Xcode 27.0 beta; SDK confirmed newer than baseline (ADR-011 Amendment A2), deployment target unchanged; `RideLink.xcodeproj` and `RideLinkPlatform` now built and verified (§7) | — | Xcode 27 being a beta remains a residual, lower risk — see the amendment |
| 5 | WebRTC artifacts are community-published on both platforms | Medium | Pin exact versions in Phase 2; isolated behind `network/voice` / `RideLinkPlatform.Voice` |
| 6 | TCP jitter may floor clock-offset precision | Low | Measure in Phase 1 (I-08). Only if it exceeds ~5 ms, add a UDP `PING`/`PONG` path. Do not pre-build it |
| 7 | mDNS may be blocked on hotspots / enterprise APs | Medium | Manual `host:port` + QR fallback in Phase 1b |
| 8 | Removing `fp6` means a discovered peer cannot be labelled "known" before connecting | Low | Accepted trade (ADR-002 A1). Mitigated by an auto-attempt silent connect when exactly one trusted peer exists. Watch whether the pre-ride UX suffers in real use |
| 9 | `minSdk 31` is assumed to be the level where a public TLS exporter is available | Medium | Assumption, not verified — folded into problem 2. Raising `minSdk` is cheap if needed; both devices are far above it |
| 10 | ~~`swift test` cannot execute on this machine~~ **Resolved 26 Aug 2026 (this session).** Root cause was Command Line Tools alone not carrying a runnable `XCTest.framework`/Swift Testing runtime. User installed full Xcode 27.0 beta; `swift test` now runs, 16/16 pass | — | Tests use XCTest (not Swift Testing) — this was a deliberate Phase 1a choice made while blocked and is fine to keep, but revisit if the team later wants Swift Testing's nicer parameterization |
| 11 | ~~Neither discovery controller has run against a real second peer~~ **Partially resolved 27 Aug 2026 (this session).** Discovery lifecycle logic (Found/Updated/Lost, self-filtering, dh rotation, TXT privacy) is now unit/integration-tested against real local sockets/`NWBrowser` change sets on both platforms — see §2d. **Still open:** neither has run against `NsdManager`/`Network.framework`'s real mDNS stack on a real Wi-Fi radio, because no second device was available (problems 15/16). Whether `NEARBY_WIFI_DEVICES` is required on API 33+ is still unverified — ARCHITECTURE §6.4 already flags this as "settle on-device, don't assume" | Medium | Needs the real-device gate (§7) |
| 12 | AGP 9.x dropped the separate `org.jetbrains.kotlin.android` Gradle plugin; Compose BOM / `androidx.core` / `androidx.lifecycle` versions newer than the ones pinned this session require `compileSdk 37` | Low, but easy to regress | Documented in §1. Don't bump these three dependency versions without checking the compileSdk requirement first |
| 13 | `RideLink.xcodeproj`'s `project.pbxproj` was hand-authored (no Apple CLI creates one, and `xcodegen`/`tuist` weren't installed without asking). It resolves, builds, and runs on-simulator, but has not been opened in the Xcode GUI to confirm it looks/behaves like a normal project (no Assets.xcassets/app icon, minimal build settings) | Low | Open it in Xcode once to sanity-check; add an app icon when one exists. Not urgent — sideloaded personal builds don't need a store-quality icon |
| 14 | SwiftLint / SwiftFormat (ARCHITECTURE §10.2) are not installed on this machine | Low | Install when convenient; not blocking — ktlint/detekt (Android) are clean, Swift Xcode builds show zero compiler warnings |
| 15 | **No Android device or emulator available in this development environment** — no `adb` on `PATH`, no AVD configured. `PlainControlTransportPhase1a` and `NsdDiscoveryController` are therefore unverified against Android's real network stack | **High (blocks the Phase 1a gate)** | Needs either a physical OnePlus Nord 5 with USB debugging, or an AVD image + emulator installed via `sdkmanager`. Neither was set up this session — not attempted without asking, since it changes the toolchain |
| 16 | **No physical iPhone available this session** — only the iOS 17 Pro Max simulator, which does not exercise real mDNS multicast or real `Network.framework` Bonjour behaviour between two independent radios | **High (blocks the Phase 1a gate)** | Needs a physical iPhone 17 Pro Max with a Personal Team signing identity (CLAUDE.md "Apple Signing") — a user decision, not made here |

Resolved 26 Aug 2026 session: `CLAUDE.md` in `.gitignore` (was problem 1); `.DS_Store` tracking
(was problem 7 — the claim was incorrect; the files are untracked and now ignored); the ADR-015/
ADR-010 leadership-independence rationale error (§2b).

---

## 5. Architectural risks that remain genuinely open

Only these. Everything else is a known task with a known shape.

1. **Secure transport (problems 1 + 2).** The whole Phase 1b security design rests on two platform capabilities that nobody has yet demonstrated working together on these two devices. Both are spikes to run *first*, and both have an explicit "stop and review" response rather than a fallback.
2. **Bluetooth duplex-profile coupling.** Now modelled honestly rather than wrongly, but modelling it does not fix it. Whether *any* of Modes A–E is genuinely pleasant with the real helmet unit is still unknown until Phase 0's results are recorded or Phase 6 measures it. The product's viability sits here.
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

**Phase 1a — control-plane skeleton, no crypto yet. IMPLEMENTATION COMPLETE, REAL-DEVICE GATE
PENDING.** Every step below is done and verified by the automatable tests available on this
machine. What remains is entirely the two-device gate, which needs hardware this session did not
have — it is not a code task.

0. ✅ **Toolchains** — JDK 21, Android SDK 36, and Xcode 27.0 beta all installed and verified (§1, ADR-011 Amendments A1 and A2). No global Gradle — deliberately.
1. ✅ **`protocol/`** — `schema/envelope.schema.json` + `vectors/{envelope,sas,dedup,session-fsm,clock}/`. `clock/` added this session (§2d).
2. ✅ **Android scaffold** — five modules, wrapper committed. `./gradlew clean test ktlintCheck detekt assembleDebug` all succeed (§2d, §3).
3. ✅ **iOS scaffold, all three pieces** — `RideLinkCore` (17/17), `RideLinkPlatform` (17/17), `RideLink.xcodeproj` (builds + runs on-simulator, screenshot-verified), zero warnings (§2d, §3).
4. ✅ **`core.model` / `RideLinkCore.Model`** — done and verified on both platforms.
5. ✅ **`core.protocol` / `RideLinkCore.Protocol`** — done and verified on both platforms against the same shared vectors.
6. ✅ **`core.sessionfsm` / `RideLinkCore.SessionFSM`** — done and verified on both platforms against the same shared vectors.
7. ✅ **Discovery** — Found/Updated/Lost lifecycle, self-filtering, dh rotation, shared-listener advertising, TXT privacy, API-tiered Android resolution (§2d). Unit/integration-tested on both platforms; **not yet run against either platform's real mDNS stack on real hardware** (§4 problems 11, 15, 16).
8. ✅ **`network.control` / `RideLinkPlatform.Control`** — `PlainControlTransportPhase1a`: framing (cap enforced pre-allocation), HELLO/HELLO_ACK, real-socket duplicate-connection dedup, `TCP_NODELAY`+keepalive, PROTOCOL §10 reconnect ladder. Done in the first session (§2d), tested with real loopback sockets on both platforms including simultaneous mutual connect. **Hardened in the follow-up session (§2e):** PING race, reconnect re-entrancy, malformed-message safety, teardown leaks, clock-sample overflow, and — ahead of the Phase 1b gate below — the plaintext transport is now actually compiled out of release builds on both platforms, not just documented as debug-only.
9. ✅ **`core.sync` / `RideLinkCore.Sync`** — offset/RTT/jitter estimator with outlier rejection, EWMA, step-confirmation, against 16 shared `clock/*.json` vectors, byte-identical on both platforms. Done this session (§2d).
10. ✅ **Diagnostics UI** on both — state, peer, RTT, offset, jitter, reconnect count, discovery count, and an explicit `PLAIN / PHASE 1A / NOT SECURE` transport banner. Done this session (§2d); screenshot-verified on iOS simulator, build-verified on Android (no emulator to run it on).

**Immediately actionable next steps, in order, and they are now all the same kind of task:**

1. **Get two real devices into this loop.** Concretely: (a) enable USB debugging on the OnePlus
   Nord 5 and get `adb devices` seeing it (or install an AVD via `sdkmanager` + `avdmanager` as a
   fallback, though a real device is what the gate actually needs), and (b) get a development-
   team signing identity set up for the iPhone 17 Pro Max (CLAUDE.md "Apple Signing" — the user's
   call, Xcode → Signing & Capabilities → Personal Team) and install the debug build via Xcode or
   `xcodebuild -destination 'platform=iOS,id=<udid>'`.
2. Run I-01 (discovery finds the peer within 5 s), I-14 (protocol version mismatch handled
   cleanly — can be forced with a debug build), I-15/I-16/I-17 (simultaneous connect/pairing/
   repeated-reconnect trials — the real hardware version of this session's loopback tests), I-22
   (capture mDNS traffic with an independent tool and confirm the TXT record really is
   `{v, dh, plat}` on the wire, not just in the code path this session's tests exercised), I-05/
   I-06 (aeroplane-mode reconnect), I-07 (repeat on both hotspot topologies), I-08 (5-minute
   clock-offset stability — this is the number that answers §16's open TCP-jitter question).
3. Record every result — pass, fail, and measured numbers (discovery time, RTT, offset stddev) —
   in `docs/test-results/`, per the brief's "measurements, not impressions" rule.

**Gate for 1a:** integration tests I-01, I-05, I-06, I-07, I-08, I-14, I-15, I-17, I-22 pass on
the two real phones; AF-10 (manifest inspection) passes; static analysis clean, including the
retired-vocabulary and discovery-privacy scans of TEST_PLAN §8 (the latter is now automated —
§2d — but I-22's independent capture is still required, since a unit test proving the *code path*
is correct is not the same as an outside observer confirming what actually goes on the wire).

> Phase 1a's plaintext control channel is **debug-build only and must never be the default**.
> `NFR-06` is satisfied by Phase 1b, not 1a. Sequenced this way so discovery, framing, the FSM,
> reconnect and connection deduplication are validated *before* the two security spikes (§4
> problems 1 and 2) are tackled.

**Then Phase 1b — security.** Run the two spikes first and record their outcome before building
on them: (a) self-signed X.509 from a Keystore/Keychain keypair, TLS 1.3 completing
Android↔iOS, identical SPKI hash on both sides; (b) a public TLS keying-material exporter on both
platforms producing identical output for one handshake. Then: device keypair in
Keystore/Keychain, TLS 1.3 both ends, `identity_spki_sha256` pinning with the re-issue semantics
of PROTOCOL §4.5.3, SAS derivation + pairing UI, manual `host:port`/QR fallback.
**Gate for 1b:** I-02, I-03, I-04, I-16, I-19, I-20, I-21 pass; `vectors/sas/` and
`vectors/identity/` pass on both platforms; the plaintext path is compiled out of release builds
(✅ **already true**, done in the §2e cleanup session, ahead of when this gate needed it).

**Recommended next coding session, once the real-device Phase 1a gate above passes:** Opus 5 /
xhigh reasoning effort, for the Phase 1b secure-transport spike (ADR-007 Amendment A1's two
open-risk items — iOS self-signed X.509 generation and TLS keying-material exporter availability
on both platforms). Do not start that work before the real-device gate passes.

---

## 8. How to update this file

Every session, revise: current milestone/phase, completed work, tests passed, tests pending,
known problems, architecture changes, next exact task. A new session must be able to read this
file and continue **without guessing**. Record what was *verified*, not what was written.
