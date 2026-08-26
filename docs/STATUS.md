# RideLink — Status

**Updated:** 26 August 2026 (Phase 1a scaffolding session)
**Current milestone:** M1 (Private voice link) — not started
**Current phase:** Phase 1a — control-plane skeleton, no crypto. **In progress.**
**Repository state:** protocol vectors + schema exist; Android scaffolded across all five modules
with `core` (model, protocol codec, SAS, dedup, leadership, session FSM, logging/redaction) fully
implemented and green (build, tests, ktlint, detekt); Android `network.discovery` (NsdManager)
implemented; a minimal Compose UI is wired end-to-end. iOS is now equally scaffolded: `RideLinkCore`
(same domain logic ported 1:1, 16/16 tests pass), `RideLinkPlatform` (Bonjour discovery via
`Network.framework`, 2/2 tests pass), and — new this session — a real `RideLink.xcodeproj` app
target that **builds for the iPhone 17 Pro Max simulator and runs**, showing the same minimal UI
as Android (verified with a screenshot, not just a successful build). Cross-platform vector parity
is confirmed by execution for envelope, SAS, dedup and session-FSM. `network.control` (TCP
framing + dedup wiring) and clock-sync are not yet started. See §7.

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §6 |
| Docs baseline | ✅ Complete (earlier session) | Requirements transcribed, architecture/protocol/test plan/ADR-001…010 written |
| **Architecture correction pass** | ✅ Complete | 15 corrections applied before implementation. Details in §2 |
| **ADR-015/ADR-010 leadership-independence correction** | ✅ **Complete this session** | See §2b |
| **Phase 1a — control-plane skeleton** | 🔶 **In progress this session** | Protocol vectors, Android scaffold + core + discovery, iOS RideLinkCore scaffold. Details in §2c. Remaining work in §7 |
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

**Xcode is the only remaining prerequisite**, and it blocks the iOS half of scaffolding only.

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

## 2b. ADR-015 / ADR-010 correction (this session)

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

## 2c. Phase 1a scaffolding (this session)

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

## 3. Tests passed / pending

**Passed and verified this session, by actually running the commands:**

- `./gradlew :core:test` (Android) — **all tests pass**, consuming
  `protocol/vectors/{envelope,sas,dedup,session-fsm}/*.json` directly (no vector data duplicated
  as Kotlin literals, per CLAUDE.md).
- `./gradlew test ktlintCheck detekt` across all five Android modules — clean.
- `./gradlew assembleDebug` — succeeds, produces `app/build/outputs/apk/debug/app-debug.apk`.
- `swift test --package-path ios/Packages/RideLinkCore` — **16/16 tests pass**, once Xcode 27.0
  beta was installed mid-session (§4 problem 10 resolved, ADR-011 Amendment A2). Same four shared
  vector files as Android, executed independently on the Swift side. **Cross-platform vector
  parity for envelope, SAS, dedup and session-FSM is now confirmed by execution, not just by
  code-parity review.**
- `swift test --package-path ios/Packages/RideLinkPlatform` — **2/2 tests pass** (pure
  `DiscoveryHandle` format checks).
- `xcodebuild -project ios/RideLink.xcodeproj -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build`
  — **succeeds.** Installed and launched on that simulator via `xcrun simctl`; a screenshot
  confirms the UI renders exactly as specified (device name, "Idle", Start Discovery button).

**Not passed / not run, stated plainly:**

- No two-device (L4) tests — no second physical device/simulator pair exercising real discovery
  was run. AF-01…AF-10, IA-01…IA-09, I-01…I-25 all remain pending exactly as before.
- The "Start Discovery" button was not interactively tapped on the simulator (no reliable
  synthetic-tap mechanism via `simctl` without a full XCUITest target, which wasn't built this
  session) — the FSM transition it triggers is covered by `SessionFsmVectorTests` on both
  platforms, but the SwiftUI/Compose button-to-coordinator wiring itself is only build-verified,
  not interaction-verified.
- Neither `NsdDiscoveryController` (Android) nor `BonjourDiscovery` (iOS) has run against a live
  peer — both compile and follow the documented API, but neither is otherwise verified.
- SwiftLint / SwiftFormat (ARCHITECTURE §10.2) are not installed on this machine and were not
  run against the new Swift code. Not installed without asking first, per this session's practice
  of asking before adding new tooling.

