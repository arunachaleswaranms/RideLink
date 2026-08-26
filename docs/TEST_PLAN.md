# RideLink — Test Plan

**Status:** baseline for Phase 1. Derived from REQUIREMENTS §17.
Every phase must satisfy its gate here before `STATUS.md` records it complete.

**Governing rule from the brief:** never report a phase complete because code *looks* correct.
Where a step cannot be automated, this plan gives an exact manual procedure with a recorded
result, not a vague instruction to "check it works".

---

## 1. Layers

| Layer | Runs on | Needs hardware? | Speed | Gate |
|---|---|---|---|---|
| **L1 Unit** | JVM / `swift test` | no | seconds | every commit |
| **L2 Shared vectors** | both platforms, same fixtures | no | seconds | every commit |
| **L3 Instrumented** | emulator / simulator | no | minutes | every phase |
| **L4 Two-device integration** | real Android + real iPhone, Wi-Fi | phones only | minutes | every phase touching the wire |
| **L5 Audio hardware** | + helmet unit + TWS | full chain | manual | Phases 2, 6 |
| **L6 Ride** | on the motorcycle | full chain | manual | Phases 6, 7 |

L1–L3 are automatable and must stay green. L4 is scripted but human-triggered. L5–L6 are
manual with recorded measurements — the brief's rule that latency bugs get measurements, not
impressions.

---

## 2. L1 — Unit tests (pure logic)

