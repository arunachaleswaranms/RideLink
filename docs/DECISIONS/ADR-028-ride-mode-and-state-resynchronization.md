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

### This amendment's own fix needed a fix, and CI at the exact head is what found it

The standing lesson again, on this pass's own code. Two versions of one mistake were made while
adding Blocker 2's obligation identity and §17's ride guard, and both produced the same symptom: a
`STATE_SNAPSHOT` that genuinely arrived for the live generation left `requestPending` **true with
nothing that could ever clear it**, and `ReconnectResyncStressTests`' 100-cycle reconnect sweep timed
out waiting for it to drop. The local suites were green; CI at the exact head was not.

1. **The obligation-identity guard was placed before `StateResyncGate.onSnapshotObserved`**, which
   made the **wire** obligation's clear conditional on the **reconciliation** obligation surviving the
   apply. That is precisely the conflation round 3's Blocker B existed to remove — re-created by the
   fix written to strengthen it. The clear now happens first, unconditionally for any outcome that
   means "a snapshot for the live generation arrived", and the identity guard scopes only what follows.
2. **A ride-lifetime refusal was reported as `.rejectedStale`**, which by §21 must *not* clear an
   outstanding request, because such a snapshot never answered it. But a snapshot refused because the
   *ride* ended **did** arrive for the live generation. The two are different facts and now have
   different outcomes: `StateSnapshotOutcome.rejectedRide` / `REJECTED_RIDE` satisfies the wire round
   trip and cancels only the reconciliation.

Building the regression for it exposed a third, smaller thing worth recording: **Android captured the
ride lifetime one function later than iOS.** iOS's content pre-check lives inside
`applyPeerPlaybackState`, so capturing there is capturing before the operation's first suspension;
Android's lives in `onPeerPlaybackState`, one level up, so the same capture site was *below* the
suspension and read a post-End-Ride value. Android now captures in `onPeerPlaybackState` and threads
it down, which is what "captured where the operation is authorised" actually means on that platform.
The divergence was invisible until a test tried to park there.

**And the first regression written for this was vacuous** — it armed the content gate with a
`{ true }` predicate, which caught an unrelated resolve, so the snapshot completed normally *before*
End Ride ran and the test passed against the broken code. It is now pinned by construction: iOS parks
on the generation gate immediately after the capture, Android on the follower's content gate, and both
assert the resulting outcome rather than merely that something parked. That is this repository's own
"counting calls does not pin it" lesson, earned again.

One behaviour is deliberately **not** changed and is now asserted so it is not mistaken for a bug: a
`STATE_SNAPSHOT` that *arrives* after End Ride, under the same still-live control generation, is
ordinary new authoritative traffic and **is applied** — exactly as a newly arriving `PLAY` is, since
`onInboundCommand` sets `syncEnabled` back to true. The ride lifetime refuses work the ended ride
*authorised*; it is not a filter on a peer who is still riding.

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

## Amendment A4 — 20 September 2026 — independent review round 5: two confirmed blockers, both fixed

Status: **Accepted.** Round 4's own fixes were audited by an independent review, which returned
**REQUEST CHANGES — DO NOT MERGE** with two blockers. Both were reproduced against the unmodified
head (`b70a11e`) before anything was changed, both are fixed, and the regressions that reproduce
them are in the suite. No wire change; no vector moved.

**The standing lesson of this pass, and it is round 4's own lesson turned one notch:** round 4
taught that *an identity is not a lifetime*. Round 5's two blockers are what happens when the
lifetime is right and something about the *value* is not — an owner read a beat too early, and a
result collapsed three different failures into one. Both are the same shape: **a fact was
reconstructed at a moment that could not know it.**

### Blocker 1 — the ride that *owns* newly established authority was read before the accepted ride was installed

Round 4 introduced `rideAuthorityEpoch`: an End Ride boundary refuses only when a **strictly newer
ride has established authority of its own**, which is what makes both ride-boundary properties hold
at once (A: a late cleanup never destroys ride 2's state; B: ride 1's state never survives into
ride 2 merely because its cleanup was delayed). That rule is correct and is unchanged.

The **value** it stamped was not. `recordRideAuthority()` read `lastRideLifecycleEpoch`, and the only
thing that could move that field was a successful `SyncPlaybackCoordinator.beginRideSegment(…)` —
which reached the coordinator across an actor hop, because `SessionCoordinator.startRide()` handed it
to `launchInSession`. So "the ride `SessionFsm` has accepted" and "the ride the one owner of
ride-scoped authority knows about" were two different facts with a window between them:

```
ride 1 establishes X             rideAuthorityEpoch = 1
End Ride accepted, epoch 2       cleanup parked in launchInSession
Start Ride accepted, epoch 3     beginRideSegment(3) parked in launchInSession too
ride 2 establishes Y             recordRideAuthority reads 1  ← the defect
End Ride(2) finally runs         rideAuthorityEpoch(1) <= 2, so it cleared Y
```

That is the invariant this repository keeps relearning, violated inside the fix written to honour
it: ownership was reconstructed from a mutable live value that had not caught up, rather than being
established where the decision was made.

**Every round-4 regression forced the safe ordering and could not see it.** Each ran
`lifecycle.startRide(epoch:)` *before* ride 2 played, which is the ordering production does not
guarantee — the "a test proves an order production does not" shape this repository's standing lesson
already names, appearing for the third time.

**The fix removes the window rather than widening a comparison.** `RideEpochBox` (both platforms) is
a small lock-protected counter whose `next()` mints **and publishes** the epoch in one step,
synchronously, on the main actor/thread, the instant the FSM accepts a Start Ride or an End Ride and
before either hands anything to a continuation. `recordRideAuthority()` reads `rideEpochs.current`.
`beginRideSegment` and `RideSegmentLifecycle.startRide` are **deleted**: once the epoch is published
there is genuinely nothing left for a Start Ride to install, because a Start Ride establishes no
synchronisation authority at all — and a Start Ride that defers nothing cannot be overtaken. The box
also removes `lastRideLifecycleEpoch`, which was a second mirror of one fact.

**Why reading a live value in `recordRideAuthority` is not the same defect.** The rule is "do not
re-read a mutable live owner *later* and use it to label work authorised *earlier*". This call does
not label earlier work: it labels *this write*, at the instant of the write, and the question
`endRideSegment` asks afterwards is exactly "which ride established what is standing". The two can
only come apart if the ride changed between the operation's authorisation and this write — and every
route from `RIDE_ACTIVE` back to `CONNECTED` (the only state a Start Ride is legal from) is already
proved against on the statement immediately above, with no suspension between:

- `SessionEvent.endRide` is the one direct transition, and it bumps `synchronizedModeEpoch` through
  `leaveSynchronizedMode` — which every caller proves against its captured `rideLifetime`.
- A peer `BYE` or a network loss goes via `RECONNECTING`, which moves the authentication generation —
  which every caller proves with `stillCurrentNow`/`stillCurrent`.
- `reconnectSucceeded` from a ride returns to `RIDE_ACTIVE`, never to `CONNECTED` (ARCHITECTURE §3
  rule 1), so it opens no Start Ride at all.

**Platform difference, stated rather than left as an accident.** Android's `startRide()` already
called through synchronously on the main thread, so the window never existed there — but that safety
rested on an implementation property rather than a stated invariant. Android now mints and publishes
through the same `RideEpochBox`, so both platforms make the same claim for the same reason and
neither depends on a dispatcher. `RideSegmentOwner.beginRideSegment` is replaced by
`RideSegmentOwner.nextRideEpoch()`, and `SessionCoordinator`'s own private `rideEpoch` mirror is gone
for the same "two sources of one fact" reason.

