# RideLink — Status

**Updated:** 26 August 2026
**Current milestone:** M1 (Private voice link) — not started
**Current phase:** Phase 1 — Peer session foundation
**Repository state:** documentation baseline complete; **no application code exists yet**

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §5 |
| **Docs baseline** | ✅ **Complete this session** | Requirements transcribed, architecture/protocol/test plan/ADRs written |
| Phase 1 — Peer session | ⏭ **Next** | Nothing built. No Gradle project, no Xcode project |
| Phases 2–8 | ⬜ Not started | |

Neither `android/` nor `ios/` contains a project yet — both are empty directories from the
initial commit. `protocol/` is empty. The build commands in `CLAUDE.md` describe intent, not
something that runs today.

---

## 2. Completed this session (26 Aug 2026)

Documentation and architecture only — **no feature implementation**, as instructed.

| Artefact | Status |
|---|---|
| `docs/REQUIREMENTS.md` | Faithful transcription of the source DOCX. **Verified programmatically**: every text fragment appears verbatim; all 25 FRs, 10 NFRs, 7 user journeys, 7 Phase-0 tests, 7 milestones, 22 tables, 158 bullets present |
| `docs/ARCHITECTURE.md` | System shape, 3 data planes, 5-layer model, 10-state FSM, discovery/pairing, leadership, audio per platform, clock sync + drift ladder, library/transfer, **module structures for both platforms**, dependency choices, risk register |
| `docs/PROTOCOL.md` | v1 wire spec: envelope, evolution rules, replay/ordering, handshake, pairing SAS, playback commands, clock sync, voice signalling, manifests, chunked transfer, queue replication, reconnect reconciliation, test-vector plan |
| `docs/TEST_PLAN.md` | 6 test layers, per-layer cases, two-device integration matrix, audio-hardware procedures, ride stages, phase exit gates, V1 acceptance mapping |
| `docs/DECISIONS/ADR-001…010` | All major choices recorded with alternatives |
| `CLAUDE.md` | Concise session primer |
| `tools/extract_docx.py` + `tools/README.md` | Stdlib-only DOCX extractor (avoided adding `python-docx`) |
| `docs/PHASE0_RESULTS.md` | Template awaiting user input |
| `docs/test-results/README.md` | Recording conventions |
| `README.md` | Project overview |

**Verification performed:** transcription completeness check (automated, passed); DOCX confirmed
unmodified (`git status` clean, SHA-256 recorded in `REQUIREMENTS.md`).
**No builds or tests were run** — there is no code to build. This is stated plainly rather than
implied.

---

## 3. Tests passed / pending

**Passed:** requirements-transcription completeness check (automated).

**Pending — everything else.** No unit, integration, platform, performance or ride test exists
yet. The first executable tests arrive with Phase 1 (`core:protocol` / `RLProtocol` vectors).

---

## 4. Known problems

