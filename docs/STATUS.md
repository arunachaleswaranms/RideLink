# RideLink — Status

**Updated:** 26 August 2026
**Current milestone:** M1 (Private voice link) — not started
**Current phase:** Phase 1 — Peer session foundation. **Not started.**
**Repository state:** **architecture baseline corrected and ready for Phase 1 scaffolding.**
No application code exists.

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §6 |
| Docs baseline | ✅ Complete (earlier session) | Requirements transcribed, architecture/protocol/test plan/ADR-001…010 written |
| **Architecture correction pass** | ✅ **Complete this session** | 15 corrections applied before implementation. Details in §2 |
| Phase 1 — Peer session | ⏭ **Next. Nothing built** | No Gradle project, no Xcode project, no vectors, no source files |
| Phases 2–8 | ⬜ Not started | |

`android/` and `ios/` **do not exist**. Git does not track empty directories, so the earlier
claim that they were "empty directories from the initial commit" was wrong — they were never in
the repository at all. They are **planned directories, created during Phase 1 scaffolding**. No
placeholder files have been added to make the old wording true; there is no reason to.

`protocol/` exists and contains only `README.md`; `schema/` and `vectors/` arrive in Phase 1
alongside the codecs that consume them.

The build commands in `CLAUDE.md` describe intent, not something that runs today. This machine
currently has **no Android SDK, no Gradle and no Xcode** — only Swift 6.3.2 and the macOS 26.5
SDK via Command Line Tools, and Temurin JDK 25. All three must be installed before scaffolding.

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

## 3. Tests passed / pending

**Passed:** requirements-transcription completeness check (automated, earlier session).

**Pending — everything else.** No unit, vector, integration, platform, performance or ride test
exists yet. The first executable tests arrive with Phase 1.

Test debt created by this correction pass, all of it specified but none of it written:

| Vector set | Covers |
|---|---|
| `vectors/sas/` | 10 boundary values incl. `000000`, leading zeroes, two paths to `999999`, big-endian assertion, tail-bytes-ignored |
| `vectors/manifest-paging/` | 1 / 1 000 / 5 000 entries, pathological metadata, digest determinism |
| `vectors/manifest-paging-errors/` | 12 failure cases; each asserts the live manifest is unchanged |
| `vectors/identity/` | SPKI formatting, pin match/mismatch, certificate re-issue with unchanged key |
| `vectors/dedup/` | `conn_tiebreak` comparison, equal-value tie, initiator ≠ leader |
| `vectors/audio-state/` | enum vocabulary, `revision` monotonicity, derived `media_quality` |

Plus: Android AF-01…AF-10 (foreground service / microphone lifecycle), iOS IA-01…IA-09 (audio
session and route), and integration tests I-15…I-25. Full list in `docs/TEST_PLAN.md`.

---

## 4. Known problems

| # | Problem | Severity | Action |
|---|---|---|---|
| 1 | **iOS self-signed X.509 generation has no first-party API** (`SecKey` cannot build certificates) | **High** | Highest-risk Phase 1b item. Needs a small hand-written DER encoder. **There is no validated fallback** — ADR-007 Amendment A1 governs the response. Spike it early |
| 2 | **TLS keying-material exporter availability is unconfirmed on both platforms** | **High** | The six-digit SAS depends on it. Second Phase 1b spike, same governing amendment. If absent, redesign the channel binding deliberately — do not weaken it |
| 3 | Phase 0 measured results not recorded (mode, helmet model, topology, latency) | Medium | Blocks **Phase 6 only**. Template ready at `docs/PHASE0_RESULTS.md`. Until filled, `AUDIO_STATE.confidence` stays `assumed` and Phase 6 defaults to Mode C |
| 4 | No Android SDK, no Gradle, no Xcode on this machine | Medium | Install before Phase 1 scaffolding. The iOS 26.0 deployment target must be confirmed against the installed Xcode SDK; if it is older, amend ADR-011 rather than changing it silently |
| 5 | WebRTC artifacts are community-published on both platforms | Medium | Pin exact versions in Phase 2; isolated behind `network/voice` / `RideLinkPlatform.Voice` |
| 6 | TCP jitter may floor clock-offset precision | Low | Measure in Phase 1 (I-08). Only if it exceeds ~5 ms, add a UDP `PING`/`PONG` path. Do not pre-build it |
| 7 | mDNS may be blocked on hotspots / enterprise APs | Medium | Manual `host:port` + QR fallback in Phase 1b |
| 8 | Removing `fp6` means a discovered peer cannot be labelled "known" before connecting | Low | Accepted trade (ADR-002 A1). Mitigated by an auto-attempt silent connect when exactly one trusted peer exists. Watch whether the pre-ride UX suffers in real use |
| 9 | `minSdk 31` is assumed to be the level where a public TLS exporter is available | Medium | Assumption, not verified — folded into problem 2. Raising `minSdk` is cheap if needed; both devices are far above it |

