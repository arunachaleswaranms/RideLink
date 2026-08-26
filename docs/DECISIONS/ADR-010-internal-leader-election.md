# ADR-010 — Internal leader election by peer_id

**Status:** Accepted · 26 Aug 2026

## Context

FR-014 requires that either user can control playback **and** that conflict resolution be
deterministic. REQUIREMENTS §9.4 forbids a permanent master phone from the user's perspective but
explicitly allows "a temporary state leader/elected authority only for conflict resolution and
reconnect recovery".

Two people pressing *next* at the same moment must not skip two tracks. Two `SEEK`s must not
leave the phones at different positions. Something has to serialise.

## Decision

One peer is internally the **leader**, selected by the simplest stable rule: **the
lexicographically smaller `peer_id`**, fixed at pairing time.

- Both sides compute it independently from data they already have — no negotiation round, no election protocol, no timers. Disagreement is impossible unless something is corrupt, in which case `ERROR/leader_mismatch` surfaces it immediately.
- The leader assigns `command_seq` and `effective_at_session_us`, and owns `queue_revision` and the session clock.
- Either peer may **issue** any command. A follower sends its intent to the leader, which stamps and broadcasts it; both then apply. Determinism comes from having exactly one serialisation point, not from comparing timestamps.
- The leader is never shown in the UI and is never selectable. Both users see identical, fully capable controls.
- On reconnect the same rule re-elects the same leader — no flapping, no handover protocol. If the leader is the peer that vanished, the survivor keeps playing locally and resumes as follower when the link returns.

## Consequences

- Conflict resolution is deterministic by construction, satisfying FR-014 without distributed-consensus machinery that two devices do not need.
- A follower-issued command costs one extra half-RTT (~5–20 ms on a LAN), negligible against the ≥ 120 ms scheduling lead.
- The UI may show optimistic local feedback (button state) immediately, but must not change **audio** until the leader's broadcast returns. That split keeps the UI responsive without ever letting the two phones diverge audibly.
- Reconnect reconciliation has an unambiguous authority: the leader's `STATE_SNAPSHOT` wins outright. No merge algorithm to get subtly wrong ([PROTOCOL §10](../PROTOCOL.md#10-reconnect-and-reconciliation)).
- Because `peer_id` is fixed, the *same* phone always leads a given pair. That is fine — the property the requirements protect is that neither user is restricted, not that the internal role rotates.
- This design does not extend to three or more peers, which is correct: >2 participants is an explicit V1 non-goal.

## Alternatives considered

| Option | Rejected because |
|---|---|
| No leader; last-write-wins on timestamps | Requires perfectly synchronised clocks to be deterministic — and clock offset is itself an estimate. Concurrent commands would resolve differently on each device |
| No leader; Lamport clocks / CRDT queue | Correct but heavy. A CRDT queue cannot express "skip exactly one track" cleanly, which is the actual conflict we have |
| Elect the initiator of the connection | Ambiguous when both connect simultaneously; changes across reconnects, so `command_seq` continuity breaks. Note that *which connection survives* a simultaneous connect is a separate question, answered by [ADR-015](ADR-015-duplicate-connection-resolution.md) with a deliberately different key |
| Elect by role (rider always leads) | Creates a permanent master, which §9.4 forbids in spirit even if hidden — and makes the pillion's controls second-class if the rider's phone stalls |
| Full Raft/Paxos | Absurd for two nodes with no durability requirement |

---

## Amendment A1 — 26 August 2026 — leadership is not connection ownership

**Status of the ADR: still Accepted.** The election rule is unchanged: the lexicographically
smaller `peer_id` leads, fixed at pairing.

Added for clarity, because the two questions are easy to conflate and the original text did not
separate them explicitly:

Leadership answers *"who assigns `command_seq`?"*. It is decided by `peer_id` alone and has
nothing to do with which peer called `connect()` or which TCP connection survived a simultaneous
mutual connect. That second question — which socket lives — is answered by
[ADR-015](ADR-015-duplicate-connection-resolution.md) using an ephemeral `conn_tiebreak`, chosen
specifically so it is *not* `peer_id`.

The two rules are deliberately keyed on different values. ADR-015 also picks the direction of its
comparison so that on the surviving connection the leader is the **acceptor**, not the initiator —
which means an implementation that quietly assumes "I dialled, therefore I lead" fails the
`dedup/*.json` vectors immediately rather than working by coincidence.