| # | Problem | Severity | Action |
|---|---|---|---|
| 1 | **`CLAUDE.md` is listed in `.gitignore`** (committed in `647dfd5`), so it will not be committed and a fresh clone or another machine loses it — defeating its purpose as cross-session memory | Medium | **Needs a user decision.** Recommend removing that line. Not changed unilaterally: `.gitignore` was a deliberate user commit |
| 2 | Phase 0 measured results not recorded (mode, helmet model, topology, latency) | Medium | Blocks **Phase 6 only**. Template ready at `docs/PHASE0_RESULTS.md` |
| 3 | iOS self-signed TLS certificate generation has no first-party API (`SecKey` can't build certs) | **High** | Highest-risk Phase 1b item. Needs a small DER X.509 encoder; fallback is TLS-PSK. Spike it early |
| 4 | WebRTC artifacts are community-published on both platforms | Medium | Pin exact versions in Phase 2; isolated behind `net:voice` / `RLVoice` |
| 5 | TCP jitter may floor clock-offset precision | Low | Measure in Phase 1 (I-08). Only if it exceeds ~5 ms, add a UDP `PING`/`PONG` path. Do not pre-build it |
| 6 | mDNS may be blocked on hotspots / enterprise APs | Medium | Manual `host:port` + QR fallback in Phase 1b |
| 7 | `.DS_Store` files are tracked in `docs/` and repo root | Low | Already in `.gitignore` but committed before it existed; `git rm --cached` when convenient |

---

## 5. Open questions for the user

Not blocking Phase 1. Answers needed before Phase 6.

1. **Phase 0 results** — which intercom mode (A–E) was validated? Helmet unit make/model? Most stable network topology (common Wi-Fi / Android hotspot / iPhone hotspot)? Measured end-to-end voice latency? Any surprises?
2. **`CLAUDE.md` in `.gitignore`** — remove the entry so it is version-controlled? (Recommended.)
3. **Library size** — roughly how many tracks, and which formats? Determines whether FLAC matters and how hard to push on index performance.
4. **iPhone cache cap** default (DOCX §24)? Suggest 8 GB with a user control.

---

## 6. Architecture changes this session

Baseline established — nothing to change yet. Decisions that go beyond the DOCX and are worth
knowing about:

- **Three data planes with control on TCP+TLS rather than a WebRTC DataChannel** (ADR-007). WebRTC can't bootstrap itself, so a signalling socket is unavoidable; therefore build it well and let it carry all non-realtime traffic. Keeps Phase 1 free of the WebRTC dependency and makes FR-025 graceful degradation structural.
- **Two-tier hashing** (ADR-005) so indexing a large library isn't gated on full-file SHA-256, while keeping the authoritative hash authoritative.
- **Drift ladder with a rate-nudge tier** (ARCHITECTURE §7.3) instead of seek-only correction — dead-band < 25 ms, ±0.2 % rate nudge 25–120 ms, hard seek > 120 ms, declare failure > 2 s.
- **Pairing SAS bound to the TLS exporter secret** (PROTOCOL §4.3), making the 6-digit confirmation a real MITM check rather than decoration.
- **Three-level sync target** reconciling the DOCX and the session instruction (ADR-008): ≤150 ms hard floor, <100 ms product target, <50 ms stretch.

---

## 7. Next exact task

**Phase 1a — control-plane skeleton, no crypto yet.**

Order matters: the pure layers come first because they are testable without a device, and they
define the interfaces the platform layers implement.

1. **Scaffold `protocol/`** — `README.md`, `schema/`, `vectors/`. Write the envelope JSON Schema and the first golden vectors: encode/decode round-trip, unknown-field tolerance, unknown-type tolerance, oversize rejection, malformed rejection.
2. **Scaffold Android** — Gradle wrapper, `settings.gradle.kts`, `gradle/libs.versions.toml` (all versions pinned), `build-logic/` convention plugins, empty `app/` that assembles. Verify `./gradlew assembleDebug` succeeds.
3. **Scaffold iOS** — `RideLink.xcodeproj` + `Packages/RideLinkCore` (SPM). Verify `swift test` and `xcodebuild build` succeed.
4. **`core:model` / `RLModel`** — the 7 entities from REQUIREMENTS §16.
5. **`core:protocol` / `RLProtocol`** — envelope codec + validation, driven by the vectors from step 1. **Both platforms must pass the same files.**
6. **`core:session-fsm` / `RLSessionFSM`** — the 10-state machine as a pure `(state, event) → (state, effects)` function, with legal *and* illegal transitions under test.
7. **`net:discovery` / `RLDiscovery`** — `NsdManager` advertise+browse; `NWListener`+`NWBrowser`. TXT records exactly as specified in ARCHITECTURE §4.
8. **`net:control` / `RLControl`** — length-prefixed framing over **plain TCP**, `TCP_NODELAY`, keepalive, reconnect with jittered backoff.
9. **`core:sync` / `RLSync`** — offset estimator with outlier rejection, against the `clock/*.json` vectors.
10. **Minimal diagnostics UI** on both — state, peer, RTT, offset, jitter, reconnect count (the FR-023 subset available now).

**Gate for 1a:** integration tests I-01, I-05, I-06, I-07, I-08, I-14 pass on the two real
phones; static analysis clean.

> Phase 1a's plaintext control channel is **debug-build only and must never be the default**.
> `NFR-06` is satisfied by Phase 1b, not 1a. Sequenced this way so discovery, framing, the FSM
> and reconnect are validated *before* the iOS certificate problem (§4 item 3) is tackled.

**Then Phase 1b — security.** Device keypair in Keystore/Keychain, self-signed certs (spike the
iOS DER encoder first, fallback TLS-PSK), TLS 1.3 on both ends, SPKI pinning, SAS derivation +
pairing UI, manual `host:port`/QR fallback.
**Gate for 1b:** I-02, I-03, I-04 pass; SAS vectors pass on both platforms; plaintext path
compiled out of release builds.

---

## 8. How to update this file

Every session, revise: current milestone/phase, completed work, tests passed, tests pending,
known problems, architecture changes, next exact task. A new session must be able to read this
file and continue **without guessing**. Record what was *verified*, not what was written.