A side effect worth recording: `RideSegmentLifecycle` used to carry the counter itself, so a
`RideSegmentLifecycle` rebuilt against an existing coordinator would have restarted at 1 and had its
`beginRideSegment` refused as stale. Today's `attachSyncPlayback` builds both exactly once, so it was
unreachable; with the counter on the coordinator it is unreachable by construction.

### Blocker 2 — direct snapshot restoration crossing End Ride had the wrong terminal outcome

Round 4 gave a reconciliation an immutable obligation **id** and explicit applied/cancelled
callbacks, and added `REJECTED_RIDE`. All of that is correct and is unchanged. What it did not reach
is the *nested* restoration: a `STATE_SNAPSHOT` can already be inside
`restoreFromPlaybackState -> applyPlay -> content.resolve` when End Ride happens. The ride guard
correctly refuses the write — and then the outer layers mistranslated the refusal.

- **2A, Android.** `applyPlay` returned `Unit`, so `restoreFromPlaybackState` returned
  `StateSnapshotOutcome.APPLIED` unconditionally. A reconciliation the ride had stopped was reported
  to `ResyncCoordinator` as `ResyncOutcome.RECONCILED`, publishing ride 1's `command_seq` and
  `manifest_revision` as a convergence that never happened.
- **2B, iOS.** `applyPlay` returned `Bool` and `restoreFromPlaybackState` mapped every `false` to
  `.deferredContent`. `applyPlay` returns `false` for four different reasons; only one of them is a
  deferral. `.deferredContent` *promises* the outer owner that work is retained and will report a
  terminal result later — on the ride-expired path nothing was retained, so the obligation stayed
  outstanding for the rest of the session with no route to either `Applied` or `Cancelled`, while
  ride 1's `manifest_revision` was published as accepted bookkeeping on the way past.

**The fix is a precise result contract.** `applyPlay` now returns `StateSnapshotOutcome` on both
platforms, and `restoreFromPlaybackState` forwards it:

| Situation | Result |
|---|---|
| authoritative state genuinely established (including a paused snapshot merely loaded) | `APPLIED` |
| content not locally resolvable, snapshot retained | `DEFERRED_CONTENT` |
| clock not trustworthy, snapshot retained | `DEFERRED_CLOCK` |
| authenticated control generation retired | `REJECTED_STALE` |
| ride segment that authorised the reconciliation ended | `REJECTED_RIDE` |

Three invariants are what make the table mean something:

1. **Every `DEFERRED_*` corresponds to actual retained work carrying the same obligation id.**
   `applyPlay` itself retains nothing — retention belongs to the caller that owns an obligation — so
   `restoreFromPlaybackState` appends the anchor to `deferredEvents` (with its `reconciliation` id)
   and starts the drain before returning `DEFERRED_CONTENT`. In practice this is defence in depth:
   `applyPeerPlaybackState`/`onPeerPlaybackState` already proved content available before reaching
   here and hold it there when it is not, so it covers only content disappearing inside the narrow
   window between those two resolves. Both lifetimes are re-proved immediately before the append, in
   ADR-024 Amendment A5's exact pattern — `await stillCurrent`, then the synchronous `stillCurrentNow`
   mirror, then the ride, then the mutation with no `await` between the last proof and the write.
2. **Every terminal cancellation names the exact obligation it cancels.** Unchanged from round 4 —
   `REJECTED_RIDE`/`REJECTED_STALE`/`REJECTED_ROLE` release the obligation by id, and only that id.
3. **Only genuine convergence may produce `RECONCILED`.** That is what 2A broke.

`runOwnedSteps` refusing is classified by asking the two lifetimes directly and synchronously: a ride
that ended inside the pre-roll is `REJECTED_RIDE`. The residue — both lifetimes live and the
*playback epoch* superseded by a newer authoritative `PLAY` — is reported `REJECTED_STALE`. That is
deliberately conservative rather than novel: it must not become `APPLIED`, and a `REJECTED_STALE`
leaves the wire request outstanding, which `StateResyncGate` already dedups and a fresh `Connected`
already re-arms. Both platforms agree.

`ResyncCoordinator` needed **no change on either platform**: it already mapped `REJECTED_RIDE` to
cancellation and `DEFERRED_*` to a retained obligation. The defect was entirely that it was being
told the wrong thing.

### The regressions

Both parked frames are pinned by construction, never by counting alone — this file already records a
vacuous content-gate regression from round 4.

- iOS `RideSegmentLifecycleTests.testAuthorityEstablishedUnderTheAcceptedRideSurvivesThePredecessorsLateCleanup`
  — the production ordering: End Ride takes epoch 2 and parks, Start Ride takes epoch 3, ride 2
  establishes Y, ride 1's cleanup is released last. Asserts identity, diagnostics mirror, timeline,
  `supersededEndRideCount`, and that the leader's `STATE_SNAPSHOT` reports Y. Its Property B twin
  (`…StillClearsRideOneWhenRideTwoHasEstablishedNothing`) is retained unchanged and still passes.
- iOS `…testAnAcceptedRideEpochIsPublishedSynchronously` — the structural half: the coordinator sees
  the accepted epoch before `nextRideEpoch()` returns. A future reintroduction of a deferred install
  fails here, not only in the ordering test.
- iOS `…testFiftyLateCleanupCyclesAtTheProductionOrderingSatisfyBothProperties` — fifty cycles,
  alternating whether ride 2 establishes anything, both properties each cycle.
- Android `ResyncRecoveryTest."an End Ride cleanup applied after Ride 2 has begun still clears Ride 1
  when Ride 2 owns nothing"` and its Property A twin are retained; the epoch source moved under them.
- iOS `ResyncCoordinatorTests.testAnEndRideInsideApplyPlayCancelsTheObligationRatherThanFakingADeferral`
  and Android `ResyncRecoveryTest."an End Ride inside applyPlay cancels the obligation rather than
  reporting APPLIED"` — S1 parked **inside `applyPlay`'s own `content.resolve`**, past
  `applyPeerPlaybackState`'s ride guard, past its clock and content pre-checks and past
  `restoreFromPlaybackState`'s ride guard; End Ride while provably parked; release. Assert S1 mutates
  nothing, is `CANCELLED`, is neither `RECONCILED` nor left permanently deferred, retains nothing,
  clears the wire request, and publishes no bookkeeping; then ride 2 + S2 under the **same** control
  generation, and only S2 reconciles — including against a late terminal signal naming S1.
  - Landing is proved, not assumed. iOS discriminates by resolve ordinal *and* asserts no player
    selection while parked; Android's gate predicate is `lastAppliedCommandSeq == 2`, which becomes
    true only once `applyPeerPlaybackState` has adopted the snapshot's sequence number — after the
    outer pre-check resolve and before `applyPlay`'s — reached by severing the Phase 5 wire so the
    follower's applied sequence and the snapshot's genuinely differ.
- `…FiftyRideCancelledMidApplyCycles…` / `"fifty ride-cancelled-mid-apply cycles complete only the
  live obligation"` — the same, fifty times per platform, fresh harness each cycle.

Each regression was additionally re-proved in isolation by reverting **only** its own fix and
observing exactly its own failure: Android reported `RECONCILED`, iOS reported `snapshotPending` with
obligation 1 still recorded and S1's `command_seq`/`manifest_revision` published; and with
`recordRideAuthority` restored to the deferred-install derivation, the Property A tests failed while
the Property B test still passed — which is the discrimination, not merely a failure.

### This pass's own fresh-fix audit

