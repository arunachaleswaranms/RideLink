# ADR-029 — Release hardening at recovery and retention boundaries

Date: 22 September 2026. Status: **accepted** — merged with Phase 8 (PR #6, `2aa728f`). Proposed
for independent review with Phase 8; the amendments below record that review.

## Context

Phase 7 is the accepted baseline. Phase 8 tests exposed four concrete defects:
End Ride was rejected while the riding screen remained visible during recovery; production
in-memory logs grew for the entire process lifetime; bounded wire queues fed unbounded
apply/scheduled task chains; an early state request arriving during iOS connection reset was
retained but never answered. A frozen-deadline test retained 300 live tasks after 300 pauses.

## Decision

1. End Ride during `RECONNECTING(returnTo=RIDE_ACTIVE)` changes only the recovery destination
   to `CONNECTED`, while the existing ride owner retires playback authority. End Ride from
   `DISCONNECTED` uses `ENDING` and its existing joined teardown. Duplicate End Ride during
   recovery to `CONNECTED` remains rejected. SwiftUI observes the entire FSM value, because
   the first transition changes `returnTo` without changing `status`.
2. The process log sink retains the latest 1,024 events in chronological order. Snapshot reads
   and writes are synchronized; no external callback occurs under the sink lock. Logs are
   diagnostics, never an authority ledger. No persistence or upload is added.
3. Apply and scheduled playback chains share a limit of 256 live nodes. Before adding a node,
   prove its original control generation. Overflow synchronously retires playback authority:
   clear role, supersede playback/request/mode tokens, cancel both chains and correction,
   discard deferred obligations and pending work, and clear authoritative identity/sequence
   claims. Report `TRANSPORT_FAILED` through the existing “Sync unavailable” presentation.
   The already-playing local audio continues at rate 1.0. A fresh authenticated connection is
   required to establish synchronization again. The refusal never waits for a parked decoder.
   A delayed predecessor retains its original tokens and cannot affect the successor.
4. Ride Mode displays existing sync state with ordinary language. Connection loss outranks
   any late synchronized-status publication. No second player or coordinator is introduced.
5. Correct PROTOCOL §2/§10's stale session-id continuity claim: both handshakes already mint
   a fresh id on reconnect. Authentication and recovery use SPKI trust, authentication
   generations and reconciliation identities, not an envelope id. STATUS problem 51 was a
   documentation mismatch; this decision changes no wire shape, codec, or handshake behavior.

6. iOS connection setup considers requests admitted both before and during its suspended reset.
   Select the newest original generation, clear the slot before replying, and prove that generation
   against the established connection. No request acquires authority from current state. Three
   gated regressions cover live admission and competing retired requests.

## Consequences and verification

Shared session-FSM vectors cover both new transitions. Mirrored lifecycle tests run 1,000
sessions with three rides and recovery variants; 100,000 log events prove bounded retention
and snapshot isolation. Coordinator tests force 1,000 commands against a frozen deadline,
then prove fresh-connection liveness. A parked non-cancellable load exercises overflow followed
by successor authority and delayed predecessor completion. Drift tests advance 2.5 virtual
hours through 1,800 real coordinator ticks, including route-transition/nudge interleavings.

The node bound is on live authoritative work, not a measurement of every runtime task or
resident memory. A non-cancellable platform callback may remain suspended after authority is
retired; correctness depends on its original lifetime proof when it returns. Tests must report
those two facts separately. No simulator result closes a physical-device gate.

---

## Amendment A1 — 22 September 2026 — independent review: the bounded-work decision, and the software gate

Status: accepted.

### Decision 3 is superseded by ADR-024 Amendment A11

Independent review confirmed that decision 3 above solved a real problem in a way that could
**abandon authority the peer had already been given**. The chain-node limit was checked where the
node is created, which on a leader is the outbound commit hook — after `send` returned true. Its
overflow path then cleared `role`, `lastAppliedSeq`, `lastReceivedSeq`, the timeline and the
ride-scoped identity for a command the follower was about to apply, and published
`TRANSPORT_FAILED` for a transport that had just succeeded.

The bound itself stays, and stays at 256. What moves is *where the question is asked*: capacity is
now **reserved before an authoritative command can be delivered** and spent by the work that
delivery obliges. [ADR-024 Amendment A11](ADR-024-synchronized-playback-integration.md#amendment-a11--22-september-2026--local-work-capacity-is-reserved-before-delivery-never-refused-after-it)
is the decision; it also introduces `SyncState.LOCAL_OVERLOAD`, because a refusal that never offered
anything to the transport must not be reported as a transport failure. Read decision 3 above as
history.

### The cross-platform software integration gate is closed

Phase 8's own evidence recorded the interactive emulator ↔ simulator journey as NOT VERIFIED and
left the software gate outstanding. Independent review was right that this is a *software* gate and
may not be moved into hardware debt.

`tools/crossplatform/run.sh` closes it by running the Swift and Kotlin implementations as **two
processes on one machine joined by a real TCP socket carrying the real RideLink protocol** —
`RideLinkPlatformTests.CrossPlatformInteropTests` and
`com.ridelink.network.interop.CrossPlatformInteropTest`, both inert unless the orchestrator supplies
the shared report directory. Neither is a vector comparison: every byte between them is produced and
consumed by production code.

It establishes, cross-language and cross-implementation:

- a real TLS 1.3 handshake with mutual authentication between an ECDSA P-256 identity issued by
  Kotlin's `IdentityIssuer` and one issued by Swift's, each pinned by `identity_spki_sha256`;
- **PROTOCOL §4.5's six digits, derived independently from each side's own TLS exporter, are
  identical** — the assertion the protocol structurally cannot make, because §4.5 has two humans
  compare them out loud, and the direct cross-platform statement of ADR-018;
- one agreed `session_id`, exactly one ADR-010 leader, and one pin persisted per side;
- ARCHITECTURE §7.1's real `PING`/`PONG` burst converging on both estimators over the real socket;
- `PLAY`, `QUEUE_SNAPSHOT`, `STATE_REQUEST`, `STATE_SNAPSHOT` and `PLAYBACK_STATE` encoded by one
  platform's production codec and decoded field-for-field by the other's;
- a link loss and reconnect that re-authenticates **silently** on the stored pin — no second
  six-digit prompt on either side — and mints a strictly greater authentication generation on both,
  with a subsequent frame accepted under the successor generation.

**What it deliberately does not establish**, and must not be read as: no UI is driven and no app is
launched, so the interactive emulator ↔ simulator journey remains an environment limitation rather
than a passing gate; and nothing here touches Bluetooth, audio, iPhone background behaviour or a
physical device. The Kotlin half also runs on the JVM against Conscrypt rather than on a device
against Android's own TLS stack — the pre-existing limitation
`docs/test-results/phase1b-security-spike-20260827.md` records, neither closed nor hidden by this
gate.

It is deliberately **not** in CI: it starts two toolchains and a real socket, and CI already runs
both suites separately. It is a re-runnable local gate, recorded with its measured result in
`docs/PHASE8_RELEASE_HARDENING.md`.
