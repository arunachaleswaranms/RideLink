# ADR-028 — Ride Mode lifecycle and authoritative state resynchronization

**Status:** Accepted · 19 Sep 2026
**Relates to:** [ADR-019](ADR-019-connected-means-authenticated.md), [ADR-024](ADR-024-synchronized-playback-integration.md) (Amendment A8), [ADR-025](ADR-025-inbound-control-frame-provenance.md), [ADR-026](ADR-026-session-lifecycle-teardown-and-restart.md)
**Governs:** ARCHITECTURE §3 (Ride Mode/reconnect states), PROTOCOL §10, TEST_PLAN Phase 7 software closure
**Vectors:** `protocol/vectors/resync-messages/`
**Wire format:** `STATE_REQUEST`/`STATE_SNAPSHOT` were already fully specified by PROTOCOL §3 (Resync
group, Phase 1) and §10 before this phase; this ADR implements that existing spec rather than
changing it. No field is invented here — `STATE_SNAPSHOT.playback`'s `queue_item_id` is derived from
§5's own cross-reference ("`PLAYBACK_STATE`'s shape is §10's `STATE_SNAPSHOT.playback` plus the two
ordering values an anchor needs"), not added by this phase.

## Context

Phase 7's brief is Ride Mode plus resilience: a simplified riding UI, automatic reconnect (already
complete from Phase 1b/PROTOCOL §10's ladder — see "What was already done" below), and the one piece
PROTOCOL §10 specified but nothing implemented: `STATE_REQUEST`/`STATE_SNAPSHOT`, the follower's path
back to authoritative state after a Phase 5 ingress refusal latches `playbackDesynchronized`/
`queueDesynchronized` (ADR-024 Amendment A1 rule 6) with no recovery. `docs/STATUS.md` had recorded
this gap explicitly since Phase 5 closure.

Everything here sits downstream of eight ADR-020/024/025/026 amendments' worth of standing lessons
about provenance: a frame's authority is the connection and generation it was read from, never a
live value re-read later; ownership travels with a value through every suspension or the work is
refused. This phase treats `STATE_REQUEST`/`STATE_SNAPSHOT` as one more inbound/outbound family
subject to exactly those rules — not a new exemption.

## Decision