Every suspension in `startRide`/`endRide`/`endRideSegment`/`recordRideAuthority`/`applyAuthoritative`/
`applyPlay`/`applyStep`/`applyTransport`/`applySeek`/`applyPeerPlaybackState`/
`restoreFromPlaybackState`/`drainDeferredEvents`/`discardDeferredEvents`/`onReconciliationApplied`/
`onReconciliationCancelled`/`ResyncCoordinator.handleStateSnapshot` was re-read against the four
questions (which control generation, which ride, which obligation, and whether each is *carried* or
*reconstructed*). One weakness was found in this pass's own first attempt and fixed before it was
committed: the new `DEFERRED_CONTENT` retention on iOS initially proved only the synchronous
`stillCurrentNow` mirror, when `applyPlay` returns from that branch immediately after
`content.requestTransfer` — a suspension carrying no proof of its own — so it is now the full
`await stillCurrent` + `stillCurrentNow` pair ADR-024 Amendment A5 requires before a mutation.

Nothing else new was found. Two pre-existing divergences remain deliberate and are re-confirmed here:
Android writes `currentPlaybackIdentity` and calls `recordRideAuthority` **before** `content.resolve`
(PROTOCOL §5 rule 4 treats a content-pending `PLAY` as authoritative) while iOS writes them after, so
Android's ride proof runs twice on that path; and the two manifest-bookkeeping placements recorded in
Amendment A3 are unchanged.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.

## Amendment A5 — 20 September 2026 — independent review round 6: one confirmed blocker (two reachable
orderings of it), fixed

Status: **Accepted.** Round 4's own fix (this file's Amendment A4) was audited by an independent
review, which returned **REQUEST CHANGES — DO NOT MERGE** with one remaining blocker: iOS still
reconstructs ride ownership from current live state after asynchronous work has already been
authorised. Reproduced against the unmodified head (`17d905a`) before anything was changed, on both
reachable orderings; both are fixed; the regressions that reproduce them are in the suite. No wire
change; no vector moved.

**The standing lesson turns one further notch, and it is aimed at Amendment A4's own words.** A4's
`recordRideAuthority` doc comment argued at length that reading `rideEpochs.current` live was *not*
the class of defect this file keeps finding, because "every route from `RIDE_ACTIVE` back to
`CONNECTED` is already proved against on the statement immediately above, with no suspension
between." That argument is wrong, and the reason it is wrong is the amendment's own subject: it
treated `synchronizedModeEpoch` moving as synonymous with "an End Ride happened", when the two are
different facts with the same asynchronous gap A4 had just finished closing for the *epoch*, still
open for the *cleanup*.

### The blocker

`SessionCoordinator.endRide()` mints and publishes its ride epoch synchronously (Amendment A4's own
fix), then hands the actual cleanup to `launchInSession`:

```swift
guard applyEvent(.endRide) else { return }
let epoch = rideSegment.nextRideEpoch()               // synchronous — rideEpochs.current moves now
launchInSession { _ in await rideSegment.endRide(epoch: epoch) }   // asynchronous — may run later
```

`rideSegment.endRide(epoch:)` is what eventually calls `SyncPlaybackCoordinator.endRideSegment`,
which is what calls `leaveSynchronizedMode()`, which is the **only** place `synchronizedModeEpoch`
moves for an End Ride. So `rideEpochs.current` and `synchronizedModeEpoch` advance at two different
instants — the FSM's accept and the scheduled cleanup's eventual execution — and every apply path's
existing ride proof (`guard synchronizedModeEpoch == rideLifetime`) only detects the second one.

