# ADR-024 — Phase 5 synchronized-playback integration

**Status:** Accepted · 8 September 2026

## Context

[ADR-004](ADR-004-local-synchronized-playback.md) already decides the *shape* of synchronized
playback — each phone plays its own file, scheduled against a synchronised clock, corrected by a
four-tier drift ladder — and [ARCHITECTURE §7](../ARCHITECTURE.md#7-clock-synchronisation-and-synchronised-playback)
already specifies the mechanics. **This ADR does not replace either of them and does not re-open
either decision.** It records the integration-level decisions Phase 5 had to make that neither
document answers, and the five places where implementing them found `docs/PROTOCOL.md` incomplete
or self-contradictory.

Implementing §5 and §9 for real surfaced three unspecified message shapes, two genuine
contradictions, one cross-platform numeric hazard, and one missing signal. Each is resolved below
rather than settled silently in code, per CLAUDE.md's "never change protocol or architecture
silently".

## 1. What this ADR does **not** decide

- The drift ladder's tiers, thresholds, hysteresis and seek budget: ADR-004 and ARCHITECTURE §7.3.
- Leadership: [ADR-010](ADR-010-internal-leader-election.md). The lexicographically smaller
  `peer_id` leads, and nothing in Phase 5 may infer leadership from who dialled, who pressed play,
  which platform a peer is, or which phone owns the track.
- Track identity: [ADR-005](ADR-005-content-hash-track-identity.md). `content_hash` is the only
  authority. `quick_id` is not, and `LocalEntryId` never reaches the wire.
- Authentication: [ADR-019](ADR-019-connected-means-authenticated.md). `Connected` means the trust
  gate passed, and nothing before it is a session.
- Transfer: [ADR-023](ADR-023-bulk-transfer-session-binding.md). Phase 5 owns no transfer logic and
  creates no third cache.

## 2. One clock estimator, extended rather than duplicated

`ClockSync` (mirrored, vector-pinned since Phase 1a) stays the only offset estimator. Phase 5 needs
one thing it did not have — `rtt_p95`, for ARCHITECTURE §7.2's `LEAD = max(120 ms, 4 × rtt_p95)` —
so `ClockSync` gained `rttP95Us` and a bounded `RttWindow`, and `SessionClockTracker` replaced the
plain estimator-state field `ControlSessionManager` used to carry. There is **one** owner of offset,
RTT history and readiness per session, and no second RTT tracker anywhere.

`SessionClock` is the mapping (`session_us = local_mono_us + offset_to_leader_us`) and the lead
formula. Nothing else in the codebase converts between the two timebases.

**Readiness is not "we have a number."** `SessionClockEstimate.ready` is false until a window has
been *accepted or confirmed*, and false again the moment one is rejected pending confirmation
(ARCHITECTURE §7.1 rule 5's unconfirmed 30 ms step) or produces no estimate. A synchronised command
is never issued against a clock in that state; the last accepted offset stays in place for playback
already in flight, and local playback is untouched. That distinction — "we have a number" versus
"we trust it" — is the whole of the gate.

The **leader** has offset zero by definition, but still waits for its own estimator to accept a
window before issuing. It cannot observe whether the follower's burst has converged; its own first
accepted window is the best available evidence that both bursts completed on a healthy link, and the
follower's own `ready` gate is what actually protects the follower.

## 3. The follower→leader intent hop: `command_seq: 0`

PROTOCOL §5 says "a follower sends its intent to the leader; the leader stamps `command_seq` and
`effective_at_session_us`, then broadcasts to both" — but names no message for the intent, and §3's
catalogue has no `*_INTENT` type.

**Decision: an intent is the same message type with `command_seq: 0`.** `command_seq` is
leader-assigned and strictly increasing starting at **1**, so zero is free and unambiguous. This
adds no message type, changes no field, and makes the role rule checkable rather than assumed:

| arriving at | `command_seq == 0` | `command_seq >= 1` |
|---|---|---|
| the **leader** | an intent — validate, stamp, broadcast | **role violation**, dropped and counted |
| a **follower** | **role violation**, dropped and counted | authoritative — order and apply |

The second column's first row is the important one: a follower cannot fabricate an authoritative
`command_seq`, because the leader refuses to accept one at all. Both rows are pinned by
`protocol/vectors/ordering/`.

An intent carries `effective_at_session_us: 0`, which the leader ignores. A follower has no
authority to choose when something becomes audible, and expressing that as a real instant would
invite an implementation to honour it.

## 4. Two unspecified payloads: `RESUME` and `PLAYBACK_STATE`

§3's catalogue lists both; §5 gives an example for neither.

- **`RESUME`** takes `PAUSE`'s shape: the common header plus `position_ms`. Resuming from an explicit
  position is what lets both phones restart from the same instant rather than from whatever each had
  drifted to while paused.
- **`PLAYBACK_STATE`** takes the shape §10's `STATE_SNAPSHOT.playback` already defines, plus the two
  ordering values a reconciliation anchor needs:
  `{ command_seq, queue_revision, track_hash, queue_item_id, position_ms, playing, at_session_us }`.
  `track_hash` and `queue_item_id` are nullable, because "nothing is loaded" is a representable
  authoritative state.

Both are filled in, not changed: no existing field moved and no existing example was edited.

## 5. `QUEUE_SNAPSHOT` is the only way the queue reaches a follower

PROTOCOL §9 says "the snapshot always wins — there is no merge algorithm to get subtly wrong," and
also describes `QUEUE_ADD`/`QUEUE_REMOVE`/`QUEUE_MOVE` as broadcast mutations. Implementing both
means implementing a merge: a follower would have to reconcile an incremental mutation against a
revision it may not hold.

**Decision: the three mutation types are the follower→leader intent channel only.** The leader
applies, bumps `queue_revision`, and broadcasts a `QUEUE_SNAPSHOT`; a follower adopts that snapshot
wholesale, revision included, and never increments a revision itself. §9's own sentence, taken
literally. There is no CRDT, and none is needed for two peers with one serialisation point.

A mutation that changes nothing — re-adding a `queue_item_id` already present (§9's
idempotency-under-retry promise), removing an absent id, moving to the index already occupied —
**does not advance the revision**. A revision that moved without the queue moving would desynchronise
the peers for no reason.

PROTOCOL §5 rule 3's "reply `ERROR/stale_revision`; the issuer refreshes via `STATE_REQUEST`" is
implemented as the leader **re-broadcasting the authoritative snapshot** on receiving a stale intent,
rather than waiting to be asked. That is strictly better than a round trip and needs no message type
§3 does not already list.

## 6. Two contradictions in §9, corrected

**(a) The 2 000-item queue cap does not fit the frame cap.** §9 states that "V1 caps the queue at
2 000 items so this cannot happen." The arithmetic disagrees: a snapshot item encodes to roughly 190
bytes (`queue_item_id` 26 + `track_hash` 71 + `added_by` 16 + `order`, plus field names and
punctuation), so 2 000 items is ~378 KB against `MAX_CONTROL_FRAME_BYTES` = 262 144.

**The cap moves; the frame limit does not** (CLAUDE.md rule 11). `MAX_QUEUE_ITEMS` is **1 000** —
~190 KB, inside the 192 KiB budget §8.1 already uses for exactly this reason, and far beyond any
queue two people build on one motorcycle. The sender still checks, mirroring §8.1 rule 6's
"arithmetically impossible after the cap above, checked anyway".

**(b) `status` was on the wire and untrusted in the same paragraph.** §9 lists
`status ∈ ready | remote_only | transferring | unavailable` on a `QUEUE_SNAPSHOT` item and then says
it is "derived locally from presence, never trusted from the peer". A field that must never be
trusted has no reason to be sent, and sending it hands a peer a channel to influence what the local
UI claims about local storage.

**`status` is removed from the wire.** Availability comes from `core.transfer.Availability`, as it
always did. A peer that still sends it is tolerated as an unknown field (PROTOCOL §2 rule 1) and the
value is ignored; both platforms assert structurally that their encoder can never emit one.

## 7. Peer content availability, with no new message

REQUIREMENTS §9.4 and PROTOCOL §5 rule 4 both require that a track present on only one phone cannot
begin synchronised playback. The leader therefore needs to know what the peer holds.

A peer's manifest answers this for its Phase 3 **library** — but a manifest is generated from the
library alone (`ManifestGenerator`), so a track the peer *received by transfer* and holds in its
verified Phase 4 cache appears in no manifest. That is UJ-05's exact case, and it would have left the
gate permanently closed for it.

**Decision: consume the `TRANSFER_RESULT` the requester already sends.** Phase 4's provider side
received `TRANSFER_RESULT { ok, sha256 }` and ignored it; Phase 5 records it. A peer holds content if
its synced manifest advertises it **or** it reported verifying a transfer *we served it* in this
session, matched against the `content_hash` we recorded at serve time rather than a hash the peer
chose to name. Both sources are session-scoped and cleared on every boundary, exactly like the
catalogue.

Trusting the peer's claim is safe because of what it gates: whether a *synchronised* `PLAY` may be
scheduled, and nothing else. A peer that lied simply does not play; no local storage, no local state
and no security decision depends on it.

**Known limitation, recorded rather than papered over:** a track the peer imports locally *mid-session*
is invisible until the next manifest synchronisation, which V1 performs on `Connected` only. The user
sees `WAITING FOR CONTENT`; reconnecting resolves it. Continuous manifest re-advertisement is not V1
scope (ADR-013).

## 8. Every Phase 5 type is absent from the pre-authentication allowlist

`PLAY`, `PAUSE`, `RESUME`, `SEEK`, `NEXT`, `PREVIOUS`, `POSITION_REPORT`, `PLAYBACK_STATE`,
`QUEUE_ADD`, `QUEUE_REMOVE`, `QUEUE_MOVE` and `QUEUE_SNAPSHOT` are **absent** from
`PRE_AUTHENTICATION_FRAME_TYPES` / `preAuthenticationFrameTypes`, and that absence *is* their access
control — the identical construction PROTOCOL §7.1 gives `VOICE_*` and §4.4.1 gives `AUDIO_STATE`.
An unpaired peer cannot move this phone's music.

Both platforms prove it over **real TLS with a real unpaired first meeting**, sending one frame of
every one of the twelve types and asserting all twelve are refused *and counted* — so the test cannot
pass vacuously by sending nothing.

## 9. `uint64` fields are bounded at 2^53 − 1

Swift decodes JSON numbers through `Double`. A `command_seq` above 2^53 − 1 would round on iOS and
stay exact on Android — two peers silently disagreeing about an ordering value, which is precisely
the class of divergence the shared vectors exist to catch.

`command_seq`, `queue_revision`, `order` and every `*_session_us` are therefore bounded at
**9 007 199 254 740 991** at parse time on both platforms. The bound is generous rather than
restrictive: 2^53 microseconds is over 285 years of monotonic uptime.

## 10. Drift is measured against the timeline, by each device, for itself

ARCHITECTURE §7.3 describes the leader computing `drift = follower_position − expected_position`.
Phase 5 implements a refinement of that, and ARCHITECTURE §7.3 is updated in the same change:

**Each device measures its own drift against the authoritative `PlaybackTimeline` and corrects
itself.** Subtracting one phone's reported position from the other's is not a drift — the two numbers
are sampled at different session instants and separated by a network delay. Both phones tracking one
authoritative timeline converges to the same result with strictly less to go wrong, and it keeps
correcting through a moment when `POSITION_REPORT`s are not arriving.

The peer's `POSITION_REPORT` is still consumed, and still produces a number: the peer's drift against
that **same** timeline, which is the FR-023 `music_drift_ms` figure. It is diagnostics and corrective
*input*, never a command, and it can never outrank one.

## 11. Playback epochs, and why `content_hash` is not enough

A scheduled start, a late `POSITION_REPORT`, a drift correction or a rate restore must be inert once
superseded. `content_hash` cannot identify the epoch, because the same track can legitimately be
played again.

Locally, the existing `OperationFence` (ADR-023 Amendment A1) is reused as the playback epoch —
reused, not reinvented. On the **wire**, no generation field was added: an epoch is distinguished by
`at_session_us` against the timeline's anchor, which strictly increases with every accepted command.
A `POSITION_REPORT` for the right track but stamped before this epoch's anchor is from the previous
play of it, and is dropped.

`PAUSE`/`RESUME`/`SEEK` deliberately do **not** begin a new epoch: they re-anchor the same one, so a
scheduled `PAUSE` is superseded automatically by a later `PLAY` without having to know one happened.

## 12. Session binding is re-proved after every suspension

ADR-023 Amendment A3 found in Phase 4 that a session-generation check at handler entry proves nothing
about what suspends afterwards. Phase 5 applies the finding rather than repeating the bug: the
generation is captured at *dispatch*, and re-proved before every mutating step that follows a
suspension — content resolution, decoder pre-roll, the deadline wait, the correction. On iOS the
coordinator is an `actor`, where re-entrancy makes this emphatically necessary.

## 13. One player, one queue, one command path

Phase 5 owns no player and no queue. Every audible effect goes through the existing
`MusicCoordinator` — its `LocalQueue`, its one `ExoPlayer`/`AVAudioEnginePlayer`, its one ADR-022
`MediaSession` / `MPNowPlayingInfoCenter` integration.

The system media controls already funnel into `MusicCoordinator`, so a **gate** there (rather than a
second, synchronisation-aware path beside it) is what makes a lock-screen pause during a synchronised
ride become a leader-ordered `PAUSE` on both phones. Outside synchronised mode every gate method
returns false and Phase 3 behaviour is bit-for-bit unchanged.

The authoritative shared queue is **not** copied wholesale into `LocalQueue`: only the current item
becomes the local queue's one selected entry, so Now Playing metadata stays correct without two
queues that could disagree about an index. `NEXT`/`PREVIOUS` resolve from the shared queue, so
nothing consults the local one for them.

## Consequences

- Three previously unimplementable message shapes are now specified, and `docs/PROTOCOL.md` §5/§9 are updated in the same change.
- The queue cap is 1 000, not 2 000. Any future need for a larger queue is a paginated-snapshot problem, never a frame-cap problem.
- `QUEUE_SNAPSHOT` items are four fields, not five. A peer built against the older prose still interoperates: its extra field is ignored, and it will ignore nothing it needs.
- Six new shared vector sets — `session-clock/`, `ordering/`, `drift/`, `queue/`, `playback-messages/`, `queue-messages/` — each generated by an independent third transcription of the spec.
- Phase 5 depends on Phase 4 and does not modify it beyond consuming one message Phase 4 already received and discarded.
- **Nothing here has run on a phone.** The <100 ms product target and the <50 ms stretch target (REQUIREMENTS §7, ADR-008) remain unmeasured and must not be described as approached.

## Alternatives considered

| Option | Rejected because |
|---|---|
| A dedicated `*_INTENT` message family | Four new types where `command_seq: 0` needs none, and PROTOCOL §3's catalogue would have to grow for a distinction the existing ordering field already expresses |
| Broadcasting incremental queue mutations to followers | Requires the merge algorithm §9 explicitly says it does not want. The snapshot is bounded, idempotent and has one authority |
| Raising `MAX_CONTROL_FRAME_BYTES` to fit a 2 000-item queue | CLAUDE.md rule 11: the cap does not move. Payloads that grow get paginated or bounded, never accommodated |
| Keeping `status` on the wire "for convenience" | It contradicts §9's own sentence, and a peer-supplied claim about *local* storage is a claim no receiver should be able to make |
| The leader computing and commanding every correction | Doubles the wire traffic, adds a half-RTT to every correction, and stops working the moment reports are delayed — which is exactly when correction matters |
| A `playback_generation` field on the wire | `at_session_us` against the anchor already distinguishes epochs, and a new field would need a version story for something already derivable |
| Adding a "peer has content" message | The peer already sends `TRANSFER_RESULT`; a second message saying the same thing would be a second source of truth to disagree with the first |