1. **`StateResyncGate`** (`core.resync` / `RideLinkCore.Resync`) is the one new pure decision table:
   whether to send a `STATE_REQUEST`, given what generation a request is already outstanding for.
   Keyed on the authentication generation rather than a bare boolean, so a stale generation's pending
   request can never block a fresh one, and a duplicate trigger for the *same* live generation is a
   no-op (`RequestDecision.ALREADY_PENDING`) rather than a resend. Whether an incoming
   `STATE_SNAPSHOT` may be *applied* is answered by the existing, already-audited Phase 5
   reconciliation path (`adoptSnapshot`/`onPeerPlaybackState`), reused rather than re-derived — a
   second provenance check is a second place for the two to disagree (rule 19/20's standing lesson).
2. **`ResyncCodec`** parses/encodes `STATE_REQUEST` (empty payload) and `STATE_SNAPSHOT` exactly per
   PROTOCOL §10's shape, reusing `QueueCodec.parseSnapshotPayload` for `.queue` and PLAYBACK_STATE's
   own field set (minus its two ordering values, which live at `STATE_SNAPSHOT`'s top level) for
   `.playback` — never a second, possibly-diverging copy of either parser. `protocol/vectors/resync-messages/`
   is the shared vector set both platforms run identically: required-field absence, wrong types,
   integer boundaries, the 1 000-item queue cap, content-hash validation, nullable playback identity,
   unknown-field tolerance, oversize rejection, round-trip encode/decode.
3. **`ResyncRelay`** mirrors `ManifestRelay`/`AudioStateRelay`'s existing pattern, not
   `PlaybackRelay`/`VoiceSignalRelay`'s bound-writer one: inbound delivery is generation-checked
   (drop + count on mismatch against `liveGeneration()`, per ADR-025), and `STATE_REQUEST`/
   `STATE_SNAPSHOT` are absent from the pre-authentication frame allowlist — the same absence that is
   the whole of `VOICE_*`/`AUDIO_STATE`'s access control. `STATE_REQUEST`/`STATE_SNAPSHOT` require no
   bound-writer upgrade because — per rule 24's own list of what already carries its own generation
   to a check of its own — the send-time check (`ResyncCoordinator.handleStateRequest` re-proving
   `generation == session.liveAuthenticatedGeneration` immediately before the outbound frame is
   admitted) plays that role, the same as it already does for Manifest and AUDIO_STATE.
4. **`STATE_SNAPSHOT`'s construction and send are one indivisible step on the leader's single ordered
   outbound writer** — not a bare `channel.send`/`session.resync.send` off to the side. This was
   found reachable during this phase's own stress testing (not by production failure) and fixed
   before closure, not recorded as an open risk: see "A note on how this was found" below.
5. **Ride Mode is real production FSM traffic, not test-only fabrication.** `SessionCoordinator.startRide()`/
   `endRide()` call `SessionFsm`'s existing `StartRide`/`EndRide` events (`CONNECTED -> RIDE_ACTIVE`
   and `RIDE_ACTIVE -> CONNECTED` — these transitions predate Phase 7; only their production callers
   were missing). `endRide()` is deliberately **not** the ADR-026 `ENDING -> IDLE` teardown — it exits
   Ride Mode while the session/pairing/control connection stay alive, exactly like PROTOCOL §10's
   `RECONNECTING` returning to "the state we left (CONNECTED or RIDE_ACTIVE)." The larger disconnect
   remains the separate, pre-existing `endSession()`/`UserEnded` path through `SessionTeardownOwner`.
6. **The Ride Mode screen is a pure projection of `SessionFsm`'s own status, never a second navigation
   authority.** Android's `nextRideModeVisibility(previous, status, returnTo)` and iOS's mirror
   (`RideModePresentation.nextRideModeVisibility`) are the one place visibility is decided: `RIDE_ACTIVE`
   shows it; `RECONNECTING` keeps it showing only when `returnTo == RIDE_ACTIVE` (never merely because
   a reconnect is in progress toward `CONNECTED`, which was never riding); `DISCONNECTED` — the 120 s
   budget exhausted — preserves whatever the previous frame showed, because `FsmState.returnTo` does
   not survive budget exhaustion and the retry banner must appear *inside* Ride Mode, not after the
   rider has already been dropped back to the developer screen. The one bit of local memory this
   needs (the previous frame's answer, for the `DISCONNECTED` case) is derived from the FSM's own
   output on every call and mutated by nothing else — never an independent decision, so it is not the
   second authority source rule 19/brief §19 forbids. The only way out of Ride Mode remains the End
   Ride button, which calls `endRide()` through `SessionFsm` — never a swipe-to-dismiss or a timeout
   short-circuiting it.
7. **Every Ride Mode control re-enters an existing gated path, never a second one.** Playback
   prev/pause/next call the same `MusicCoordinator`/`SyncPlaybackPresenter` methods the existing
   library/queue UI already calls. Mic mute/PTT call the same `VoiceController` entry points
   `VoiceCard` already uses. The intercom mode switch calls the same `IntercomPolicy` selection path.
   None of this is new command plumbing; Ride Mode only re-styles existing calls larger.
8. **`STATE_SNAPSHOT` reconciliation reuses Phase 5's existing role/generation-checked apply path
   wholesale — playback and queue both.** `ResyncCoordinator.handleStateSnapshot` hands the frame to
   `SyncPlaybackCoordinator.onStateSnapshot`, which delegates to `adoptSnapshot` (queue) and
   `onPeerPlaybackState`/`restoreFromPlaybackState`-equivalent (playback) — the same functions the
   ordinary `QUEUE_SNAPSHOT`/`PLAYBACK_STATE` wire messages already used. No second reconciliation
   path exists. Manifest is reconciled separately and narrowly: `manifest_revision` is compared
   against the previously-observed value and a refresh is triggered only on a **difference after the
   first snapshot of a session** — the first snapshot never triggers one of its own, because the
   pre-existing unconditional `Connected -> requestCatalogue()` already covers it, and this is the
   only thing that would notice the leader's catalogue moving *again* mid-ride, between the initial
   reconnect refresh and a later desync-triggered snapshot. Transfers are never resumed (PROTOCOL §10
   rule 4): `transfersInFlight` is parsed and bounds-checked per the shared vectors but carries no
   functional consumer on either platform — the receiver already discards and re-requests on every
   session boundary through the existing Phase 4 mechanism regardless of this field's contents, so
   threading a transfer id up through this coordinator would add a second, unused source of the same
   fact. This is a disclosed, deliberate limitation, not an oversight.
9. **Desynchronization recovery is the one new closed loop.** `Phase5FrameQueue`'s existing bounded
   refusal (ADR-024 Amendment A1 rule 6) sets `playbackDesynchronized`/`queueDesynchronized` and
   publishes it; `ResyncCoordinator` observes that publication and drives `StateResyncGate.onTrigger`,
   sending exactly one `STATE_REQUEST` per live generation regardless of how many times the trigger
   fires while one is outstanding. A `STATE_SNAPSHOT` accepted and applied for the live generation
   clears only the desync flags the snapshot actually repairs (both, since a snapshot carries both
   playback and queue state) via the existing `adoptSnapshot`/playback-restore functions' own
   clearing — no new clearing logic. A foreign-generation, stale, duplicate, or malformed snapshot
   clears nothing: rejection happens in the codec before a `ResyncMessage` is even constructed
   (malformed), or in the same generation comparison every other Phase 7/Phase 5 inbound frame
   already goes through (foreign/stale).
10. **A `STATE_REQUEST` sent by a lifetime that has since ended is inert on its answer's arrival, not
    on its own send.** The request itself is a fire-and-count outbound frame like any other; what
    matters is that the answering `STATE_SNAPSHOT`, when it eventually arrives, is bound to *its own*
    `ReadFrameBinding`'s generation (ADR-025) and refused if that generation is not the live one —
    the same rule every other inbound Phase 5/7 frame already obeys. No separate request-lifetime
    tracking was needed beyond `StateResyncGate`'s generation-keyed pending value, which a fresh
    trigger for a new generation naturally supersedes.

## Consequences

- The recorded Phase 5 gap — "a desynchronized receiver has no production `STATE_REQUEST` recovery
  path" — is closed on both platforms, with the recovery loop bounded, idempotent under duplicate
  triggers/duplicate snapshots, and provenance-checked at every inbound and outbound hop.
- Ride Mode is reachable from and returns to production UI on both platforms through the existing
  FSM, with no second command path, no second navigation authority, and no second teardown owner.
- Two real, reachable defects were found and fixed during this phase's own stress testing before
  closure — see below and ADR-024 Amendment A8. Both were reproduced against unmodified production
  first, per this codebase's standing audit discipline, and both are closed by the fixes this ADR and
  ADR-024 A8 record — not deferred.
- Physical ride qualification (R-03/R-04/R-05, real Android↔iPhone reconnect, real Bluetooth/Wi-Fi
  transition, screen-lock radio behavior, battery/thermal) remains **DEFERRED — HARDWARE NOT
  AVAILABLE**, unchanged from Phase 6. Nothing in this ADR claims otherwise.

## A note on how this was found — STATE_SNAPSHOT outbound ordering

The initial implementation had `ResyncCoordinator.handleStateRequest` construct a `STATE_SNAPSHOT`
from a role/generation-checked read of current leader state and send it directly
(`session.resync.send(...)` / `session.channel.send(...)`) — bypassing the single ordered outbound
writer (`Phase5FrameQueue`/`enqueueOutbound` and its one consumer) that every other Phase 5 broadcast
(`QUEUE_SNAPSHOT`, `PLAYBACK_STATE`) is required to use, per this ADR's own Amendment A1 Finding B:
*"one writer drains that order onto the connection... a transport write lock orders bytes, not the
decisions that produced them."* `STATE_SNAPSHOT`, new in this phase, was never folded into that
discipline.

This phase's own reconnect-cycle stress testing found the consequence directly: a `STATE_SNAPSHOT`
built from state read at instant T could reach the wire *after* a separately-sent `QUEUE_SNAPSHOT`
reflecting a later revision, because the two sends did not share an ordering primitive — a follower
receiving them in that order would adopt the `STATE_SNAPSHOT`'s older revision last and regress its
queue, since §10 rule 1's "the leader's snapshot is authoritative, no merge" has no monotonicity
guard against out-of-order arrival on its own.

**Fixed on both platforms** by folding `STATE_SNAPSHOT` into the existing single-writer path:
`SyncPlaybackCoordinator` gained a read-and-enqueue method (Android:
`emitStateSnapshot(generation, leaderPeerId, manifestRevision)`; iOS:
`enqueueStateSnapshotReply(generation:leaderPeerId:manifestRevision:transfersInFlight:)`) that reads
playback/queue state and calls the *same* `enqueueOutbound`/`Phase5FrameQueue` admission
`QUEUE_SNAPSHOT`/`PLAYBACK_STATE` already use, inside the same critical section (Android:
`commandMutex`; iOS: the standard `stillCurrent` → `stillCurrentNow`, no-`await`-between-proof-and-
enqueue pattern from Amendments A1/A5). `ResyncCoordinator.handleStateRequest` is now only the role
gate; it no longer independently constructs or sends. Whichever operation — a queue mutation or a
`STATE_REQUEST` answer — acquires the critical section first is both read first and written to the
wire first, making out-of-order delivery structurally impossible rather than merely unlikely. Each
platform added a regression test proving the ordering guarantee under contention (Android: a
transport gate held across a racing mutation and a `STATE_REQUEST` answer; iOS: 20 rounds of a real
queue mutation raced against a real desync-triggered `STATE_REQUEST` answer, asserting the follower's
observed revision sequence is monotonically non-decreasing throughout).

## What was already done, and needed nothing

Three of the brief's items turned out to already be correct, unconditionally, from Phase 1b/2a/2b/5
mechanisms — confirmed by direct audit before concluding nothing needed building, not assumed:

- **Automatic reconnect and backoff.** `ReconnectPolicy`/`ControlSessionManager`'s existing ladder is
  exactly 0.5/1/2/4/8/8/8…s with ±20% jitter, capped at a 120 s total window, then `DISCONNECTED` —
  matching PROTOCOL §10 verbatim, unmodified by this phase.
- **Fresh clock sync after reconnect.** `ControlSessionManager.promote()` calls `clock.reset()`
  unconditionally on every connection that wins duplicate-connection arbitration — both the first
  connect and every reconnect share this one call site — and the estimator's `ready` state is
  unreachable until a fresh sample burst completes. Every Phase 5 scheduling path already reads the
  live estimate rather than caching it, so this held for a second (or Nth) generation exactly as it
  does for the first, with nothing to fix.
- **Voice/coexistence continuation across reconnect.** ADR-020 rule 23 (a negotiation belongs to the
  control lifetime that established it) and ADR-027 rule 26 (coexistence effects are generation-bound)
  already generalize to reconnect without modification: `SessionCoordinator.attachVoice()`'s
  `controlAuthenticated(authGeneration)` call on every `Connected` — including reconnects — is
  ADR-020 Amendment A11's existing consented-reconnect rebuild, and `beginLifetime` starts a fresh
  ADR-027 coexistence generation the same way. Nothing Phase-7-specific was needed here.

## Alternatives rejected

- **A second reconciliation path for `STATE_SNAPSHOT`'s playback/queue halves.** Rejected in favor of
  reusing `adoptSnapshot`/the playback-restore path wholesale — a second path is a second place for
  provenance rules to be re-litigated and to drift.
- **Threading a real `transfer_id` up from `TransferManager` into `STATE_SNAPSHOT.transfersInFlight`.**
  Rejected as unused complexity: V1 never resumes a transfer, and the receiver's existing
  discard-and-re-request behavior on every session boundary already does not depend on this field's
  contents.
- **Gating manifest refresh only on the pre-existing unconditional `Connected` trigger, with no
  revision comparison in `ResyncCoordinator`.** Rejected as a resource-bound violation (brief's "no
  unnecessary manifest retransmission"): it would miss a leader's catalogue moving a second time
  mid-ride, between the initial reconnect refresh and a later desync-triggered snapshot.
- **A `STATE_SNAPSHOT`-specific bound-writer generation record, matching `VOICE_*`/Playback's
  stricter pattern (ADR-024 Amendment A2 / ADR-020 Amendment A9).** Rejected as unnecessary at the
  time of initial implementation — **and that rejection was wrong. See Amendment A1, Blocker 1.**

## Amendment A1 — 20 September 2026 — independent review: two confirmed blocker groups, both fixed

**Status:** Accepted · appended, nothing above rewritten except the one reversed rejection noted
inline above. An independent review of this ADR's initial implementation found two confirmed,
reachable blocker groups before physical qualification could even be considered — both reproduced
against the unmodified pre-fix sources first, per this codebase's standing audit discipline, and both
fixed on both platforms before this amendment was written.

### Blocker 1 — outbound `STATE_SNAPSHOT`/`STATE_REQUEST` were not generation-bound to the actual write

**The defect.** This ADR's original "alternatives rejected" section reasoned that `STATE_SNAPSHOT`
didn't need the bound-writer pattern ADR-020 Amendment A9 built for `VOICE_*`, because "the send-time
re-proof immediately before admission plays the same role Manifest/AUDIO_STATE's existing pattern
already relies on." That reasoning held for the *admission* proof (`stillCurrent`/`stillCurrentNow`
before `enqueueOutbound`) but not for what happened after: the outbound dispatch
(`SyncPlaybackCoordinator.sendFrame`'s `.Resync`/`.resync` case) discarded the `generation` its caller
already had and called `ResyncRelay.send(message)` — soon Android's `ResyncCoordinator.triggerRequest`
did the same for the follower's `STATE_REQUEST` — both resolving the authenticated writer *live*, at
the moment of the write, from an unbound `authenticatedWriter` supplier. Because everything between
admission and the actual socket write suspends (the outbound queue's single consumer, the write lock,
the flush), a frame admitted under generation A could be written on generation B's connection if A
retired and B authenticated in that window — the exact class ADR-020 Amendment A9 fixed for `VOICE_*`
and ADR-024 Amendment A2 fixed for Playback, reopened here because this ADR's original authors
(rightly) noted resync's *admission*-time check already existed, and (wrongly) concluded that made a
bound writer redundant. It does not: admission answers "was this frame's decision current when it was
made," and the bound writer answers a different question, "is the connection this byte stream is about
to go out on still owned by the generation that made that decision" — the same distinction ADR-024
Amendment A2 draws between commit and send-success.

**The fix**, on both platforms: `ResyncRelay.send`/`ResyncChannel.send` now takes the authorising
`generation` as a parameter and resolves the writer from the same generation-bound supplier
`VoiceSignalRelay` already uses (Android: `authenticatedWriterFor: (Long) -> AuthenticatedFrameWriter?`
over the one immutable `AuthenticatedConnection` record; iOS: the equivalent bound-writer resolution
ADR-020 Amendment A9 built) — refusing and counting on a generation mismatch rather than resolving
whatever connection happens to be live. `SyncPlaybackCoordinator`'s outbound dispatch and
`ResyncCoordinator`'s `STATE_REQUEST` send both now pass the generation through instead of discarding
it. No duplicated socket-ownership logic was added — this reuses the existing mechanism outright.

**Regressions** (both platforms, real `ControlSessionManager` pairs, no sleeps): a `STATE_SNAPSHOT`
admitted under generation A whose writer resolution is paused until generation B authenticates is
refused and never reaches B's wire; a queued A item that survives to the outbound consumer after A has
retired is refused without wedging the queue; a B-authorized item sent afterward succeeds normally.
The same trio for the follower's outbound `STATE_REQUEST`.

### Blocker 2 — reconnect/resync did not reliably reconstruct authoritative playback

Five linked defects, all in the same reconciliation machinery, found and fixed as one coherent piece
rather than isolated patches. **All five are Phase 5 defects that predate Phase 7** — this ADR's own
`onStateSnapshot`/`adoptSnapshot`/`onPeerPlaybackState` reuse is what made Phase 7 the first caller to
exercise them under the specific conditions (a null `timeline`, a not-yet-ready fresh clock) that a
reconnect reliably produces and an ordinary wire `PLAYBACK_STATE` rarely does. Recorded here because
Phase 7's brief is what surfaced them and what an independent review of *this* ADR is what found them,
but the underlying machinery — `resetForNewSession`, `applyPeerPlaybackState`, `restoreFromPlaybackState`,
`drainDeferredEvents` — is ADR-024's, so the same fixes are also recorded as **ADR-024 Amendment A9**,
which is the fuller technical account; this section is the summary from Phase 7's side.

1. **The leader forgot its own current track across a link loss** (`emitStateSnapshot` read the
   session-clock-scoped `timeline`, which `resetForNewSession` correctly clears, for content
   identity it should never have needed from there). Fixed with a new ride-segment-scoped
   `PlaybackIdentity` (trackHash + queueItemId only — position and playing state were always
   correctly read live from the player) that survives a control-lifetime boundary, the same
   principle already applied to capture (rule 17) and the shared queue (ADR-024 Amendment A8).
2. **A normal reconnect's snapshot silently skipped restoration** because the routing decision used
   the ingress-overflow-specific `playbackDesynchronized` flag alone, and `resetForNewSession` clears
   that flag and `timeline` together — so a reconnect (which needs exactly the same full restoration
   an ingress desync does) fell through to the "already synced, just re-anchor" branch and found
   nothing to re-anchor. Fixed by restoring whenever there is no live anchor to update
   (`playbackDesynchronized || timeline == null`), not only on the narrower flag.
3. **A snapshot arriving before the fresh clock (or before locally-available content) was ready
   silently dropped**, with the desync obligation already cleared by the caller before the drop
   happened. Fixed by holding it in the same `deferredEvents`/drain machinery already used for
   commands whose clock isn't ready, extended to also gate on content availability (a real,
   independently-found gap during content-unavailable testing: iOS's first pass checked clock
   readiness but let a missing-content case fall through to `applyPlay`'s own unheld transfer
   request, which retained nothing — a later-arriving transfer had no snapshot left to apply it to).
   The desync/reconciliation obligation now clears only once restoration genuinely completes.
4. **The outer coordinator couldn't distinguish applied from deferred from rejected.** A new
   `StateSnapshotOutcome` (`applied`/`deferredClock`/`deferredContent`/`rejectedStale`/`rejectedRole`)
   flows from `onStateSnapshot` up to `ResyncCoordinator.handleStateSnapshot`, which now gates
   `pendingRequestGeneration` clearing, manifest-refresh triggering, and `lastOutcome` on the genuine
   outcome rather than assuming receipt equals reconciliation.
5. **A leader's ride-segment `PlaybackIdentity` survived past its own ride's end**, found while
   building the second-ride regression for this fix: `leaveSynchronizedMode()` did not clear it, so a
   stale identity from Ride 1 could re-enter Ride 2's own reconnect resync. Fixed by clearing it in
   `leaveSynchronizedMode()`, before teardown, matching the real End Ride UX order.
   **Corrected by Amendment A2 (Blocker C):** clearing it there is right, but at the time of
   this amendment *no production path reached that call from End Ride* — the order was real
   only in the tests that called it by hand. A2 supplies the production wiring. The finding
   above stands as written; this note records what it did not yet establish.

**Command-sequence floor (this ADR's own §14 question): investigated, no behavior change.**
`resetForNewSession` resets `nextSeq`/`lastAppliedSeq` symmetrically on both sides at every control
boundary; a fresh generation's snapshot truthfully reports a fresh floor. This is safe: ADR-025's
generation provenance already refuses any frame from a retired generation regardless of the
`command_seq` it carries, so the two generations' sequence spaces never actually have to agree with
each other, and both sides reset together, so there is no divergence to produce. Confirmed by a
regression proving a low post-reconnect `command_seq` is accepted normally rather than rejected as
stale against the pre-reconnect high-water mark.

**Sections 22/23 (content-unavailable, route-transition non-regression): confirmed, not reworked.**
A content-unavailable snapshot is a legitimate `deferredContent` outcome, resolved by the existing
Phase 4 transfer-request path and this amendment's own hold/drain extension — no second transfer
mechanism, no infinite retry loop. A reconnect's restoration runs through the same single
`applyPlay`/select-load-seek path regardless of `route_state`, spends no hard-seek budget by itself,
and adds no second route-state consumer — Phase 6's drift-suppression-during-transition rule is
unaffected because nothing here bypasses `DriftController`.

**No wire change.** Every fix is local reconciliation-state discipline and outbound-writer binding;
`protocol/vectors/resync-messages/` is unchanged, because none of these five defects were about what
crosses the wire — only about what each side truthfully constructs before sending, and honestly does
after receiving.

## Amendment A2 — 20 September 2026 — independent review round 3: three confirmed blockers, all fixed

**Status:** Accepted. Extends this ADR and [ADR-024](ADR-024-synchronized-playback-integration.md);
supersedes nothing. **No wire change; no vector change** — all three defects are local reconciliation
and lifetime discipline, exactly as Amendment A1's were.

A second independent review of the PR accepted Amendment A1's Blocker 1 fix (the generation-bound
resync writer) and found three more. All three were reproduced against unmodified production on both
platforms before anything was changed, and each reverts to failing in isolation.

### Blocker A — a deferred reconciliation could deadlock while desynchronised

`drainDeferredEvents` began with a blanket
`if (playbackDesynchronized || queueDesynchronized) return`. Amendment A1 then made a reconciliation
snapshot that cannot restore yet — no fresh clock, or no local copy of the authoritative track —
*retained* in the very same `deferredEvents` stream that guard protects. The two are a cycle:

- `playbackDesynchronized` clears **only** when the retained authoritative reconciliation actually
  applies (`applyPeerPlaybackState`), and
- that snapshot can apply **only** from the drain, which the same flag stopped.

So the reachable production sequence — ingress overflow, `STATE_REQUEST`, a valid `STATE_SNAPSHOT`
whose restore needs a clock or a transfer — left the follower desynchronised **permanently**, however
promptly the precondition resolved. A1's own regressions missed it because they exercised the
deferral on a follower that was not *also* desynchronised, which is the narrower of the two states.

**The fix is a per-item rule, not the removal of the guard.** The original guard exists to stop
*incremental* authority being applied against state we have declared untrustworthy (ADR-024
Amendment A1 Finding C), and that stays. But an **authoritative state frame** — a `QUEUE_SNAPSHOT`,
or the reconciliation `PLAYBACK_STATE` a `STATE_SNAPSHOT` produces — is not incremental: it names its
own instant (PROTOCOL §5 rule 2) and it *is* the repair. Blocking it on the condition it exists to
clear is the deadlock. Nothing else is bypassed: generation, role, clock readiness, content
availability, ordering and queue authority are all still proved, unchanged, below that point.

**Liveness needed a second half, and it is the same rule read honestly.** A guard alone would still
deadlock whenever a held incremental command sat *in front of* the repair snapshot, because stepping
past it would reorder the authoritative stream (A1 Finding D) — and the drain must never do that. The
answer is that A1 Finding C's refusal rule was only ever applied to a command **arriving** while the
latch was closed; a command already **held** is untrusted for precisely the same reason and could
never have been applied. So the latch now refuses both: `latchDesynchronized` — one function, the
only writer of the two flags on a follower — sets them *and* refuses the held incremental commands,
counting them in `refusedHeldCommandCount` rather than dropping them silently. With that, an
incremental command can never be at the head while the latch is closed, and the drain's remaining
command branch is fail-closed defence for a shape unreachable by construction rather than by
assumption.

Two existing audit regressions per platform had baked the old behaviour in as an invariant
(`deferredCommandCount == 1` after an overflow) and were corrected to the new, stated semantics.

### Blocker B — iOS deferred reconciliation could never report completion

Amendment A1 correctly made `.deferredClock`/`.deferredContent` clear the **wire** obligation
(`pendingRequestGeneration`): a snapshot arrived, so there is nothing left to ask for. It then had
`onReconciliationApplied` complete the **reconciliation** obligation only if
`generation == pendingRequestGeneration` — which, by that point, is always `nil`. `.snapshotPending`
could therefore never become `.reconciled`, on either precondition, however promptly it resolved. One
field was carrying two different facts.

They are now two. `deferredReconciliation` records the accepted-but-unreconciled snapshot and, with
it, **immutable ownership**: the generation that delivered it, compared and never reconstructed from
whatever is live when the completion callback runs (rule 20's rule, applied to a local obligation
rather than to a frame). A successor's obligation supersedes a predecessor's; a predecessor's
completion finds the wrong owner and is inert.

Two further points that the fix made explicit:

- The obligation is **monotonic**. `onStateSnapshot` suspends, so an older generation's outcome can
  arrive after a newer obligation has been recorded; an older generation may never displace a newer
  one, or the newer could never complete.
- **Android's mechanism was replaced too, and for a different defect.** It inferred completion from
  `pendingPlaybackReconciliationGeneration` going `nil` in the diagnostics flow, which cannot
  distinguish "the obligation converged" from "the obligation was **discarded**" —
  `leaveSynchronizedMode()` legitimately does the second and would have reported a reconciliation
  that never happened as `RECONCILED`. Both platforms now use the same explicit,
  generation-carrying signal raised only where the apply genuinely succeeds.

### Also found while building Blocker A's regression — an unbounded `STATE_REQUEST` storm

A follower that is desynchronised **and** holds a deferred reconciliation asked again on every
`SyncPlaybackDiagnostics` emission: `ingressDesynchronized` stays true until the retained snapshot
applies, and `StateResyncGate` cannot refuse the repeat because the *wire* request was legitimately
completed by that very snapshot. The regression exhausted the JVM heap before this was closed; on a
real socket it is a flood on the control plane.

**The first fix for this was wrong, and CI found it.** Suppressing the retrigger while an obligation
is outstanding also suppresses a genuinely *new* desync event — iOS's
`ReconnectResyncStressTests` correctly failed, waiting for a `desyncRequestCount` that could no
longer move. The real cause is a platform divergence nobody had named: **Android's desync trigger was
level-triggered where iOS's was edge-triggered.** Android collected `SyncPlaybackDiagnostics` and
acted whenever `ingressDesynchronized` was *true*; iOS raised a callback once, at the latch. With
Blocker A's retained reconciliation keeping the flag set, level-triggering had nothing left to dedup
against.

So the storm is removed at its source. Both platforms now raise one explicit
`onDesynchronizedTrigger` per latch event, from `latchDesynchronized` — which also means all three
latch sites are covered on both, where iOS previously raised it only from `onIngressOverflow` — and
the suppression is deleted. `StateResyncGate` dedups a repeat while a request is genuinely
outstanding, which is all it ever needed to do.

This is this pass's own instance of the standing lesson: **the freshest fix is the least-audited code
in the repository**, and a fix's own regression can pass while the fix is wrong in a way only another
suite reaches.

### Blocker C — production End Ride did not end ride-segment playback authority

Amendment A1 introduced ride-segment `PlaybackIdentity` and cleared it in `leaveSynchronizedMode()`.
Both are right. What was missing is that **no production path connected End Ride to that call**: the
real button reaches `SessionCoordinator.endRide()`, which produced `RIDE_ACTIVE -> CONNECTED` and
nothing else, and the only production caller of `leaveSynchronizedMode()` was "Play locally". The
End Ride *order* existed only in tests that called it by hand — the "a test proves an order
production does not" shape this repository's standing lesson already names. So ride 1's track was
still what a leader's `STATE_SNAPSHOT` reported in ride 2, before ride 2 had established any playback
authority of its own.

**End Ride is not End Session, and the fix does not make it one.** Nothing here reaches ADR-026's
terminal teardown: the control connection, the pairing and the peer session stay alive, and local
music keeps playing exactly as a Phase 3 ride (FR-025). What ends is the ride segment's
synchronisation authority — which is precisely what `leaveSynchronizedMode()` already means, so this
is that one call plus a lifetime proof, never a second teardown path and never a second player owner.

**The ride is a third lifetime**, beside the authenticated control generation and the playback epoch,
and it gets the treatment this codebase gives every other one. A strictly increasing **ride epoch** is
assigned on both Start Ride and End Ride, synchronously, before any scheduling hop; `endRideSegment`
compares it and refuses anything not newer, counting `staleRideLifecycleCount`. This is ADR-024
Amendment A5's rule ("a local mutation that has been authorised is not a local mutation that may
still happen") applied to the ride, and it is load-bearing on iOS specifically, where `endRide()`
cannot `await` and the cleanup therefore crosses a hop that ride 2 can start inside.

Ownership placement differs by platform, deliberately:

- **Android** wires `SessionCoordinator` to a narrow `RideSegmentOwner` port, implemented by
  `SyncPlaybackCoordinator` through an adapter in `AppContainer` — the same shape
  `ForegroundServiceController` already uses, so `SessionCoordinator` ends a ride without gaining a
  Phase 5 dependency. `endRide()` proves the FSM transition first and then calls it synchronously,
  with no suspension in between.
- **iOS** puts the same decisions in `RideSegmentLifecycle`, inside `RideLinkPlatform`. This is a
  **disclosed test-infrastructure limitation, not a design preference**: `ios/RideLink.xcodeproj`
  declares one application target and no unit-test bundle, so `SessionCoordinator.endRide()` is
  unreachable from every test in this repository. Putting the ride lifetime's decisions in the
  package makes them testable at their real seam and leaves `SessionCoordinator` holding two calls
  with no logic in them. Android's regression exercises the genuine `SessionCoordinator.endRide()`
  entry point, so the production ordering is proved end to end on one platform and at the highest
  reachable seam on the other. Delivery is `launchInSession`, never a bare `Task` (rule 21).

### And one more, found by CI running this pass's own new ride regression

**End Ride does not move the control generation — and `applyPlay` proves only that.** The session,
the pairing and the control connection all stay alive on purpose (that is the whole distinction
between End Ride and End Session), so every ownership proof `applyPlay` takes is satisfied across a
ride boundary. `content.resolve` suspends in the middle of it. An apply authorised before End Ride
therefore resumed afterwards and wrote `currentPlaybackIdentity`, the timeline and a fresh playback
epoch straight back over the state `leaveSynchronizedMode` had just retired — ride 1's track reported
as ride 2's truth by a different route than Blocker C's, and "Play locally" resurrecting a
synchronised timeline by the same one.

`synchronizedModeEpoch` is bumped by **every** exit from synchronised mode and by nothing else;
`applyPlay` captures it before its first suspension and compares it — never re-reads it — adjacent to
each write. That is ADR-024 Amendment A5's rule applied to the third lifetime. It is kept separate
from `lastRideLifecycleEpoch`, which is `SessionCoordinator`'s to assign and must stay comparable
with it.

**The regression had to be made deterministic twice over, and that is the part worth keeping.** A
first version gated the content resolve by *counting* calls — and passed **vacuously**, by parking on
a harmless frame, because how many resolves run before `applyPlay`'s depends on scheduling. The gate
now parks on a *condition the test states* ("a `PLAY` is already on the wire"), which pins the frame
by construction, and the test asserts both halves of that pinning before it does anything else. The
lesson is the one this repository already records about regressions: **audit what a regression
supplies as carefully as what it asserts.**

Separately, the 50-cycle ride test's own helper waited a fixed number of scheduling yields for a
leader's play to converge; it now waits on the condition. A larger fixed budget would only have made
the flake rarer, which is precisely what this brief's §16 forbids.

### One more found by this pass's own fresh-fix audit

`drainDeferredEvents` suspends on the clock estimate and on content resolution, and its very next
statement is an index-based `removeFirst()`. Two things can legitimately shorten the held stream
inside those windows, because the drain is reached from the inbound consumer, the retry cadence
**and** the content-availability callback: `applyPeerPlaybackState`'s supersede rule (pre-existing)
and, new in this pass, `latchDesynchronized`'s refusal of held commands. Removing by index afterwards
would take whatever had moved into position 0 — a different authoritative frame. Both platforms now
end the pass when the stream moved; the retry cadence re-reads the real head and re-proves everything
for it. Ending a pass is a retry, never a wedge.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.

## Amendment A3 — 20 September 2026 — independent review round 4: two confirmed blockers, both fixed

Round 3's own fixes were reviewed and two lifecycle blockers were confirmed in them. Both were
reproduced against unmodified production on both platforms before anything was changed, and both are
about the same missing distinction: **an identity is not the same thing as a lifetime**.

Round 3 gave the ride an epoch and the reconciliation a generation, and then asked each of them a
question it could not answer. This amendment gives each the question it can.

### Blocker 1 — an accepted End Ride could be superseded before its cleanup ran, and then never ran

`SessionCoordinator.endRide()` cannot `await`, so the ride-segment cleanup crosses a scheduling hop.
Round 3 closed the obvious half of that: a cleanup that arrives after a successor ride has begun must
not clear the successor's state. It did so by refusing any End Ride whose epoch was no longer the
current one — and that single statement bought one property while breaking the other.

A ride boundary owes two properties, and they are **not** the same property:

- **Property A** — ride 1's cleanup must never destroy ride 2's state.
- **Property B** — ride 1's state must never survive into ride 2 merely because its cleanup was
  delayed.

`startRide` deliberately establishes nothing — synchronised playback is legitimately usable from
`CONNECTED`, before any ride begins, and a ride starting must not disturb it. So a Start Ride pressed
before ride 1's cleanup ran did nothing except **bump the epoch**, which made that cleanup "stale".
Ride 1's `currentPlaybackIdentity` was then still standing, and because ride 2 had established
nothing of its own to overwrite it, it was the only thing ride 2's first `STATE_SNAPSHOT` had to
report. That is precisely the defect round 3's Blocker C existed to remove, reached from the other
side of the same race.

**The fix is not removing the epoch check**, which would be strictly unsafe: a genuinely late cleanup
released after ride 2 owns track Y would then clear Y. The fix is that the boundary compares against
the right thing.

`SyncPlaybackCoordinator` now records **`rideAuthorityEpoch`** — the ride epoch under which the live
ride-scoped synchronisation authority was *established*, stamped at exactly the three places that
establish it (a track becomes authoritative in `applyPlay`; the leader's authoritative "nothing
loaded" is adopted in `applyStep`'s and `restoreFromPlaybackState`'s nil branches) and reset to 0
whenever that authority ends. `endRideSegment` then refuses **only** when `rideAuthorityEpoch >
rideEpoch`: a strictly newer ride already owns something of its own. In every other case what is
standing belongs to this ride or an earlier one, and ending the ride is exactly the instant it must
go.

Both properties hold by construction, and neither is bought by weakening the other:

- Property A: ride 2's Y is stamped with ride 2's epoch, which is newer, so the late boundary is
  refused (`staleRideLifecycleCount`).
- Property B: ride 2 having established nothing means the live authority is ride 1's, and the
  boundary clears it.

`lastRideLifecycleEpoch` is still what `beginRideSegment` keeps monotonic; it is simply no longer
asked a question about ownership that it cannot answer. `RideSegmentLifecycle.endRide` stops deciding
and starts forwarding: only the coordinator can see whose authority is standing, so the boundary
always reaches it and `RideBoundaryOutcome` is the answer coming back. The type still owns ride-segment
cleanup **ordering and ownership only** — it is not a second `SessionFsm`, holds no session or
navigation state, and never decides whether a ride may start or end.

Android's `endRide()` performs its cleanup synchronously, so the *window* is iOS's; the rule is
mirrored anyway, because "this ordering probably cannot happen here" is not a guarantee.

### Blocker 2 — End Ride discarded the inner reconciliation while the outer obligation survived

`SyncPlaybackCoordinator` owns the retained authoritative snapshot; `ResyncCoordinator` owns the
`STATE_SNAPSHOT` metadata needed to later report `RECONCILED` with the right `command_seq` and
`manifest_revision`. That separation is right, and round 3 made both halves explicit rather than
inferred. What it did not do is connect them in the *failing* direction.

`leaveSynchronizedMode()` clears `deferredEvents` outright — correct, the ride that asked for the
reconciliation is over. Nothing told the outer owner. And **End Ride deliberately does not move the
authenticated control generation**: the connection, the pairing and the session all stay alive on
purpose. So the stale outer obligation kept a generation that was still live, and
`onReconciliationApplied(generation)` — keyed on the generation alone — let the *next* genuine
reconciliation under that same generation complete it, publishing ride 1's `command_seq` and
`manifest_revision` as a reconciliation that never happened, manifest-refresh side effects included.

The existing B→C tests could not reach this. They move the generation; this defect exists precisely
because the generation does **not** move.

Two things were needed, and both are now explicit:

1. **An obligation identity beyond the generation.** Each accepted snapshot gets an immutable,
   process-local `id` (strictly increasing, from 1, never on the wire, never derived from live
   state). It travels *into* the retained anchor — `DeferredEvent.playbackState(…, reconciliation:)`
   — and comes back out with the terminal result. Both `id` and generation are compared; neither is
   re-derived.
2. **An explicit cancellation signal.** `onReconciliationCancelled(obligation, generation)` is raised
   from the **one** place the held stream is thrown away — a new `discardDeferredEvents()` through
   which all four callers now go: `leaveSynchronizedMode` (End Ride, "Play locally"),
   `resetForNewSession` (a control-lifetime boundary, including a terminal teardown's link loss),
   `failClosedOutbound`, and `drainDeferredEvents` finding its own generation retired. A popped
   anchor whose apply is refused reports it too; a re-deferral is the one non-terminal answer, and it
   keeps the same id.

Applied and cancelled are the two terminal results; they are mutually exclusive, and **only applied
may produce `RECONCILED`**. Nothing is inferred from an absence — round 3 already had to remove one
inference of that shape, and this is that rule applied to the obligation rather than to the flag.

**The obligation is recorded before the suspending apply, and that ordering is load-bearing in both
directions.** `onStateSnapshot` suspends, so a cancellation raised inside that window must find
something to cancel; and the cancellation callback hops to the coordinator's own executor, so it can
equally arrive after `handleStateSnapshot` resumes. Recording up front makes both orders converge:
the cancel clears the record whenever it lands, and every branch acts only if its own id is still the
recorded one. That identity check also **replaces** round 3's `supersededByNewerObligation` generation
comparison outright — same protection, by comparison of two recorded owners rather than two
generations, and it now covers two obligations that share a generation as well.

Diagnostics gain `ResyncOutcome.CANCELLED` / `.cancelled`. Local state only; **no wire change**, and
no vector moved — this is a local obligation's lifetime, not a distributed decision.

### §17's audit of round 3's `synchronizedModeEpoch` — two more, both fixed

Round 3 added `synchronizedModeEpoch` to stop an apply suspended across End Ride from writing its
state back, and applied it to `applyPlay` **by re-reading the field at that function's own entry**.
That is right when `applyPlay` *is* the operation and wrong when it is a later step of one. Sweeping
every apply path with the question the review asks — *could this work have been authorised before End
Ride and resume after it without the control generation changing?* — found two reachable instances:

- **`applyStep` had no ride proof at all, and it is the one that could stop the music.**
  `stillCurrent` suspends; End Ride does not move the control generation; and the `selected == nil`
  branch calls `epoch.begin()`, minting a *fresh, live* playback epoch over the one
  `leaveSynchronizedMode` had just superseded, then schedules `[.stop, .clearSelection]` — which the
  new token makes owned, so it reaches the player. End Ride's whole contract is that local playback
  continues (FR-025). It stopped it, and cleared the local selection with it.
- **`applyPlay` reached *through* `applyStep` or `restoreFromPlaybackState`** captured the epoch at
  its own entry, which by then was already the post-End-Ride value — so its guard compared the new
  value with itself and passed, re-establishing `currentPlaybackIdentity`, the timeline and a fresh
  playback epoch for a ride that was over. Round 3's own defect, one function further along.

The ride lifetime is now **captured once, where the operation is authorised, and threaded** —
`applyAuthoritative` for every authoritative command, `applyPeerPlaybackState` for every
reconciliation — and every later step compares it rather than re-reading. That is the rule the
*generation* already follows (CLAUDE.md rules 19/20) applied to the third lifetime: an operation's
authorising ride travels with it, and a later stage never asks what the ride is *now*. Android's
windows here are narrower (`stillCurrent` and `estimate` are synchronous, so the suspensions do not
exist), which is exactly why the shape is mirrored rather than left to that accident.

**Deliberately not stamped:** the *admission* stage (`onInboundCommand`, `admitAuthoritativeCommand`)
writes `lastReceivedSeq`/`lastAppliedSeq`/`deferredEvents`, which are control-generation-scoped
ordering bookkeeping that `leaveSynchronizedMode` correctly does not reset. A new inbound authoritative
command arriving *after* an End Ride re-enters synchronised mode on this device — unchanged, and
correct: the peer is still riding, and this is not work authorised by the ride that ended.

### Two pre-existing platform divergences, audited and deliberately unchanged

- iOS performs `STATE_SNAPSHOT` manifest bookkeeping on **acceptance**; Android performs it inside
  `completeReconciliation`. Both satisfy the property that matters here — a cancelled obligation
  triggers no refresh — and changing either is a behaviour change outside this review's scope.
- Android publishes `lastSnapshotCommandSeq`/`lastSnapshotManifestRevision` only from
  `completeReconciliation`; iOS also publishes them on the pending branch. Diagnostics only. Android's
  regressions therefore assert the snapshot's `command_seq` read from the **wire**, which is the
  stronger claim.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.