Resolved by this session: `CLAUDE.md` in `.gitignore` (was problem 1); `.DS_Store` tracking
(was problem 7 — the claim was incorrect; the files are untracked and now ignored).

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

**Phase 1a — control-plane skeleton, no crypto yet.** Nothing below has been started.

Order matters: the pure layers come first because they are testable without a device, and they
define the interfaces the platform layers implement.

0. **Install the toolchains** — Android SDK (API 36), Gradle, and Xcode. Then confirm the iOS SDK version against ADR-011's iOS 26.0 baseline and record the result.
1. **Scaffold `protocol/`** — `schema/` and `vectors/`. Write the envelope JSON Schema and the first golden vectors: encode/decode round-trip, unknown-field tolerance, unknown-type tolerance, oversize rejection at 262 144 + 1, malformed rejection. Add the ten `vectors/sas/` cases from PROTOCOL §4.5.2 now, while the algorithm is fresh — they are pure data and need no code.
2. **Scaffold Android** — Gradle wrapper (committed), `settings.gradle.kts`, `gradle/libs.versions.toml` with every version pinned, JDK 21 toolchain pinned explicitly, and the five modules of ARCHITECTURE §9.1 with `core` as a `kotlin("jvm")` library. Verify `./gradlew assembleDebug` and `./gradlew :core:test` succeed.
3. **Scaffold iOS** — `RideLink.xcodeproj` plus `Packages/RideLinkCore` and `Packages/RideLinkPlatform`. Verify `swift test --package-path Packages/RideLinkCore` and `xcodebuild build` succeed.
4. **`core.model` / `RideLinkCore.Model`** — the 7 entities from REQUIREMENTS §16, with `identity_spki_sha256` on the peer entity.
5. **`core.protocol` / `RideLinkCore.Protocol`** — envelope codec + validation, driven by the vectors from step 1. **Both platforms must pass the same files.**
6. **`core.sessionfsm` / `RideLinkCore.SessionFSM`** — the 10-state machine as a pure `(state, event) → (state, effects)` function, with legal *and* illegal transitions under test, and the rule that a duplicate-connection close is neither a transition nor a fault.
7. **`network.discovery` / `RideLinkPlatform.Discovery`** — `NsdManager` advertise+browse; `NWListener`+`NWBrowser`. TXT records exactly `{v, dh, plat}` per ARCHITECTURE §4.1, with the privacy assertions of TEST_PLAN §4 written *as* the test.
8. **`network.control` / `RideLinkPlatform.Control`** — length-prefixed framing over **plain TCP**, `TCP_NODELAY`, keepalive, reconnect with jittered backoff, and the §4.2 duplicate-connection resolution including its `vectors/dedup/` cases.
9. **`core.sync` / `RideLinkCore.Sync`** — offset estimator with outlier rejection, against the `clock/*.json` vectors.
10. **Minimal diagnostics UI** on both — state, peer, RTT, offset, jitter, reconnect count (the FR-023 subset available now).

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
