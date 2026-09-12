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

---

## Amendment A1 — 8 September 2026 — closure audit: six correctness findings in the integration layer

**Status:** Accepted · appended, nothing above rewritten.

Phase 5 shipped CI-green on both platforms and had **not** been closure-audited. Phase 4 was audited
five times (ADR-023 Amendments A1–A5), each pass finding real defects in code that was already
CI-green, and each time in session lifetime, cancellation ownership or platform I/O contracts rather
than in the wire format or the pure domain layer. This pass audited Phase 5 against six
independently identified findings, confirmed **all six**, and found a **seventh** by stress-running
one of its own new regressions (**G** below).

Nothing in §§1–13 above is withdrawn. The wire format does not change: `MAX_CONTROL_FRAME_BYTES` is
untouched, no message type is added or removed, no field is added, moved or renamed, and all twelve
pre-existing vector sets regenerate byte-for-byte identically. One *handler semantic* changes, on
the reconciliation path only, and is stated explicitly in **C** below.

The common shape of all six is the one §12 already names and this pass found under-applied: **a
decision made under a guard, and a consequence that escaped it** — by a suspension, by a lock
released too early, by a queue that dropped what it had accepted, or by a sequence number spent
before the work it authorised was done.

### A. A follower's first Play lost a race with its own queue add

`playSynchronized` sent `QUEUE_ADD` as an intent and then, without waiting, issued `PLAY` **carrying
the revision it still held**. The leader accepted the add (revision → *n+1*) and then refused the
`PLAY` under its own §5 rule 3 stale-revision check. **The user's first press did nothing**, and a
second press worked only because the snapshot had arrived by then. Both platforms.

The revision rule does not move (weakening it would be the wrong fix, and accepting a command against
a revision the leader no longer owns is precisely what rule 3 exists to prevent). Instead **one press
is one retained request**: `PendingPlay`, fenced by an `OperationFence` token, held until the
authoritative `QUEUE_SNAPSHOT` names its `queue_item_id`, then issued carrying the authoritative
revision. The issuer-minted `queue_item_id` survives the wait, so §9's idempotency-under-retry
promise is unaffected.

### B. The leader's semantic order was not the wire-visible order

The leader locked its `command_seq` allocation and its `queue_revision` bump — and **released the
lock before sending**. Two coroutines then raced for the socket, so a `PLAY` stamped for revision *n*
could reach the wire ahead of the `QUEUE_SNAPSHOT` that created revision *n*, and the peer would
refuse a valid command for a revision it had not been told about. The inverse race existed too.
`ControlSocket`'s write lock does not help: it serialises **bytes**, not RideLink semantic order.

On iOS the same defect wore actor clothing. `await session.channel.send(…)` sat between the step that
stamped a frame and the frame reaching the transport — an **actor re-entrancy point**. Actor
isolation alone did not make the pair atomic, which is worth recording because it is the kind of
thing that reads as safe.

**There is now one outbound serialisation owner.** Allocation and hand-off happen in the same
critical section — under `commandMutex` on Android, with **no `await` between them** on iOS — and one
consumer drains the ordered queue onto the transport. Enqueue order is wire order.

The invariant is asserted the strongest way available: **the leader's own outbound stream is replayed
through the follower's stale-revision rule.** If the order the leader chose is valid, nothing in that
replay is rejected — which is the receiving side's actual check rather than a statement about
internals. Both platforms, plus a two-peer run (in-process on Android, real TLS on iOS) asserting the
peer's `stale_revision` counter stays at zero.

### C. The post-TCP handoff could silently lose an authoritative command

The inbound handoff was `Channel(256, onBufferOverflow = DROP_OLDEST)` on Android and
`AsyncStream(bufferingPolicy: .bufferingNewest(256))` on iOS — **a lossy queue immediately behind
reliable, ordered, authenticated TCP.** A `PLAY` could be evicted while the `PAUSE` behind it
survived; `CommandOrderGate` would legitimately accept the `PAUSE`, and the follower would pause a
track it never loaded.

Worse, the loss was invisible. `trySend` on a `DROP_OLDEST` channel returns **success**, so Android's
`droppedInboundCount` diagnostic was structurally incapable of ever counting an eviction. iOS's
`Continuation.yield` *does* report `.dropped`, but the forwarder discarded the result — and reporting
a silent loss is not the same as not losing it.

**Nothing is evicted now.** `Phase5FrameQueue` is bounded, lossless and order-preserving, used in both
directions, and its admission decision is the pure `Phase5Ingress` table:

