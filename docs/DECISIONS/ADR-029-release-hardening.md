# ADR-029 — Release hardening at recovery and retention boundaries

Date: 22 September 2026. Status: proposed for independent review with Phase 8.

## Context

Phase 7 is the accepted baseline. Phase 8 tests exposed three concrete defects:
End Ride was rejected while the riding screen remained visible during recovery; production
in-memory logs grew for the entire process lifetime; bounded wire queues fed unbounded
apply/scheduled task chains. A frozen-deadline test retained 300 live tasks after 300 pauses.

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
