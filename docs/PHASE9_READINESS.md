# Phase 9 readiness — project audit, 24 September 2026

Baseline: `main` at `5b06918` — merged Phase 8 (`2aa728f`, PR #6) and Phase 8.5's UI polish
(PR #12, presentation only; see [PHASE8_5_UI_POLISH.md](PHASE8_5_UI_POLISH.md)). This audit's
changes are listed in §4.

**Verdict: MANUAL VALIDATION REQUIRED.** The software is in a fit state to begin physical
qualification, once the two prerequisites in §6 are done. Nothing in this document is a physical
result: no phone, helmet unit, TWS earbuds or ride was used.

## 1. What Phase 9 is

REQUIREMENTS §13 defines Phases 0–8 and nothing after them. The only definition of Phase 9 in the
repository is [PHASE8_5_UI_POLISH.md](PHASE8_5_UI_POLISH.md): **physical qualification** — the
hardware gates every phase since 1b has recorded as **DEFERRED — HARDWARE NOT AVAILABLE**. This
document keeps that scope. It adds no feature, and it does not reopen REQUIREMENTS.

Phase 9 therefore needs, at minimum, the Android rider phone (OnePlus Nord 5), a physical iPhone,
the helmet Bluetooth unit and the pillion's TWS earbuds. **No physical iPhone is available today**
(STATUS §4 problem 16), and that alone blocks most of §7.

## 2. Current architecture, in one paragraph

Two native apps (Kotlin/Compose, Swift/SwiftUI) speak one protocol over the local network. Control
is TCP + TLS 1.3 with mutual authentication and an SPKI pin set by a six-digit SAS comparison
(ADR-012/017/018/019). Voice is WebRTC over host-only ICE, negotiated on the control channel and
gated behind authentication (ADR-020/021). Bulk file transfer is a second session-bound TLS
connection (ADR-023). Each phone plays its own copy of a track against a synchronised session
clock, with drift correction and a replicated queue (ADR-004/024). Ride Mode, reconnect and state
resynchronisation are ADR-028. Every distributed decision is a pure, mirrored table pinned by
shared vectors. The coordinators own wiring and lifetime, never policy. The authoritative
description is [ARCHITECTURE.md](ARCHITECTURE.md).

## 3. What this audit covered, and how

Earlier passes audited the synchronised-playback and voice-negotiation lifetimes more than twenty
times. This pass deliberately went where they had not: the platform adapters and plumbing
underneath the pure tables. It covered the foreground service and Activity lifecycle, backup and
migration, the handshake and liveness path, untrusted input framing, the music player adapters,
CI, dependencies, logging, the Info.plist, and document consistency. Every finding below was
reproduced or traced from production code before anything changed. Every fix has a regression
that fails with the fix neutralised.

## 4. Findings

| STATUS § 4 | Area | Finding | Outcome |
|---|---|---|---|
| 101 | Android lifecycle | In-app End intercom awaited release on the Activity's scope; recreation inside the window left an orphaned `microphone` foreground service | **Fixed** — one process-scoped `IntercomStopOwner` for both entry points |
| 102 | Privacy / persistence | `allowBackup=false` does not stop device-to-device transfer on targetSdk 31+ | **Fixed** — `dataExtractionRules` exclude everything |
| 103 | Networking, both platforms | No deadline on `HELLO`/`HELLO_ACK`; a silent peer parked a reconnect attempt forever (RECONNECTING never ends) and held listener sockets | **Fixed** — 6 s watchdog, mirrored, real-TLS regressions both directions |
| 104 | Android audio | Music takes no audio focus and ignores becoming-noisy | **Open** — needs an ADR; built-in ExoPlayer handling would violate rules 18/26 |
| 105 | iOS audio | Music engine ignores `AVAudioEngineConfigurationChange` | **Open** — needs a device to verify a restart path |
| 106 | iOS audio | Local pause/resume double-scheduled the remainder and inflated position | **Fixed** — measured, then `stop()` + generation, as `seek` already does |
| 107 | Scope | NFR-08 log export and a sideload build procedure were never delivered | **Open** — first Phase 9 prerequisite |
| — | CI | `ci.yml` actions were tag-pinned while the Phase 8 record said "SHA-pinned"; no Gradle wrapper-JAR validation; no Gradle cache | **Fixed** — SHA-pinned, `gradle/actions/wrapper-validation`, `setup-java` cache |
| — | Dependencies | `:audio` declared `media3-session` and never used it | **Fixed** — removed (`:app` declares its own) |
| — | Documentation | README, CLAUDE.md, STATUS, the Phase 8 record and ADR-029 still described Phase 8 as an unmerged PR | **Fixed** |

**Checked and found sound**, so the next pass need not start there:

- Frame length validated before allocation (both platforms).
- TLS 1.3 only, with a cipher-suite check.
- The TLS handshake timeout; keepalive at 2 s, loss declared at 6 s.
- `START_NOT_STICKY`, with no background restart of the microphone service.
- `FLAG_IMMUTABLE` notification intents; only the launcher activity is exported.
- Empty-type `startForeground` handled.
- No `BluetoothDevice`/`BluetoothAdapter` call that could throw without `BLUETOOTH_CONNECT`.
- No raw logging in production sources on either platform.
- iOS privacy strings, `NSBonjourServices` and `UIBackgroundModes: audio` present; no ATS exception.
- CSPRNG `peer_id`.
- `runCatching` around pings still observes cancellation at the next `delay`.
- Identity loss on Android regenerates rather than crashing.

**Observed and deliberately not changed:**

- Android's accept loop runs the TLS handshake inline, so one stalling client can delay the next
  accept by up to 5 s.
- The clock-sync cadence (11 pings every 10 s, plus keepalive every 2 s) keeps the Wi-Fi radio busy
  for the whole session, including with the screen locked. It is specified by ARCHITECTURE §7.1, so
  its battery cost is an R-05 measurement, not a code change.
- `BLUETOOTH_CONNECT` is requested but no code path needs it today. Removing a permission is a
  device-verified change, not a desk one.
- `tools/crossplatform/run.sh` starts both halves together, and the iOS half's readiness window is
  shorter than a cold Gradle compile. After a source change the first run can fail with `notReady` /
  "no pairing prompt" before either half speaks; the warm re-run is the real result. Pre-compile
  (`./gradlew :network:compileDebugUnitTestKotlin`) before running it.

## 5. Remaining risks and technical debt (carried forward, not new)

- **No physical iPhone** (problem 16). Every iOS audio-session, Keychain, background and
  lock-screen claim is unexecuted.
- **Nothing has run on a phone with audio** (problems 22–25). This covers the Android WebRTC media
  path, both audio sessions and the foreground service.
- **Phase 0 results are empty** (problem 3). The Mode C default is architectural, not measured.
- **The manual `host:port`/QR fallback for blocked mDNS was never built** (problems 7, 19). This
  matters first on a phone hotspot, which is the likeliest ride topology.
- **VOX has no level source** (problem 30).
- **The iOS app target has no unit-test bundle** (problem 48).
- **SwiftLint/SwiftFormat are named but run nowhere** (problem 49).
- **Problems 104 and 105** above, both of which a first ride will surface.
- **No alignment, latency, battery or thermal figure exists.** The < 100 ms sync and < 200 ms
  voice targets must not be described as approached.

## 6. Prerequisites, and the recommended first Phase 9 task

1. **Diagnostics export (problem 107, NFR-08) — recommended first task.** Physical qualification is
   a field exercise, and its evidence is the transition log ARCHITECTURE §3 rule 5 already produces.
   The export must be a user-initiated share of the *redacted* sink (ARCHITECTURE §11 item 3) through
   the platform share sheet, on both platforms, with no new network path. Nothing that has no log
   path today (SAS, TLS secrets, exporter output, tokens) may gain one.
2. **A repeatable, documented sideload build** for both phones: Android signing with a local
   keystore that never enters the repository (`.gitignore` already covers it), and iOS installation
   with a personal team. REQUIREMENTS §13 lists it for Phase 8; nothing documents it.
3. **Decide problem 104's design (an ADR)** before the first ride, or record its behaviour as a
   known limitation for the first sessions.
4. Qualify one known build: `main` at or after this audit's merge, which includes Phase 8.5.

## 7. Manual tests still required (all MANUAL REQUIRED)

| Area | TEST_PLAN | Needs |
|---|---|---|
| Two-phone control, mDNS, hotspot, reconnect | I-02, I-07, §5 | Android + iPhone |
| Voice two-device gate | §5.1 V-01…V-11 | Android + iPhone |
| Synchronised playback gate | §5.2 S-01…S-12 | Android + iPhone + recorder |
| Audio hardware chain | §6 A-01…A-15 | + helmet unit + TWS |
| Foreground service, lock screen, notification actions | V-08, AF-01, AF-05, §4.1 | Android phone |
| Problem 101 on a device | — | Android phone: start the intercom, press End, rotate within the release window, and confirm the notification goes |
| Problem 102 | — | Two Android phones: device-to-device setup, then confirm a fresh `peer_id` and a pairing prompt |
| Problem 104 | — | Android: a navigation prompt, a call, and a helmet-unit disconnect while music plays |
| Problem 105 | — | iPhone: TWS connect/disconnect while playing; Start Intercom while playing |
| Ride tests | §7 R-01…R-05 | Stationary first; closed area before road |

## 8. Validation evidence for this audit

See [STATUS.md](STATUS.md)'s 24 September entry for the exact commands and counts. They are
recorded there, after the final source change, rather than restated here.