- **room ⇒ admit**, arrival order preserved;
- **full, and the frame is one whose newest instance subsumes its older ones** —
  `POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT` — **with an older sibling queued ⇒ coalesce**:
  the sibling is replaced by this frame at *this* frame's arrival position. Lossless, because applying
  only the newest of such a run reaches the same state as applying all of them in order (that is
  §5's own "`PLAYBACK_STATE` … the reconciliation anchor, not an incremental update" and §9's "the
  snapshot always wins", taken literally). Two different families never supersede each other;
- **otherwise ⇒ overflow**, *returned to the caller and counted*.

Coalescing is what keeps overflow from firing under a peer's ordinary 5 s report cadence: after it,
reaching the bound requires a flood of frames that cannot be superseded, which is a pathological peer
rather than a busy link.

**An overflow is an explicit synchronisation failure, not a drop.** On a follower it latches
`ingressDesynchronized` and **no further incremental command is applied** — critically, without
spending its `command_seq`, so the reconciliation that follows decides where ordering resumes. The
`PAUSE` behind a refused frame is refused too: a `PAUSE` treated as coherent state without its `PLAY`
is the exact incoherence this finding is about. `SyncState.DESYNCHRONIZED` surfaces it, and local
music keeps playing throughout (ADR-004, FR-025).

The refusal is observed by the **consumer**, at the top of its next iteration, reading the queue's own
counters. That direction is deliberate: `offer` runs on the control read loop, which cannot suspend
into a coordinator and must not do work, and observing at the top of the iteration puts the halt
*before* the next frame is dispatched rather than one frame late.

**On the leader an overflow does not halt.** The only incremental frames a leader accepts are
*intents*, which it stamps rather than applies, so its authoritative state cannot have become
incoherent — what was lost is a button press. It re-broadcasts authoritative state (which is also
what unsticks a follower whose revision has drifted) and continues.

**The one handler-semantic change this amendment makes.** §5 says `PLAYBACK_STATE` "deliberately
starts nothing, because a change of track is a `PLAY`". That stays true in normal operation.
**While a receiver is desynchronised, a `PLAYBACK_STATE` restores playback** — it loads and schedules
the track it names at the position and instant it carries. Without this, reconciliation would leave a
follower coherent about *ordering* and wrong about *what is playing*, which is not reconciliation. No
wire field changed: the snapshot already carries every value needed, and because the instant it names
is in the past, §5 rule 2's "apply immediately and record the lateness" is what happens — **no expired
deadline is ever reused as though it were still ahead.** `docs/PROTOCOL.md` §5 is updated in this same
change.

**Recorded rather than papered over:** recovery is *awaited*, not *requested*. `STATE_REQUEST` is in
§3's catalogue and is still unimplemented; a desynchronised follower recovers on the leader's next
authoritative snapshot or at the next session boundary, and there is no bound on how long that takes.
A test pins the corollary honestly: while the bound is reached, even a reconciliation frame can be
refused for want of room, so recovery needs a moment where the queue has space. At the production
bound of 256 with coalescing, any non-pathological link provides it. Bounding the latency properly is
reconnect work (§10) and is deliberately not done here.

### D. An accepted command was recorded as applied before the clock was consulted

`onInboundCommand` set `lastAppliedSeq = header.command_seq` and **then** called `readyEstimate()`.
An estimator momentarily untrusted — §2's unconfirmed 30 ms step, which resolves itself in
milliseconds — therefore spent the sequence number and applied nothing. The leader's replay of that
same command was then correctly dropped as a **duplicate**. The command was lost *permanently*, by
the ordering layer doing exactly its job on a receiver that had lied about what it had done.

**"Accepted for ordering" and "applied" are now different facts in different fields.**
`lastReceivedSeq` is what `CommandOrderGate` reads and advances on acceptance; `lastAppliedSeq` moves
only when the command takes effect, and is the only one `PLAYBACK_STATE` reports. `CommandOrderGate`
itself is unchanged and its vectors are untouched — only which field the call site passes changed.

A command accepted while the clock is untrusted is **held**, in authoritative order, in a bounded
buffer, and applied when the estimator recovers — re-checked on a 100 ms cadence so a held `PLAY`
becomes audible promptly rather than at the next 5 s tick. `PendingCommandGate` also answers `DEFER`
when the clock *is* ready but something is already held: `PLAY(n)` then `PAUSE(n+1)` must not become
`PAUSE` alone because the estimator happened to converge between the two. A held command whose
`command_seq` a later `PLAYBACK_STATE` accounts for is superseded by it. Overflowing the held buffer
is the same explicit halt as **C**, never a silent drop. Offset 0 is never substituted for an unready
estimate, and nothing is ever scheduled against one.

### E. One press of Play did not survive a Phase 4 transfer

`gateContent` requested the transfer and returned false; the user action then simply ended. When the
transfer verified, **nothing was retained to reschedule** — the user had to press Play again. That is
not REQUIREMENTS §9.4's first-play flow, and UJ-05 is exactly this case.

The same `PendingPlay` that closes **A** closes this: the request is held and re-evaluated whenever a
precondition changes, including on Phase 4's **own** verified-availability notification. That seam is
a notification, not a poll and not a third source of truth — `SharedLibraryCoordinator` already owned
both facts (the verified cache, which changes only after `TransferCacheRepository.commit` succeeds,
and `peerVerifiedHashes`, written only on a `TRANSFER_RESULT { ok: true }` for a hash we ourselves
served, §7). Phase 4 is asked for the transfer **once per retained request**, and a new authoritative
`PLAY` with a **fresh** `effective_at_session_us` is what eventually plays.

The peer half of §19's gate is the **leader's** question and deliberately not a follower's:
§5 rule 4 makes requesting the transfer the leader's job, and the leader cannot do that job without
receiving the intent, so a follower that gated on the peer half would withhold the one message that
unblocks it. `PendingPlayGate` takes that as an explicit input rather than inferring it.

Supersession is fenced by **token**, never by `content_hash`: the same track can legitimately be asked
for again in a later epoch, so a hash would let a superseded request resurrect when a transfer it no
longer owns completes. Requesting X supersedes a pending H; a session boundary cancels; leaving
synchronised mode cancels; and a failed transfer starts nothing and keeps saying `WAITING FOR
CONTENT` rather than pretending.

### F. A superseded correction still had four side effects

On iOS `runIfCurrent` returned `Void`, and `applyCorrection` mutated `diagnostics.lastCorrection`,
incremented `hardSeekCount`, set `.syncFailed` and awaited `emitPlaybackState()` **after** it —
unconditionally. A correction the guard had just refused therefore still spent the seek budget,
claimed to have corrected something, could latch a sync failure, and **emitted authoritative state
onto the wire**. Android put its mutations inside the guarded block, so it was safer — but not
correct: the guard was proved *before* the player call, and the player call suspends.

`runIfCurrent` now **returns whether it ran**, every caller branches on the answer, and ownership is
re-proved *after* the player's own suspension and before anything externally visible. A correction
belonging to a superseded epoch or a dead session has **zero** effects — not merely no player action.
`emitPlaybackState` re-proves ownership after its own two reads and immediately before the enqueue.

### G. The apply path did not preserve authoritative order either — found by this audit's own stress run

Not one of the six. **Stress-running the Finding C regression above found it**: 2 failures in 100 on
iOS, reproduced twice with the same shape, in a test whose whole assertion is "arrival order
survived".

Every accepted command's audible effect was armed as its own coroutine/`Task`. That preserves only
the order in which they *start*: each action then suspends inside the player, and the next one runs
inside that suspension. `PAUSE(n)` and `RESUME(n+1)` — a pair the leader stamps *microseconds* apart,
so both deadlines have already passed by the time they arrive — could therefore take effect in
**either** order. `command_seq` had done its job perfectly all the way to the last step and then the
last step threw the order away.

It is the *same* defect the inbound handoff already had (`docs/STATUS.md` §2aa: "a coroutine per frame
preserves only the order in which coroutines are *started*"), on the other end of the same pipe —
which is the useful lesson: that fix was applied where the bug had been observed rather than
everywhere the reasoning held. iOS failed visibly because unstructured tasks have no ordering
guarantee at all; Android's single-threaded dispatcher hid it, because the fakes in its tests do not
actually suspend where a real `MusicCoordinator` does.

**Each armed action now joins the previous one before doing anything.** Authoritative deadlines
increase with `command_seq`, so waiting for the previous action costs nothing and the chain's order
*is* the authoritative order; a superseded action fails its ownership proof and returns at once, so it
never holds the chain up; and a session boundary starts a fresh chain, because ordering across a
boundary is meaningless and every link still in flight is already inert. Both platforms.

The regression is deterministic on both, driven by a gate that parks the `PAUSE` strictly inside the
player while the `RESUME` is delivered and fully processed — and it was **verified to fail against the
pre-fix code** on Android as well as observed failing on iOS.

### What this amendment adds, and what it deliberately does not

**Adds:** one ordered scheduled-action chain per platform (**G**); three pure, mirrored, vector-pinned tables (`Phase5Ingress`, `PendingCommandGate`,
`PendingPlayGate`) in `core.playback.Phase5Gates` / `RideLinkCore.Playback.Phase5Gates`, pinned by the
new `protocol/vectors/phase5-gates/` (228 rows, full cross products, generated by an independent third
transcription); one mirrored bounded queue (`Phase5FrameQueue`) used for both directions; two
`SyncState` values (`WAITING_FOR_QUEUE`, `DESYNCHRONIZED`); nine diagnostics fields, every one a
measurement or a count of something refused; and one narrow read-only availability notification on the
Phase 4 coordinator.

That the three tables are *tables* is the point, and CLAUDE.md rule 18's direct application: all three
rules were coordinator control flow before this pass, which is why no vector could pin them and why
the two platforms had already drifted on the details.

**Removes:** `OrderedEventChannel.init(bufferingNewest:)`. Phase 5's inbound pipe was its only
caller, so it became dead code — and it is the exact footgun **C** is about, left loaded for the next
person to reach for. Its doc comment is replaced by a note recording why there is no bounded,
drop-oldest initialiser on that type. No Phase 2a/2b behaviour changes: `VoiceController`'s route
channel uses the unbounded `init()`, as it always did.

**Does not:** change the wire format, add a message type, implement `STATE_REQUEST`, raise
`MAX_CONTROL_FRAME_BYTES`, alter the drift ladder, its hysteresis or its 3-seeks-per-60 s budget,
alter `LEAD = max(120 ms, 4 × rtt_p95)`, add a second player, queue, `MediaSession` or RTT tracker,
touch Phase 6 or Phase 7, or make any claim about audio.

**And still does not run on a phone.** Every figure this amendment adds is a software figure. The
<100 ms product target and the <50 ms stretch target remain unmeasured; TEST_PLAN §5.2's S-01…S-12
are what will change that.

## Amendment A2 — 9 September 2026 — delivery audit: five correctness findings on the outbound join

**Status:** Accepted · appended, nothing above rewritten. Amendment A1 is unchanged.

Amendment A1 closed six findings and its own stress run found a seventh. Independent verification of
A1 then found **five more**, all on one seam A1 had built but not finished: the join between
*deciding* something authoritative and *the peer actually receiving it*. This pass confirmed all
five and, while fixing them, found a sixth defect of its own (**F** below) that the fix for **D**
would otherwise have introduced.

**The wire format does not change.** `MAX_CONTROL_FRAME_BYTES` is untouched, no message type is
added, removed or activated, no field is added, moved or renamed, and every pre-existing vector set
regenerates byte-for-byte identically. What changes is one **internal** signature —
`PlaybackRelay.send` now takes the authorising generation — and the timing of local commits.

The common shape of all five is one sentence: **A1 made the leader's order the wire order, and then
treated "handed to the outbound queue" as if it were "the peer has it".**

### A. Admission to the outbound queue was treated as delivery

`enqueueOutbound` returned `Unit`. A full queue incremented `outboundOverflowCount` and returned, and
every caller carried straight on — `issue` consumed the `command_seq`, recorded it as applied and
scheduled the audible effect; `applyLeaderMutation` bumped `queue_revision` and published the queue.
**The leader played a command, and sat on a revision, that the follower had no way of ever
receiving.** Silent divergence, reported as a counter nobody read. Both platforms.

`enqueueOutbound` now **answers whether the frame was accepted**, and every caller branches on it:

- **`command_seq` is consumed only on admission** (§6 of the audit brief's preferred invariant). A
  refused candidate leaves no gap, because it was never assigned;
- **`queue_revision` becomes authoritative only on admission.** The candidate `SharedQueueState` is
  computed first and published only once the snapshot carrying it is on the ordered path;
- a refused **authoritative** frame fails the session closed (see **G**); a refused **intent** or
  **advisory** frame is counted and nothing more, because neither ever owned authority to roll back.

### B. An outbound frame was not bound to the session that authorised it

The outbound queue deliberately outlives sessions — that is A1's design, and it is right. But the
envelope was the bare message, and `PlaybackRelay.send` resolved the authenticated writer **and the
`session_id`** at send time. A frame stamped under Session A that was still queued when Session B
activated was therefore written under **Session B's identity**. That is exactly the session-confusion
class ADR-023 Amendments A3/A5 hardened Phase 4 against, on the outbound end of the same pipe.

Two independent guards now close it, and both are needed:

1. **The envelope carries its authorising generation.** The single outbound consumer refuses to write
   a frame whose generation is not the live one, counting it as `outboundStaleCount`. Frames from a
   dead session may stay physically queued; they are inert.
2. **The relay takes the generation as an argument.** `PlaybackRelay.send(message,
   authorizingGeneration:)` checks it, resolves the writer and the `session_id`, checks it again, and
   only then writes — and the writer it resolved closes over *that* session's socket, so even a
   boundary landing inside the write fails rather than landing on the new session. The coordinator
   guard alone narrows the window; only the relay closes it, which is why the audit's own first fix
   was insufficient and its own test said so.

`PlaybackRelay` is the only relay that takes this argument, because Phase 5 is the only family whose
outbound frames outlive the step that created them.

### C. The transport's answer was discarded and counted as success

The drain did `send(frame)` and then `outboundSentCount += 1`, the `Bool` thrown away — on iOS
silently, because `SyncPlaybackChannel.send` is `@discardableResult`. `send` answers false when there
is no authenticated writer or the write throws, so **`outboundSentCount == outboundEnqueuedCount`
could be reported while frames had been discarded**, and the local command had already been
committed.

The result is now consumed and split into three counters that partition every attempt:
`outboundSentCount` (the write returned **true**, and nothing weaker), `outboundFailedCount` and
`outboundStaleCount`, summing to `outboundAttemptCount`. All three are incremented *after* the
attempt completes, never before it starts, because that pair is also what a test reads to know the
wire has caught up.

### D. A deferred command could later execute against a newer queue revision

A1 held an authoritative command whose clock was untrustworthy — correctly, because losing it was
Finding D of A1. It held **nothing else**. A `QUEUE_SNAPSHOT` arriving behind a held `NEXT` was
therefore applied *immediately*, and when the clock recovered the `NEXT` resolved against a revision
it was never authored against. `NEXT @ rev 5` on `[A, B, C]` means B; run against `[A, C]` it means
C. The two phones select different tracks, and `command_seq` and `queue_revision` — which exist for
precisely this — were both satisfied.

Re-checking the revision at drain time and dropping the command would only convert the reordering
into a **lost authoritative operation**, which is A1 Finding A again. The rule is instead **nothing
may overtake held authoritative work**:

- the held buffer holds an **authoritative event stream**, not a command list: playback commands,
  `QUEUE_SNAPSHOT` and `PLAYBACK_STATE`, in arrival order;
- `POSITION_REPORT` is deliberately **never** held. It produces one diagnostics number and can change
  no command's meaning, so holding it would buy nothing and cost the bound;
- when the clock recovers the whole stream replays **in original arrival order**, so the receiver
  reproduces exactly the sequence the leader produced;
- the bound is the same one, and overflowing it is the same explicit halt-and-reconcile as A1's — an
  older accepted event is never evicted and later events are never applied incoherently;
- a session boundary clears the whole stream, so nothing held under Session A can execute under B.

`AuthoritativeHoldGate` is the pure, mirrored, vector-pinned table for it.

### E. A correction-triggered snapshot lost its causal session and epoch

A1 Finding F made a superseded correction have *zero* side effects — almost. `emitPlaybackState()`
read `session.currentAuthGeneration` **inside itself**, after the two reads that suspend. A correction
that had legitimately proved `owns(generation A, epoch A)` therefore handed the enqueue whatever
generation happened to be live by then: **a snapshot caused by a correction in Session A could be
enqueued into Session B.** On iOS that window is wide — every `await` on an actor is a re-entrancy
point — and the regression reproduces it by parking the emit's own `playerState()` read and landing
the boundary inside it. On Android the window is between two statements a single-threaded test
dispatcher cannot interleave, so the mirrored test asserts the contract rather than reproducing the
race; the defect is real there on the multi-threaded dispatcher the app actually uses.

There are now **two** functions, because there are genuinely two use cases and one signature cannot
serve both honestly:

- `emitCurrentPlaybackState()` — the leader re-stating its current state *now* (the reconciliation
  re-broadcast). Reading the live generation is correct here, because "now" is what the call means;
- `emitPlaybackStateIfOwned(generation:token:)` — a snapshot that exists *because of* an earlier
  operation. It carries that operation's generation and epoch all the way to the enqueue, re-proves
  both immediately before it, and emits nothing if either has moved.

### F. Found while fixing D: the revision rule was checked against the wrong state

Holding `QUEUE_SNAPSHOT` behind a held command immediately broke a valid stream. The leader sends
`SEEK @ rev 5`, `QUEUE_SNAPSHOT rev 6`, `PAUSE @ rev 6`; the receiver holds the first two, and then
refused the `PAUSE` under PROTOCOL §5 rule 3 — because the revision *applied* was still 5, while the
snapshot that would make it 6 was sitting in front of it. A perfectly ordered command, refused for a
revision it was about to be given.

The rule did not move; **where it is evaluated** did. §5 rule 3 is checked against the state the
command will actually be applied to: immediately when nothing is held, and at replay time when
something is. A mismatch at replay cannot happen in a stream the leader actually produced, so it is
treated as evidence that a frame between them is missing, and takes the same explicit
halt-and-reconcile posture as every other "we can no longer account for the authority we hold".

### G. What happens when delivery fails: the fail-closed posture

`OutboundCommitGate` is the pure, mirrored, vector-pinned table: **`COMMIT` for exactly the `SENT`
outcome and no other**, `ABORT_FAIL_CLOSED` for any other outcome of an `AUTHORITATIVE` frame, and
`ABORT_QUIET` for an `INTENT` or `ADVISORY` frame that never owned authority.

Failing closed means **this device stops being authoritative for this authentication generation**:
no further command is issued, no further queue mutation is accepted or served, no further
`PLAYBACK_STATE` is emitted, and `SyncState.TRANSPORT_FAILED` says so. It deliberately does **not**
stop the music, and deliberately does **not** supersede the playback epoch:

- synchronised mode is *left*, so `MusicCoordinator`'s transport controls answer locally again and
  the user keeps control of their own music, exactly as a Wi-Fi drop leaves them (ADR-004, FR-025);
- correction stops and the rate returns to exactly 1.0;
- **a frame the transport did accept still commits and still takes effect.** Refusing to apply a
  command the peer already has would manufacture the mirror image of the divergence this amendment
  closes. Only work that was never delivered is abandoned.

Recovery is a **new session**, which clears the latch. It is not a retry, and it is not a
reconciliation: there is no protocol message that tells a peer about a command it never received, and
a peer that never received one does not know to ask for it.

### Authoritative commit timing, stated once

For a leader's authoritative operation there are three moments, and this is what happens at each:

| | candidate created | admitted to the ordered outbox | authenticated send returned true |
|---|---|---|---|
| `command_seq` | allocated as a candidate | **consumed** | — |
| `queue_revision` | candidate state computed | **committed and published** | pending Play re-evaluated |
| `lastReceivedSeq` / `lastAppliedSeq` | — | — | **committed** |
| local audible effect | — | — | **scheduled**, on the ordered apply chain |

`command_seq` and `queue_revision` commit at **admission** because they must be allocated before the
frame that carries them can be built, and a second operation must build on the first; the audit
brief's §7 blesses exactly this. Everything with a *local effect* commits at **send success**. A send
failure after admission is the fail-closed case in **G**, and is why the admission-time commits are
safe: nothing further is issued under that generation.

The commit hook runs on the single outbound consumer, so commits happen strictly in send order, which
is `command_seq` order. The apply is launched onto an ordered chain rather than run on that consumer,
so a decoder pre-roll cannot stall the wire while still preserving A1 Finding G's ordering.

### What this amendment adds, and what it deliberately does not

**Adds:** two pure, mirrored, vector-pinned tables (`OutboundCommitGate`, `AuthoritativeHoldGate`) in
`core.playback.Phase5Gates` / `RideLinkCore.Playback.Phase5Gates`, pinned by 36 new rows in the
existing `protocol/vectors/phase5-gates/`; an outbound envelope carrying its authorising generation
and its commit hook; one `SyncState` value (`TRANSPORT_FAILED`); five diagnostics fields
(`outboundAttemptCount`, `outboundFailedCount`, `outboundStaleCount`, `outboundAuthorityLost`, and
the widened meaning of `deferredCommandCount`); one bound (`DEFAULT_OUTBOUND_CAPACITY`, unchanged at
256, now named and injectable); and one internal parameter on `PlaybackRelay.send`.

**Does not:** change the wire format, add a message type, **implement or activate `STATE_REQUEST`**
(it remains catalogued in PROTOCOL §3 and unimplemented — none of these five findings needed it, and
it would not help the one case it looks like it might, because a peer that never received a command
does not know to request state), raise `MAX_CONTROL_FRAME_BYTES`, alter the drift ladder, its
hysteresis or its 3-seeks-per-60 s budget, alter `LEAD = max(120 ms, 4 × rtt_p95)`, add a second
player, queue, `MediaSession` or RTT tracker, touch Phase 6 or Phase 7, or make any claim about audio.

**And still does not run on a phone.** Every figure this amendment adds is a software figure. The
<100 ms product target and the <50 ms stretch target remain unmeasured; TEST_PLAN §5.2's S-01…S-12
are what will change that.

## Amendment A3 — 10 September 2026 — lifecycle audit: the apply and scheduled chains outlived their session

**Status:** Accepted · appended, nothing above rewritten. Amendments A1 and A2 are unchanged.

A1 closed six findings and its own stress run found a seventh. A2, verifying A1, found five more and
found a sixth while fixing them. Independent verification of A2 then named **one narrow but critical
remaining class**, and this pass confirmed it in full:

> **Old Session-A local apply/schedule work could survive a session boundary and touch Session-B
> state.**

Three findings, all confirmed against the code as A2 left it, all on both platforms.

**The wire format does not change.** No message type is added, removed or activated, no field is
added, moved or renamed, `MAX_CONTROL_FRAME_BYTES` is untouched, and all thirteen vector generators
reproduce every existing vector byte-for-byte. What changes is *coroutine and `Task` lifetime* and
*where an ownership proof sits* — neither of which is a distributed decision, so A3 adds **no new
vector table**. A1's and A2's five gate tables (`Phase5Ingress`, `PendingCommandGate`,
`PendingPlayGate`, `OutboundCommitGate`, `AuthoritativeHoldGate`) are unchanged and still pinned by
`protocol/vectors/phase5-gates/`.

The common shape of all three is one sentence: **A2 made the local commit wait for delivery, and
then let the waiting work outlive the session that authorised it.**

### A. The apply chain was detached at a session boundary, not retired

A2 moved a leader's own authoritative apply to *after* the transport confirmed the frame went out,
onto an ordered chain so a decoder pre-roll could not stall the wire (A2 §"Authoritative commit
timing"). `resetForNewSession` then retired that chain with `applyChain = null` / `= nil`.

**That detaches the tail reference. It cancels nothing and fences nothing.** The nodes already
created went on existing, and the reference stored was the *newest* node — while the one that
matters is the *oldest*, the one actually blocked. The reproducible interleaving:

| | |
|---|---|
| Session A | `PLAY(seq n)` is written to the wire; A2 commits its `command_seq`; its local apply parks inside `player.prepare` |
| Session A | `NEXT(seq n+1)` is written to the wire and committed; its local apply waits behind the parked `PLAY` |
| — | the link drops; the generation moves; **Session B authenticates** and establishes its own queue, timeline and playback epoch |
| Session A | the pre-roll returns. `PLAY`'s own continuation is refused (`owns` after the pre-roll — A1 Finding F). **`NEXT` then wakes and runs `applyStep` against Session B's queue** |

`PLAY`'s continuation was already safe. The command *behind* it was not, because nothing between
"the node ahead finished" and "mutate the queue" ever asked which session had authorised it.

**The fix has two layers, and only the second is the correctness boundary.**

1. **Retirement.** Every apply-chain and scheduled-chain node is now created as a child of one
   session-owned handle — a `SupervisorJob` parented to the coordinator's scope on Android, an
   explicit live-node registry on iOS, where an unstructured `Task` has no parent to cancel. A
   boundary cancels all of them, oldest included, and installs a fresh handle. Both tails are
   cleared in the same call, which is what makes **§G** below true by construction.
2. **Fencing.** Each node captures the generation that authorised it and re-proves it *after*
   waiting for the node ahead, before invoking its action.

**Cancellation alone would not be enough, and must not be presented as the fix.** `ExoPlayer.prepare`
runs on the application looper; every real `AVAudioEngine` and `AVAudioFile` callback is bridged
through `withCheckedContinuation`. Neither observes cancellation, so a cancelled node still returns
from such a call and carries straight on to its next statement. The generation is what stops it
there. The regressions therefore block on a deliberately **non-cancellable** seam, so what they pin
is the fence and not the cancellation.

### B. Three apply paths mutated live state before proving anything

A2 §12 already said the generation is "re-proved before every mutating step that follows a
suspension". Three of the five apply paths did not do it at all.

| path | what it did before any proof | consequence |
|---|---|---|
| `applyTransport` (`PAUSE`/`RESUME`) | read `currentEpochToken`, re-anchored `timeline`, armed a scheduled action | re-anchored the **new** session's timeline to an instant its own dead leader chose. The scheduled action was correctly refused, but every drift measurement afterwards was taken against a timeline no leader had authorised — measured at −598 s of fabricated drift in the iOS regression |
| `applySeek` | the same | the same |
| `applyStep` (`NEXT`/`PREVIOUS`) | `SharedQueue.step` against the live queue, wrote the selection, published it, and on the `selected == null` branch called `playbackFence.begin()` / `epoch.begin()`, cleared `timeline` and armed a stop | stepped the **new** session's queue; and **retired the new session's playback epoch**, so Session B's own already-armed scheduled start failed its ownership proof and never fired. The old session did not merely write state it did not own — it silently disabled the new session's audio |

`applyPlay` was already correct on this point (proof after the resolve, `owns` after the pre-roll)
and is preserved; it gains an entry proof only because it is reachable from three callers.

Every apply path now proves its authorising generation **before its first read of live state**, and
does so for itself rather than trusting its caller — `applyAuthoritative`'s entry proof is the cheap
common case, never the guarantee. On iOS this made `applyTransport` and `applySeek` `async`, which is
the honest cost of the proof: `stillCurrent` reads another actor. There is deliberately **no `await`
between the proof and the writes** in either, so the proof still holds when they happen.

Two paths outside the brief's list were found to be in the same class and fixed with it:
`applyPeerPlaybackState`, whose writes follow a lock acquisition, and `restoreFromPlaybackState`,
whose nil-track branch supersedes the epoch and clears the timeline.

**Why the epoch is not required here.** `applyTransport` and `applySeek` read the epoch token *live*,
because `PAUSE` legitimately attaches to whatever epoch is current. The session generation is the
right fence for them; adding an epoch check would break correct within-session behaviour. The epoch
is proved where it is owned — in `applyPlay`, and in every scheduled action.

### C. A retired scheduled action wrote the live session's diagnostics

`scheduleAt`'s node measured its own scheduling error immediately after its sleep and **before**
`runIfCurrent`. The player action was correctly refused — but a Session-A deadline arriving after
Session B was live still overwrote, and on iOS published, Session B's `lastScheduleErrorUs`: the
FR-023 figure a rider reads as "this is how well the last synchronised command landed".

`resetForNewSession` likewise only detached `scheduledChain`, so the node was there to fire at all.

The ownership proof now comes **before** the measurement, and again before the sleep, so a node whose
epoch or session is already gone returns without sleeping. **"Diagnostics only" is not an
exemption** — A1 Finding F's "a superseded correction has *zero* effects" is the standard, and this
was one write short of it.

### D. What is now checked before any live-state mutation

Stated once, for every asynchronous Phase 5 operation:

| | proved before the first mutation | re-proved after |
|---|---|---|
| apply-chain node | cancellation, then `stillCurrent(generation)` | — (the wait for the node ahead *is* the suspension) |
| `applyPlay` | `stillCurrent` | the content resolve, then `owns(generation, token)` after the pre-roll |
| `applyTransport` / `applySeek` | `stillCurrent` | no suspension follows before the writes |
| `applyStep` | `stillCurrent` | delegates to `applyPlay`, which proves again |
| scheduled node | cancellation, then `owns(generation, token)` | the deadline sleep — before the measurement, and again before the action |
| correction | `owns` (A1 Finding F, A2 Finding E) | unchanged |

### E. Sent, but not yet locally applied, at a link loss

**An authoritative frame that reached the peer under Session A and whose local effect has not yet
happened when the session dies is abandoned locally.** It is not replayed into Session B.

This is a deliberate choice and it is the *opposite* of A2 §G's rule for a frame the transport
already accepted, so the distinction is worth being exact about. A2 says: a frame the transport
accepted must still commit and still take effect, because *the peer has it*, and refusing to apply it
here would manufacture divergence. That reasoning holds **within** the session — the peer is there,
acting on it, and this device agreeing is the whole point.

Across a boundary it stops holding. Phase 5 coordination has ended: there is no peer left to agree
with, the timeline the command was authored against is gone, and Session B's state was established
independently. Applying an old Session-A effect into Session-B state is strictly worse than dropping
it. So local playback simply continues from whatever state exists at the boundary — Phase 3's, and
ADR-004's "a Wi-Fi drop does not interrupt music" — and recovery is fresh-session synchronisation, not
replay. **`STATE_REQUEST` is still not implemented and is still not needed** (A2 §H).

### F. Schedule diagnostics are written only by still-owned work

The general rule the three findings share, stated as a rule rather than as three fixes: **a Phase 5
operation whose authorising generation is gone writes nothing at all** — not the queue, not the
timeline, not the epoch, not the player, not the wire, and not a diagnostics counter.

Which Phase 5 diagnostics are session-lifetime and which are process-lifetime is unchanged by this
amendment and is worth having written down once. `resetForNewSession` clears the session-lifetime
ones (`syncState`, `lastAppliedCommandSeq`, `lastReceivedCommandSeq`, `nextCommandSeq`,
`queueRevision`, `queueSize`, `currentTrackHash`, `localDriftMs`, `peerDriftMs`, `lastCorrection`,
`playbackRate`, `hardSeekCount`, `lastScheduleErrorUs`, `correctionTickCount`,
`deferredCommandCount`, `ingressDesynchronized`, `outboundAuthorityLost`) and deliberately does not
clear the cumulative ones (`lateCommandCount`, `duplicateCommandCount`, `staleCommandCount`,
`roleViolationCount`, `staleRevisionCount`, `inboundProcessedCount`, `inboundOverflowCount`,
`inboundCoalescedCount`, `recoveredCommandCount`, the five `outbound*` counters,
`resumedPendingPlayCount`, `cancelledPendingPlayCount`). Cumulative counters may keep accumulating
**only from work that still owns its generation**; that is the change. A retired node may not
increment one, which is exactly what **C** got wrong.

### G. A new session never waits for the old session's apply chain

Session B's first apply must not join, or queue behind, a Session-A node that may be blocked
indefinitely inside a player call. Clearing both chain tails at the boundary makes the new session's
first node have no predecessor, so this is true by construction rather than by Session A happening to
finish. Pinned by a regression in which Session A's apply stays blocked for the whole of Session B's
work — connect, queue, `PLAY`, scheduled start — and is released only afterwards.

### H. No wire change

Restating, because it is the thing most worth being unambiguous about: no message type, no field, no
bound, no encoding and no vector changed. The three internal signature changes are
`chainApply(generation:)`, and `applyTransport`/`applySeek` becoming `async` on iOS.

### I. Stress

The A2 pass requested stress runs and did not perform them; this pass did, and they earned their
place. The Phase 5 iOS suites were run **200 times** and the Android Phase 5 package **100 times**,
covering the blocked-apply race, the independent-new-session race, the old-deadline race, the
multi-node boundary, and A2's outbound-capacity and deferred-authoritative-stream regressions.

**Every one of the run's findings was a test defect, not a production one**, and they are recorded
because they are the reason stress is mandatory rather than optional:

1. Two premise assertions in the new iOS regressions read `clock.pendingDeadlines()` and the queue selection immediately after a send, and both are one or two task hops early — a scheduled node reaches the sleeper only after `applyPlay` has selected *and* pre-rolled. One failed **6 runs in 12** before being changed to wait on the condition rather than assert it.
2. A pre-existing A2 test, `testACorrectionSupersededBeforeItsSnapshotEnqueueEmitsNothing`, flaked **1 in 13**: `handleConnected` *starts* the cadence loop, which then computes `now + interval` and parks one task hop later, so a test that advances the fake clock inside that hop moves time out from under it and the tick never fires. `SyncPlaybackDriftTests` already had an `awaitTickArmed()` helper from A1's harness pass; the delivery-audit and closure-audit harnesses did not, and now do.
3. A second pre-existing A2 harness gap, same class, found on the next run: `leaderPlaying()` waited only for the `PLAY` to reach the wire, and A2 deliberately runs the leader's *own* apply after that, so the helper could return with `timeline` still nil and a cadence tick would then correctly do nothing. `testACurrentCorrectionStillEmitsExactlyOneAuthoritativeSnapshot` failed that way **1 in 7**. `SyncPlaybackDriftTests` already carried this exact fix from A1, comment and all; A2's newer harness reintroduced the race.
4. A third pre-existing A2 harness gap, **1 in 9**: `testACorrectionWhoseEpochIsSupersededBeforeItsSnapshotEnqueueEmitsNothing` superseded the playback epoch with `playSynchronized` and then used `settle()` as the barrier before releasing its gate. `epoch.begin()` is reached only at the end of a long chain — queue add, send, commit hook, apply chain, content resolve — and A3's own added proof hops made an already-marginal yield budget worse. When the supersession had not happened yet, the correction was *legitimately still current*, so the emit was correct and the test failed for the one reason a test must never fail: **the code was right.** Now waits for `currentTrackHash`, which `applyPlay` writes immediately after `epoch.begin()` with no `await` between. Its latent sibling (`…WhosePlaybackEpochIsSupersededEmitsNothing`) had the same barrier and was fixed with it.
5. The first stress script itself was wrong, and is recorded because it would have produced a false green: a filtered `swift test` prints one `Executed N tests` line **per suite**, so grepping the first one only ever checked the first suite. Replaced with `swift test`'s own exit code.

**Nothing in the coordinator was at fault in any of the five.** The pattern is worth naming: the fake
monotonic clock can be wound forward out from under a parked sleeper, which no real monotonic clock
can, and A2's harness — written in a pass that skipped stress — reintroduced *three* races A1's
harness had already solved. Android is immune to all of them, because `runCurrent()` under a
`StandardTestDispatcher` drains every pending coroutine before the test's next statement.

**One negative result, recorded because it is the more useful half.** A3's boundary regressions assert
that a retired Session-A node produced *no* effect, and a fixed yield budget cannot distinguish
"correctly fenced" from "has not run yet" — so two attempts were made to replace it with an exact
signal: waiting for the live-node registry to empty, then awaiting the apply chain's tail. **Both were
rejected, and the second is instructive:** a boundary clears the chain tail in the fixed code *and* in
the pre-A3 code, so awaiting it returns immediately and says nothing about the retired node — the
"stronger" signal silently cost one regression its pre-fix failure. A retired node is unreachable by
construction, and any signal precise enough to await would be an effect the fence exists to prevent.
The budget therefore stays, deliberately named `awaitRetiredWorkSettled` and documented as a budget,
and **what makes the assertions credible is the pre-fix run** — seven of nine cases failing at that
same budget, on both platforms. The exact tail await is kept only where the tail genuinely is the node
under test: the same-session ordering control.

### What this amendment adds, and what it deliberately does not

**Adds:** one session-owned lifetime handle per platform (`sessionChains` / `sessionChainNodes`);
a generation parameter on `chainApply`; pre-mutation ownership proofs in `applyAuthoritative`,
`applyPlay`, `applyTransport`, `applySeek`, `applyStep`, `applyPeerPlaybackState` and
`restoreFromPlaybackState`; the ownership proof moved ahead of the schedule-error measurement; and
two mirrored regression suites (`SyncPlaybackLifecycleAuditTest` /
`SyncPlaybackLifecycleAuditTests`, nine tests each) plus one two-peer regression on Android.

**Does not:** change the wire format or any vector; add a gate table (coroutine and `Task` lifetime
is not a distributed decision, and CLAUDE.md's rule 18 is about *decisions*); change A2's authority
semantics — `command_seq` and `queue_revision` still commit at admission, `lastAppliedSeq` and the
local effect still at send success, a failed send still fails closed, a new session still clears the
latch; change the deferred authoritative stream, `AuthoritativeHoldGate`, or the held-stream overflow
posture; alter the drift ladder, its hysteresis or its seek budget; alter
`LEAD = max(120 ms, 4 × rtt_p95)`; add a second player, queue, `MediaSession`, coordinator or RTT
tracker; implement `STATE_REQUEST`; touch Phase 6 or Phase 7; or make any claim about audio.

**And still does not run on a phone.** Every figure here is a software figure. The <100 ms product
target and the <50 ms stretch target remain unmeasured, no alignment figure exists, and TEST_PLAN
§5.2's S-01…S-12 are what will change that.

---

## Amendment A4 — 11 September 2026 — player-operation lifetime / post-suspension ownership

**Status:** Accepted · appended, nothing above rewritten. Amendments A1, A2 and A3 are unchanged.

A3 fenced *operations*: an apply-chain or scheduled-chain node created under Session A is retired at
a boundary, and every apply path proves its authorising generation before its first read of live
state. Independent verification of A3 named the narrower class **underneath** that fence, and this
pass confirmed it:

> An operation may pass its ownership check while Session A is valid, enter a **compound** async
> player operation, suspend inside that operation, have Session A end and Session B become live, and
> then resume and perform **another** player or local-queue side effect against Session B.

A3 proved that a command whose session ended *while it queued* does nothing. A4 is the case where
the command legitimately **started**: one ownership proof authorised *two* externally visible
effects, and only the first of them was inside the proof's lifetime.

### A. The three compounds

| Where | Effects behind one proof | Reachable how |
|---|---|---|
| `applyTransport`'s scheduled action | `pause` → `seek`, or `seek` → `start` | one closure, one `runIfCurrent` |
| `MusicCoordinator.syncPrepare` | materialise → `load` → `seek` | *below* `SyncPlayerPort.prepare` |
| `MusicCoordinator.syncStop` | player `stop` → clear the local queue | *below* `SyncPlayerPort.stop` |

The second and third are the sharper finding, because no coordinator-level proof could ever have
reached between their sub-effects: the port hid the compound. **The port shape was the root cause**,
not any individual missing `guard`.

### B. The exact interleaving, on iOS, where it is real

`MusicCoordinator` is `@MainActor`; `AVAudioEnginePlayer` is an `actor`. So:

```
Session A  applyPlay → runOwnedSteps → player.prepare
             → hop to MainActor → syncPrepare
             → queueState = <A's item>
             → await player.execute(.load)          ← MainActor released
                                                       ── boundary: A ends, B authenticates
                                                       ── B's applyPlay runs to completion:
                                                          selects, loads and starts its own track
             → await player.execute(.seek(A's pos)) ← **Session B's player, seeked by Session A**
```

`syncStop` is the same shape with `queueState = LocalQueueState()` as the second effect — a retired
`NEXT`-off-the-end clearing the live session's Now Playing entry and lock-screen metadata.
`applyTransport`'s pair is the same shape one layer up, and it is visible to the coordinator's own
test seam, so it is the one demonstrable against literally unmodified source.

**Android was not observably defective, and this amendment does not claim it was.** Every compound
there reaches `ExoPlayerMusicPlayer.execute`, which wraps its body in
`withContext(Dispatchers.Main.immediate)`; every Phase 5 caller runs on `AppContainer`'s
`Dispatchers.Main` scope and is therefore already on the main thread, so the block starts
undispatched and the call returns **without suspending** — and where nothing suspends, nothing
interleaves. That is now measured rather than reasoned about:
`SyncScheduledPlaybackTest.aPlayerCommandFromTheMainDispatcherDoesNotSuspend` queues a competitor on
the main looper and asserts it does not run between two real `ExoPlayer` commands.

That safety is an accident of which `CoroutineScope` the composition root happens to build. It was
undocumented, untested, and one dispatcher change — or one `Player` that genuinely awaits
`STATE_READY` — away from being false. The shape is therefore **mirrored**, so the guarantee stops
depending on the accident, and the Android regressions build the interleaving with a genuinely
suspending fake so the fence is proven independently of the dispatcher.

### C. Why an already-started effect is treated differently from a new one

The fix does **not** attempt to un-start a platform effect already dispatched. A player exposes no
such rollback, and demanding one would be a fiction. The line this amendment draws is:

> A synchronized playback operation authorised by Session A may execute an atomic platform effect
> that was already in progress when Session A ended, but after any suspension it may not **initiate
> another** externally visible effect unless Session A's generation and the relevant playback epoch
> are still current.

So "one indivisible effect completing late" is allowed; "choosing to do the next thing" is not.
Making every port method exactly one indivisible effect is what turns that sentence into something
the type system helps enforce rather than a rule someone has to remember.

### D. The fix

**`SyncPlayerPort` has one externally visible effect per method.** `prepare(content:positionMs:)`
became `select(content:)` + `load(content:)` + the existing `seek(positionMs:)`; `stop()` became
`stop()` + `clearSelection()`. `MusicCoordinator` on both platforms gained the matching
single-effect entry points and lost both compounds.

**Sequencing moved up into `SyncPlaybackCoordinator.runOwnedSteps(_:generation:token:)`**, which
re-proves ownership before **every** step. A scheduled action is now a `[PlayerStep]` value rather
than a closure — deliberately, because a closure can contain a second `await` and nothing about its
type says so, which is exactly how `applyTransport` came to drive two effects behind one proof.
`runIfCurrent` is gone; `runOwnedSteps` subsumes it, and a single-step list is the correction
ladder's case.

**iOS needed one thing Android did not.** Android's `owns()` is synchronous — `currentAuthGeneration`
is a plain property — so a proof placed immediately before a call is atomic with dispatching it. On
iOS `owns()` must `await` the session actor, and that `await` is itself a re-entrancy point: a
boundary landing inside it means the guard resumes and dispatches a step for a session that ended
while the guard was being taken. `liveGeneration` (written wherever `diagnostics.sessionGeneration`
is, and nowhere else) and the synchronous `ownsNow` close that window, giving iOS the property
Android gets for free. Both proofs are taken, and neither is redundant: the `async` one catches a
generation the manager has already advanced but this actor has not been told about; the synchronous
one makes the proof and the dispatch one actor-isolated step.

**Two further findings came out of building the regressions**, both the A3 Finding C shape —
"diagnostics only" is not an exemption — one function further along:

- **Finding E:** `tickOnce` read the live `timeline` and `currentEpochToken` immediately after
  `await drainDeferredEvents()`, which resolves content and applies commands and therefore suspends.
  A retired session's tick read the *new* session's timeline; the `POSITION_REPORT` it enqueued was
  correctly refused at the wire, but the outbound counters still moved. It now re-proves the
  generation after the drain.
- **Finding F:** `tickOnce` incremented `correctionTickCount` unconditionally after awaiting
  `applyCorrection`. A tick parked inside a rate nudge across a whole boundary woke and moved the
  live session's counter. Confirmed identically on both platforms (`correctionTickCount 0 → 1`).

**`restoreRate` is a deliberate, documented exemption.** Its three callers — `resetForNewSession`,
`failClosedOutbound`, `leaveSynchronizedMode` — are each the *ending* of an authority, so there is no
generation left to prove, and fencing it would leave the previous session's nudge in force on music
ADR-004 says keeps playing. It is idempotent and names an absolute rate, so a late one cannot fight
a live correction into a wrong value.

**What was checked and found sound.** `AVAudioEnginePlayer.execute` has no internal suspension —
`load` is `async` but awaits nothing — and its only callback, `scheduleSegment`'s completion, is
already fenced by its own monotonic `generation`. `ExoPlayerMusicPlayer.execute` likewise never
suspends, and its position-tick job is cancelled and replaced on every load/seek/stop. **No
ownership token needed to reach into either player**, so none does: the fence stops at the port, and
`MusicCoordinator` stays free of any session dependency. Now Playing and `MediaSession` are derived
from `MusicCoordinator.queueState`/`playerState`, both of which are now written only through fenced
steps, so §18's invariant holds transitively rather than needing its own mechanism.

### E. Regression evidence

`SyncPlaybackOperationLifetimeAuditTests` (iOS, 7 tests) and `SyncPlaybackOperationLifetimeAuditTest`
(Android, 7 tests) — parked decoder load, parked resume-seek, parked pause, parked stop, the
correction ladder, new-session independence, and a same-session control.

Two of the four compounds lived in the app target, which has **no test target on iOS**
(`docs/STATUS.md` §4 problem 20), so they cannot be demonstrated against literally unmodified
pre-A4 source and this amendment does not pretend otherwise. Three separate pre-fix runs were taken
instead, each reverting exactly one thing:

| Reverted | iOS | Android |
|---|---|---|
| the fence (`runOwnedSteps` proving once, which is pre-A4's semantics for all four compounds) | 5 of 7 fail | 5 of 7 fail |
| `applyTransport` literally (its two effects back in one closure, everything else fixed) | exactly the parked-pause and parked-resume cases fail | — |
| `tickOnce`'s post-correction proof (Finding F) | the correction case fails on `correctionTickCount` | same, `0` vs `1` |

The two cases that pass under the first revert are the correction proof (the ladder's player half was
never vulnerable) and the same-session control (no boundary).

### F. One test-signal regression A4 caused, found by stress and fixed

The A3 lifecycle suite was clean at 200/200 on unmodified `72e95ec` and flaked at roughly 1 % after
A4 — **in the tests, not in production**. Making a pre-roll three calls instead of one turned
`expect { player.calls.count >= 2 }` from "the second apply has begun" into a condition already true
before the release, and `awaitApplyChainDrained` then raced a chain node `chainApply` had not created
yet, because the outbound counters move before the commit hook runs. Separately, `.contains(.start)`
could be satisfied by an *earlier* session's start, and `.start` is recorded inside the step while
`markSynced` follows it, so a "before" snapshot could be taken between the two. All three signals
were replaced with exact ones (the second apply's own effect; a counted start; `syncState == .synced`).
Clean at 300/300 afterwards. Recorded here because the honest reading is that A4 weakened those
signals and the stress run is what caught it.

**Adds:** three single-effect port methods per platform (`select`, `load`, `clearSelection`) and the
matching `MusicCoordinator` entry points; the `PlayerStep` value type; `runOwnedSteps`/`perform`;
iOS's `liveGeneration` + `ownsNow`; two ownership proofs in `tickOnce`; two mirrored seven-test
regression suites; and one instrumented measurement of Android's non-suspension premise.

**Does not:** change the wire format or any vector — all thirteen generators reproduce byte-identical
output; add a gate table (a per-step ownership proof is lifetime, not a distributed decision, exactly
as A3 §E argued); weaken any A1, A2 or A3 guarantee; change ordering, admission, delivery-bound
authority, the held authoritative stream, the drift ladder, `LEAD`, or the epoch/fence semantics; add
a second player, queue, `MediaSession`, coordinator or RTT tracker; give the platform players any
knowledge of sessions; implement `STATE_REQUEST`; touch Phase 6 or Phase 7; or make any claim about
audio.

**And still does not run on a phone.** Every figure here is a software figure. The <100 ms product
target and the <50 ms stretch target remain unmeasured, no alignment figure exists, and TEST_PLAN
§5.2's S-01…S-12 are what will change that.

## Amendment A5 — 11 September 2026 — coordinator-state lifetime: the post-suspension mutation class

**Status:** Accepted · appended, nothing above rewritten. Amendments A1, A2, A3 and A4 are unchanged.

A4 fenced the **player**: every `SyncPlayerPort` method became exactly one externally visible effect,
and `runOwnedSteps` re-proves ownership before each of them. Independent verification of A4 named the
class that fence does not reach, and this pass confirmed it:

> Old Session-A asynchronous work → `await` → Session B becomes live → the old continuation resumes
> → it **mutates live Phase 5 coordinator state** before proving Session A is still current.

A4's question was "may this operation still perform its *next player effect*?" A5's is the same
question about everything that is **not** the player: ordering state, the held authoritative stream,
the outbound queue, the drift state, the diagnostics. The player action such a continuation goes on
to attempt may well be refused correctly afterwards — but **the mutation before that refusal has
already happened, and nothing later undoes it.**

**A local mutation that has been authorised is not a local mutation that may still happen.**

### A. `admitAuthoritativeCommand` contaminated the new session's ordering state

`estimate()` awaits `SyncSessionPort.sessionClockEstimate()` (and, on a leader, `rttP95Us()`). Both
are cross-actor reads, and on an actor every `await` is a re-entrancy point. The three branches that
follow it had **no proof of any kind**:

| Branch | What a retired continuation wrote into Session B |
|---|---|
| `.apply` | `lastReceivedSeq`, `lastAppliedSeq`, and both diagnostics mirrors |
| `.defer_` | `lastReceivedSeq`, an **append to `deferredEvents`**, `deferredCommandCount`, `syncState = .clockUnready`, and a deferred-drain task |
| `.overflow` | `playbackDesynchronized`, `queueDesynchronized`, `inboundOverflowCount` |

`applyAuthoritative`'s A3 Finding B proof is not a defence: it runs *after* those writes. It refused
a command whose damage was done, and the consequence is permanent for the session — with
`lastReceivedSeq = 50` carried over from a dead session, `CommandOrderGate` then correctly refused
Session B's own `command_seq` 1 as **stale**, and every command that session ever issued with it.
Measured, not argued: see §F.

The `.defer_` branch is the worse of the three, because A2 Finding D made the held stream *replay in
arrival order* — so a Session-A `NEXT` sitting in Session B's buffer would have stepped Session B's
queue against a revision no leader authored it for.

The same shape sat one function along, in `drainDeferredEvents`: after its own `await estimate()` the
`.command` branch calls `deferredEvents.removeFirst()`. A boundary landing in that read means
`resetForNewSession` has already emptied the buffer, and `Array.removeFirst()` on an empty collection
**traps**. That one was a crash, not a divergence.

### B. `tickOnce` had three post-suspension windows, not one

A4 Finding E added the proof after `drainDeferredEvents()` and Finding F the one after
`applyCorrection`. Between them sat three more suspensions with live writes after each:

1. `await estimate()` → the "clock not ready" branch publishes `syncState = .clockUnready` and
   `clockReady = false`. A retired tick announced an untrustworthy clock on the session that
   replaced it.
2. `await player.playerState()` → the outbound `POSITION_REPORT` **enqueue**. The existing `owns`
   proof sat *after* it. The frame itself is correctly refused at the wire — `Phase5Outbound` carries
   the authorising generation (A2 Finding B) — but enqueueing and counting it moved three of the new
   session's counters, and A3 §D already settled that "it never reached the peer" is not an
   exemption.
3. `await routeState.isRouteTransitioning()` → `driftState` and six diagnostics fields
   (`clockReady`, `clockOffsetUs`, `rttP95Us`, `leadUs`, `localDriftMs`, `routeTransitioning`), with
   ADR-004's ladder evaluated from values **Session A** sampled. This is the sharpest of the three:
   a retired tick left the live session with `DriftState(nudging: true, nudgeRate: 0.998)` and a
   drift reading taken against a timeline that no longer existed.

The tick is also where the epoch, not merely the session, is the right unit: `timeline` and
`currentEpochToken` are now read as one, with no `await` between them, and every proof below is
`owns`/`ownsNow` rather than the session half alone.

### C. `onPeerPositionReport` did not carry a generation at all

It could not prove anything, because it was never told what to prove. `await player.playerState()`,
then `diagnostics.peerDriftMs = positionMs - expected` — FR-023's observed-peer-drift figure,
computed against **Session A's** anchor for a track Session B is not playing, written onto Session
B's diagnostics screen. `onPlaybackMessage` now threads its dispatch generation in.

**Retain the epoch, do not re-read it.** `active` and `currentEpochToken` are captured together
*before* the suspension and proved after it. The report's meaning is "the peer's position minus what
*this* timeline expects at that instant" — the timeline its `track_hash` and `at_session_us` were
just matched against. Re-reading the live timeline after the suspension would silently answer a
different question about a different track; proving the captured epoch and otherwise producing
nothing is the honest answer. It is the same choice `applyTransport` and `applySeek` already make.

### D. The asynchronous proof is not adjacent to what it authorises

A4 Finding D established this for the player and gave it `liveGeneration` + `ownsNow`. A5's three
findings are all in work that legitimately has **no playback epoch yet** — an admission decides
ordering before any track is chosen — so `ownsNow` could not be used without inventing an epoch
requirement that would refuse perfectly valid work.

`stillCurrentNow(_:)` is therefore the session half on its own, and `ownsNow` is now expressed as
`stillCurrentNow && epoch.isCurrent(token)`. The pattern, everywhere:

```swift
guard await stillCurrent(generation) else { return }   // catches a generation the manager moved
guard stillCurrentNow(generation) else { return }      // closes the window the first one's own await opens
// no `await` here
mutate live actor state
```

The two have **different jobs and neither is redundant.** `stillCurrent` asks the session manager and
therefore catches a boundary this actor has not been told about yet; `stillCurrentNow` reads the
mirrored generation this actor owns, so nothing can run on the actor between it and the mutation.
Using the synchronous mirror *instead of* the manager proof would be a regression, because the mirror
lags by exactly the interval between the manager advancing and `SessionCoordinator` forwarding the
event.

### E. Every site changed

All iOS, all in `SyncPlaybackCoordinator` / `SyncPlaybackCoordinator+Inbound`.

| Site | Suspension | What followed it unproved |
|---|---|---|
| `admitAuthoritativeCommand` | `estimate()` | ordering state, held stream, desync latches (Finding A) |
| `drainDeferredEvents` | `stillCurrent`, `estimate()` | `removeFirst()` on a cleared buffer, `lastAppliedSeq` |
| `tickOnce` | `estimate()`, `playerState()`, `isRouteTransitioning()` | Finding B's three windows |
| `onPeerPositionReport` | `playerState()` | `peerDriftMs` (Finding C) |
| `readyEstimate` | `estimate()` | the clock-unready diagnostics — it had no generation to prove; it takes one now |
| `issue` | `readyEstimate`, `stillCurrent` | `nextSeq` — the session's own sequence allocator |
| `onCommandOutcome` | `stillCurrent` | `lastReceivedSeq`/`lastAppliedSeq`, Finding A from the leader's side |
| `playSynchronized`, `servePlaybackIntent` | `currentAuthGeneration`, `stillCurrent` | `playRequestFence.begin()`, which **supersedes the live session's retained Play** |
| `mutateQueue`, `applyLeaderMutation`, `onQueueIntent`, `rebroadcastAuthoritativeState` | `stillCurrent` | `queueState`, outbound admission and its counters |
| `applyPlay`, `applyTransport`, `applySeek`, `applyStep`, `applyAuthoritative`, `applyPeerPlaybackState`, `restoreFromPlaybackState`, `runApplyNode` | `stillCurrent` | A3 Finding B's live-state writes — `epoch.begin()`/`epoch.supersede()` among them |
| `runScheduledNode`, `markSyncedAndPublish`, `applyCorrection` | `owns`, `sleeper.sleep`, `runOwnedSteps` | A3 Finding C's schedule error and the correction diagnostics |
| `emitPlaybackStateFrame` | `estimate()`, `playerState()` | the enqueue — its `stillOwned` closure was `async`, so "no `await` to the enqueue" was true of the statements and false of the guard; the closure is now a `token: Int64?` |
| `failClosedOutbound` | `currentAuthGeneration` | the whole fail-closed latch |
| `onPlaybackMessage`, `onQueueMessage` | `stillCurrent` | the dispatch itself, which each handler then re-proves for |

**Deliberately unchanged, with reasons:**

- `resolvePendingPlay` — structurally safe. `PendingPlayGate.decide` reads `playRequestFence`
  synchronously, a boundary supersedes it, so `operationCurrent` is false and the decision is
  `.cancel`; `clearPendingPlay` is token-guarded and writes nothing for a token it does not hold.
- `restoreRate` and `leaveSynchronizedMode` — the *ending* of an authority, with no generation left
  to prove (A4 §D).
- `drainOutbound`'s `outboundAttemptCount`/`inboundProcessedCount` — the queues deliberately outlive
  sessions (A2 Finding B), `resetForNewSession` deliberately does not reset these, and the relay's
  own `authorizingGeneration` argument is what refuses a stale write (A2 Finding B again). Pipe
  accounting, not session state.

### F. Pre-fix evidence — measured, not asserted

Each case below was run against unmodified `902f3675` production sources, with only the new test file
and the fakes' three new gates present. Exact values:

| Case | What moved in Session B, pre-fix |
|---|---|
| A5-IOS-1 admission, `.apply` | `lastReceivedSeq` **50** (expected 1) · `lastAppliedSeq` **50** (expected 1) · `lastAppliedCommandSeq` 50 · and Session B's own next `PAUSE` then never committed its `command_seq` at all |
| A5-IOS-1b admission, `.defer_` | `deferredEvents` **1** (expected 0) · `lastReceivedSeq` **50** · `deferredCommandCount` 1 · `syncState` `.clockUnready` |
| A5-IOS-2 tick in `playerState` | `outboundEnqueuedCount` **1** · `outboundAttemptCount` **1** · `outboundStaleCount` **1** (all expected 0) |
| A5-IOS-3 tick in `routeState` | `driftState` **`(nudging: true, nudgeRate: 0.998)`** · `clockReady` true · `clockOffsetUs` 0 · `rttP95Us` 8000 · `leadUs` 120000 · `localDriftMs` 60 — and Session B's own next tick could then no longer nudge, because the ladder's hysteresis saw a nudge already in force |
| A5-IOS-4 peer report | `peerDriftMs` **250000** (expected nil) |
| A5-IOS-5a / 5b same-session controls | **pass** before and after — as they must |

`A5-IOS-6` isolates §D's *synchronous* half and needs A5's asynchronous proof present in order for
there to be a suspension to land in, so its pre-fix run reverts exactly one thing — `stillCurrentNow`
returning `role != nil` without the generation comparison — exactly as A4 did for the two compounds
below `SyncPlayerPort`. Under that revert it is the **only** case in the file that fails, with
`lastReceivedSeq`/`lastAppliedSeq` at 50, which is the isolation the case exists for.

### G. Android: structurally safe, and why it is not mirrored

A4 found Android non-defective for a reason that was an *accident of the composition root* —
`withContext(Dispatchers.Main.immediate)` never suspending when called from the main thread — so A4
mirrored the shape anyway. **A5's reason is stronger and is not an accident: the suspensions do not
exist on Android, because the port signatures are synchronous.**

| Finding | Android | Evidence |
|---|---|---|
| A | STRUCTURALLY SAFE | `estimate()` and `readyEstimate()` are `private fun`, not `suspend fun`. `SyncSessionPort.clockEstimate` is a `StateFlow` (`.value`) and `rttP95Us`/`currentAuthGeneration` are plain properties, so there is no suspension between `CommandOrderGate` accepting and the sequence-number writes |
| B | STRUCTURALLY SAFE | `player.playerState` is a `StateFlow` and `routeTransitioning` is a `() -> Boolean` constructor parameter. No suspension between the post-drain `stillCurrent` proof and `enqueueOutbound`, nor between `owns` and the `driftState` write |
| C | STRUCTURALLY SAFE | `onPeerPositionReport` is a `private fun` with no suspension at all, so there is no continuation that could resume into a later session |

The one suspension-shaped construct in the same code is `commandMutex.withLock`. Every critical
section in the file was inspected: all of them have non-suspending bodies (the two that mention a
`suspend fun` mention it only *inside* an `Outbound` commit-hook lambda, which `drainOutbound`
invokes later, outside the lock), and `AppContainer` builds the coordinator's scope as
`CoroutineScope(SupervisorJob() + Dispatchers.Main)` — single-threaded. A mutex whose every holder
runs to completion without yielding is never observed held, so `lock()` never suspends.

Android therefore gets **no production change and no test churn**. Reintroducing any of these three
windows there would require turning a property on `SyncSessionPort` or `SyncPlayerPort` into a
`suspend fun`, which is a visible interface change rather than a silent one — which is the guard that
makes not mirroring honest.

### H. Regression evidence and stress

`SyncPlaybackSessionStateAuditTests` (iOS, 8 tests): parked admission `.apply`, parked admission
`.defer_`, parked tick in `playerState`, parked tick in `routeState`, parked peer report, the
synchronous-proof isolation, and two same-session controls. Every stale-session case asserts on a
whole-state snapshot — `lastReceivedSeq`, `lastAppliedSeq`, `deferredEvents`, the entire
`SyncPlaybackDiagnostics` value, `queueState`, every player call, the wire, and the set of armed
deadlines — rather than on one field, and then proves Session B's own next command, tick or report
still works.

Three new deterministic gates in the fakes make it real rather than approximate:
`FakeSyncSession.armClockGate` parks a session-clock read, `FakeRouteState.armGate` parks a
route-state read, and `FakeSyncSession.armGenerationGate` parks the authentication-generation read
that `stillCurrent` itself takes, returning the value that was live when it parked. No sleeps.

One structural fact the harness had to be designed around, worth recording: the ingress is **one
ordered consumer** by construction (A1 Finding C), so while Session A is parked inside an inbound
frame's handler nothing else can be delivered — the queue doing exactly its job. Where the parked
operation is an inbound one, Session B is therefore established through the *outbound* path as a
leader. A role that differs between sessions is not a contrivance: ADR-010 recomputes it at every
handshake.

**Stress:** the new suite 200/200 with zero failures; `SyncPlaybackClosureAuditTests` (A1),
`SyncPlaybackDeliveryAuditTests` (A2), `SyncPlaybackLifecycleAuditTests` (A3),
`SyncPlaybackOperationLifetimeAuditTests` (A4), `SyncPlaybackDriftTests`,
`SyncPlaybackCoordinatorTests` and `SyncPlaybackTwoPeerTests` 50/50 each, zero failures.

**Adds:** `stillCurrentNow` on iOS, `ownsNow` re-expressed through it, paired asynchronous/synchronous
proofs at every site in §E, a `generation` parameter on `onPeerPositionReport` and `readyEstimate`,
`emitPlaybackStateFrame`'s `token: Int64?` in place of its `async` closure, and one eight-test iOS
regression suite with three new deterministic gates.

**Does not:** change the wire format, any message type, any field, any encoding, any bound, or any
vector — all thirteen generators reproduce byte-identical output; add a gate table (post-suspension
ownership is lifetime, not a distributed decision, exactly as A3 §E and A4 argued); weaken any A1,
A2, A3 or A4 guarantee; change Android production code; change ordering, admission, delivery-bound
authority, the held authoritative stream, the drift ladder, `LEAD`, or the epoch/fence semantics;
add a second player, queue, `MediaSession`, coordinator or RTT tracker; implement `STATE_REQUEST`;
touch Phase 6 or Phase 7; or make any claim about audio.

**What it deliberately does not close**, restating A4 §C one level up: a mutation already *performed*
under Session A is not rolled back, and an indivisible platform effect already dispatched may still
complete after the session ends. What is closed is the *next* mutation and the *next* effect.

**And still does not run on a phone.** Every figure here is a software figure. The <100 ms product
target and the <50 ms stretch target remain unmeasured, no alignment figure exists, and TEST_PLAN
§5.2's S-01…S-12 are what will change that.

## Amendment A6 — 12 September 2026 — generation-scoped ingress loss and terminal cleanup lifetime

**Status:** Accepted · appended, nothing above rewritten. Amendments A1, A2, A3, A4 and A5 are
unchanged.

A5 closed the class where an old session's continuation resumes and mutates live coordinator state.
Independent verification of A5 confirmed every one of its findings and then named two things A5's
sweep did not reach, because neither is a *continuation* resuming:

> 1. A **fact recorded by an object that deliberately outlives sessions**, carrying no generation, so
>    whichever session reads it next is charged with it.
> 2. A terminal cleanup path whose one deliberately **unfenced** player call was read as licensing
>    everything written after it.

Both are the same sentence one level further out than A5's:

**Something that outlives a session must not carry that session's verdict into the next one.**

### A. Why the queue's lifetime may exceed a session's

`Phase5FrameQueue` is created once per coordinator and torn down only by `shutdown()`/`close()`. That
is deliberate and A6 does **not** change it. The inbound instance is referenced by the two sinks the
authenticated control read loop calls (`PlaybackForwarder`/`QueueForwarder` on iOS, the two `Sink`
lambdas on Android); it owns the one parked consumer's continuation or channel signal; and the
outbound instance owns every frame this device has stamped but not yet written. Destroying and
recreating it at each authentication boundary would mean re-wiring the read loop's sinks, ending and
restarting the one ordered consumer, and deciding what happens to frames in flight at that instant —
a far larger change than the one below, and one that would reintroduce exactly the "who owns this
frame" ambiguity A2 Finding B closed. **A session boundary is expressed by the generation each frame
carries, not by tearing the pipe down**, and that remains true.

### B. Why the *identity* of a loss may not exceed a session's

What the queue recorded was two cumulative `Int`s — `overflows` and `coalesces` — which the consumer
diffed against its own two baselines at the top of each drain iteration. A difference is not a fact
about a session; it is a fact about a counter. So:

```
Session A, follower:
  one inbound handler is parked in an await
  the control read loop keeps accepting frames
  the bounded ingress fills
  another Session-A authoritative command arrives
  offer() refuses it            <- a real loss, under generation A
  the global overflow counter increments

Session A dies. resetForNewSession() clears both desync latches.
Session B authenticates as a follower. Clean.

The parked work releases. The consumer's next iteration calls observeIngressStats():
  overflowCount - reportedOverflowCount > 0
  -> onIngressOverflow() with the CURRENT role
  -> playbackDesynchronized = true
  -> queueDesynchronized = true
```

Session B is halted because Session A dropped something. The Session-A frames still queued behind it
then fail their own generation proofs correctly (A3 Finding B) — but the damage was done *before*
dispatch, by the observation itself.

**This is not a diagnostics bug.** `playbackDesynchronized`/`queueDesynchronized` decide whether an
incremental authoritative command is applied at all: while either is set a follower applies only
authoritative full state. A session halted by another session's loss stays halted until its peer
happens to send a `PLAYBACK_STATE` or `QUEUE_SNAPSHOT`.

**A baseline reset at the boundary is not a fix**, which is why one was not adopted. `offer` runs on
the control read loop; at the instant `resetForNewSession` sampled a new baseline, the read loop may
still be producing frames tagged with the old generation, and any refusal microseconds later would be
read back as the new session's. Correctness would rest on a timing assumption. Generation binding
does not.

### C. How overflow and coalescing are now generation-bound

`Phase5FrameQueue` takes a fourth constructor argument, `generationOf`, and records an ordered ledger
of `IngressLoss { generation, overflowCount, coalescedCount }` instead of two counters. A loss is
attributed to **the generation of the frame that caused it**: the refused frame's own for an
overflow, the arriving frame's own for a coalescing (it is the frame whose arrival produced the
event). Nothing is inferred from `currentAuthGeneration` at observation time — that inference *is* the
defect — and nothing is inferred from the next successfully dequeued frame, which need not share the
loss's generation at all.

Consecutive events from one generation share a bucket, so the ledger's length is the number of
generation changes the consumer has not drained across, not the number of events. It is hard-capped
at **8 buckets**; on eviction the oldest bucket's counts are folded into the next oldest rather than
dropped. With more than one bucket present, generations being strictly increasing per authentication
(ADR-023 §3) makes both of the two oldest strictly older than the newest and therefore both already
retired, so the total is preserved exactly and the only thing merged is which *dead* generation two
retired losses belong to. **No loss is ever silently discarded.**

`observeIngressStats` drains the ledger — it does not diff it — and judges each record against the
generation it carries:

- **live** (`stillCurrent` on Android, `stillCurrentNow` on iOS) ⇒ `inboundOverflowCount` /
  `inboundCoalescedCount`, and an overflow latches the halt exactly as A1 Finding C specified;
- **retired** ⇒ the new `inboundRetiredLossCount`, and nothing else. There is no live incremental
  authority left to distrust, so there is nothing to halt — but the event happened, and it is
  surfaced as what it was.

The whole function is synchronous on both platforms. There is no suspension between reading the
records, deciding whose they are and acting on them, so the decision cannot be overtaken by a
boundary the way A5's three sites were — which is why iOS uses `stillCurrentNow` alone here rather
than A5's `await stillCurrent` + `stillCurrentNow` pair. Adding the `await` would manufacture the
very re-entrancy point that pairing exists to defend against.

The **outbound** queue receives the same `generationOf` for symmetry, and deliberately never drains
its ledger: its producer is `enqueueOutbound`, which is *answered* synchronously by `offer` and acts
on the refusal there and then (A2 Finding A). That direction never had this defect.

### D. Same-generation ordering: loss is still observed before later work

A1 Finding C's property is unchanged and is asserted in its own test on both platforms: within one
generation, a refusal is observed at the top of the drain iteration that follows it, **before** the
next frame is dispatched. A `PAUSE` queued behind a refused `PLAY` is never applied as coherent
state, no `command_seq` is spent, and only authoritative full state ends the halt. Fixing §B by
moving the observation after dispatch, or by weakening the halt, would have been no fix at all.

### E. Cross-generation rule

A loss recorded under generation A can affect **only** generation A. It can never desynchronize a
later session, halt its queue or its playback, spend one of its sequence numbers, or appear in its
`inboundOverflowCount`/`inboundCoalescedCount`. Generation B's own loss still affects B, exactly
once, and both facts are asserted in the same test so that "scoped" is distinguishable from
"suppressed".

### F. The queue object still survives sessions

Unchanged, per §A. `close()`/`finish()` remain process/coordinator teardown only, the read loop's
sinks are wired once, and the one ordered consumer per direction is unchanged. What changed is one
constructor argument and what the ledger holds.

### G. `failClosedOutbound`: the rate restore stays unfenced

`restoreRate` is the one player call in this phase that is deliberately not fenced (A4 §D). Its three
callers — `resetForNewSession`, `failClosedOutbound` and `leaveSynchronizedMode` — are all the
*ending* of an authority, so there is no generation left to prove; it names an absolute 1.0 rather
than a relative change, it is idempotent, and ADR-004 says the music keeps playing. **That exemption
is unchanged and was not narrowed away.** An old authority may still finish restoring 1.0.

### H. What the exemption never covered: the writes after it

On iOS, `failClosedOutbound` proved its generation, mutated its own fail-closed state, then did:

```swift
await restoreRate()               // the deliberately unfenced player effect
diagnostics.syncState = .transportFailed
diagnostics.outboundAuthorityLost = true
diagnostics.deferredCommandCount = 0
diagnostics.localDriftMs = nil
diagnostics.peerDriftMs = nil
diagnostics.cancelledPendingPlayCount += cancelled
publishDiagnostics()
```

A boundary landing inside `player.setRate` therefore had Session A's fail-closed verdict overwrite
Session B's live diagnostics: `.transportFailed` and `outboundAuthorityLost` on a session whose
transport was working perfectly, with `localDriftMs`/`peerDriftMs` cleared under it.

The fix is ordering, not a new guard: **every write happens before the one suspension, and nothing
follows it.** Recording the restored rate moved from inside `restoreRate` into each of its three
callers for the same reason — `diagnostics.playbackRate = 1.0` after `await player.setRate(1.0)` is
itself a coordinator-state write from a dead session. What remains in `restoreRate` is one player
call with nothing behind it. The identical shape in `leaveSynchronizedMode` and `resetForNewSession`
was swept at the same time, since it is the same three lines.

### I. Android classification

**Finding A: affected, and fixed identically.** The queue, the counters and the diff were mirrored,
and so was the defect — the Android pre-fix probe records `ingressDesynchronized=true`,
`inboundOverflowCount=1`, `syncState=DESYNCHRONIZED` on a Session B that lost nothing.

**Finding B: structurally safe, and now asserted rather than assumed.** All three Android callers
*launch* `restoreRate` (`scope.launch { restoreRate() }`) rather than awaiting it, so the entire
fail-closed verdict is written in one uninterrupted synchronous block and the player call is the only
thing that outlives it. Android's `restoreRate` is given the same shape anyway — the two
implementations must agree, and a future caller that awaited it would otherwise reintroduce the
window silently. `SyncPlaybackIngressLifetimeAuditTest` lands a real boundary strictly inside the
parked rate restore and proves nothing of Session A's reaches Session B, so the structural property
is a test rather than a belief.

`inboundProcessedCount` is examined and **left alone**: it is the pipe's own accounting, counts what
the one ordered consumer has finished considering (refusals included), and `resetForNewSession`
deliberately does not reset it — `SyncPlaybackSessionStateAuditTests` already normalises it out of
its whole-state snapshots for exactly that reason. Its doc comment on both platforms now says
"process-lifetime, not session-lifetime" explicitly instead of leaving it to be inferred.

### J. Pre-fix regression evidence

Every value below was **run**, not reasoned about.

| Case | Platform | Pre-fix observation |
|---|---|---|
| Session-A overflow, boundary, Session B follower | Android | `ingressDesynchronized` false → **true**; `inboundOverflowCount` 0 → **1**; `syncState` → **DESYNCHRONIZED** |
| Same | iOS | `ingressDesynchronized` false → **true**; `inboundOverflowCount` 0 → **1**; `syncState` → **.desynchronized**; Session B's own next command then never applies (`lastAppliedCommandSeq` nil, not 1) |
| Session-A coalescing, boundary | iOS | Session B inherits **2** coalescings it never made; its own later pair then reads **4** |
| Generation A and B each lose one | iOS | A's loss reaches B, and B's own is then miscounted |
| `failClosedOutbound` parked in `setRate`, boundary, Session B follower playing | iOS | Session B `syncState` **.synced → .transportFailed**; `outboundAuthorityLost` **false → true** |
| Same | Android | **no contamination** — the verdict is written before the launched restore can suspend |

The committed regressions were additionally re-run against production code with exactly one thing
reverted — the generation check in `observeIngressStats`, and the statement order in
`failClosedOutbound` — the same isolation technique A4 and A5 used. Android: 3 of 8 fail. iOS: 4 of
8 fail, and each failure is one of the rows above.

### K. Regression evidence and stress

`SyncPlaybackIngressLifetimeAuditTest` / `…Tests` (8 tests each, mirrored): the cross-session
overflow, the same-generation halt and its authoritative reconciliation, per-generation ownership of
two separate losses, coalescing attribution in both directions, the parked-fail-closed boundary, the
same-session fail-closed control, a 200-permutation queue sweep, and the ledger bound. Deterministic
throughout — a virtual clock, an injected ingress bound of 1, and existing gates that park the
consumer inside a decoder call or the rate restore. Nothing sleeps.

**Stress:** the new suite 200/200 with zero failures on iOS (which includes the fail-closed
stale-continuation case, so that case is 200/200 rather than the 100 asked for);
`SyncPlaybackSessionStateAuditTests` (A5) 100/100; `SyncPlaybackOperationLifetimeAuditTests` (A4),
`SyncPlaybackLifecycleAuditTests` (A3), `SyncPlaybackDeliveryAuditTests` (A2),
`SyncPlaybackClosureAuditTests` (A1), `SyncPlaybackTwoPeerTests`, `SyncPlaybackDriftTests`,
`SyncPlaybackCoordinatorTests` and `Phase5FrameQueueTests` 50/50 each. Zero failures. One harness
defect was found and fixed by the full-suite run rather than by rerunning until green: the new
fail-closed test read its Session-B baseline after the frame was *considered* rather than after the
`.synced` transition it publishes one hop later, so under full-suite load the baseline was
`.inactive`. The wait is now on the transition.

**Adds:** `generationOf` on `Phase5FrameQueue` (both platforms), its `IngressLoss` ledger and
`drainLosses()` in place of `stats`, `generation` on `Phase5Inbound`/`Inbound`,
`SyncPlaybackDiagnostics.inboundRetiredLossCount`, a generation-tagged `deliver` on Android's
`FakeSyncSession`, and one eight-test regression suite per platform.

**Does not:** change the wire format, any message type, any field, any encoding, any bound,
`command_seq`, `queue_revision`, or any vector — all thirteen generators reproduce byte-identical
output; block or slow the authenticated control read loop (`offer` still never suspends and still
never waits on a lock held across I/O); unbound the queue; change the queue's lifetime; add a gate
table (loss lifetime is lifetime, not a distributed decision — A3 §E, A4 and A5 argued this already);
weaken any A1, A2, A3, A4 or A5 guarantee; change ordering, admission, delivery-bound authority, the
held authoritative stream, the drift ladder, `LEAD`, or the epoch/fence semantics; add a second
player, queue, `MediaSession`, coordinator or RTT tracker; implement `STATE_REQUEST`; touch Phase 6
or Phase 7; or make any claim about audio.

**What it deliberately does not close**, restating A4 §C and A5 once more: a mutation already
*performed* under Session A is not rolled back, and an indivisible platform effect already dispatched
— including `restoreRate`'s own `setRate(1.0)` — may still complete after the session ends. What is
closed is the *next* mutation and the *next* effect.

**And still does not run on a phone.** Every figure here is a software figure. The <100 ms product
target and the <50 ms stretch target remain unmeasured, no alignment figure exists, and TEST_PLAN
§5.2's S-01…S-12 are what will change that.

---

## Amendment A7 — 12 September 2026 — the inbound generation's origin, and the loss ledger's arrival order

**Status:** Accepted · appended, nothing above rewritten. Amendments A1, A2, A3, A4, A5 and A6 are
unchanged.

A6 bound every Phase 5 *loss* to the generation that caused it, so that a frame refused under
Session A could never halt Session B. Independent verification of A6 accepted that work and then
asked the question A6 had not: **where does the generation a frame arrives with actually come
from?**

It came from live state. Every consumer downstream took it as a *value* — `ControlRelays.deliver`,
`PlaybackRelay.deliverPlayback`/`deliverQueue`, `PlaybackSink.submit`, `SyncPlaybackCoordinator`'s
guard, and A6's own `Phase5FrameQueue.generationOf` — and `PlaybackSink.submit`'s doc comment stated
the contract in so many words: *"the authentication generation that was live **when the frame was
read off the wire**"*. But `ControlSessionManager.handleFrame` produced that value by reading its own
live `authenticationGeneration` field at **dispatch** time, which is not the same instant as the
read. The whole chain's contract was never met at its origin.

So A7 is A3's sentence applied one layer above everything A1–A6 touched:

**A frame is authorised by the connection it was read from, and by that connection's authentication
epoch. No later session transition may give it a newer one.**

### A. The reachable interleaving, before the fix

`endConnection` does **not** cancel the read loop on either platform — only `shutdown` does — and
the loop's next step after a completed read is a scheduling point on both:

- **Android.** `ControlSocket.readFrame()` runs its body in `withContext(Dispatchers.IO)`. Returning
  from it resumes the read-loop coroutine on the manager scope's own dispatcher, and that resumption
  is queued, not immediate. `endConnection` is reachable concurrently — `keepaliveLoop` is a separate
  coroutine and a pong timeout calls it.
- **iOS.** `ControlSessionManager` is an actor, so `await socket.readFrame()` is a re-entrancy point
  by construction: while that task is suspended, every other actor-isolated call runs to completion.

```
1. Session A authenticates                      authenticationGeneration = 1
2. Session A's read loop reads a valid PAUSE off socket A; its continuation is queued
3. the keepalive loop times out -> endConnection: activeSocket = null, socket A closed
4. the reconnect completes; Session B authenticates
                                                authenticationGeneration = 2
5. the parked continuation finally runs handleFrame, and reads `authenticationGeneration` -> 2
```

Session A's frame is now, to everything below, Session B's authority: `CommandOrderGate` considers
it against Session B's floor, `lastReceivedSeq`/`lastAppliedSeq` may move for it, and any refusal it
causes is charged to Session B. A6's retired-loss accounting cannot help — the frame was relabelled
**before** it ever reached `Phase5FrameQueue`.

**Measured, not argued.** `StaleReadGenerationTest` / `StaleReadGenerationTests` run against
`a0b81c1` production sources (the A6 closure commit) with only the A7 guard reverted, and record the
`PAUSE` arriving at the sink tagged generation **2** where it must be **1**, on both platforms, with
three of the four cases failing.

**Both platforms are affected, equally.** There is no structural accident here of the kind that
spared Android in A4 and A5: neither read loop is cancelled at the boundary, and both resume across
a scheduling point.

### B. Why A6's own suite could not see it

Every A6 regression supplies the generation itself — `session.deliver(message, generation = 1)`
against a `FakeSyncSession`. That is exactly the right seam for asserting **what the coordinator does
with a generation**, and exactly blind to **where the number comes from**. The defect lives entirely
above it. The same is true of A1–A5: all six audits worked at or below the sink, and the sink's
argument was the thing that was wrong.

### C. The fix: an immutable `(connection, generation)` record, and a per-frame binding

Two small types, one per platform, mirrored (`ReadFrameBinding.kt` / `ReadFrameBinding.swift`):

- **`AuthenticatedConnection(socket, generation)`** — created once in
  `activateAuthenticatedSession`, never mutated, discarded whole at the boundary. It **replaces** the
  `authenticated` boolean rather than sitting beside it; `authenticated` is now a derived read, so
  the two cannot disagree. There is still exactly **one** generation counter
  (`authenticationGeneration`), and this record copies from it — A7 adds no second source.
- **`ReadFrameBinding(socket, sessionId, generation?)`**, built by `ReadFrameBinding.of(...)` in
  `readLoop`, immediately after the read returns and before anything else can run. `handleFrame`
  takes the binding instead of a socket and a session id, and:
  - the pre-authentication gate asks `binding.generation == null` rather than `!authenticated` — the
    question is now "was **this** frame's own connection an authenticated session when it was read",
    never "is *some* session authenticated now";
  - the generation handed to the relays is `binding.generation`, never a live read.

Identity comparison is against the *record's* socket, not against `activeSocket`: the record and its
generation are created together and discarded together, so a connection either is the authenticated
connection under the exact generation its own activation assigned, or is not an authenticated
connection at all. A retired socket's binding resolves to `null` — never to the successor's number.

Both permitted outcomes from the brief occur, and both are pinned:

- a frame **read while its session was live** and dispatched after the boundary keeps generation A
  and is retired downstream exactly as A6 designed;
- a frame **read after the boundary** — Android's read loop genuinely runs once more, and a frame
  already buffered inside `BufferedInputStream` survives `socket.close()` — carries no
  authorisation, so the pre-authentication gate refuses and counts it, by the same construction that
  refuses an unpaired peer's `PAUSE`.

**What must not happen, and does not:** a frame dispatched late *within its own still-live session*
is still delivered, tagged that session's generation. Being late is not being stale, and a fix that
dropped every scheduling delay would be worse than the defect. "a frame dispatched late within the
same live session is still delivered normally" is that assertion, on both platforms.

`ReadFrameBinding` lives in its own file rather than inside `ControlSessionManager` because detekt
fired `TooManyFunctions` (35 against a threshold of 34) and `config/detekt/detekt.yml` records that
the answer is to extract rather than raise the number again — the discipline Phase 2a followed for
`VoiceSignalRelay` and Phase 5 for `ControlRelays`. iOS is mirrored for shape, not for a ceiling.

### D. The consequence for A6's loss ledger: arrival is **not** monotonic in the generation

A7's own fix makes an assumption A6 wrote down become false. Once a frame keeps its own session's
generation instead of inheriting the live one, a read loop whose session has ended still dispatches
the frame it had already read — and does so *after* the successor session's read loop has begun
offering. **`A, B, A` reaches `Phase5FrameQueue.offer`.** This is not a theoretical ordering: it is
the exact interleaving §A describes, and it is what the new regressions drive.

A6's ledger bucketed by **adjacency** — a new bucket whenever the incoming generation differed from
the *newest* one — and, once past eight buckets, evicted the oldest **by arrival**, folding its
counts into the next oldest by arrival. Its written justification was "generations strictly increase
per authentication, so both of the two oldest are retired". Under an alternating run that is wrong
twice over:

- the cap counted **buckets**, not generations, so nine buckets can be as few as two generations —
  `A, B, A, B, …`;
- the fold target is then the **newest** generation, which may be **live**.

And the consequence is not a diagnostics one. A follower answers a *live*-generation loss by latching
`playbackDesynchronized`/`queueDesynchronized`, which decide whether incremental authoritative
commands are applied at all. Folding a dead session's refusal into the live generation is therefore
**the exact cross-session halt A6 existed to remove, re-entering through the ledger's own
compaction.**

**Measured, not argued.** With A6's `recordLoss` restored and everything else at A7,
`SyncPlaybackReadGenerationAuditTest` / `...Tests` record, on a Session B whose own ingress refused
nothing: `ingressDesynchronized` **true**, `syncState` **DESYNCHRONIZED**, `inboundOverflowCount`
**1**. The longer run records `inboundRetiredLossCount` **20** for a session that caused twelve
refusals — the counts slosh between generations on every eviction, in both directions.

**The correction.** One bucket per **distinct generation**, in the order each generation first caused
an event; eviction removes the bucket with the **smallest** generation and folds its counts into the
next smallest. The safety argument no longer depends on arrival order at all:

> Generations strictly increase per authentication (ADR-023 §3), so a frame can only ever carry a
> generation ≤ the live one. Any bucket whose generation is live is therefore the **largest**
> generation present. With one bucket per generation, the fold target — the smallest generation that
> remains — is never the largest, and eviction only fires with at least two buckets remaining.

Which gives, exactly:

- the ledger stays **bounded** (≤ 8 buckets, and `MAX_LOSS_GENERATIONS` now bounds what its name
  says it bounds);
- **no retired loss is ever reclassified into the live generation**;
- **no live-generation loss is silently discarded** — the live generation is the maximum and is never
  the one evicted;
- the total is preserved exactly; nothing is dropped;
- same-generation ordering is intact, expressed as the counts it has always been expressed as, and a
  late arrival joins its own generation's bucket rather than opening a second.

Cross-generation *arrival* order is no longer preserved across buckets, and does not need to be: the
consumer (`observeIngressStats`) asks only whether each record's generation is `stillCurrent`, and
A1 Finding C's real ordering property — a loss is observed **before** the frame behind it is
dispatched, within one generation — is untouched and re-asserted.

### E. A7 adds no vector table

For A3's reason, restated by A4, A5 and A6: read-loop and connection lifetime is not a distributed
decision. Nothing about the wire, the ordering algebra, the drift ladder or the queue algebra
changed, so `protocol/vectors/` is untouched — and every generator was re-run to prove it produces
byte-identical output.

### F. Regressions

Deterministic, mirrored, no sleeps in the assertions:

| Test | Proves |
|---|---|
| `StaleReadGenerationTest` / `StaleReadGenerationTests` (4 cases each) | A Session A frame is never delivered as Session B's generation; a retired socket rebinds to `null`, never to the successor's number; a late frame of a still-live session is still delivered; a frame read after the boundary is refused and counted; Session B's own frame carries Session B's generation |
| `Phase5FrameQueueTest` / `Phase5FrameQueueTests` (3 new cases each) | The alternating run never folds a retired loss onto the live generation; the ledger stays bounded with one bucket per generation; a late arrival joins its own bucket; coalescing obeys the same ownership rule |
| `SyncPlaybackReadGenerationAuditTest` / `...Tests` (3 cases each) | Through the real coordinator: the first compaction never hands the live session a refusal it did not have; a long alternating run keeps every event with the generation that caused it; the live session's **own** refusal still halts it amid a retired session's noise |

**How the park is produced.** Nothing a test controls can suspend a coroutine or task between
`readFrame()` returning and the dispatch that follows it — which is the point of the fix. So the two
halves of that one step are called as two statements with a **real** session boundary between them:
`currentReadBinding()` is the capture `readLoop` performs, and `handleFrame(binding, frame)` is the
very function it calls. Both are `internal`, reachable only from each platform's own tests, and hold
the same standing `writeRawFrame` already has and for the same recorded reason. Everything else is
production: two real TLS 1.3 sessions on one real `ControlSessionManager`, the real trust gate, the
real allowlist, the real codec, the real relay.

### G. A6 Finding B is untouched, and re-verified

iOS `failClosedOutbound` still writes **every** coordinator and diagnostics field — including
`diagnostics.playbackRate` — before `await restoreRate()`, and `restoreRate()` is still the last
statement in the function with nothing after it. Android's three `restoreRate` callers all still
`scope.launch { restoreRate() }` rather than awaiting it. Same-session fail-closed still produces
`outboundAuthorityLost = true`, sync mode exited, `syncState = transportFailed`, deferred work
cleared, drift state reset, rate restored to exactly 1.0, and local music **not** stopped. A7 does
not touch `SyncPlaybackCoordinator` on either platform.

### H. What A7 deliberately does not do

No wire change of any kind — no new message type, no changed field, encoding or bound;
`protocol/vectors/` is byte-identical under regeneration. No change to `command_seq`,
`queue_revision`, `ContentHash` semantics, the ADR-010 leader rules or Phase 4 transfer behaviour. No
second coordinator, player, queue, `MediaSession`, RTT tracker or `ClockSync`. No second
authentication-generation source — `authenticationGeneration` remains the one counter, and the new
record copies from it. No weakening of any A1–A6 guard. No Phase 6 or Phase 7 work. No claim about
audio.

**What it deliberately does not close**, restating A3 §E and A4 §C: a frame already *delivered* under
Session A is not un-delivered, and an indivisible platform effect already dispatched may still
complete. What is closed is the *labelling* of the next frame.

### I. An open finding this amendment does **not** fix — Phase 4's identical origin

While sweeping for other live-generation derivations, the same defect was found in **Phase 4's**
manifest/transfer dispatch, on both platforms, and is recorded here rather than fixed because
correcting it means threading a generation through `ManifestRelay`/`TransferRelay` — an ADR-023
change this Phase 5 pass is explicitly scoped out of.

`SharedLibraryCoordinator`'s `ManifestSink`/`TransferSink` lambdas are invoked **synchronously from
`handleFrame`**, and each reads a live value at that moment: `controlSessionManager
.currentAuthGeneration` on Android, `sessionEpoch.current()` on iOS. Android's own doc comment makes
exactly the claim A7 disproved for Phase 5 — *"`currentAuthGeneration` as it was the moment this
message was read off the wire"*. It is not: `handleFrame` can legitimately be entered with a
**retired** binding (that is the window §A describes and the new regressions drive), and the live
read inside the sink then returns the **successor's** number. `handleManifestMessage`'s re-check
`if (generation != controlSessionManager.currentAuthGeneration) return` passes spuriously, and a
Session A `MANIFEST_PAGE` can mutate Session B's catalogue — the precise thing ADR-023 Amendment A2
Finding S exists to prevent.

This is **pre-existing and not introduced by A7**; A7 narrows the window (a frame read *after* the
boundary is now refused outright) but does not close it. `binding.generation` is the value those two
sinks should receive. **Phase 5 software closure is therefore not claimed by this amendment** — see
`docs/STATUS.md` §4.

### J. And still does not run on a phone

Every figure in this amendment is a software figure produced by unit tests on a laptop. The <100 ms
product target and the <50 ms stretch target remain unmeasured, no alignment figure exists, no audio
has reached a speaker or a Bluetooth endpoint, and TEST_PLAN §5.2's S-01…S-12 remain the only things
that will change that.
