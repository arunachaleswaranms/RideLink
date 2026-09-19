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