**Reachable ordering 1 — stale ride-1 work relabelled as ride 2's authority.** An operation admitted
under ride 1 (`applyPlay`, reached from a leader's own committed `PLAY` or a follower's inbound frame
via `applyAuthoritative`) captures `rideLifetime = synchronizedModeEpoch` at its own entry, then
suspends in `content.resolve`. While it is parked: End Ride 1 is accepted (`rideEpochs.current` moves
to the End Ride's own epoch; cleanup parked in `launchInSession`); Start Ride 2 is accepted before
that cleanup ever runs (`rideEpochs.current` moves again). The operation resumes. Its
`synchronizedModeEpoch == rideLifetime` guard still passes — `leaveSynchronizedMode` has not
executed, so `synchronizedModeEpoch` never moved — so it proceeds to write `currentPlaybackIdentity`,
`timeline` and a fresh playback epoch, and `recordRideAuthority()` stamps `rideAuthorityEpoch` with a
**live** `rideEpochs.current` that Start Ride 2 already advanced. Ride 1's stale write is now labelled
ride 2's authority. When ride 1's parked cleanup finally runs, `rideAuthorityEpoch (ride 2's value)
<= rideEpoch (ride 1's)` is false, so the boundary finds what it believes is a newer ride's authority
and leaves the stale write standing — permanently, because nothing else will ever ask the question
again.

**Reachable ordering 2 — genuinely new post-End authority destroyed by its own boundary's delayed
cleanup.** The inverse: End Ride 1 is accepted (`rideEpochs.current` moves to the End Ride's own
epoch; cleanup parked). Before that cleanup runs, genuinely new authoritative state arrives —
legitimate, because synchronised playback stays usable in the CONNECTED gap that follows a ride (a
Start Ride establishes no authority of its own, ARCHITECTURE §3). It is admitted, captures
`rideLifetime` (unchanged, since `leaveSynchronizedMode` still has not run) and is stamped by
`recordRideAuthority()` with the **same** live `rideEpochs.current` value the parked End Ride minted
for itself — the two are indistinguishable at that value. When the parked cleanup runs,
`rideAuthorityEpoch <= rideEpoch` (round 4's comparison) is now `true` for this genuinely new
authority too, and the boundary destroys work it never owned.

Both orderings are the same root cause: `recordRideAuthority()` answered "which ride is current *right
now*" by re-reading `rideEpochs.current` at the moment of the write, rather than carrying "which ride
authorised *this operation*" from the moment the operation was admitted. `rideEpochs.current` is
authoritative for the first question; the second question is provenance, and provenance cannot be
reconstructed from live state after a suspension — CLAUDE.md rules 19/20/23/24/25, restated for the
third lifetime for the second time (Amendment A4 restated it the first time, and restated it
incompletely).

### The fix

**Provenance travels with the operation, not the write.** Every function that can establish or
replace ride-scoped authority — `applyAuthoritative` (the one admission point every `PLAY`/`PAUSE`/
`RESUME`/`SEEK`/`NEXT`/`PREVIOUS` reaches, whether from a follower's inbound frame or the leader's own
committed command via `chainApply`) and `applyPeerPlaybackState` (PROTOCOL §5's reconciliation
anchor, reached directly and from the deferred drain) — now captures a second value in the same first
statement that already captures `rideLifetime`:

```swift
let rideLifetime = synchronizedModeEpoch   // unchanged: catches "Play locally" and a *completed* End Ride
let admittedRideEpoch = rideEpochs.current // new: catches an *accepted* End Ride whose cleanup has not run yet
```

`admittedRideEpoch` is threaded as a parameter through every intermediate function exactly as
`rideLifetime` already was (`applyPlay`, `applyTransport`, `applySeek`, `applyStep`,
`restoreFromPlaybackState`), and every one of those functions' existing `guard synchronizedModeEpoch
== rideLifetime` checks gained a second clause: `rideEpochs.current == admittedRideEpoch`. Since any
accepted Start *or* End Ride moves `rideEpochs.current` synchronously (Amendment A4's own guarantee —
this fix spends that guarantee rather than repeating its mistake), a mismatch here means a ride
boundary was accepted since this operation was admitted, whether or not its cleanup has run — and the
operation is refused (`.rejectedRide`), writing nothing, rather than proceeding and hoping a later
cleanup will undo it. This closes ordering 1 at its root: the stale operation never writes, so there
is nothing for `recordRideAuthority` to mislabel.

`recordRideAuthority()` itself changed shape to make the invariant structural rather than merely
provably true at one moment:

```swift
func recordRideAuthority(admittedRideEpoch: Int64) {
    rideAuthorityEpoch = admittedRideEpoch   // the captured value, never rideEpochs.current
}
```

Every one of its three call sites (`applyPlay`'s established track, `applyStep`'s authoritative
"nothing loaded", `restoreFromPlaybackState`'s authoritative "nothing loaded") sits immediately after
its function's own `admittedRideEpoch` guard, with no `await` between guard and call — so the live
value and the captured parameter are provably equal at that exact instant, and stamping the parameter
rather than re-reading the property is what stops a future edit that inserts an `await` between them
from silently reopening this defect.

**`endRideSegment`'s comparison became strict**, closing ordering 2:

```swift
guard rideAuthorityEpoch < rideEpoch else { … }   // was <=
```

`rideEpoch` is the value *this* End Ride minted for itself, and — because `rideEpochs.current` only
moves on an accepted Start or End Ride — that same value also names the CONNECTED-state gap that
follows the ride, where synchronised playback stays legitimately usable. Under `<=`, authority
admitted in that gap (stamped with the End Ride's own value by construction) was indistinguishable
from ride 1's own stale residue (which also compares `<=` against a strictly newer value). Under `<`,
only a value **strictly older** than this boundary's own belongs to the ride that is ending; a value
equal to it belongs to what came after, and survives.

Round 4's two ride-boundary properties are unchanged in what they mean and are re-verified at the new
comparison: **Property A** — a late cleanup never destroys a strictly newer ride's own authority
(`rideAuthorityEpoch < rideEpoch` is `false` whenever a later ride established something, `<` and `<=`
agreeing whenever the values actually differ). **Property B** — ride 1's own residue is still cleared
when nothing later replaces it (`rideAuthorityEpoch < rideEpoch` is `true` for a value that predates
this boundary, `<` and `<=` agreeing there too). Neither property moved; a third case — authority
established *at* this boundary's own value — is what the strict comparison newly tells apart from both.

### The regressions

Both are pinned by construction, with `content.armResolveGate` parking the exact suspension that
matters and both ride-boundary epochs minted (never merely raced) while the operation is provably
parked — the same discipline every prior amendment in this ADR and in ADR-024 uses. Both fail against
the unmodified pre-fix head; re-verified by reverting only the fix and observing each fail with the
predicted before/after values, not merely `XCTFail`.

- iOS `RideSegmentLifecycleTests.testAnOldRideOnesOperationParkedAcrossEndAndStartCannotBecomeRideTwosAuthority`
  — ordering 1. Ride 1 establishes X; a second Play (Y) is admitted under ride 1 and parked at its own
  `content.resolve`, after `applyAuthoritative` has captured Y's ride-authority provenance and before
  `applyPlay` writes anything; End Ride 1 is accepted (epoch minted, cleanup **not** invoked); Start
  Ride 2 is accepted; Y is released and resumes; only then is ride 1's parked cleanup invoked. Asserts
  `currentPlaybackIdentity`, `diagnostics.currentTrackHash` and `timeline` are all `nil` (X was
  cleared by ride 1's own now-correctly-firing cleanup; Y never wrote) and `supersededEndRideCount ==
  0` (the cleanup was not fooled into standing down).
- iOS `…testGenuinelyNewAuthorityEstablishedAfterEndRideSurvivesThatSameEndRidesDelayedCleanup` —
  ordering 2. Ride 1 is started with nothing played; End Ride 1 is accepted (epoch minted, cleanup
  **not** invoked); while still CONNECTED and before any Start Ride 2, genuinely new authoritative
  track Z is established; only then is ride 1's own parked cleanup invoked. Asserts
  `currentPlaybackIdentity`/`diagnostics.currentTrackHash`/`timeline` all still name Z, and
  `supersededEndRideCount == 1` (the boundary recognised Z as not its own and said so, rather than
  destroying it silently).
- iOS `…testASupersededEndRideStillClearsRideOneWhenRideTwoHasEstablishedNothingAtTheStrictCompare` —
  Property B re-run once more at the new comparison, so a future change that loosens `<` back to `<=`
  fails here rather than only in the ordering-2 regression above.
- iOS `…testFiftyCyclesOfRegression1AndRegression2SatisfyBothNewProperties` — fifty cycles alternating
  between the two orderings, fresh harness each cycle. Run an additional ten times standalone (500
  effective cycles total) with no failures.

All pre-existing `RideSegmentLifecycleTests` (Properties A/B at every earlier ordering, the §17 apply
proofs, the fifty-cycle suites from rounds 3–5) are unchanged and still pass — the comparison change
only distinguishes a case (authority admitted *exactly at* a boundary's own epoch) none of the earlier
tests exercised, because none of them established anything without first advancing past that exact
value via a further Start Ride.

### This pass's own fresh-fix audit

Every site that writes `rideAuthorityEpoch`, `currentPlaybackIdentity` or `timeline` was re-read
against: what operation authorised this write; what ride/boundary token did that operation capture
before its first suspension; is that exact token — not a fresh `rideEpochs.current` read — what is
compared and stamped; can End Ride or Start Ride happen while this work is suspended; if so, does the
guard immediately preceding the write catch it. `applyPlay`, `applyTransport`, `applySeek`,
`applyStep` (both its recursive `applyPlay` call and its own "nothing loaded" branch),
`applyPeerPlaybackState` and `restoreFromPlaybackState` (both its "nothing loaded" branch and its own
re-proof before the `.deferredContent` retention append) all now carry and check `admittedRideEpoch`.
Two sites were deliberately left unchanged after inspection: `applyPeerPlaybackState`'s final
"already synced, just re-anchor" branch, reached only when no suspension has occurred since its own
entry guard (so the guard already covers it with no window); and the clock/content-readiness retry
append inside `applyPeerPlaybackState`'s `needsFullRestore` block, which is generation-scoped retry
bookkeeping rather than ride-scoped authority — the eventual drain re-captures `admittedRideEpoch`
fresh at its own resumption, which is the same "fresh admission at the drain, not a reused stale
value" pattern already governing every other deferred-event replay in this file.

**Introduced-then-caught, before anything was pushed.** The first draft added the
`rideEpochs.current == admittedRideEpoch` guard but left `recordRideAuthority()`'s signature reading
`rideEpochs.current` directly, reasoning (correctly, for that instant) that the guard immediately
above already proved the two equal. That is true and is not wrong, but it is also exactly the shape
Amendment A4's own broken argument took — "provably equal right now" is not the same claim as
"structurally cannot disagree later" — so before running anything the signature was changed to take
`admittedRideEpoch` as an explicit parameter, matching every other provenance value in this file.

### Platform parity

**Android is unaffected by construction, not by omission.** `SessionCoordinator.endRide()` on Android
calls `owner.endRideSegment(owner.nextRideEpoch())` as two back-to-back synchronous, non-suspending
calls with no scheduling hop between the epoch mint and the cleanup — unlike iOS, there is no
`launchInSession`-equivalent deferral of the cleanup itself, only of `restoreRate()`'s single
unfenced player call *after* every state mutation already completed (Amendment A4's own documented
platform difference). Android's `endRideSegment`/`leaveSynchronizedMode` are plain functions with no
suspension point of any kind, so by the time any other code — including a parked `applyPlay` resuming
on a coroutine dispatcher — can run, an accepted End Ride's cleanup has *already* fully executed and
`synchronizedModeEpoch` has *already* moved. Android's existing `synchronizedModeEpoch == rideLifetime`
guard alone is therefore sufficient: there is no "accepted but not yet cleaned up" window for a
second guard to close, because acceptance and cleanup are the same statement. Android's
`recordRideAuthority()` was deliberately left reading `rideEpochs.current` live — changing it would be
motion with no defect behind it, the thing round 4's own "never invent an Android race just to make
the implementations look identical" instruction forbids. Android's full test suite
(`android/app/src/test/kotlin/com/ridelink/app/resync/ResyncRecoveryTest.kt` included) was re-run in
full and is unaffected; no Android source file changed.

### Full test results

**iOS.** `swift test` for both `RideLinkCore` (343 tests) and `RideLinkPlatform` (619 tests) — all
passing, including `RideSegmentLifecycleTests` (now 17 tests, up from 12), `ResyncCoordinatorTests`,
`ReconnectResyncStressTests` and every Phase 5 synchronised-playback suite. `RideSegmentLifecycleTests`
alone re-run ten additional times standalone with no failures. The full `RideLink` app target builds
(`xcodebuild -scheme RideLink -destination 'generic/platform=iOS' build`, `CODE_SIGNING_ALLOWED=NO` —
this machine has no development team configured, unrelated to this change) with no warnings from the
changed files.

**Android.** `./gradlew :core:test :network:test :app:test --rerun-tasks` — 1,048 tests across all
three modules, 0 failures, 0 errors (verified by parsing every `TEST-*.xml`, not just the console
summary). `ktlintCheck`, `detekt` and `assembleDebug` all clean. No Android source file touched.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.

## Amendment A6 — 20 September 2026 — independent review round 7: retained work must carry the ride that admitted it

Status: **Accepted.** Round 6's own fix (this file's Amendment A5) was audited by an independent
review, which returned **REQUEST CHANGES — DO NOT MERGE** with one remaining blocker in three
reachable forms. Reproduced against the unmodified head (`fbbf1e19d88d0b30c0ca9a255ea438c219badda3`)
before anything was changed, on **both** platforms; all three are fixed; the regressions that
reproduce them are in the suite. No wire change; no vector moved.

**The standing lesson, one notch on from round 6: provenance that exists only while an operation is
*executing* is not provenance.** Round 6 was right that a ride lifetime must be captured at admission
and compared rather than re-read — and it threaded exactly that through every directly-executing
apply path. What it did not ask is what happens when the operation stops executing and becomes
*stored*. At that moment round 6's two carefully-threaded values went out of scope, the retained
event recorded the control generation and the reconciliation obligation and nothing else, and the
replay — `drainDeferredEvents`, minutes later, after a clock recovered or a file transfer finished —
captured a **fresh** ride admission from whatever was live by then. The defect is not a missing
check; it is a value that was correct at every moment it was looked at and simply was not kept.

### The blocker, in three reachable forms

**Bug A — a deferred ride-1 command becomes ride-2 authority.** `admitAuthoritativeCommand` accepts a
`PLAY` for ordering while the clock is untrustworthy and retains it as
`DeferredEvent.command(message, generation:)`. End Ride 1 is accepted (`rideEpochs.current` moves;
the cleanup that would discard the held stream is parked in `launchInSession`), then Start Ride 2 is
accepted. The clock recovers, `drainDeferredEvents` replays the command into `applyAuthoritative`,
and *that function* captured the ride — so `synchronizedModeEpoch` was still ride 1's value (cleanup
never ran) and `rideEpochs.current` was ride 2's. Both halves of round 6's guard compared equal to
themselves and passed. Measured against the unmodified head, the pre-roll and the scheduled start
reached the real player, `currentPlaybackIdentity` and `timeline` were written, and
`recordRideAuthority` stamped `rideAuthorityEpoch = 3` — ride 2's epoch on ride 1's work. Ride 1's
own delayed cleanup then found a strictly newer owner, correctly stood down, and left the stale
authority standing permanently.

**Bug B — a deferred `STATE_SNAPSHOT` reconciles as ride 2.** The identical shape through
`DeferredEvent.playbackState`. The obligation id (round 4) and the control generation (round 3)
travelled correctly and answered their own questions — "is this S1 or S2?" and "is this lifetime
live?" — and neither answers "is S1 still authorised by the ride that admitted it?". S1, admitted in
ride 1 and held for a clock or a transfer, was replayed under ride 2, reported `RECONCILED`, and
published ride 1's `command_seq` and `manifest_revision` as a convergence that never happened.

**Bug C — the append-time race, and the only form Android can reach.** `applyPeerPlaybackState`'s
full-restore pre-check suspends in `estimate()` and `content.resolve` and then retains the snapshot.
Both platforms re-proved only the control generation before that append (Android re-proved neither),
so a ride boundary accepted inside those suspensions led straight to a retention that carried no ride
provenance at all — on iOS put back into a stream an accepted End Ride had not yet emptied, on
Android put back into one End Ride had *already* emptied. The Android reproduction prints the finding
verbatim: the retained event is stamped with the successor ride's epoch on ride 1's snapshot.

### The fix

**One immutable value, captured at the real admission point, stored with the work, compared at every
stage and re-derived nowhere.**

```swift
struct RideAdmission: Sendable, Equatable {
    let synchronizedModeEpoch: Int64   // moves when synchronised mode is actually LEFT
    let rideEpoch: Int64               // moves when a ride boundary is ACCEPTED
}
```

The two halves are one type precisely so a caller cannot thread one without the other, and so that
storing provenance is a single field on the retained event rather than a pair a future edit could
half-forget. `admitRide()` captures it; `rideStillLive(_:)` compares it; nothing else reads either
value. The sweep that closes this amendment is that every occurrence of `synchronizedModeEpoch` and
`rideEpochs.current` in either platform's production sources is now one of exactly four things: the
declaration, `admitRide()`, `rideStillLive`, or `leaveSynchronizedMode`'s own increment.

**Retained work carries it.** `DeferredEvent.command` and `DeferredEvent.playbackState` gained a
`RideAdmission`; `drainDeferredEvents` replays with `held.ride` and never captures one.

**The real admission points, identified per path rather than assumed:**

| path | where the ride is captured | why there |
|---|---|---|
| inbound command | `admitAuthoritativeCommand`, before `estimate()` | both branches — retain and apply — take responsibility for the command there, and `estimate()` is the first suspension |
| leader's own command | `issue`, in the same no-`await` block that stamps the header; carried on the outbound envelope to `onCommandOutcome` | the transport answers across the outbound consumer, an actor hop and a real socket write |
| retained Play | `playSynchronized` / `servePlaybackIntent`, before the first suspension; stored on `PendingPlay` | `resolvePendingPlay` is re-entered from Phase 4's availability callback, arbitrarily later |
| wire `PLAYBACK_STATE` | the `onPlaybackMessage` dispatch, atomic with its synchronous generation guard | nothing suspends between |
| `STATE_SNAPSHOT` | `onStateSnapshot`, **before** `adoptSnapshot` | the playback half is reached only after the queue half has awaited its own proofs |
| user transport actions | each public method, before it reads the player | `playerState()` is a cross-actor read |

**A drain that meets retired work pops it, cancels its obligation and continues.** Leaving it at the
head would wedge the stream exactly as round 3's Blocker A did, and a later item may have been
admitted under a newer, still-live ride. That claim is pinned by a test rather than left in prose:
changing the rule's `continue` to a `return` fails
`testARetiredRideEventAtTheHeadDoesNotBlockLiveWorkQueuedBehindIt` with "B never reached the player",
which is round 3's deadlock reintroduced by a fix rather than by the original code. The discard is counted (`retiredRideDeferredCount`), and a
reconciliation among them receives `REJECTED_RIDE` → `CANCELLED` — never `RECONCILED`, never silence,
never an indefinite deferral. Every `DEFERRED_*` still corresponds to actual retained work carrying
generation, obligation id **and** ride admission.

**The append sites prove the ride immediately before retaining**, with no suspension between, and a
ride that is over asks Phase 4 for no transfer and retains nothing.

### The queue-snapshot audit, answered rather than assumed

`DeferredEvent.queueSnapshot` deliberately carries **no** `RideAdmission`. End Ride does not retire
queue authority: `leaveSynchronizedMode` — End Ride and "Play locally" alike — clears the timeline,
the playback epoch, `currentPlaybackIdentity`, `rideAuthorityEpoch` and the retained Play, and leaves
the replicated queue exactly where it was, just as `resetForNewSession` does for a control-lifetime
boundary (ADR-024 Amendment A8). Neither `adoptSnapshot` nor `applyQueueSnapshot` proves a ride or
stamps `recordRideAuthority`, because PROTOCOL §9's "the snapshot always wins" is scoped to the
authenticated control generation and nothing narrower — and the leader's own queue survives *its* End
Ride by the same code, so a held snapshot replayed after a ride boundary carries state that is still
the leader's current authoritative queue. Adding ride provenance there would refuse valid queue state
rather than protect anything. The one effect `applyQueueSnapshot` has beyond the queue is
`resolvePendingPlay`, and the retained Play now carries its own admission.

### Android

**Affected, and fixed — but by one form only, and the production-path reason is exact.**
`SessionCoordinator.endRide()` calls `owner.endRideSegment(owner.nextRideEpoch())` as two
back-to-back synchronous statements, and `endRideSegment`/`leaveSynchronizedMode` contain no
suspension point, so an accepted End Ride's cleanup has *already* discarded `deferredEvents` and
moved `synchronizedModeEpoch` before any other code can run. Bugs A and B are therefore unreachable
there: retained work cannot outlive an accepted End Ride. Bug C is reachable, because
`content.resolve` in the pre-check is a genuine suspension between the snapshot's admission and its
retention (`estimate()` and `readyEstimate()` are synchronous on Android, so it is the *only* one) —
and the retained event was then replayed by a drain that read `synchronizedModeEpoch` live. That is
the same structural loss, and it is fixed the same way. The `rideEpoch` half is mirrored even though
Android has no window for it today, for the reason this repository keeps relearning: "this ordering
cannot happen here" is a property of a call site, not of this type.

### Fresh-fix audit

Every stored-work path was re-inspected after the fix: `DeferredEvent` (all three cases, all eight
removal sites on iOS and all seven on Android, each confirmed to emit a terminal result for any
obligation-bearing event it discards); `PendingPlay`; `transferRequestedForToken` (keyed on the
retained Play's token, which is ride-proved); `ResyncCoordinator.deferredReconciliation` (id plus
generation, and its terminal results now include the retired-ride cancellation); the inbound
`Phase5FrameQueue` (frames carry the generation their read produced — CLAUDE.md rules 19/20 —
unchanged); the `applyChain`/`scheduledChain` nodes; and the outbound queue.

Two residues are recorded rather than silently accepted, both pre-existing and unchanged by this
amendment:

- **`Phase5Outbound` carries the control generation and no ride.** A frame admitted while the ride was
  live can still be written after an End Ride. `issue` now proves the ride adjacent to the enqueue, so
  nothing enters the queue under a dead ride; what remains is the window between enqueue and the
  consumer's write. Gating the *wire* on a ride would be a protocol-semantics change — PROTOCOL has no
  ride concept, Ride Mode is a local UI state and the peer is never told an End Ride happened — so it
  belongs in an ADR of its own, not in a surgical provenance pass.
- **A scheduled player step armed while the ride was live can fire after an End Ride is accepted but
  before its cleanup runs.** The *decision* is now ride-proved at arming (round 4 §17 closed
  `applyStep`); what can land late is the effect of a decision the live ride genuinely made, which is
  ADR-024 Amendment A4 §C's stated residue ("an indivisible platform effect already dispatched may
  complete after the session that authorised it ends").

This pass's own test runs also found a harness defect worth recording: `ResyncCoordinatorTests`'
`expect` helper waited on wall time while advancing the virtual clock exactly once, and
`startDeferredDrain` computes its sleep deadline when the drain task *reaches* the sleep — so a single
advance taken first left the drain waiting on a deadline nothing would ever reach. It surfaced once,
under the load of a concurrent compile, in a fifty-cycle test unrelated to this fix. The helper now
advances on every poll, which removes the ordering dependency rather than enlarging the budget; no
assertion changed.

### Full test results

**iOS.** `swift test` for `RideLinkCore` (343 tests) and `RideLinkPlatform` (626 tests, up from 619)
— 0 failures, run in full three times. `ResyncCoordinatorTests` (28 tests) additionally re-run eight
times standalone. Both `xcodebuild` app-target builds (Debug and Release, `iphonesimulator`,
`CODE_SIGNING_ALLOWED=NO`) succeed. SwiftLint/SwiftFormat are not installed on this machine and are
not part of the repository's CI workflow; that is stated rather than implied.

**Android.** `./gradlew test` across all modules, `:app:test`, `:core:test`, `:network:test`,
`ktlintCheck`, `detekt`, `lint` and `assembleDebug` — all clean, and green in CI at the exact head.

**iOS CI is red at this head, and it is red at the *pre-change* head too — proven by experiment, not
argued.** Every CI run in this window fails **exactly one** `ReconnectResyncStressTests` case with
`notReady` — a 30 s `poll` timeout inside a **real-TLS** reconnect loop — and it is a *different* case
each run (the 50-cycle one, then the 100-cycle one, then the changed-track reconnect). 625 of 626
tests pass. A logic defect fails the same test every time; a timing wall moves. That test's own comment says a recurrence at this budget is "new evidence worth a
fresh investigation rather than another mechanical bump", so the budget was **not** touched and the
investigation was done.

**The decisive datapoint is an A/B at the same wall-clock time.** Re-running the *unchanged*
`fbbf1e19d88d0b30c0ca9a255ea438c219badda3` — the head the independent review audited, whose iOS job
was green earlier the same day — fails **both** of those tests, at the same `poll`, on the same
Xcode 26.6 / Swift 6.3.3 image, in the same window — 33.8 s and 30.9 s against 3.6 s and 5.0 s in the
morning run of the identical commit, with the whole class going from 52.7 s to ~80 s. The regression is therefore in the runner, not in this
amendment: a commit containing none of this work reproduces it. **The iOS suite is green locally**
— 626 tests, four full runs, plus those two tests six further standalone runs and one full-class run
under four saturated cores (1.0 s and 2.1 s).

That experiment is what settles it; the reasoning below is why the result is unsurprising rather
than why it can be dismissed:

- **This amendment's code is unreachable in that test.** Every early return round 7 adds to the
  reconciliation path sits inside `if let trackHash = fields.trackHash` / `if !contentReady`, and
  the test's harness (`buildPersistentPair`) seeds no track and never plays one — so every
  `STATE_SNAPSHOT` it exchanges carries `playback: nil`, `fields.trackHash` is nil, and the pre-check
  branch is never entered. The nil-track path returns `.applied` before reaching any new statement.
  The remaining new code needs a *non-live* ride, and that test starts no ride: `rideEpochs.current`
  and `synchronizedModeEpoch` are both 0 throughout, so `rideStillLive` is unconditionally true.
- **The new `.rejectedStale` return cannot wedge `requestPending` either**, which was the specific
  failure shape worth ruling out: `ResyncCoordinator`'s `.rejectedStale` branch never touches
  `pendingRequestGeneration`, and `StateResyncGate.onTrigger` re-arms on any generation change, so a
  refused snapshot for a dead generation can neither clear nor clobber a successor's request.
- **The far likelier poll is `reconnectCycle`'s own** `poll { connectedCount(a.session) > beforeA }`
  — waiting for a real TLS re-authentication to produce `.connected` — which is exactly the
  transport-timing point the comment already attributes to runner scheduling variance.
- Locally the same test passes in ~0.6 s, six consecutive standalone runs and four full-suite runs.

It is recorded here rather than dismissed, because "CI-only" is a claim a reviewer should be able to
check — and the way to check it is the A/B above: re-run `fbbf1e1` and watch an audited, previously
green commit fail the same two tests. **The red iOS job at this head is not a pass being claimed as a
fail-free result**: it is reported as red, with the evidence that its cause predates this work.
Whether the 30 s budget is now simply too small for GitHub's current macOS runners is a real question
this amendment deliberately does not answer, because answering it by raising the number is exactly
what that test's comment forbids.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.

---

## Amendment A7 — 21 September 2026 — independent review round 8: a proof taken before a suspension authorises nothing after it, and the CI wall was a real defect

**Status:** Accepted. Starting SHA `02496ae60afd7424c30d9e5a420c7758f1e35fe4`. No wire change; no
vector moved.

Round 7 gave retained work its own immutable `RideAdmission` and threaded it everywhere. That
architecture is accepted and unchanged here. What this round found is that storing the right value
is not the same as **proving** it at the right instant: three code paths proved the ride lifetime,
then suspended, then wrote bookkeeping that claimed an effect the downstream apply path would go on
to refuse. And, separately, the CI timeouts the previous pass recorded as a runner slowdown turned
out to have a **real production defect** underneath them, found by instrumented measurement rather
than by argument.

### Standing lesson

**The caller owns its own bookkeeping, and a downstream refusal cannot un-publish it.** Every one of
this round's three blockers had a correct guard, a correct retained provenance and a correct
downstream refusal. What none of them had was a proof *adjacent to the write the caller itself
performs*. `applyPlay` returning `.rejectedRide` is the right answer at the wrong time when
`lastAppliedSeq` was published two statements earlier — and `lastAppliedSeq` is what
`PLAYBACK_STATE.command_seq` and `STATE_SNAPSHOT.command_seq` carry onto the wire as *"this command
is reflected in my authoritative playback state"*.

And the round's second lesson, from the CI work: **"a different test fails each run" is evidence
about *variance*, not about *cause*.** The previous pass's A/B was sound and its conclusion — that
the runner had slowed — was true. It was also not the whole story, and the way to find that out was
not more argument but instrumentation: label every poll, dump the state at the timeout, count the
production early-returns. That took one afternoon and produced a defect.

### Blocker A — the drain could publish a refused command as applied

`drainDeferredEvents` proves the retained `RideAdmission` at the top of its loop, then — in the
`.command` branch — takes `await estimate()` and `await stillCurrent(generation)`. Both suspend, and
on an actor every `await` is a re-entrancy point.

`SessionCoordinator.endRide()` mints and publishes its ride epoch **synchronously** and hands
`leaveSynchronizedMode` — the only thing that empties the held stream — to `launchInSession`. So an
accepted End Ride *and* an accepted Start Ride can both land inside those suspensions while ride 1's
cleanup is still parked: the control generation does not move, `deferredEvents` is not emptied, and
`stillCurrentNow` plus the `heldCount` witness both still pass on resume.

The pre-fix code then did, in order: `removeFirst()`, `lastAppliedSeq = seq`,
`diagnostics.lastAppliedCommandSeq = lastAppliedSeq`, `recoveredCommandCount += 1`,
`publishDiagnostics()` — and *only then* called `applyAuthoritative`, which refused the frame as
`.rejectedRide`. The playback effect never happened; the wire was told it had.

**Fix.** The retained admission is re-proved immediately before the pop, with no `await` between the
proof and the writes it guards. A retired item is **retired** — popped, counted as
`retiredRideDeferredCount`, its reconciliation obligation cancelled — and the drain `continue`s, so
live work queued behind a dead item is not wedged (round 3's Blocker A, not reintroduced).

### Blocker B — the immediate admission path had the same shape

`admitAuthoritativeCommand` captures its `RideAdmission` correctly (round 7) and then awaits
`estimate()`. The `.apply` branch on the far side wrote `lastReceivedSeq`, `lastAppliedSeq` and
`diagnostics.lastAppliedCommandSeq` with no adjacent ride proof, relying on `applyAuthoritative` to
refuse — one call too late.

**Fix, and the sequence-number decision it required.** The ride is proved adjacent to the branch
writes, and **neither** sequence number moves on a retired ride. That is traced rather than
symmetric:

- `lastReceivedSeq` is the ordering floor `CommandOrderGate` reads. A *gap* is `.accept`, so leaving
  the floor where it was refuses nothing the leader sends afterwards.
- Advancing it for a command that will never apply would make the leader's own re-statement of that
  command a `.duplicate` — ADR-024 Amendment A1 Finding D's exact failure, reached by a different
  route.
- Not spending the sequence number of a refused command is already this codebase's rule: A1 Finding
  C says an incremental command refused while incremental state is untrusted keeps its number so the
  authoritative snapshot decides where ordering resumes. This is that rule applied to the third
  lifetime rather than to the desynchronisation latch.

Refusals are counted as `retiredRideAdmissionCount` — a new diagnostic, distinct from
`retiredRideDeferredCount` (work that was already *retained* when its ride ended), because "never
retained and never applied" and "retained, then retired" are different facts.

### Blocker C — `recoveredCommandCount` meant "applied" and was incremented before the outcome

The `.playbackState` drain branch popped the anchor and incremented `recoveredCommandCount` *before*
calling `applyPeerPlaybackState`, whose answer can legitimately be `.rejectedRide`, `.rejectedStale`
or a **re-deferral** (it re-appends the same anchor for a clock or a transfer). The field's own
documentation says "how many held commands were **applied** once the clock became trustworthy
again", so every one of those was a success claim for a reconciliation that had not happened.

**Fix.** The adjacent ride re-proof, as in Blocker A, plus the counter moved to `outcome ==
.applied`. The `.command` and `.queueSnapshot` branches keep theirs where they are: both hand the
event straight to an apply whose every precondition — clock, ordering revision, control generation
and ride lifetime — has just been proved synchronously adjacent to the pop.

**One thing deliberately *not* changed, and it was found by an assertion that failed:** a
`STATE_SNAPSHOT`'s own `command_seq` moves `lastReceivedSeq`/`lastAppliedSeq` at the frame's
**arrival**, inside `applyPeerPlaybackState`, immediately after that function's own adjacent
`rideStillLive` proof and before it decides whether restoration must be deferred. PROTOCOL §5 rule 2
is why that is right — the snapshot names its own instant, and the leader has *stated* that its
authority stands at that `command_seq`. The regression therefore asserts "unchanged by the drain",
not "never set". A first draft asserted the latter and was wrong.

### The sweep — three more sites of the same shape

`rideStillLive` / `await` / write, across every iOS path the review named:

- **`onCommandOutcome`** (the leader's own commit): the transport answers across the outbound
  consumer, an actor hop and a real socket write, and `stillCurrent` suspends again. The two
  sequence writes would publish this `command_seq` as applied while `chainApply`'s
  `applyAuthoritative` refused it. The frame did reach the peer and is deliberately not un-sent —
  but nothing on this device applied it, so nothing here may claim it did.
- **`playSynchronized`** and **`servePlaybackIntent`**: both capture the ride, then suspend, then
  call `playRequestFence.begin()`, which **supersedes** whatever retained Play is current. A press
  whose ride ended inside that suspension would cancel a *successor* ride's retained Play and
  install one of its own that `resolvePendingPlay` can only cancel.

`resolvePendingPlay`, `applyAuthoritative`, `applyPlay`, `applyTransport`, `applySeek`, `applyStep`,
`applyPeerPlaybackState` and `restoreFromPlaybackState` were all audited and were already correct —
every one of them proves the ride synchronously, adjacent to its first write.

### Android

**The drain and admission orderings are unreachable on Android, and a test now says so rather than a
comment.** `SessionCoordinator.endRide()` calls `endRideSegment` on the same thread one statement
after `nextRideEpoch()`; `endRideSegment` calls `leaveSynchronizedMode()` synchronously; and
`leaveSynchronizedMode` calls `discardDeferredEvents()` synchronously. The epoch moving and the held
stream emptying are therefore one indivisible step, so "ride epoch moved, retained ride-1 work still
queued" never exists there. `an end ride empties the held stream in the same step that moves the ride
epoch` asserts exactly that chain, with no `runCurrent()` between the call and the observation — if a
future change makes any link asynchronous, the window opens on Android too and that test fails first.

The guards are mirrored anyway, in the position the platform's own suspensions demand (inside
`commandMutex.withLock`, which suspends on contention, and adjacent to the pop after
`content.resolve`), for the reason `RideEpochBox` already gives: safety that rests on two statements
happening to be synchronous is an undocumented accident until something asserts it.

### The CI investigation — and the production defect it found

The two `notReady` failures at the starting SHA were investigated rather than re-documented. The
method was instrumentation, in four steps:

1. **Label every poll.** All ~50 call sites threw the same bare `ControlTransportError.notReady`, so
   a CI log said only "something somewhere". The hanging poll turned out to be the same one every
   time: `!a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending`.
2. **Dump the state at the timeout.** Fully settled — both sides authenticated at the same
   generation, `a.role == .leader`, `isLocalLeader` correct on both, zero role violations, zero
   relay drops, zero codec rejections — and the follower still `requestPending`, `lastOutcome
   == .requested`. Its request had gone out and had simply never been answered.
3. **Count the leader's silent early returns.** Exactly one per wedge, always the first guard:
   `role == nil`.
4. **Reproduce deterministically at the production seam**, which is what the regressions below do.

**The defect.** `SyncPlaybackCoordinator.role` is cleared by a link loss and set again by
`handleConnected`, which `SessionCoordinator` reaches through `launchInSession` — a continuation. The
peer's `STATE_REQUEST` travels a different path entirely: the read loop on the freshly authenticated
connection, through `ResyncRelay.deliver`'s own hop. Nothing orders the two, so a request for the
**live** generation can be dispatched at a leader whose own `.connected` is still queued.
`enqueueStateSnapshotReply` returned silently, and the loss was permanent: PROTOCOL §10 has no retry,
and `StateResyncGate` deliberately sends exactly one request per generation — a request storm is the
failure mode it exists to prevent — so the follower stayed desynchronised until the *next* reconnect.

On a ride that is: reconnect, follower asks for state, leader drops it, follower is left
desynchronised for the rest of that link. Phase 7 is precisely about not doing that.

**The fix is retention, not a retry.** `role == nil` means "not ready yet", not "never": the request
is stored in one slot with the generation that authorised it, and `handleConnected` replays it once
the session it names is established — captured *before* `resetForNewSession()`, which is deliberately
the one thing that drops a request no session ever came for. The generation is **compared, never
re-read**, so a request authorised by a lifetime that has since retired is dropped
(`droppedStateSnapshotReplyCount`) rather than answered with a successor's state — the same rule
Amendment A1 established for the outbound write, applied to a reply this device is only now able to
build. One slot is sufficient by construction: §10 allows one outstanding request per generation, so
a second retained request can only be a newer generation's, and nothing can answer the older one any
more. Mirrored on Android, where the same three unordered paths exist (two independent `SharedFlow`
collectors plus the relay).

**Two harness defects were found alongside it, and both are readiness signals rather than margins.**

- `reconnectCycle` redialled immediately after `b.manager.shutdown()`, into a peer that had not yet
  observed the loss — so `a`'s duplicate-connection resolution compared a fresh inbound connection
  against a corpse. Measured: under CPU saturation `a` still reported the *old* generation as live
  eight seconds after the redial. Production never produces that ordering; `ReconnectPolicy` backs a
  real reconnect off. The cycle now waits for both sides to observe the loss first. Every assertion
  downstream is unchanged, and each cycle is still a genuine link loss, a genuine fresh TLS
  authentication and a genuine resync round trip.
- `settleResyncForwarding` polled `isLocalLeader`, which is set on the first connect and never
  changes afterwards — so from cycle 2 onwards it returned immediately, proving nothing about the
  connection just built. Its own doc comment described the *generation* comparison. It now performs
  the comparison the comment always claimed.

Neither the cycle count, the timeout budget, nor any assertion was touched. `poll` additionally
captures `#filePath`/`#line` at each call site, so the next timeout names the condition that hung —
which is the one thing the previous pass's investigation had to reconstruct by hand.

### This pass's own fresh-fix audit

The standing instruction is to audit the newest fix first, and this pass's own newest fix needed
one. The Android mirror's first draft put the ride proof and the `lastAppliedSeq` write inside
`commandMutex` — correctly, since that lock suspends on contention — and left `heldStreamChanged`
*after* it. A stream that shortened inside the lock acquisition therefore left `lastAppliedSeq`
advanced for a command that was never popped and never applied: **the exact defect this amendment
exists to remove, reintroduced by the amendment itself**, and the third time this repository has had
a fix recreate its own class of bug (problem 56's wedge re-created by rule 23; A9's held-offer
adoption).

Three things can legitimately shorten the held stream there — `latchDesynchronized`'s refusal of
held incremental commands, `applyPeerPlaybackState`'s supersede rule, and `discardDeferredEvents` —
so the witness must come **first** and must be inside the same lock as the write. It now is, and the
verdict is acted on outside the lock because retiring a held event reports a reconciliation outcome
and a callback must never run under `commandMutex`. iOS was already correct: its witness, ride proof,
pop and write are one synchronous block with no `await` between them.

### Regressions

Every one of these fails against the unmodified starting SHA and passes after.

**iOS, `RideSegmentLifecycleTests`** — the drain and admission windows, parked in the real
`sessionClockEstimate()` read via `FakeSyncSession.armClockGate`:

- `testARideRetiringInsideTheDrainsClockReadNeverPublishesTheCommandAsApplied` (Blocker A)
- `testARideRetiringInsideTheAdmissionsClockReadNeverPublishesTheCommandAsApplied` (Blocker B)
- `testValidSameRideWorkStillAppliesThroughBothParkedWindows` (liveness — passes both before and
  after, which is what makes it a guard against over-rejection rather than a second safety test)
- `testFiftyCyclesOfTheParkedDrainWindow` (50 deterministic cycles alternating the two)

**iOS, `ResyncCoordinatorTests`** — the snapshot drain and the held request:

- `testARideRetiringInsideTheSnapshotDrainCancelsWithoutClaimingARecovery` (Blocker C)
- `testAValidSameRideSnapshotStillReconcilesThroughTheParkedDrain` (liveness)
- `testAStateRequestArrivingBeforeThisLeadersSessionIsEstablishedIsAnsweredOnceItIs` (the CI defect)
- `testAHeldStateRequestWhoseGenerationRetiredIsDroppedRatherThanAnsweredByTheSuccessor`

**Android, `ResyncRecoveryTest` / `ResyncCoordinatorTest`:**

- `an end ride empties the held stream in the same step that moves the ride epoch` (the
  unreachability claim, asserted)
- `valid same-ride retained work still reconciles through a parked drain resolve`
- `a STATE_REQUEST arriving before the leader's own playback session is established is answered once
  it is`
- `a held STATE_REQUEST whose generation retired is dropped rather than answered by the successor`

Each park is **proved** rather than assumed: the test asserts the gate is parked, that nothing has
been popped and that no bookkeeping has moved, before it creates the boundary. A test whose final
state happens to be right without having entered the window is not a regression for that window.

### Physical qualification

Unchanged: **DEFERRED — HARDWARE NOT AVAILABLE.** No Android↔iPhone reconnect, Bluetooth or hotspot
recovery, screen-lock networking, battery, thermal, audible resync quality or two-hour ride result is
claimed by this amendment.