Targets `core:*` / `RideLinkCore`, which contain no platform types precisely so this layer can
be exhaustive ([ARCHITECTURE §2](ARCHITECTURE.md#2-layering-identical-concepts-on-both-platforms)).

| Area | Cases |
|---|---|
| Envelope codec | round-trip all types; unknown field ignored; unknown `type` ignored; missing required field rejected; `v` mismatch rejected; 256 KiB+1 rejected; malformed UTF-8 rejected; `payload: null` rejected |
| Session FSM | every legal transition; **every illegal transition rejected without crashing**; `RECONNECTING` returns to the state it left; `BYE` suppresses reconnect; `ENDING` is the only path that releases audio |
| Clock estimator | offset recovery from synthetic samples with known truth; outlier injection (one 500 ms spike in 11); all-samples-bad ⇒ no estimate rather than a wrong one; EWMA convergence; 30 ms step rejected until confirmed twice |
| Drift ladder | boundary values 24/25/119/120/121/1999/2000/2001 ms; hysteresis (no nudge/restore oscillation); 3-seeks-in-60 s ⇒ sync-failed; rate restored to exactly 1.0 after convergence |
| Command ordering | duplicate `command_seq` dropped; stale dropped; past `effective_at` applied immediately and counted; `stale_revision` rejected |
| Queue algebra | add/remove/move; sparse `order` renumbering; concurrent adds converge; `queue_item_id` idempotent under retry; snapshot overwrites local |
| Manifest diff | presence classification for all 6 states; delta correctness; `content_hash: null` entry is displayable but not transferable |
| Hashing | `quick_id` and `content_hash` against fixed vectors; files smaller than 128 KiB (the quick_id window overlaps — must not double-count); empty file; 0-byte and 1-byte edge cases |
| Leader election | smaller `peer_id` wins; stable across reconnect; disagreement ⇒ `leader_mismatch` |
| Redaction | paths → basename; `peer_id` → 6 chars; **SAS / tokens / TLS secrets have no log path at all** (assert by searching the emitted log for a planted secret) |

**Coverage intent:** `core:*` / `RideLinkCore` ≥ 85 % line coverage. Coverage is a smoke alarm,
not a goal — the boundary cases above matter more than the number.

---

## 3. L2 — Shared protocol vectors

The seam that keeps two independent implementations honest. Same `protocol/vectors/*.json`,
two table-driven runners, identical expected output. Files listed in
[PROTOCOL §11](PROTOCOL.md#11-test-vectors).

**Gate:** every vector passes on **both** platforms. A vector passing on one platform only is a
release blocker, not a warning.

**Discipline:** every wire bug found on a device gets a vector added *before* the fix.

---

## 4. L3 — Instrumented tests (emulator / simulator)

| Area | Test | Platform |
|---|---|---|
| Database | Room migrations; FTS4 search ranking; 5 000-track insert performance | Android |
| Database | GRDB migrations; FTS5 parity — **same query, same result set as Room** | iOS |
| Library scan | synthetic MP3/AAC/M4A/FLAC fixtures in `test-media/`; tag extraction; artwork; malformed/truncated file does not crash the scan | both |
| Player | scheduled start at a future deadline; seek accuracy; rate change applied and restored | both |
| Transfer | loopback to self: 1 KiB / 5 MiB / 50 MiB; corrupt a chunk ⇒ rejected; truncate ⇒ rejected; cancel mid-transfer ⇒ `.part` removed; **no `.part` ever promoted** | both |
| Discovery | advertise + browse on loopback/emulator network; service resolves with expected TXT keys and *no* secret keys | both |
| Route handling | simulated route change and interruption callbacks fire the right state transitions | both |
| Background | Android `MediaSessionService` survives Doze simulation; iOS background-audio assertion holds | both |

---

## 5. L4 — Two-device integration (the two real phones)

Wi-Fi only, no Bluetooth audio yet. This is where most cross-platform defects will surface.

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| I-01 | Both apps open on the same Wi-Fi → observe discovery | each sees the other within 5 s | 1 |
| I-02 | Tap peer → compare the 6-digit codes on both screens | codes **identical**; both confirm; session reaches `CONNECTED` | 1 |
| I-03 | Kill and reopen both apps | reconnects silently, **no code prompt** | 1 |
| I-04 | Edit one device's stored pin to a wrong value, reconnect | refused with `pin_mismatch`, surfaced as a security warning, **no auto re-pair** | 1 |
| I-05 | Aeroplane-mode one phone 10 s, restore | `RECONNECTING` → returns to the state it left; `reconnect_count` = 1 | 1 |
| I-06 | Aeroplane-mode 3 min | `DISCONNECTED`; recovers on manual retry | 1 |
| I-07 | Repeat I-01…I-03 on an Android hotspot, then an iPhone hotspot | all three topologies work, or the failure is documented with a reason | 1 |
| I-08 | Observe clock sync for 5 min | offset stddev < 5 ms; no step > 30 ms | 1 |
| I-09 | Both phones tap *play* within ~100 ms | exactly one track plays; both agree; no double-skip | 5 |
| I-10 | Transfer a 40 MB track while issuing `PAUSE` | `PAUSE` applies within 200 ms — proves the bulk plane does not block control | 4 |
| I-11 | Transfer with a deliberately corrupted chunk | rejected; nothing promoted; clear error | 4 |
| I-12 | Play the same track on both, measure drift for 30 min | steady-state < 25 ms; **never** > 150 ms | 5 |
| I-13 | Voice session up, then break Wi-Fi | music continues (FR-025); voice recovers on reconnect | 6 |
| I-14 | Wrong protocol version (test build) | clean `version_mismatch`, no crash | 1 |

**Drift measurement method (I-12)** — needed because ear-judgement is not a measurement:
both apps log `POSITION_REPORT` pairs with session timestamps; a script in `tools/` computes
the drift series and emits min/median/p95/max. Acceptance is read off the p95. *Independent
cross-check:* record both phones' output on one stereo recorder and cross-correlate the
channels — this validates the app's own numbers rather than trusting them.

---

## 6. L5 — Audio hardware (full four-device chain)

Requires the helmet unit and the pillion TWS. Phase 0 covered the feasibility question; these
are regression checks on the built app.

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| A-01 | Music to helmet unit; music to TWS | stable, no dropouts, 10 min | 2 |
| A-02 | Two-way voice, stationary, 10 min | intelligible both ways, no runaway echo | 2 |
| A-03 | Mic active while music plays; record the observed route/profile | behaviour recorded; a usable mode identified | 6 |
| A-04 | Lock both screens; continue A-02 for 30 min | session survives; audio continues | 2 |
| A-05 | Disconnect and reconnect the helmet unit mid-session | route recovers; no crash; state consistent | 6 |
| A-06 | Inbound phone call during a session | interruption handled; session restored after the call | 6 |
| A-07 | Deny microphone permission, then start | clear message; **music still works** (FR-025) | 6 |
| A-08 | Ducking on/off with speech | smooth 150–250 ms ramp, no step or click | 6 |
| A-09 | Voice latency measurement | measured and recorded; target < 200 ms | 2 |

**Voice latency method (A-09):** play a sharp click into the rider mic; record the pillion
earbud output and the source on one recorder; measure the offset by cross-correlation. Report
the end-to-end figure *including* both Bluetooth hops, since that is what the humans hear.
Record it in `docs/test-results/` — one row per run, with app version and hardware.

---

## 7. L6 — Ride tests

Ordered by risk. Do not skip forward: REQUIREMENTS §17.1 sequences these deliberately.

| ID | Stage | Checks | Gate |
|---|---|---|---|
| R-01 | Stationary, helmets on, engine off | full pre-ride flow, 15 min conversation | Phase 6 |
| R-02 | Engine running, stationary | vibration and engine noise; mic intelligibility | Phase 6 |
| R-03 | Low speed, controlled/closed area | wind onset, route stability, reconnects | Phase 7 |
| R-04 | Normal road, 30 min | conversation quality, music sync, no manual intervention | Phase 7 |
| R-05 | Long ride, 2 h+ | battery drain both phones, thermal, drift, reconnect count | Phase 7 |

Safety: R-01/R-02 with the bike stationary. R-03 in a closed area. All phone configuration
happens **before** motion — the product rule is also the test rule (NFR-09).

Record per ride: duration, ambient conditions, reconnect count, drift p95, battery start/end,
subjective intelligibility 1–5, and every anomaly. Template in `docs/test-results/`.

---

## 8. Static analysis and hygiene

| Check | Tool | Gate |
|---|---|---|
| Kotlin style | ktlint | zero violations |
| Kotlin complexity | detekt | zero new issues |
| Android correctness | Android Lint | zero errors; warnings triaged |
| Swift style | SwiftLint | zero violations |
| Swift format | SwiftFormat | `--lint` clean |
| Swift concurrency | Swift 6 strict mode | zero warnings |
| Dependency allowlist | script in `tools/` | **no analytics/ads/telemetry/crash-reporter SDK** (NFR-05) |
| Secret scan | script in `tools/` | no keystores, `.p12`, `.mobileprovision`, personal audio |
| Log-hygiene scan | script in `tools/` | no raw-audio write path; no SAS/token log path |

The last three are privacy requirements expressed as tests, which is the only way they stay true.

---

## 9. Phase exit gates

A phase is complete only when **all** of its row passes and `STATUS.md` records the evidence.

| Phase | L1/L2 | L3 | L4 | L5 | L6 | Extra |
|---|---|---|---|---|---|---|
| 1 Peer session | all | discovery, DB | I-01…I-08, I-14 | — | — | §8 clean |
| 2 Intercom | + voice state | route sim | I-13 | A-01, A-02, A-04, A-09 | — | latency recorded |
| 3 Local music | + library, hashing | library, player | — | A-01 | — | 1 000+ real tracks indexed |
| 4 Shared library | + manifest, transfer | transfer loopback | I-10, I-11 | — | — | 50 MB transfer verified |
| 5 Sync playback | + drift, queue | player scheduling | I-09, I-12 | — | — | drift p95 recorded |
| 6 Coexistence | + audio policy | — | I-13 | A-03, A-05…A-08 | R-01, R-02 | mode chosen and recorded |
| 7 Ride Mode | all | all | all | all | R-03…R-05 | battery/thermal recorded |
| 8 Hardening | all | all | all | all | R-05 | security review, log export, sideload build |

---

## 10. V1 acceptance

REQUIREMENTS §17.2, mapped to the tests that prove each line. This is the checklist that decides
whether V1 exists.

| Acceptance item | Proven by |
|---|---|
| Trusted local session, repeatably | I-01, I-02, I-03 |
| Both Bluetooth devices work | A-01 |
| 60 min two-way voice, no restart | A-02 extended to 60 min |
| One acceptable music+intercom mode validated | A-03, R-02 |
| Import / index / search local music | L3 library + 1 000-track real run |
| Missing track transferred, hash-verified, played | I-11 + manual UJ-05 |
| Either user controls queue and playback | I-09 |
| Drift within target over 30 min | I-12 |
| Screen lock does not end the session | A-04 |
| Disconnect/reconnect restores state | I-05, I-06 |
| No backend/internet required | full run with mobile data **off** on both phones |
| No raw audio in logs | §8 log-hygiene scan + manual log review |