Test debt remaining, all specified in `docs/PROTOCOL.md` §11 / `docs/TEST_PLAN.md` but not yet
written (these are Phase 1b/4/5/6 concerns, not Phase 1a's):

| Vector set | Covers |
|---|---|
| `vectors/manifest-paging/` | 1 / 1 000 / 5 000 entries, pathological metadata, digest determinism |
| `vectors/manifest-paging-errors/` | 12 failure cases; each asserts the live manifest is unchanged |
| `vectors/identity/` | SPKI formatting, pin match/mismatch, certificate re-issue with unchanged key |
| `vectors/audio-state/` | enum vocabulary, `revision` monotonicity, derived `media_quality` |
| `vectors/clock/`, `vectors/drift/`, `vectors/queue/`, `vectors/manifest/`, `vectors/ordering/` | Phases 1a(clock)/5/8 |

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
| 11 | Neither `NsdDiscoveryController` (Android) nor `BonjourDiscovery` (iOS) has run against a real second peer — no two-device/two-simulator discovery test was run this session | Medium | First real run is the Phase 1a gate (I-01, discovery privacy scan). Whether `NEARBY_WIFI_DEVICES` is actually required on API 33+ is also unverified — ARCHITECTURE §6.4 already flags this as "settle on-device, don't assume" |
| 12 | AGP 9.x dropped the separate `org.jetbrains.kotlin.android` Gradle plugin; Compose BOM / `androidx.core` / `androidx.lifecycle` versions newer than the ones pinned this session require `compileSdk 37` | Low, but easy to regress | Documented in §1. Don't bump these three dependency versions without checking the compileSdk requirement first |
| 13 | `RideLink.xcodeproj`'s `project.pbxproj` was hand-authored (no Apple CLI creates one, and `xcodegen`/`tuist` weren't installed without asking). It resolves, builds, and runs on-simulator, but has not been opened in the Xcode GUI to confirm it looks/behaves like a normal project (no Assets.xcassets/app icon, minimal build settings) | Low | Open it in Xcode once to sanity-check; add an app icon when one exists. Not urgent — sideloaded personal builds don't need a store-quality icon |
| 14 | SwiftLint / SwiftFormat (ARCHITECTURE §10.2) are not installed on this machine | Low | Install when convenient; not blocking — ktlint/detekt (Android) are clean, Swift code has had no static-analysis pass yet |

Resolved by this session: `CLAUDE.md` in `.gitignore` (was problem 1); `.DS_Store` tracking
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

**Phase 1a — control-plane skeleton, no crypto yet.** In progress; steps 0–7 are now done on both
platforms (7 built but unverified against a live peer on either side — see below). 8 and 9 remain
before the diagnostics UI in 10.

Order matters: the pure layers come first because they are testable without a device, and they
define the interfaces the platform layers implement.

0. ✅ **Toolchains** — JDK 21, Android SDK 36, and Xcode 27.0 beta all installed and verified (§1, ADR-011 Amendments A1 and A2). No global Gradle — deliberately.
1. ✅ **`protocol/`** — `schema/envelope.schema.json` + `vectors/{envelope,sas,dedup,session-fsm}/`. Done this session (§2c).
2. ✅ **Android scaffold** — five modules, wrapper committed (Gradle 9.7.1, SHA-256-verified). `./gradlew assembleDebug` and `./gradlew :core:test` both succeed (§2c, §3).
3. ✅ **iOS scaffold, all three pieces** — `Packages/RideLinkCore` (16/16 tests), `Packages/RideLinkPlatform` (2/2 tests), and `RideLink.xcodeproj` (builds + runs on the iPhone 17 Pro Max simulator, screenshot-verified) all exist and build (§2c, §3).
4. ✅ **`core.model` / `RideLinkCore.Model`** — done and verified on both platforms (§2c, §3).
5. ✅ **`core.protocol` / `RideLinkCore.Protocol`** — done and verified on both platforms against the same shared vectors (§2c, §3).
6. ✅ **`core.sessionfsm` / `RideLinkCore.SessionFSM`** — done and verified on both platforms against the same shared vectors (§2c, §3).
7. 🔶 **Discovery** — implemented on both platforms now: Android `network.discovery.NsdDiscoveryController` and iOS `RideLinkPlatform.Discovery.BonjourDiscovery`, both advertise+browse with TXT limited to `{v, dh, plat}`. **Neither has run against a real second peer** (§4 problem 11) — that requires two devices/simulators actually finding each other, which is the next real milestone, not a code task.
8. ⬜ **`network.control` / `RideLinkPlatform.Control`** — length-prefixed framing over **plain TCP**, `TCP_NODELAY`, keepalive, reconnect with jittered backoff, and wiring the already-implemented, already-tested `core.protocol.Dedup`/`Leadership` (both platforms) into real socket handling. **Not started.**
9. ⬜ **`core.sync` / `RideLinkCore.Sync`** — offset estimator with outlier rejection, against `clock/*.json` vectors (which also don't exist yet — write them alongside this step). **Not started.**
10. ⬜ **Minimal diagnostics UI** on both — state, peer, RTT, offset, jitter, reconnect count (the FR-023 subset available once step 9 exists). **Not started**; the current UI (state + discovered-peer count + Start/Stop Discovery) is step 2/3/7's UI, not this one.

**Immediately actionable next steps, in order:** (a) run the Android app on a device/emulator and
the iOS app on a real iPhone or two simulators to verify discovery finds a real peer and close
problem 11 — this is the actual Phase 1a gate item, not more code; (b) `network.control` (step 8);
(c) clock sync (step 9).

**Gate for 1a:** integration tests I-01, I-05, I-06, I-07, I-08, I-14, I-15, I-17, I-22 pass on
the two real phones; AF-10 (manifest inspection) passes; static analysis clean, including the
retired-vocabulary and discovery-privacy scans of TEST_PLAN §8.

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
`vectors/identity/` pass on both platforms; the plaintext path is compiled out of release builds.

---

## 8. How to update this file

Every session, revise: current milestone/phase, completed work, tests passed, tests pending,
known problems, architecture changes, next exact task. A new session must be able to read this
file and continue **without guessing**. Record what was *verified*, not what was written.
