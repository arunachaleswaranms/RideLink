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
