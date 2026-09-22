import Foundation
import RideLinkCore

/// The single owner of Phase 5 synchronised-playback state on this platform (CLAUDE.md rule 8),
/// mirroring `com.ridelink.app.sync.SyncPlaybackCoordinator` decision for decision.
///
/// **It owns no player and no queue** (brief §21). Every audible effect goes through
/// `SyncPlayerPort`, which is the *existing* app-target `MusicCoordinator` — its `LocalQueue`, its
/// one `AVAudioEnginePlayer`, its one Now Playing integration. There is no `SyncPlayer`, no
/// `PeerPlayer` and no second coordinator in this phase.
///
/// **Every distributed decision is made by a pure, mirrored, vector-pinned type**, never here:
/// ordering by `CommandOrderGate`, deadline mapping by `ScheduledCommand`, correction by
/// `DriftController`, queue algebra by `SharedQueue`, timing by `SessionClock` — and, since ADR-024
/// Amendment A1, ingress admission by `Phase5Ingress`, clock-readiness admission by
/// `PendingCommandGate` and retained-Play readiness by `PendingPlayGate`. That is ADR-019's direct
/// lesson, and it is why this type is wiring and lifetime rather than policy.
///
/// **An `actor`, deliberately.** Every mutable field below is read-then-written across a suspension
/// somewhere in this file — the leader's `command_seq` allocation and the receiver's order gate most
/// obviously — and Swift 6 strict concurrency will not let that be accidental. The actor is this
/// device's one serialisation point, under the one serialisation point per session that is the
/// ADR-010 leader.
///
/// **Session binding (the Phase 4 lesson, ADR-023 §3).** Every inbound frame is tagged at *dispatch*
/// with the session generation, and every step after a suspension re-proves it (`stillCurrent`).
/// Amendment A3 found exactly this class of bug in Phase 4: a check at handler entry proves nothing
/// about what suspends afterwards, and actor re-entrancy makes that emphatically true here.
///
/// ## What ADR-024 Amendment A1 (the Phase 5 closure audit) changed here
///
/// - **One outbound serialisation owner** (`outbound`). Allocating a `command_seq` or a
///   `queue_revision` and handing the resulting frame to the transport now happen with **no `await`
///   between them**, so actor isolation makes the pair atomic, and one consumer writes them in that
///   order. Before this, `await session.channel.send(…)` sat between the two — an actor re-entrancy
///   point — so a `PLAY` stamped for revision *n* could reach the wire ahead of the `QUEUE_SNAPSHOT`
///   that created revision *n*, and the peer would refuse the valid command for a revision it had
///   not been told about yet (Finding B).
/// - **A lossless ingress** (`inbound`). See `Phase5FrameQueue` (Finding C).
/// - **Received is not applied** (`lastReceivedSeq` versus `lastAppliedSeq`) (Finding D).
/// - **One press of Play survives its waits** (`pendingPlay`) (Findings A and E).
/// - **A superseded correction has no side effects at all** (`owns`) (Finding F).
///
/// ## What ADR-024 Amendment A2 (the second closure audit) changed here
///
/// - **Authority is bound to delivery** (`drainOutbound`, `OutboundCommitGate`). A leader's
///   `command_seq`, its `queue_revision` and the local audible effect are committed **only after the
///   authenticated transport actually accepted the frame**. Admission to `outbound` is not delivery,
///   and `send` returning false is not a send. Before this, an overflow incremented a counter and
///   returned, and the drain incremented "sent" for a write that had just failed — either way the
///   leader played a command the follower never received (Findings A and C).
/// - **Every outbound frame carries the generation that authorised it** (`Phase5Outbound`). The
///   queue deliberately outlives sessions, so resolving the writer at send time meant a Session A
///   frame could be written under Session B's `session_id` — the session-confusion class Phase 4
///   Amendments A3/A5 hardened against, on the other end of the pipe (Finding B).
/// - **Nothing overtakes held authoritative work** (`deferredEvents`, `AuthoritativeHoldGate`). A1
///   held a command whose clock was untrusted but let a later `QUEUE_SNAPSHOT` apply straight past
///   it, so the held `NEXT` resolved against a revision it was never authored against. The whole
///   authoritative stream is now held in arrival order and replayed in it (Finding D).
/// - **A correction's snapshot keeps the correction's own identity** (`emitPlaybackStateIfOwned`).
///   Reading the *live* generation inside the emit meant a snapshot caused by a correction in
///   Session A could be enqueued into Session B (Finding E).
public actor SyncPlaybackCoordinator {
    let monotonicNowUs: @Sendable () -> Int64
    let localPeerId: PeerId
    let session: any SyncSessionPort
    let player: any SyncPlayerPort
    let content: any SyncContentPort
    let sleeper: any SyncDeadlineSleeper
    let routeState: any SyncRouteStatePort
    private let nextQueueItemId: @Sendable () -> String

    /// `internal(set)`, not `private(set)`: the inbound/apply half of this actor lives in
    /// `SyncPlaybackCoordinator+Inbound.swift`, which is the same actor and the same isolation
    /// domain but a different file. Nothing outside this module can write it.
    public internal(set) var diagnostics = SyncPlaybackDiagnostics()
    /// The authoritative replicated queue. `LocalQueue` is untouched and still owns local-only rides.
    public internal(set) var queueState = SharedQueueState()

    var role: PlaybackRole?
    var syncEnabled = false

    /// The authentication generation this actor has been *told* is live, mirrored locally
    /// (ADR-024 Amendment A4 Finding D).
    ///
    /// `stillCurrent` asks `session` and is therefore `async` — `SyncSessionPort` is actor-facing on
    /// this platform, unlike Android's synchronous `currentAuthGeneration`. That difference is the
    /// whole of Finding D: an `async` proof cannot be adjacent to the effect it authorises, because
    /// the `await` that takes it is itself a re-entrancy point, so a boundary can land between
    /// "still current" answering true and the step it authorised being dispatched. This field is
    /// what makes a **synchronous** proof possible, and `ownsNow` is what takes it.
    ///
    /// It is deliberately not a replacement for `stillCurrent`: it lags by exactly the interval
    /// between the manager advancing the generation and `SessionCoordinator` forwarding the event.
    /// The two are used together — the `async` proof first, catching a generation the manager has
    /// already moved, then the synchronous one immediately before the effect, closing the window the
    /// first one's own `await` opens.
    var liveGeneration: Int64 = -1

    /// The highest `command_seq` this device has taken responsibility for — *the* input to
    /// `CommandOrderGate`. Amendment A1 Finding D: it advances when a command is accepted, whether
    /// that command is applied immediately or held for a trustworthy clock, so a replay of a held
    /// command is correctly a duplicate rather than a second copy.
    var lastReceivedSeq: Int64?
    /// The highest `command_seq` actually applied. What `PLAYBACK_STATE` reports, and only that.
    var lastAppliedSeq: Int64?
    var nextSeq: Int64 = PlaybackBounds.firstCommandSeq
    var timeline: PlaybackTimeline?
    /// Ride-segment playback identity (independent review, Blocker 2B) — see `PlaybackIdentity`'s
    /// own doc comment. Updated everywhere `timeline`'s track/queue-item identity changes; **not**
    /// cleared by `resetForNewSession()`, unlike `timeline` itself.
    var currentPlaybackIdentity: PlaybackIdentity?

    /// Independent-review round 3, Blocker C: the ride segment `currentPlaybackIdentity` belongs to.
    ///
    /// Strictly increasing, minted **and published in the same step** by `RideEpochBox.next()` on
    /// the main actor the instant `SessionFsm` accepts a Start Ride or an End Ride, and never
    /// derived here. It answers "which ride is current", and it is what `recordRideAuthority`
    /// stamps into `rideAuthorityEpoch` when authority is established.
    ///
    /// **Independent-review round 5, Blocker 1: this is a `nonisolated let` box rather than an
    /// actor-isolated field a `beginRideSegment` had to travel here to set.** A field only a
    /// successful hop could move made "the ride `SessionFsm` accepted" and "the ride this actor
    /// knows about" two different facts with a window between them, and authority established
    /// inside that window was stamped with the *predecessor* ride. See `RideEpochBox`.
    ///
    /// **Independent-review round 4, Blocker 1: it is deliberately not what an End Ride boundary
    /// compares against.** "A newer ride-lifecycle decision has been taken" is not the same fact as
    /// "a newer ride owns something", because a Start Ride establishes nothing — and treating them
    /// as one left ride 1's state standing whenever a Start Ride merely got there first. See
    /// `endRideSegment` and `rideAuthorityEpoch`.
    nonisolated let rideEpochs = RideEpochBox()

    /// Bumped by **every** exit from synchronised mode — End Ride and "Play locally" alike — and by
    /// nothing else (independent-review round 3, found by CI on this pass's own new regression).
    ///
    /// `applyPlay` proves the *control* generation before it writes, and End Ride does not change
    /// that generation: the control connection, the pairing and the session all stay alive on
    /// purpose. So an `applyPlay` suspended in `content.resolve` when the user ends the ride resumed
    /// afterwards and wrote `currentPlaybackIdentity`, `timeline` and a fresh playback epoch back
    /// over the state `leaveSynchronizedMode` had just retired — ride 1's track reported as ride 2's
    /// truth by a different route than Blocker C's, and "Play locally" resurrecting a synchronised
    /// timeline by the same one. Kept separate from `rideEpochs` because that one is
    /// `SessionCoordinator`'s to assign and must stay comparable with it.
    var synchronizedModeEpoch: Int64 = 0

    /// Independent-review round 4, Blocker 1: the ride epoch under which the **live** ride-scoped
    /// synchronisation authority was established.
    ///
    /// `rideEpochs.current` says which ride is nominally current. That is not the question an
    /// End Ride boundary has to answer, and round 3 answered the wrong one. `SessionCoordinator`
    /// assigns the epoch synchronously and then *hops*, so a Start Ride can make ride 2 current
    /// before ride 1's End Ride cleanup has run — and refusing the cleanup because "a newer ride
    /// exists" left ride 1's `currentPlaybackIdentity` standing, with ride 2 having established
    /// nothing of its own to overwrite it. Ride 2's first `STATE_SNAPSHOT` then reported ride 1's
    /// track, which is the very thing Blocker C existed to stop, reached by the other side of the
    /// same race.
    ///
    /// This field is the honest discriminator: it is stamped with the **admission-time** ride epoch
    /// at each of the three places ride-scoped playback authority is *established* (a track becomes
    /// authoritative, or is authoritatively replaced by "nothing loaded"), and reset to 0 whenever
    /// that authority ends. `endRideSegment` then refuses **only** when a strictly newer ride has
    /// already established authority of its own — so a late cleanup can never clear ride 2's track
    /// (Property A) and can never leave ride 1's behind either (Property B).
    ///
    /// Deliberately **not** cleared by `resetForNewSession`, exactly as `currentPlaybackIdentity`
    /// is not: an ordinary control-link blip does not change which ride owns what is playing.
    var rideAuthorityEpoch: Int64 = 0

    /// Stamps `rideAuthorityEpoch` for the ride that **authorised the operation now writing**.
    /// Called from the three sites that establish ride-scoped playback authority, and from nowhere
    /// else — see `rideAuthorityEpoch`. Synchronous, and every caller places it adjacent to the
    /// write it describes, with no `await` between.
    ///
    /// **Independent-review round 6: the round-5 fix read `rideEpochs.current` here — live, at the
    /// instant of the write — and that is the defect the repository's standing rule forbids, applied
    /// to the third lifetime.** `applyPlay`, `applyStep` and `restoreFromPlaybackState` all suspend
    /// (`content.resolve`, `readyEstimate`, `runOwnedSteps`'s pre-roll) between the point their
    /// caller captures the ride lifetime and the point this function runs. A round-5 doc comment on
    /// this very function argued the two could not come apart because every exit from `RIDE_ACTIVE`
    /// bumps `synchronizedModeEpoch` or the auth generation "adjacent to this call" — but
    /// `synchronizedModeEpoch` only bumps when `leaveSynchronizedMode` **actually runs**, and End
    /// Ride's cleanup is asynchronous (`SessionCoordinator.endRide()` hands it to
    /// `launchInSession`). An operation admitted under ride 1, parked in a real suspension, can
    /// resume after both an accepted End Ride *and* a further accepted Start Ride — `rideEpochs`
    /// already strictly newer — while `synchronizedModeEpoch` is still the value it captured,
    /// because ride 1's cleanup has not been *scheduled* to run yet. Re-reading `rideEpochs.current`
    /// at that instant relabels ride 1's work as ride 2's, exactly the class this file keeps finding
    /// and fixing everywhere else. The inverse also reproduced: genuinely **new** authority admitted
    /// after an accepted End Ride but before its parked cleanup executes was stamped with the End
    /// Ride's own freshly-minted epoch — indistinguishable, under `<=`, from residue the cleanup
    /// exists to clear — and a same-valued predecessor cleanup then destroyed it.
    ///
    /// **The fix is provenance, not a smarter live read.** `admittedRideEpoch` is `RideAdmission
    /// .rideEpoch` — `rideEpochs.current` captured once, at the coordinator's own admission point,
    /// before this operation's first suspension — the same discipline `generation` already follows
    /// (CLAUDE.md rules 19/20) applied to the ride lifetime. It travels through every intermediate
    /// `await` as a parameter, is re-proved live immediately before every write it could reach
    /// (`applyPlay`, `applyTransport`, `applySeek`, `applyStep`, `applyPeerPlaybackState`,
    /// `restoreFromPlaybackState` — a mismatch is `.rejectedRide`, refused rather than written), and
    /// is what this function stamps — never re-derived. By the time this runs, the guard immediately
    /// above it has already proved `rideStillLive` with no suspension between the proof and here, so
    /// stamping the parameter and reading the live property agree at this exact instant — the
    /// difference is that the parameter cannot silently start disagreeing if a future edit inserts
    /// an `await` between the guard and this call, and the live read could.
    ///
    /// **Independent-review round 7 is why the parameter is now half of a single `RideAdmission`
    /// rather than one of two loose values**: round 6 threaded both correctly through every
    /// *executing* path and neither survived the moment an operation became *retained*. See
    /// `RideAdmission`.
    ///
    /// `endRideSegment`'s comparison is `<`, not `<=`, for the same reason: an End Ride's own newly
    /// minted epoch names the boundary between "ride 1's work" and "everything after ride 1", and
    /// authority admitted *at* that value belongs to the second half.
    func recordRideAuthority(admittedRideEpoch: Int64) {
        rideAuthorityEpoch = admittedRideEpoch
    }

    /// Captures the ride provenance of an operation **at the instant this coordinator admits it**
    /// (independent-review round 7).
    ///
    /// Synchronous, and every caller places it before that operation's first suspension — which is
    /// what makes the captured pair the ride that actually authorised the work rather than whichever
    /// ride happens to be current when a later stage runs. See `RideAdmission`.
    func admitRide() -> RideAdmission {
        RideAdmission(synchronizedModeEpoch: synchronizedModeEpoch, rideEpoch: rideEpochs.current)
    }

    /// Whether the ride lifetime that admitted an operation is still the live one.
    ///
    /// Both halves are load-bearing and neither subsumes the other:
    ///
    /// - `synchronizedModeEpoch` moves only when `leaveSynchronizedMode` **runs**, so it is the half
    ///   that catches "Play locally" and an End Ride whose cleanup has already executed;
    /// - `rideEpochs.current` moves the instant `SessionFsm` **accepts** a Start Ride or an End Ride,
    ///   synchronously, with no window — so it is the half that catches an accepted End Ride whose
    ///   cleanup is still parked in `launchInSession` (independent-review round 6).
    ///
    /// Synchronous by construction: every caller places it immediately before the write or the
    /// retention it authorises, with no `await` between.
    func rideStillLive(_ admission: RideAdmission) -> Bool {
        synchronizedModeEpoch == admission.synchronizedModeEpoch && rideEpochs.current == admission.rideEpoch
    }

    var driftState = DriftController.reset()
    private var tickTask: Task<Void, Never>?

    /// Ends the PROTOCOL §5 cadence loop. `tickTask` is `private`, and the inbound half of this
    /// actor needs to stop it when outbound authority is lost (Amendment A2).
    func cancelTick() {
        tickTask?.cancel()
        tickTask = nil
    }

    /// The playback-epoch fence. A scheduled start, a late `POSITION_REPORT`, a drift correction or
    /// a rate restore belonging to a superseded epoch is inert. `content_hash` alone would not do,
    /// because the same track can legitimately be played again (brief §32).
    var epoch = OperationFence()
    var currentEpochToken: Int64 = -1

    /// The fence for the *user's* Play request, distinct from `epoch`: a request still waiting for a
    /// queue revision or for a transfer has no playback epoch yet, and a newer request must
    /// supersede it without disturbing whatever is currently playing (brief §18).
    var playRequestFence = OperationFence()
    var pendingPlay: PendingPlay?
    /// The `PendingPlay.token` a Phase 4 transfer has already been requested for. Keyed on the
    /// *token* rather than the hash so a superseded request's transfer is never mistaken for the
    /// current one's, and so re-evaluating the same request asks Phase 4 exactly once (brief §20).
    var transferRequestedForToken: Int64?

    /// Amendment A1 Finding D, widened by Amendment A2 Finding D: the **authoritative event
    /// stream** held in arrival order while the clock is untrustworthy.
    ///
    /// A1 held commands only, and let a later `QUEUE_SNAPSHOT` apply straight past them — so a held
    /// `NEXT` authored against revision 5 executed against revision 6 and stepped to the wrong
    /// track. Once anything is held, every later authoritative frame whose semantics could change a
    /// held command's meaning joins the queue behind it, and the whole stream replays in the order
    /// the leader chose. A `POSITION_REPORT` is never held: it produces one diagnostics number and
    /// can change no command's meaning.
    var deferredEvents: [DeferredEvent] = []

    /// A peer `STATE_REQUEST` that reached this coordinator **before** its own session was
    /// established (independent-review round 8's CI investigation).
    ///
    /// One slot, never a queue: PROTOCOL §10 allows one outstanding request per generation
    /// (`StateResyncGate`), so a second retained request could only ever be a newer generation's,
    /// and nothing can answer the older one any more. Cleared by `resetForNewSession`, so a request
    /// never survives into a session that did not admit it.
    var pendingStateSnapshotReply: PendingStateSnapshotReply?
    var deferredDrainTask: Task<Void, Never>?
    let deferredCommandCapacity: Int

    /// The tail of the **scheduled-action chain** (Amendment A1 Finding G).
    ///
    /// Every accepted command's audible effect is armed by `scheduleAt` and then waits — for its own
    /// deadline, or not at all when that deadline has already passed. Arming one `Task` per command
    /// preserves nothing: Swift makes no guarantee that independently created tasks run in creation
    /// order, which is the *same* fact that made the inbound `Task`-per-frame shape a defect. Two
    /// commands whose deadlines have both passed — `PAUSE(n)` and `RESUME(n+1)`, a pair the leader
    /// stamps microseconds apart — could therefore take effect in either order, the exact opposite of
    /// what `command_seq` is for. A stress run of this amendment's own Finding C regression caught it
    /// at 2 in 100.
    ///
    /// Each armed action now awaits the previous one before doing anything. Authoritative deadlines
    /// increase with `command_seq` (the leader stamps `session_now + LEAD`), so waiting for the
    /// previous action costs nothing and the chain's order *is* the authoritative order. A superseded
    /// action fails its ownership proof and returns at once, so it never holds the chain up.
    var scheduledChain: Task<Void, Never>?

    /// The tail of the **leader's authoritative apply chain** (Amendment A2 Finding A).
    ///
    /// A leader's own command is applied by `drainOutbound`'s commit hook, once the transport has
    /// confirmed the frame went out — which is the whole point of A2. Applying it *on* that consumer
    /// would stall the outbound path behind a decoder pre-roll, so the apply is launched instead;
    /// and an unstructured `Task` preserves nothing at all about order, which is precisely the
    /// defect Amendment A1 Finding G was about. Each apply therefore awaits the previous one,
    /// exactly as `scheduledChain` does, so commit order — which *is* `command_seq` order, because
    /// one consumer commits — is apply order.
    var applyChain: Task<Void, Never>?

    /// Every `applyChain` and `scheduledChain` node the **current** session authorised
    /// (Amendment A3 Findings A and C), so a boundary can cancel all of them.
    ///
    /// A1 and A2 both stored only the *tail* of each chain, and `resetForNewSession` retired a chain
    /// by setting that reference to `nil`. That detaches; it cancels nothing. The nodes already
    /// created went on existing — one parked inside a decoder pre-roll, the ones behind it parked on
    /// `await previous?.value` — and when the pre-roll finally returned they ran in whatever session
    /// was live by then. Cancelling the tail would not have helped either: the tail is the *newest*
    /// node, and the one that matters is the *oldest*, the one actually blocked. An unstructured
    /// `Task` has no parent to cancel, so on this platform the set is tracked explicitly; Android
    /// gets the same reach from one `SupervisorJob`.
    ///
    /// Each node removes its own entry when it finishes, so the dictionary is the *live* set rather
    /// than a growing log.
    ///
    /// **Cancellation is defence one, not the correctness boundary.** `withCheckedContinuation` —
    /// which is how every real `AVAudioEngine` and `AVAudioFile` callback is bridged — ignores
    /// cancellation by nature, so a cancelled node still returns from such a call and carries on to
    /// its next statement. What stops it there is the generation each node captured.
    var sessionChainNodes: [Int64: Task<Void, Never>] = [:]

    /// ADR-024 **Amendment A11**: the bound on retained local playback work, and the reason a
    /// delivered authoritative command always has somewhere to land.
    ///
    /// Reserved *before* the command can be delivered (`issue` on the leader,
    /// `admitAuthoritativeCommand` on a follower), spent by the apply node and by the scheduled node
    /// that node arms, and released when both are done. Phase 8's first attempt counted live task
    /// nodes and refused at node-creation time — which on the leader is *after* the frame reached the
    /// peer — so an overflow abandoned authority the follower had already applied.
    let workLedger: SessionWorkLedger
    private var nextChainNodeId: Int64 = 0

    /// Amendment A2 Findings A and C: an authoritative frame this device produced never reached the
    /// peer, so Phase 5 authority is over for this authentication generation.
    ///
    /// Latched rather than retried. There is no protocol message that tells a peer about a command
    /// it never received (`STATE_REQUEST` remains unimplemented — ADR-024 Amendment A2 §H), so the
    /// only honest options are to continue from a state only this device knows about, which is the
    /// divergence A2 exists to close, or to stop being authoritative. This is the second.
    ///
    /// Synchronised mode is left when it latches, so `MusicCoordinator`'s transport controls go
    /// straight back to Phase 3 behaviour rather than being answered by a coordinator that will
    /// refuse them. Local music is untouched (ADR-004, FR-025).
    var outboundAuthorityLost = false

    /// Amendment A1 Finding C: set on a **follower** when the ingress refused a frame it could not
    /// supersede, or when the deferred buffer overflowed. While either is set, no incremental
    /// command is applied — only authoritative full state is.
    var playbackDesynchronized = false
    var queueDesynchronized = false

    /// The bounded, **lossless**, arrival-ordered handoff from the control read loop
    /// (Amendment A1 Finding C, replacing the `.bufferingNewest` stream this originally was).
    let inbound: Phase5FrameQueue<Phase5Inbound>

    /// The one ordered outbound path (Amendment A1 Finding B). Every Phase 5 frame this device sends
    /// is enqueued here — synchronously, in the same actor-isolated step that stamped it — and
    /// written by the single consumer below, in enqueue order.
    ///
    /// **Amendment A2 Finding A: a refusal is a failure of the operation, not a statistic.**
    /// `enqueueOutbound` used to return `Void`, so no caller could learn the frame had been refused
    /// — and every caller carried straight on to stamp, publish and apply. What this queue holds is
    /// therefore no longer a bare frame but a `Phase5Outbound` envelope: the generation that
    /// authorised it (Finding B) and the commit hook the single consumer invokes with the real
    /// outcome (Findings A and C).
    let outbound: Phase5FrameQueue<Phase5Outbound>

    private var drainTask: Task<Void, Never>?
    private var outboundTask: Task<Void, Never>?

    public var onDiagnosticsChanged: (@Sendable (SyncPlaybackDiagnostics) -> Void)?
    public var onQueueChanged: (@Sendable (SharedQueueState) -> Void)?

    /// Phase 7 (ADR-028): fired whenever an ingress overflow latches
    /// `playbackDesynchronized`/`queueDesynchronized` on a **follower** — the trigger
    /// `ResyncCoordinator` turns into a `STATE_REQUEST` (PROTOCOL §10). A dedicated slot,
    /// deliberately separate from `onDiagnosticsChanged` (which `SyncPlaybackPresenter` already owns
    /// for the UI): two owners of one concern each, not one slot serving two. Carries no generation —
    /// `ResyncCoordinator` reads the live one at the instant it reacts, exactly as Android's
    /// `ResyncCoordinator.init`'s diagnostics collector reads `session.currentAuthGeneration` rather
    /// than a frame-bound value, because this is a "current state" trigger, not frame provenance.
    public var onDesynchronizedTrigger: (@Sendable () -> Void)?

    /// Independent review, Blocker 2E: fired with the authorising `generation` whenever a full
    /// playback restoration actually **completes** — including one that was first deferred for the
    /// clock and only applied later, from `drainDeferredEvents`. `ResyncCoordinator` is the one
    /// consumer: it compares the generation against its own `pendingRequestGeneration` before
    /// reacting, the same generation-keyed matching `StateResyncGate` already does for the
    /// synchronous case, so a signal belonging to an unrelated (e.g. ordinary wire `PLAYBACK_STATE`,
    /// or a retired generation's) restoration is simply ignored rather than mismatched. Never fired
    /// for the ordinary "already synced, just re-anchor" path, which is always synchronous and
    /// already answered directly by `onStateSnapshot`'s return value.
    ///
    /// **Independent-review round 4, Blocker 2: it carries the obligation's own id, not just the
    /// generation.** End Ride deliberately does not move the authenticated control generation, so
    /// two reconciliation obligations can exist sequentially under one generation — S1 discarded by
    /// an End Ride, S2 accepted in the ride that follows — and a generation-only signal let S2's
    /// success complete S1, publishing ride 1's `command_seq`/`manifest_revision` as a reconciliation
    /// that never happened. The id is minted by `ResyncCoordinator`, travels *into* the retained
    /// event, and comes back out with the terminal result; it is compared, never re-derived.
    public var onReconciliationApplied: (@Sendable (_ obligation: Int64, _ generation: Int64) -> Void)?

    /// Independent-review round 4, Blocker 2: the other terminal result a retained reconciliation
    /// can reach — it was **discarded** rather than applied.
    ///
    /// `leaveSynchronizedMode` (End Ride, "Play locally"), `resetForNewSession` (a control-lifetime
    /// boundary), `failClosedOutbound` and a drain that finds its generation retired all legitimately
    /// throw held authoritative work away. Before this, the outer owner of the obligation simply
    /// never heard: `ResyncCoordinator.deferredReconciliation` stayed alive holding ride 1's snapshot,
    /// and the next completion signal that matched its generation completed it.
    ///
    /// Applied and cancelled are the two terminal results, they are mutually exclusive, and **only
    /// applied may produce `RECONCILED`**. Nothing is ever inferred from an absence.
    public var onReconciliationCancelled: (@Sendable (_ obligation: Int64, _ generation: Int64) -> Void)?

    /// Phase 7 (ADR-028 Amendment): where a `.resync` outbound frame is actually written.
    /// `ResyncCoordinator.attach()` installs this once, mirroring `onDesynchronizedTrigger` — a
    /// second late-bound collaborator, not a constructor dependency, so Phase 5 does not need to
    /// know Phase 7 exists at `init` time.
    private var resyncChannel: (any ResyncChannel)?

    public init(
        monotonicNowUs: @escaping @Sendable () -> Int64,
        localPeerId: PeerId,
        session: any SyncSessionPort,
        player: any SyncPlayerPort,
        content: any SyncContentPort,
        sleeper: any SyncDeadlineSleeper,
        routeState: any SyncRouteStatePort,
        nextQueueItemId: @escaping @Sendable () -> String,
        inboundCapacity: Int = Phase5GateBounds.defaultInboundCapacity,
        deferredCommandCapacity: Int = Phase5GateBounds.defaultDeferredCommandCapacity,
        outboundCapacity: Int = Phase5GateBounds.defaultOutboundCapacity,
        /// ADR-024 Amendment A11: how many local playback obligations may be outstanding at once.
        /// Injectable for the same reason as every bound above — a test forces the edge at 1 or 2
        /// rather than producing 256 commands.
        sessionWorkCapacity: Int = Phase5GateBounds.defaultSessionWorkCapacity
    ) {
        self.monotonicNowUs = monotonicNowUs
        self.localPeerId = localPeerId
        self.session = session
        self.player = player
        self.content = content
        self.sleeper = sleeper
        self.routeState = routeState
        self.nextQueueItemId = nextQueueItemId
        self.deferredCommandCapacity = deferredCommandCapacity
        workLedger = SessionWorkLedger(capacity: sessionWorkCapacity)
        inbound = Phase5FrameQueue(
            capacity: inboundCapacity,
            kindOf: { $0.kind },
            coalesceKeyOf: { $0.coalesceKey },
            // Amendment A6: the generation a refusal belongs to is the refused frame's own, never
            // whichever session happens to be live when the consumer gets round to observing it.
            generationOf: { $0.generation }
        )
        outbound = Phase5FrameQueue(
            capacity: outboundCapacity,
            // Never coalesced: a frame this device has already stamped may not be superseded.
            kindOf: { _ in .command },
            coalesceKeyOf: { _ in nil },
            // Recorded for symmetry only: this direction's producer is `enqueueOutbound`, which is
            // *answered* synchronously by `offer` and acts on the refusal there and then
            // (Amendment A2 Finding A), so its loss ledger is never drained.
            generationOf: { $0.generation }
        )
    }

    /// Attaches the two Phase 5 sinks and starts both drains. Called once by the composition root,
    /// after construction, so the actor is fully initialised before anything can be delivered.
    public func start() async {
        drainTask = Task { [weak self] in await self?.drainInbound() }
        outboundTask = Task { [weak self] in await self?.drainOutbound() }
        await session.channel.setPlaybackSink(PlaybackForwarder(inbound: inbound))
        await session.channel.setQueueSink(QueueForwarder(inbound: inbound))
        // Amendment A1 Finding E: Phase 4's own verified-availability notification is what lets one
        // press of Play survive a transfer. A notification, never a poll.
        let queue = inbound
        await content.observeAvailability { [weak self] in
            _ = queue
            Task {
                await self?.resolvePendingPlay()
                // Independent review, Race 7: a `STATE_SNAPSHOT` restoration held for missing content
                // (`DeferredEvent.playbackState`, held by `applyPeerPlaybackState`) is a different
                // obligation from `pendingPlay`'s local-intent one, and content becoming available is
                // exactly the event that can unblock it — the same drain `startDeferredDrain`'s own
                // retry cadence already re-attempts, triggered promptly instead of waiting out the
                // interval.
                await self?.drainDeferredEvents()
            }
        }
    }

    /// Ends both drains. The queues outlive individual sessions deliberately — a session boundary is
    /// expressed by the generation each frame carries, not by tearing the pipe down — so this is for
    /// process/coordinator teardown only.
    public func shutdown() async {
        inbound.finish()
        outbound.finish()
        drainTask?.cancel()
        drainTask = nil
        outboundTask?.cancel()
        outboundTask = nil
        tickTask?.cancel()
        tickTask = nil
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        retireSessionChains()
    }

    /// Whether the ingress consumer is parked with nothing buffered — everything offered so far has
    /// been dispatched and counted. A real liveness fact (a consumer that never parks is a consumer
    /// falling behind), and the exact signal a test needs to know the ingress is idle rather than
    /// guessing how many scheduler turns a frame takes.
    public func isIngressIdle() -> Bool { inbound.isConsumerWaiting }

    public func setDiagnosticsObserver(_ observer: (@Sendable (SyncPlaybackDiagnostics) -> Void)?) {
        onDiagnosticsChanged = observer
    }

    public func setQueueObserver(_ observer: (@Sendable (SharedQueueState) -> Void)?) {
        onQueueChanged = observer
    }

    /// Phase 7 (ADR-028): installs the desync trigger. A method rather than direct property
    /// assignment, matching `setDiagnosticsObserver`/`setQueueObserver` exactly — external callback
    /// registration on an actor-isolated property goes through a method on this platform.
    public func setDesynchronizedTrigger(_ trigger: (@Sendable () -> Void)?) {
        onDesynchronizedTrigger = trigger
    }

    /// Independent review, Blocker 2E: installs where a deferred-then-later-applied reconciliation
    /// is reported, mirroring `setDesynchronizedTrigger` exactly.
    public func setReconciliationAppliedTrigger(_ trigger: (@Sendable (Int64, Int64) -> Void)?) {
        onReconciliationApplied = trigger
    }

    /// Independent-review round 4, Blocker 2: installs where a retained reconciliation that was
    /// **discarded** is reported, mirroring `setReconciliationAppliedTrigger` exactly.
    public func setReconciliationCancelledTrigger(_ trigger: (@Sendable (Int64, Int64) -> Void)?) {
        onReconciliationCancelled = trigger
    }

    /// Phase 7 (ADR-028 Amendment): installs where a `.resync` outbound frame is written. See
    /// `resyncChannel`'s doc comment.
    public func setResyncChannel(_ channel: (any ResyncChannel)?) {
        resyncChannel = channel
    }

    // MARK: - Session lifecycle

    /// ADR-019: `.connected` means the trust gate passed, so this is the first instant a Phase 5
    /// message may be sent or acted on at all. The role comes straight from ADR-010's rule as the
    /// handshake already computed it.
    ///
    /// Forwarded by the app's `SessionCoordinator` rather than subscribed to here, for the reason
    /// `SyncSessionPort` records: `onEvent` is a single mutable callback slot on this platform.
    public func handleConnected(isLocalLeader: Bool) async {
        // Round 8: taken **before** the reset, because `resetForNewSession` is what drops a request
        // no session ever came for — and this is the one caller for which a session *is* arriving.
        // Held locally across the reset so nothing between here and the flush can answer it twice.
        let heldReply = pendingStateSnapshotReply
        await resetForNewSession()
        role = isLocalLeader ? .leader : .follower
        diagnostics.role = role
        diagnostics.sessionGeneration = await session.currentAuthGeneration()
        liveGeneration = diagnostics.sessionGeneration
        publishDiagnostics()
        let generation = diagnostics.sessionGeneration
        tickTask = Task { [weak self] in await self?.tickLoop(generation: generation) }
        // Independent-review round 8's CI investigation: a `STATE_REQUEST` that reached
        // `enqueueStateSnapshotReply` before this session was established is answered here, once,
        // and only if it named *this* generation. See `PendingStateSnapshotReply`.
        // Reset suspends. A request can arrive after it cleared the slot but before the role
        // was installed. Retain the newest original generation from either admission window;
        // a late predecessor request must not displace a live one captured before reset.
        let reply: PendingStateSnapshotReply?
        if let duringReset = pendingStateSnapshotReply, let beforeReset = heldReply {
            reply = duringReset.generation >= beforeReset.generation ? duringReset : beforeReset
        } else {
            reply = pendingStateSnapshotReply ?? heldReply
        }
        await flushPendingStateSnapshotReply(reply)
    }

    /// PROTOCOL §10's `STATE_REQUEST` answered late, because it arrived early.
    ///
    /// `handleConnected` retains candidates from before and during reset, preserving their
    /// original generations. Only the selected request's own generation can authorize a reply;
    /// a retired request cannot borrow the successor's state. Clear the retained slot before
    /// suspension so another caller cannot answer the same request twice.
    private func flushPendingStateSnapshotReply(_ held: PendingStateSnapshotReply?) async {
        guard let held else { return }
        pendingStateSnapshotReply = nil
        guard held.generation == liveGeneration else {
            diagnostics.droppedStateSnapshotReplyCount += 1
            publishDiagnostics()
            return
        }
        await enqueueStateSnapshotReply(
            generation: held.generation,
            leaderPeerId: held.leaderPeerId,
            manifestRevision: held.manifestRevision,
            transfersInFlight: held.transfersInFlight
        )
    }

    /// ADR-004: "A Wi-Fi drop does **not** interrupt music. Both phones keep playing; only
    /// synchronisation pauses." Local audio is deliberately left alone; what is torn down is every
    /// *coordination* obligation.
    public func handleLinkLost() async {
        await resetForNewSession()
        diagnostics.role = nil
        diagnostics.syncState = .inactive
        publishDiagnostics()
    }

    private func resetForNewSession() async {
        // `role = nil` alone already makes every synchronous `ownsNow` fail from here until a role
        // is set again, which is what covers the window before `liveGeneration` is refreshed below.
        role = nil
        syncEnabled = false
        tickTask?.cancel()
        tickTask = nil
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        // Amendment A3 Findings A and C: the previous session's chains are **retired**, not merely
        // detached — every node of both chains, including the oldest, which is the one actually
        // blocked. Clearing the two tails in the same call is what makes "Session B never waits for
        // Session A" true by construction: the new session's first node has no predecessor.
        retireSessionChains()
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        epoch.supersede()
        // Amendment A1 Finding E: a session boundary cancels the retained Play outright. A transfer
        // that completes afterwards must never resurrect it — the token it held is already stale.
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        discardDeferredEvents()
        // Round 8: a `STATE_REQUEST` held for a session that never arrived belongs to a lifetime
        // that is over. The follower's own `StateResyncGate` re-arms on the next generation.
        pendingStateSnapshotReply = nil
        // Amendment A2 Finding B: a fresh generation retires everything the previous one authorised.
        // Frames still queued outbound stay physically queued and become inert, because each carries
        // the generation that authorised it and `outboundUsable` refuses to write them.
        outboundAuthorityLost = false
        // ADR-024 Amendment A11: and so does every local obligation, because the chains those
        // obligations were spent on have just been retired above. Bounded by the generation that
        // *ended* — `liveGeneration` is still the predecessor's here, refreshed only after this call
        // returns — never a blanket clear, so a successor that has already reserved capacity keeps
        // it. A node from the retired session that returns later releases an id the ledger no longer
        // holds, which is a no-op and can never free a successor's capacity.
        workLedger.retire(throughGeneration: liveGeneration)
        publishRetainedWork()
        lastReceivedSeq = nil
        lastAppliedSeq = nil
        nextSeq = PlaybackBounds.firstCommandSeq
        timeline = nil
        // Independent review, Blocker 2B: `currentPlaybackIdentity` is deliberately **not** reset
        // here, unlike `timeline` — it is ride-segment truth (which track, which queue item), not
        // the session-clock-relative scheduling apparatus `timeline` carries. Before this fix, a
        // leader whose control link merely blipped reported `track_hash: nil, queue_item_id: nil` in
        // its next STATE_SNAPSHOT even while still audibly playing something, because the snapshot
        // read `timeline?.trackHash`/`timeline?.queueItemId` — both wiped here — instead of anything
        // that survives a link loss the way ADR-004 says local playback itself does.
        driftState = DriftController.reset()
        playbackDesynchronized = false
        queueDesynchronized = false
        // ADR-024 Amendment A8: `queueState` is deliberately **not** reset here. Everything above it
        // is session-bound coordination state (sequence numbering, chains, epoch, timeline, drift) —
        // scoped to the authentication generation that is ending, correctly retired with it. The
        // queue is not: it is ride-segment-local state PROTOCOL §10 assumes survives a link loss
        // ("session_id survives a reconnect… the follower adopts the leader's command_seq and
        // queue_revision wholesale" presumes the leader still *has* authoritative state to resume
        // from), and this repo's brief rule 7 requires it, the same principle already applied to
        // capture/voice consent surviving a control-lifetime boundary. Before this fix, an ordinary
        // link loss unconditionally wiped it via `queueState = SharedQueueState()` here, with no
        // leader/follower distinction and nothing downstream that ever repopulated a leader's copy —
        // reachable with no peer at all, see `testALeadersQueueSurvivesAnOrdinaryLinkLoss`.
        diagnostics.syncState = .inactive
        diagnostics.lastAppliedCommandSeq = nil
        diagnostics.lastReceivedCommandSeq = nil
        diagnostics.nextCommandSeq = nil
        diagnostics.queueRevision = queueState.revision
        diagnostics.queueSize = queueState.items.count
        diagnostics.currentTrackHash = nil
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.lastCorrection = .none
        diagnostics.playbackRate = DriftController.rateNormal
        diagnostics.hardSeekCount = 0
        diagnostics.lastScheduleErrorUs = nil
        diagnostics.sessionGeneration = await session.currentAuthGeneration()
        // Amendment A4 Finding D: `liveGeneration` is written wherever `diagnostics.sessionGeneration`
        // is, and nowhere else, so there is one answer to "which generation does this actor believe
        // is live" rather than two that could disagree.
        liveGeneration = diagnostics.sessionGeneration
        diagnostics.correctionTickCount = 0
        diagnostics.deferredCommandCount = 0
        diagnostics.ingressDesynchronized = false
        diagnostics.outboundAuthorityLost = false
        diagnostics.cancelledPendingPlayCount += cancelled
        publishQueue()
        publishDiagnostics()
        // Amendment A6 Finding B, swept: the third caller. Last, so nothing this reset decides can
        // be overtaken by a second boundary landing inside the rate restore.
        await restoreRate()
    }

    /// ADR-023 §3's guard, re-proved at every transition rather than once at handler entry.
    func stillCurrent(_ generation: Int64) async -> Bool {
        guard role != nil else { return false }
        let live = await session.currentAuthGeneration()
        return generation == live
    }

    /// Both halves of "this work is still authorised": the session that dispatched it, and the
    /// playback epoch that armed it. Amendment A1 Finding F made this a named predicate because it
    /// has to be re-proved *after* every suspension that precedes an externally visible effect, not
    /// only before the first one.
    func owns(generation: Int64, token: Int64) async -> Bool {
        await stillCurrent(generation) && epoch.isCurrent(token)
    }

    /// `stillCurrent`, taken **synchronously** from state this actor owns (ADR-024 Amendment A5,
    /// generalising Amendment A4 Finding D).
    ///
    /// Android's `stillCurrent` is already synchronous — `SyncSessionPort.currentAuthGeneration` is
    /// a plain property there — so a proof placed immediately before a mutation is atomic with
    /// performing it. On this platform it must `await`, and that `await` is an actor re-entrancy
    /// point: a boundary landing in it means the guard resumes and mutates state for a session that
    /// ended while the guard was being taken. Reading the mirrored generation instead makes the
    /// proof and the mutation one actor-isolated step, which is the same property Amendment A1
    /// Finding B established for stamping-and-enqueueing.
    ///
    /// A4 gave this only to work that owns a **playback epoch** (`ownsNow`). A5's three findings are
    /// all in work that legitimately has no epoch yet — an admission decides ordering before any
    /// track is chosen — so requiring one would refuse perfectly valid work. The session half alone
    /// is what such an operation can honestly prove, and it is what it must prove.
    ///
    /// Used **with** `stillCurrent`, never instead of it: see `liveGeneration`.
    func stillCurrentNow(_ generation: Int64) -> Bool {
        role != nil && generation == liveGeneration
    }

    /// `owns`, taken synchronously. `stillCurrentNow` plus the playback epoch, for work that has one.
    func ownsNow(generation: Int64, token: Int64) -> Bool {
        stillCurrentNow(generation) && epoch.isCurrent(token)
    }

    // MARK: - Fenced player steps (ADR-024 Amendment A4)

    /// Runs a sequence of **single-effect** player steps, re-proving ownership before each one.
    ///
    /// This is Amendment A4's whole invariant in one function. A3 proved that a command whose
    /// session ended while it queued must do nothing; A4 is the narrower case A3 left open — an
    /// operation that legitimately *started* under Session A, suspended inside its first effect, and
    /// then chose to perform a *second* effect after Session B was live. Two player calls behind one
    /// ownership proof is exactly that shape, and it existed in three places: `applyTransport`'s
    /// pause-then-seek and seek-then-start, `MusicCoordinator.syncPrepare`'s load-then-seek, and
    /// `MusicCoordinator.syncStop`'s stop-then-clear-the-local-queue.
    ///
    /// Two proofs per step, deliberately. `owns` catches a generation the session manager has
    /// already advanced but this actor has not been told about yet; `ownsNow` is synchronous, so
    /// nothing can run on this actor between it and the step it authorises. Neither is redundant —
    /// see `liveGeneration`.
    ///
    /// **It does not, and cannot, un-start a step already dispatched.** ADR-024 Amendment A4 §C: an
    /// indivisible platform effect authorised while Session A held the session may complete after
    /// Session A ends, and rolling that back is not something a player exposes. What is closed is
    /// the *next* effect.
    ///
    /// - Returns: true only if every step ran.
    @discardableResult
    func runOwnedSteps(_ steps: [PlayerStep], generation: Int64, token: Int64) async -> Bool {
        for step in steps {
            guard await owns(generation: generation, token: token) else { return false }
            // No `await` between this proof and the dispatch below.
            guard ownsNow(generation: generation, token: token) else { return false }
            await perform(step)
        }
        return true
    }

    /// The one place a `PlayerStep` becomes a call on the port. Exhaustive by construction: a new
    /// step cannot be added without a case here, and a new *effect* cannot be added without a step.
    private func perform(_ step: PlayerStep) async {
        switch step {
        case .select(let content): await player.select(content: content)
        case .load(let content): await player.load(content: content)
        case .seek(let positionMs): await player.seek(positionMs: positionMs)
        case .start: await player.start()
        case .pause: await player.pause()
        case .setRate(let rate): await player.setRate(rate)
        case .stop: await player.stop()
        case .clearSelection: await player.clearSelection()
        }
    }

    // MARK: - The clock

    /// The session clock as **this** device sees it.
    ///
    /// On the leader the session clock *is* the local monotonic clock, so the offset is exactly zero
    /// — but readiness is still taken from the estimator. The leader cannot observe whether the
    /// follower's own burst has converged, and its own first accepted window is the best available
    /// evidence that both sides' bursts have completed on a healthy link. The follower's own
    /// `ready` gate is what actually protects it.
    func estimate() async -> SessionClockEstimate? {
        let raw = await session.sessionClockEstimate()
        switch role {
        case .leader:
            return SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: await session.rttP95Us(), ready: raw?.ready == true)
        case .follower:
            return raw
        case nil:
            return nil
        }
    }

    /// `estimate`, with the "not ready" diagnostics it publishes fenced by the generation that asked
    /// for it (ADR-024 Amendment A5).
    ///
    /// `estimate()` suspends — `sessionClockEstimate()` and `rttP95Us()` are both cross-actor reads —
    /// and this used to publish straight afterwards with no generation available to prove. A leader
    /// whose session ended inside that read announced `clockUnready` on the session that replaced it.
    func readyEstimate(generation: Int64) async -> SessionClockEstimate? {
        let estimate = await estimate()
        guard await stillCurrent(generation) else { return nil }
        // No `await` between this proof and the writes below.
        guard stillCurrentNow(generation) else { return nil }
        guard let estimate, estimate.ready else {
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .clockUnready }
            diagnostics.clockReady = false
            publishDiagnostics()
            return nil
        }
        return estimate
    }

    // MARK: - The one ordered outbound path (Amendment A1 Finding B)

    /// Hands one frame to the ordered outbound path.
    ///
    /// Synchronous by design. Every caller stamps a `command_seq` or a `queue_revision` and calls
    /// this with **no `await` in between**, which is what makes actor isolation cover the pair.
    ///
    /// **Amendment A2 Finding A: this answers whether the frame was accepted, and every caller
    /// branches on the answer.** It used to return `Void`, so a refusal was a counter and the caller
    /// carried on regardless — stamping a `command_seq`, bumping a `queue_revision` and starting
    /// audio for a frame that had just been thrown away.
    ///
    /// - Returns: true if the frame is now on the one ordered outbound path. Being on it is *still*
    ///   not delivery: `drainOutbound` is what learns that, and `Phase5Outbound.onOutcome` is what
    ///   acts on it.
    @discardableResult
    func enqueueOutbound(_ envelope: Phase5Outbound) -> Bool {
        if outbound.offer(envelope) == .overflow {
            diagnostics.outboundOverflowCount += 1
            publishDiagnostics()
            return false
        }
        diagnostics.outboundEnqueuedCount += 1
        publishDiagnostics()
        return true
    }

    /// The single writer, and — since Amendment A2 — the single **commit point** for anything this
    /// device says with authority.
    ///
    /// Enqueue order is wire order, which was the whole of A1 Finding B's invariant. A2 adds the two
    /// facts that invariant did not carry:
    ///
    /// - **the frame is written under the session that authorised it, or not at all** (Finding B).
    ///   The generation is the envelope's, captured when the frame was created; resolving the writer
    ///   at send time meant a Session A frame could be written with Session B's `session_id`;
    /// - **the transport's answer is consumed** (Finding C). `send` returns false when there is no
    ///   authenticated writer or the write throws, and that used to increment "sent" anyway — the
    ///   `@discardableResult` on `SyncPlaybackChannel.send` made discarding it silent.
    ///
    /// Commits happen here, on this one consumer, so they are strictly in send order — which is
    /// `command_seq` order, because this consumer is also what sends.
    private func drainOutbound() async {
        while let envelope = await outbound.take() {
            let usable = await outboundUsable(envelope.generation)
            var outcome: OutboundOutcome = .staleSession
            if usable {
                outcome = await sendFrame(envelope.frame, generation: envelope.generation) ? .sent : .transportFailed
            }
            // Counted *after* the attempt finished, never before it started: this pair is what a
            // test reads to know the wire has caught up, and a counter incremented ahead of the
            // write would say "drained" while a frame was still inside the socket.
            diagnostics.outboundAttemptCount += 1
            switch outcome {
            case .sent: diagnostics.outboundSentCount += 1
            case .staleSession: diagnostics.outboundStaleCount += 1
            default: diagnostics.outboundFailedCount += 1
            }
            publishDiagnostics()
            await envelope.onOutcome?(outcome)
        }
    }

    private func sendFrame(_ frame: Phase5Outbound.Frame, generation: Int64) async -> Bool {
        switch frame {
        case .playback(let message): return await session.channel.send(message, authorizingGeneration: generation)
        case .queue(let message): return await session.channel.send(message, authorizingGeneration: generation)
        // Independent review, Blocker 1 (fixed): `ResyncRelay` now takes `authorizingGeneration` and
        // is bound to the one immutable `AuthenticatedConnection` record, exactly like Playback/Voice
        // — `outboundUsable(generation)`'s upstream proof alone left a window between that proof and
        // the actual write (the queue consumer, the actor hop, the write lock all suspend), which is
        // the same class ADR-020 Amendment A9 already closed for `VOICE_*`.
        case .resync(let message): return await resyncChannel?.send(message, generation: generation) ?? false
        }
    }

    /// Whether a frame authorised under `generation` may still be written (Amendment A2 Finding B).
    ///
    /// Two ways it may not. The obvious one is that the session it belonged to is gone — the
    /// generation is strictly increasing per authentication (ADR-023 §3), so a mismatch is decisive,
    /// and `stillCurrent` additionally refuses a link that has dropped but not yet re-authenticated.
    /// The other is that Phase 5 authority for this very generation has already been abandoned: once
    /// one authoritative frame failed, sending the ones queued behind it would tell the peer about
    /// commands whose predecessor it never got, which is a different divergence rather than a
    /// recovery.
    private func outboundUsable(_ generation: Int64) async -> Bool {
        if outboundAuthorityLost { return false }
        return await stillCurrent(generation)
    }

    /// What a producer does when the ordered outbound path refuses its frame outright
    /// (Amendment A2 Finding A). The frame never existed as far as the peer is concerned, so nothing
    /// it would have committed may be committed.
    func onOutboundRefused(_ authority: OutboundAuthority, generation: Int64) async {
        if OutboundCommitGate.decide(authority: authority, outcome: .admissionRefused) == .abortFailClosed {
            await failClosedOutbound(generation: generation)
        }
    }

    /// ADR-024 Amendment A2's fail-closed posture: **an authoritative frame this device produced did
    /// not reach the peer, so this device stops being authoritative.**
    ///
    /// Deliberately not a retry and deliberately not a reconciliation. `STATE_REQUEST` is catalogued
    /// in PROTOCOL §3 and still unimplemented, and even implemented it would be the *peer* asking
    /// for state it knows it is missing — a peer that never received a command does not know to ask.
    /// The two honest options are therefore to carry on from a state only this device knows about,
    /// which is the divergence this amendment exists to close, or to stop. This is stopping.
    ///
    /// What it does **not** do is stop the music, and it deliberately does **not** supersede the
    /// playback epoch. Synchronised mode is left, so `MusicCoordinator`'s remote commands answer
    /// locally again; correction stops and the rate goes back to exactly 1.0 (ADR-004, FR-025). But
    /// a frame the transport *did* accept, still in flight when a later one failed, must still
    /// commit and still take effect — the peer has it, so refusing to apply it here would
    /// manufacture the mirror image of the divergence this whole amendment is closing. Only work
    /// that was never delivered is abandoned. Recovery is a new session, which clears the latch in
    /// `resetForNewSession`.
    /// - Parameter state: ADR-024 Amendment A11: what the rider is told. `.transportFailed` for
    ///   every pre-existing caller — the write failed, or the ordered outbound path was full because
    ///   the socket is not draining. `.localOverload` for the one caller where the transport was
    ///   never asked: this device could not guarantee it could honour another command locally, so it
    ///   refused the command before sending it. The posture is identical; calling them the same
    ///   thing would not be true.
    func failClosedOutbound(generation: Int64, state: SyncState = .transportFailed) async {
        // A dead session needs no latch: its authority is already gone, and latching would then
        // survive into the session that replaced it.
        guard generation == (await session.currentAuthGeneration()) else { return }
        // Amendment A5: that read is itself a suspension, and everything below it is live state.
        guard stillCurrentNow(generation) else { return }
        guard !outboundAuthorityLost else { return }
        outboundAuthorityLost = true
        syncEnabled = false
        cancelTick()
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        discardDeferredEvents()
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        driftState = DriftController.reset()
        // Amendment A6 Finding B: **every** coordinator write this path makes happens here, before
        // the one suspension it takes, and nothing follows that suspension.
        //
        // The shape it replaces read `await restoreRate()` and *then* wrote these seven fields. The
        // rate restore is deliberately unfenced (see `restoreRate`) because it is the ending of an
        // authority — but "the player call may still happen" was silently taken to mean "and so may
        // everything after it". A boundary landing inside `player.setRate` therefore had Session A's
        // fail-closed verdict overwrite Session B's live diagnostics: `.transportFailed` and
        // `outboundAuthorityLost` on a session whose transport was working perfectly.
        //
        // Recording the rate here rather than inside `restoreRate` is the same rule one level down:
        // the call below is now one player effect with no write behind it.
        diagnostics.syncState = state
        diagnostics.outboundAuthorityLost = true
        diagnostics.deferredCommandCount = 0
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.playbackRate = DriftController.rateNormal
        diagnostics.cancelledPendingPlayCount += cancelled
        publishDiagnostics()
        await restoreRate()
    }

    /// Runs a leader's own authoritative apply, in commit order (Amendment A2 Finding A).
    ///
    /// See `applyChain`: the commit hook runs on `drainOutbound`'s single consumer, so doing the
    /// apply there would stall the outbound path behind a decoder pre-roll, and an unstructured
    /// `Task` preserves nothing at all about order — precisely the defect A1 Finding G was about.
    func chainApply(
        generation: Int64,
        /// ADR-024 Amendment A11: the capacity `issue` reserved **before** the frame could reach the
        /// peer. Released when this node finishes — unless the node arms a scheduled action, which
        /// joins the same obligation first, so the obligation outlives the apply exactly as far as
        /// the audible effect it promised does.
        reservation: WorkReservation,
        _ action: @escaping @Sendable () async -> Void
    ) {
        let previous = applyChain
        let id = claimChainNodeId()
        let node = Task { [weak self] in
            await previous?.value
            await self?.runApplyNode(id: id, generation: generation, reservation: reservation, action: action)
        }
        applyChain = node
        trackChainNode(id, node)
    }

    /// One apply-chain node's body, once the node ahead of it has finished (Amendment A3 Finding A).
    ///
    /// Waiting for that node is a suspension like any other, and an authentication boundary can land
    /// inside it — which is the whole defect. The cancellation check is cheap and prompt; the
    /// generation is what is *decisive*, because the node ahead may have been parked in a player call
    /// that ignored the cancellation entirely.
    private func runApplyNode(
        id: Int64,
        generation: Int64,
        reservation: WorkReservation,
        action: @Sendable () async -> Void
    ) async {
        defer { releaseChainNode(id) }
        // Amendment A11: the apply phase is over however it ended — applied, refused for a retired
        // lifetime, or cancelled outright. A boundary may have released this obligation already, in
        // which case this is a no-op on an id the ledger no longer holds and can never touch the
        // reservation that replaced it.
        defer { releaseWork(reservation) }
        guard !Task.isCancelled, await stillCurrent(generation) else { return }
        // Amendment A5: the proof above suspends, so the synchronous mirror is what makes reaching
        // `action` atomic with having proved it. `action` re-proves for itself as well (A3 Finding B).
        guard stillCurrentNow(generation) else { return }
        await action()
    }

    /// Takes capacity for one local playback obligation, or answers `nil` (ADR-024 Amendment A11).
    ///
    /// **Every caller is somewhere the answer can still change what reaches the wire.** A `nil` here
    /// is the whole point of the amendment: the leader has not sent anything yet, so it refuses the
    /// command outright; a follower has not spent the `command_seq` yet, so it declares itself
    /// desynchronised and lets the existing reconciliation repair it; a replay leaves the work where
    /// it already is. None of them abandons authority the peer already holds, which is precisely
    /// what Phase 8's first attempt did.
    func reserveWork(generation: Int64) -> WorkReservation? {
        let reservation = workLedger.reserve(generation: generation)
        if reservation == nil { diagnostics.workCapacityRefusedCount += 1 }
        publishRetainedWork()
        return reservation
    }

    func releaseWork(_ reservation: WorkReservation) {
        workLedger.leavePhase(reservation)
        publishRetainedWork()
    }

    /// Whether a reservation is *likely* to succeed — a pre-check, never an authority.
    ///
    /// This pass's own fresh-fix audit: `drainDeferredEvents`' `.playbackState` branch pops its item
    /// **before** the apply that needs capacity, and the apply re-appends it when there is none. With
    /// no pre-check the drain pops the same item, fails, re-appends and pops it again in a tight
    /// loop — the exact shape the clock and content pre-checks above it already exist to prevent. It
    /// is deliberately advisory: `reserveWork` is still what decides, so a reservation taken between
    /// this check and that one merely costs one extra drain iteration rather than correctness.
    var hasWorkCapacity: Bool { workLedger.liveCount < workLedger.capacity }

    func publishRetainedWork() {
        diagnostics.retainedWorkCount = workLedger.liveCount
        diagnostics.peakRetainedWorkCount = max(diagnostics.peakRetainedWorkCount, workLedger.liveCount)
        publishDiagnostics()
    }

    /// A follower could not take responsibility for more authoritative work (ADR-024 Amendment A11).
    ///
    /// The same explicit halt-and-reconcile posture as an ingress overflow or a held-stream overflow,
    /// and for exactly the same reason: more authority is outstanding than this device can honestly
    /// account for. **No sequence number is spent**, so the authoritative snapshot that reconciles us
    /// decides where ordering resumes (Amendment A1 Finding C's rule). Deliberately *not* the
    /// leader's fail-closed posture — a follower has delivered nothing and owes the peer nothing.
    func onWorkCapacityExhausted() {
        latchDesynchronized()
    }

    /// The bound a boundedness test reads: outstanding local obligations, never task objects.
    var retainedWorkCount: Int { workLedger.liveCount }

    /// Reserves an identity for one chain node. Monotonic, so a retired node's own cleanup can never
    /// remove a node the *next* session created.
    func claimChainNodeId() -> Int64 {
        nextChainNodeId += 1
        return nextChainNodeId
    }

    func trackChainNode(_ id: Int64, _ node: Task<Void, Never>) {
        sessionChainNodes[id] = node
    }

    func releaseChainNode(_ id: Int64) {
        sessionChainNodes[id] = nil
    }

    /// Retires both chains: the live set is cleared first, then every node in it is cancelled, then
    /// the two tails are dropped so nothing new joins what has just been retired.
    private func retireSessionChains() {
        let nodes = sessionChainNodes
        sessionChainNodes.removeAll()
        for node in nodes.values { node.cancel() }
        scheduledChain = nil
        applyChain = nil
    }

    // MARK: - Issuing

    /// ADR-010's whole design in one function. The leader stamps and broadcasts; a follower sends the
    /// **same message type** with `command_seq: 0` — ADR-024 §3's intent marker — and waits for the
    /// leader's authoritative broadcast to arrive back.
    ///
    /// `effective_at_session_us` on an intent is `0` and is ignored by the leader: a follower has no
    /// authority to choose when something becomes audible.
    ///
    /// **Amendment A2 Finding A: the local commit moved out of this function entirely.** It used to
    /// set `lastReceivedSeq`/`lastAppliedSeq` and then apply the command — both unconditionally,
    /// because `enqueueOutbound` could not report a refusal and `drainOutbound` discarded the
    /// transport's answer. A full outbound queue or a dead socket therefore produced a leader
    /// playing a command the follower never received. What happens here now is *candidate* work:
    /// stamp, try to admit, and consume the sequence number only if the admission succeeded.
    /// Everything after that is `onCommandOutcome`, which the single outbound consumer calls with
    /// what actually happened.
    ///
    /// **Independent-review round 7: `ride` is the caller's, never read here.** Every caller captures
    /// it before its own first suspension — the user action before it reads the player, the retained
    /// Play from the press that created it, a follower's intent at the instant the leader admitted it
    /// — and it is proved live in the same no-`await` block that stamps the header, so a command whose
    /// ride ended is never stamped, never enqueued, and never applied. The envelope carries it back to
    /// `onCommandOutcome`, so the leader's own local apply is authorised by the ride that *issued* the
    /// command rather than by whichever ride is current when the transport finally answers.
    func issue(ride: RideAdmission, _ build: (PlaybackCommandHeader) -> PlaybackMessage) async {
        guard let currentRole = role, !outboundAuthorityLost else { return }
        let generation = await session.currentAuthGeneration()
        if currentRole == .follower {
            guard await stillCurrent(generation) else { return }
            // Amendment A5: and the synchronous mirror, because the proof above is itself a
            // suspension. No `await` from here to the enqueue: the actor makes the pair atomic
            // (Finding B).
            guard stillCurrentNow(generation) else { return }
            // Round 7: and the ride that admitted this action, proved in the same block.
            guard rideStillLive(ride) else { return }
            let header = PlaybackCommandHeader(
                commandSeq: PlaybackBounds.unassignedCommandSeq,
                effectiveAtSessionUs: 0,
                issuedBy: localPeerId,
                queueRevision: queueState.revision
            )
            // An intent owns no authority (ADR-024 §3), so nothing local is riding on it and there
            // is nothing to roll back — but a refusal is still not a send.
            let admitted = enqueueOutbound(
                Phase5Outbound(generation: generation, authority: .intent, frame: .playback(build(header)))
            )
            if !admitted { await onOutboundRefused(.intent, generation: generation) }
            return
        }
        guard let estimate = await readyEstimate(generation: generation) else { return }
        guard await stillCurrent(generation), !outboundAuthorityLost else { return }
        // Amendment A5: `nextSeq` below is the session's own ordering state, and the proof above
        // suspends — so a boundary landing in it would let this stamp Session B's sequence number.
        // No `await` from here to the enqueue.
        guard stillCurrentNow(generation) else { return }
        // Round 7: `readyEstimate` above suspends too, and End Ride moves no control generation.
        guard rideStillLive(ride) else { return }
        // **ADR-024 Amendment A11: capacity before delivery, in the same no-`await` block as the
        // `command_seq` allocation and the hand-off to the wire.** This is the one placement that
        // makes the invariant structural: the peer cannot come to rely on a command this device has
        // no room left to honour, because the command is never stamped, never enqueued and never
        // written. Phase 8's first attempt asked the same question at the *apply* node, which on
        // this side of the pipeline is after the transport said yes.
        guard let reservation = reserveWork(generation: generation) else {
            // Deliberately the *existing* fail-closed posture rather than a new one: an authoritative
            // operation this device produced did not reach the peer, which is precisely what
            // `failClosedOutbound` is for. The state it publishes is `.localOverload` rather than
            // `.transportFailed`, because the transport did not fail — nothing was ever offered to
            // it. Sequence truth is untouched: a command that was never stamped leaves no gap, and
            // every command already delivered keeps its local obligation and its `lastAppliedSeq`.
            await failClosedOutbound(generation: generation, state: .localOverload)
            return
        }
        let seq = nextSeq
        let header = PlaybackCommandHeader(
            commandSeq: seq,
            effectiveAtSessionUs: estimate.sessionUs(localMonoUs: monotonicNowUs()) + estimate.leadUs,
            issuedBy: localPeerId,
            queueRevision: queueState.revision
        )
        let message = build(header)
        let admitted = enqueueOutbound(
            Phase5Outbound(generation: generation, authority: .authoritative, frame: .playback(message)) {
                [weak self] outcome in
                await self?.onCommandOutcome(
                    seq: seq, message: message, generation: generation, ride: ride, estimate: estimate,
                    reservation: reservation, outcome: outcome
                )
            }
        )
        // Amendment A2 §6: a `command_seq` becomes authoritative exactly when the frame carrying it
        // enters the outbound authority pipeline, and not a moment earlier. A refused candidate
        // leaves no gap, because it was never assigned.
        // Amendment A11: and its reservation goes straight back, so a refused admission cannot
        // consume capacity. Repeated refusals therefore leave the bound exactly where it was.
        guard admitted else {
            releaseWork(reservation)
            await onOutboundRefused(.authoritative, generation: generation)
            return
        }
        nextSeq = seq + 1
        diagnostics.nextCommandSeq = nextSeq
        publishDiagnostics()
    }

    /// The leader's own command, once the transport has answered (Amendment A2 Findings A and C).
    ///
    /// The leader is the assigner, so its own command cannot be lost between accepting and applying
    /// it — there is no inbound path that could replay it, because an authoritative command arriving
    /// at the leader is a role violation. Received and applied therefore still move together here;
    /// A1 Finding D's split matters on the receiving side. What changed is *when*: only on `.sent`,
    /// because a command the follower never received is not a command.
    ///
    /// The apply itself goes through the leader's ordered `applyChain` rather than running on the
    /// outbound consumer, so a decoder pre-roll cannot stall the wire. The leader applies its own
    /// command exactly as the follower will — same header, same effective instant, same code path —
    /// because an "issuer applies immediately" shortcut is precisely how two phones end up on two
    /// timelines.
    func onCommandOutcome(
        seq: Int64,
        message: PlaybackMessage,
        generation: Int64,
        /// Independent-review round 7: the ride that **issued** this command, carried on the outbound
        /// envelope. The transport answers across the outbound consumer, an actor hop and a real
        /// socket write, so reading a live ride here would be the same defect one layer out.
        ride: RideAdmission,
        estimate: SessionClockEstimate,
        /// ADR-024 Amendment A11: the capacity `issue` took **before** this frame could be written.
        /// Released on every branch that does not hand it to `chainApply` — an unsent frame owes no
        /// local work — so a session of nothing but failed sends never consumes the bound.
        reservation: WorkReservation,
        outcome: OutboundOutcome
    ) async {
        switch OutboundCommitGate.decide(authority: .authoritative, outcome: outcome) {
        case .abortFailClosed:
            releaseWork(reservation)
            await failClosedOutbound(generation: generation)
            return
        case .abortQuiet:
            releaseWork(reservation)
            return
        case .commit:
            break
        }
        // Deliberately **not** gated on `outboundAuthorityLost`: this frame reached the peer, so the
        // peer will act on it, and the only consistent thing this device can do is act on it too.
        // The latch stops *new* authority; it does not un-send what was sent.
        guard await stillCurrent(generation) else {
            releaseWork(reservation)
            return
        }
        // Amendment A5: the two sequence numbers below are exactly what Finding A is about, reached
        // from the leader's side. No `await` between the synchronous proof and the writes.
        guard stillCurrentNow(generation) else {
            releaseWork(reservation)
            return
        }
        // **Independent-review round 8's sweep, the leader's own half of Blocker B.** The transport
        // answers across the outbound consumer, an actor hop and a real socket write, and
        // `stillCurrent` above suspends again — so a ride boundary accepted in any of those windows
        // leaves the control generation untouched and both proofs above passing. The two writes
        // below would then publish this `command_seq` as applied while `chainApply`'s
        // `applyAuthoritative` refused it as `.rejectedRide`: the same false bookkeeping as the
        // receiving side's, reached from the issuing side.
        //
        // The frame did reach the peer, and this deliberately does not un-send it — but nothing on
        // this device applied it, so nothing on this device may claim it did. Proved with no
        // `await` between the proof and the writes.
        guard rideStillLive(ride) else {
            releaseWork(reservation)
            diagnostics.retiredRideAdmissionCount += 1
            publishDiagnostics()
            return
        }
        // max, not assignment: these commit on the outbound consumer, in send order, and a monotone
        // write says the same thing without depending on that ordering twice over.
        lastReceivedSeq = max(lastReceivedSeq ?? seq, seq)
        lastAppliedSeq = max(lastAppliedSeq ?? seq, seq)
        diagnostics.lastAppliedCommandSeq = lastAppliedSeq
        diagnostics.lastReceivedCommandSeq = lastReceivedSeq
        publishDiagnostics()
        // Amendment A11: the reservation moves into the ordered apply, which is now guaranteed to
        // be creatable — that guarantee *is* the fix. The node releases it when its work is done.
        chainApply(generation: generation, reservation: reservation) { [weak self] in
            await self?.applyAuthoritative(
                message, generation: generation, ride: ride, estimate: estimate, reservation: reservation
            )
        }
    }

    // MARK: - User-facing actions (also the remote-command path, brief §39)

    /// Starts synchronised playback of `contentHash` — **one press, one eventual authoritative
    /// `PLAY`** (Amendment A1 Findings A and E).
    ///
    /// The request is *retained*, not attempted-and-forgotten. Two things can legitimately not be
    /// ready yet, and before this amendment each of them silently cost the user a second press:
    ///
    /// - the track may not be in the **authoritative** queue yet, and a `PLAY` stamped against a
    ///   revision the leader has already moved past is refused by the leader's own stale-revision
    ///   rule (Finding A);
    /// - the track may not be playable here yet, in which case Phase 4 is asked for it and the
    ///   request waits for the *verified* cache rather than for another button press (Finding E).
    ///
    /// Neither is fixed by weakening the revision rule or by starting playback early. Both halves of
    /// brief §19's gate still hold before anything is issued.
    public func playSynchronized(_ contentHash: ContentHash) async {
        guard role != nil else { return }
        // Amendment A2: authority for this generation is over, so there is nothing to press Play
        // *into*. Local playback is Phase 3's again and answers this on its own.
        guard !outboundAuthorityLost else { return }
        syncEnabled = true
        // Independent-review round 7: the press's own ride, captured before this function's first
        // suspension. It is stored on the retained request and travels all the way to the `PLAY` this
        // press eventually issues — never re-read when the transfer that unblocked it completes.
        let ride = admitRide()
        let generation = await session.currentAuthGeneration()
        // Amendment A5: `playRequestFence.begin()` below supersedes whatever Play is current, so a
        // press whose session changed inside that read would cancel the live session's own retained
        // Play. The synchronous proof is also what refuses a press stamped for a generation this
        // actor has not been told about yet — `resolvePendingPlay` would otherwise issue it into a
        // session whose state has not been reset.
        guard stillCurrentNow(generation) else { return }
        // Independent-review round 8's sweep: `currentAuthGeneration()` above suspends, and
        // `playRequestFence.begin()` below **supersedes** whatever retained Play is current. A press
        // whose ride ended inside that read would therefore cancel a *successor* ride's retained
        // Play and install one of its own that `resolvePendingPlay` can only cancel — so the ride is
        // proved here, adjacent to the fence, rather than only where the request is resolved.
        guard rideStillLive(ride) else { return }
        // No `await` in this block: the fence, the id and the retained request move together.
        let existing = queueState.items.first { $0.trackHash == contentHash }
        let queueItemId = existing?.queueItemId ?? nextQueueItemId()
        // begin() supersedes whatever earlier request held the slot, which is what makes "the user
        // asked for X while H was still transferring" resolve to X and only X.
        pendingPlay = PendingPlay(
            token: playRequestFence.begin(), generation: generation, ride: ride, contentHash: contentHash,
            queueItemId: queueItemId, positionMs: 0
        )
        if existing == nil {
            await mutateQueue(.add(items: [
                QueueAddItem(
                    queueItemId: queueItemId, trackHash: contentHash, addedBy: localPeerId,
                    position: PlaybackBounds.queuePositionEnd
                ),
            ]))
        }
        await resolvePendingPlay()
    }

    public func enqueue(_ contentHash: ContentHash) async {
        await mutateQueue(.add(items: [
            QueueAddItem(
                queueItemId: nextQueueItemId(), trackHash: contentHash, addedBy: localPeerId,
                position: PlaybackBounds.queuePositionEnd
            ),
        ]))
    }

    public func removeFromQueue(_ queueItemId: String) async { await mutateQueue(.remove(queueItemIds: [queueItemId])) }

    public func moveInQueue(_ queueItemId: String, toIndex: Int) async {
        await mutateQueue(.move(queueItemId: queueItemId, toIndex: toIndex))
    }

    // Independent-review round 7: each of these captures the ride **before** its own first
    // suspension — `playerState()` is a cross-actor read — and hands it to `issue`, which proves it
    // adjacent to the stamp. A transport press made in ride 1 can no longer be stamped, sent and
    // applied as ride 2's authority.

    public func pause() async {
        let ride = admitRide()
        let position = max(await player.playerState().positionMs, 0)
        await issue(ride: ride) { header in .pause(header: header, positionMs: position) }
    }

    public func resume() async {
        let ride = admitRide()
        let position = max(await player.playerState().positionMs, 0)
        await issue(ride: ride) { header in .resume(header: header, positionMs: position) }
    }

    public func seek(positionMs: Int64) async {
        await issue(ride: admitRide()) { header in .seek(header: header, targetPositionMs: max(positionMs, 0)) }
    }

    public func next() async { await issue(ride: admitRide()) { header in .next(header: header) } }

    public func previous() async { await issue(ride: admitRide()) { header in .previous(header: header) } }

    /// ARCHITECTURE §3's `RIDE_ACTIVE -> CONNECTED`, reaching the one owner of ride-segment playback
    /// authority (independent-review round 3, Blocker C; ADR-028 Amendment A2).
    ///
    /// **End Ride is not End Session.** The control connection, the pairing and the peer session all
    /// stay alive, and local music keeps playing exactly as a Phase 3 ride — which is precisely what
    /// `leaveSynchronizedMode` already means, so this is that call plus the ride-lifetime proof,
    /// never a second teardown path and never a second player owner.
    ///
    /// What it must end is the *ride segment's* synchronisation authority, and the reason is
    /// `currentPlaybackIdentity`: it deliberately survives an ordinary control-link loss (Blocker 2B),
    /// so without this the track ride 1 was playing was still the value a leader's `STATE_SNAPSHOT`
    /// reported after ride 2 had begun — a stale identity presented as ride 2's authoritative truth.
    ///
    /// **Independent-review round 4, Blocker 1: what the guard compares changed.** Round 3 refused a
    /// cleanup whose epoch was no newer than the current ride epoch — "a newer ride-lifecycle
    /// *decision* has been taken". That is the wrong question, and it satisfied only half of what an
    /// End Ride boundary owes:
    ///
    /// - **Property A** — a late cleanup must never destroy a successor ride's state. Round 3 had
    ///   this right.
    /// - **Property B** — ride 1's state must never survive into ride 2 merely because its cleanup
    ///   was delayed. Round 3 got this wrong: `startRide` deliberately establishes nothing, so a
    ///   Start Ride that merely *bumped the epoch* made ride 1's cleanup "stale" while leaving ride
    ///   1's `currentPlaybackIdentity` in place as the only thing a ride-2 `STATE_SNAPSHOT` had to
    ///   report.
    ///
    /// Both hold when the comparison is against `rideAuthorityEpoch` — the ride that established the
    /// authority actually standing here — rather than against whichever ride is nominally current.
    /// A strictly newer ride owning live authority is the one and only case where this boundary has
    /// nothing to do; in every other case what is standing belongs to this ride or an earlier one,
    /// and ending the ride is exactly the instant it must go.
    ///
    /// **Independent-review round 6: the comparison is `<`, not `<=`.** `rideEpoch` is the epoch
    /// *this* End Ride minted for itself, and it also names the CONNECTED-state gap that follows —
    /// synchronised playback stays usable there (a Start Ride establishes nothing new), so genuinely
    /// new authority admitted in that gap, before a further Start Ride, is stamped with this same
    /// value by `recordRideAuthority`. `<=` could not tell that authority apart from ride 1's own
    /// stale residue — both compare equal to `rideEpoch` — and cleared it. Only a value **older**
    /// than this boundary's own belongs to the ride that is ending.
    ///
    /// - Parameter rideEpoch: the strictly-increasing epoch `RideSegmentLifecycle` assigned to *this*
    ///   End Ride, synchronously, before the hop that carried it here. The guard is the first
    ///   statement and every mutation `leaveSynchronizedMode` performs precedes its single trailing
    ///   `await`, so there is no suspension between proving ownership and acting on it.
    /// - Returns: whether this boundary cleared the ride segment, or found a newer ride's authority
    ///   and left it alone. `RideSegmentLifecycle` counts the second; no caller may act on the first.
    @discardableResult
    public func endRideSegment(rideEpoch: Int64) async -> RideBoundaryOutcome {
        guard rideAuthorityEpoch < rideEpoch else {
            diagnostics.staleRideLifecycleCount += 1
            publishDiagnostics()
            return .supersededByLiveRideAuthority
        }
        await leaveSynchronizedMode()
        return .cleared
    }

    /// Leaves synchronised mode without ending the control session: local playback continues exactly
    /// as a Phase 3 ride, correction stops and the rate goes back to exactly 1.0 (brief §38).
    public func leaveSynchronizedMode() async {
        // First statement: everything below retires synchronised-mode state, and an apply already in
        // flight must be refused before it can write any of it back.
        synchronizedModeEpoch += 1
        syncEnabled = false
        epoch.supersede()
        // Amendment A1 Finding E: leaving synchronised mode cancels the retained Play. A transfer
        // completing afterwards must not start music the user has stopped asking for.
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        discardDeferredEvents()
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        timeline = nil
        // Independent review, Race 7 (mirroring Android's identical finding): leaving synchronised
        // mode is the user genuinely ending authoritative playback, not a control-lifetime blip —
        // unlike `resetForNewSession`, which deliberately preserves `currentPlaybackIdentity` across
        // a reconnect (Blocker 2B), this is a legitimate place for ride-segment identity to clear
        // too. Without this, a stale track hash from an already-ended synchronised session would
        // still be reported by the next `enqueueStateSnapshotReply` as if still authoritative.
        currentPlaybackIdentity = nil
        // Nothing is established any more, so no ride owns authority. Kept in lockstep with
        // `currentPlaybackIdentity` above — the two answer "what is standing" and "whose it is", and
        // they must never disagree.
        rideAuthorityEpoch = 0
        driftState = DriftController.reset()
        // Amendment A6 Finding B, swept: the identical post-`restoreRate` write shape, in the second
        // of that call's three callers. Every write first, the unfenced player effect last.
        diagnostics.syncState = .inactive
        diagnostics.currentTrackHash = nil
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.deferredCommandCount = 0
        diagnostics.playbackRate = DriftController.rateNormal
        diagnostics.cancelledPendingPlayCount += cancelled
        publishDiagnostics()
        await restoreRate()
    }

    /// Whether a synchronised session currently owns transport control (brief §39/§40).
    public func isSynchronizedModeActive() -> Bool { syncEnabled && role != nil }

    /// Brief §38's "correction always ends at exactly 1.0", and the one player call in this phase
    /// that is deliberately **not** fenced (ADR-024 Amendment A4 §D).
    ///
    /// Its three callers — `resetForNewSession`, `failClosedOutbound` and `leaveSynchronizedMode` —
    /// are all the *ending* of an authority, so there is no generation left to prove and fencing it
    /// would leave the previous session's nudge in force on music ADR-004 says keeps playing. It is
    /// idempotent, it names an absolute rate rather than a relative one, and 1.0 is what the next
    /// session would set anyway, so a late one cannot fight a live correction into a wrong value.
    ///
    /// **Amendment A6 §H: the exemption is for the player effect and nothing else.** This used to
    /// write `diagnostics.playbackRate` *after* `setRate` returned — a coordinator-state write from
    /// a dead session, which is exactly the class A5 closed everywhere else, and the one the three
    /// callers were guilty of on a larger scale. Each caller now records the rate itself, before
    /// this is called, so what remains here is one player call with nothing behind it: an old
    /// authority may still finish restoring 1.0, and may write nothing while doing it.
    func restoreRate() async {
        await player.setRate(DriftController.rateNormal)
    }

    /// Throws the held authoritative stream away, and tells whoever was waiting on a reconciliation
    /// in it that it was **discarded** (independent-review round 4, Blocker 2).
    ///
    /// The one place `deferredEvents` is emptied wholesale, so a cancellation cannot be forgotten at
    /// one of the four callers that legitimately do this — `leaveSynchronizedMode` (End Ride, "Play
    /// locally"), `resetForNewSession` (a control-lifetime boundary, including a terminal teardown's
    /// link loss), `failClosedOutbound`, and `drainDeferredEvents` finding its generation retired.
    /// Each retained anchor reports its own obligation id and the generation that authorised it;
    /// nothing is inferred from the buffer merely becoming empty, which is the inference round 3
    /// already had to remove once.
    ///
    /// Deliberately does **not** publish diagnostics or touch `deferredCommandCount`: every caller
    /// already writes its own diagnostics block, and adding a second publish here would emit a
    /// half-updated snapshot between them.
    func discardDeferredEvents() {
        guard !deferredEvents.isEmpty else { return }
        let discarded = deferredEvents
        deferredEvents.removeAll()
        for event in discarded {
            guard let obligation = event.reconciliation else { continue }
            onReconciliationCancelled?(obligation, event.generation)
        }
    }

    func publishDiagnostics() { onDiagnosticsChanged?(diagnostics) }

    func publishQueue() {
        diagnostics.queueRevision = queueState.revision
        diagnostics.queueSize = queueState.items.count
        onQueueChanged?(queueState)
    }
}

/// One press of Play, retained across the waits it has to survive (Amendment A1 Findings A/E).
///
/// Fenced by `token` rather than keyed on `contentHash`: the same track can legitimately be asked
/// for again in a later epoch, so a hash would let a superseded request resurrect when a transfer it
/// no longer owns completes (brief §18/§32).
struct PendingPlay: Sendable {
    let token: Int64
    let generation: Int64
    /// The ride lifetime that admitted this press (independent-review round 7).
    ///
    /// A retained Play is stored work by definition — it exists precisely so one press survives a
    /// queue revision and a file transfer — so it must carry its own authorising ride exactly as the
    /// held authoritative stream does. `leaveSynchronizedMode` clears it, but an End Ride's cleanup
    /// is asynchronous on this platform: between the FSM accepting the End Ride and that cleanup
    /// running, a completing transfer could otherwise fire `resolvePendingPlay` and issue ride 1's
    /// `PLAY` under a successor ride, establishing ride-1 work as ride-2 authority — Bug A's shape
    /// reached through `pendingPlay` rather than through `deferredEvents`.
    let ride: RideAdmission
    let contentHash: ContentHash
    let queueItemId: String
    /// The intent's own `position_ms`, so serving a follower's Play never silently rewinds it to 0.
    let positionMs: Int64
}

/// The ride lifetime that authorised one operation, captured once at the instant this coordinator
/// admitted it and thereafter **compared, never re-derived** (independent-review round 7; ADR-028
/// Amendment A6).
///
/// Round 6 established the two values and threaded them through directly-executing work. Round 7 is
/// the half that was missing: *retained* work — a held authoritative event, a retained Play — dropped
/// them at the point of storage and a later replay captured whatever ride was current then. That is
/// the repository's standing provenance rule (CLAUDE.md rules 19/20) violated for the third lifetime,
/// in the one place it is hardest to see, because the loss happens at *storage* rather than at a read.
///
/// The two fields are one value so that a caller cannot thread one without the other, and so that
/// storing provenance is a single field on the retained event rather than a pair a future edit could
/// half-forget. See `SyncPlaybackCoordinator.rideStillLive` for why both are required.
struct RideAdmission: Sendable, Equatable {
    /// `SyncPlaybackCoordinator.synchronizedModeEpoch` at admission — moves when synchronised mode is
    /// actually **left** (End Ride cleanup, "Play locally").
    let synchronizedModeEpoch: Int64
    /// `RideEpochBox.current` at admission — moves the instant a Start Ride or an End Ride is
    /// **accepted**, synchronously, before any continuation runs.
    let rideEpoch: Int64
}

/// One outbound Phase 5 frame, waiting its turn on the one ordered outbound path — with the two
/// things Amendment A2 found missing from it.
///
/// - `generation` is the authentication generation that **authorised** this frame, captured when it
///   was created rather than looked up when it is written (Finding B). The outbound queue
///   deliberately outlives individual sessions, so a frame stamped under Session A that is still
///   queued when Session B activates must not be written using Session B's writer and `session_id`.
///   Looking the generation up at send time is exactly how that happens.
/// - `authority` is what committing this frame's local effect would mean — see `OutboundAuthority`.
/// - `onOutcome` is the producer's commit hook, invoked by the single consumer with what actually
///   happened. This is where a leader's `command_seq`, its `queue_revision` and its local audible
///   effect are committed, and it runs on the one consumer, so commits happen strictly in send
///   order (Findings A and C).
struct Phase5Outbound: Sendable {
    enum Frame: Sendable {
        case playback(PlaybackMessage)
        case queue(QueueMessage)
        /// PROTOCOL §10 (Phase 7, ADR-028 Amendment): a `STATE_SNAPSHOT` answering a `STATE_REQUEST`.
        /// Folded into this same enum, not a second outbound path, so it can never be written out of
        /// order relative to a `QUEUE_SNAPSHOT`/`PLAYBACK_STATE` decided around the same time — the
        /// exact hazard ADR-024 Amendment A1 Finding B closed for every other Phase 5 broadcast.
        case resync(ResyncMessage)
    }

    let generation: Int64
    let authority: OutboundAuthority
    let frame: Frame
    let onOutcome: (@Sendable (OutboundOutcome) async -> Void)?

    init(
        generation: Int64,
        authority: OutboundAuthority,
        frame: Frame,
        onOutcome: (@Sendable (OutboundOutcome) async -> Void)? = nil
    ) {
        self.generation = generation
        self.authority = authority
        self.frame = frame
        self.onOutcome = onOutcome
    }
}

/// One authoritative event accepted for ordering but not yet applied — because the clock is not
/// trusted, or because something ahead of it is not (Amendment A1 Finding D, widened by
/// Amendment A2 Finding D).
/// **Independent-review round 7: a retained event carries the ride that admitted it.** The control
/// generation was already stored here and compared at replay; the ride was not, so
/// `drainDeferredEvents` handed the work to an apply path that captured a *fresh* `RideAdmission` —
/// ride 1's held `PLAY` and ride 1's held `STATE_SNAPSHOT` were replayed as ride 2's work, and the
/// authority they established then made ride 1's own (correctly superseded-refusing) late cleanup
/// stand down. See `RideAdmission`.
enum DeferredEvent: Sendable {
    /// A `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS` the order gate accepted.
    case command(PlaybackMessage, generation: Int64, ride: RideAdmission)
    /// PROTOCOL §9's authoritative queue state, held so it cannot change a held command's meaning.
    ///
    /// **Deliberately the one case with no `RideAdmission`** (independent-review round 7's queue
    /// audit). The replicated queue is not ride-scoped state: `leaveSynchronizedMode` — End Ride and
    /// "Play locally" alike — clears the timeline, the playback epoch, `currentPlaybackIdentity`,
    /// `rideAuthorityEpoch` and the retained Play, and leaves `queueState` exactly where it was, just
    /// as `resetForNewSession` does for a control-lifetime boundary (ADR-024 Amendment A8). Neither
    /// `adoptSnapshot` nor `applyQueueSnapshot` proves a ride or stamps `recordRideAuthority`, because
    /// PROTOCOL §9's "the snapshot always wins" is scoped to the authenticated control generation and
    /// nothing narrower — and the leader's own queue survives *its* End Ride by the same code, so a
    /// held snapshot replayed after a ride boundary carries state that is still the leader's current
    /// authoritative queue. Adding ride provenance here would refuse valid queue state, not protect
    /// anything. The one effect `applyQueueSnapshot` has beyond the queue is `resolvePendingPlay`,
    /// and the retained Play carries its own `RideAdmission`.
    case queueSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?, generation: Int64)
    /// PROTOCOL §5's reconciliation anchor, held for the same reason.
    ///
    /// `reconciliation` is the `ResyncCoordinator` obligation id this anchor discharges, and is
    /// non-nil **only** when the anchor came from a PROTOCOL §10 `STATE_SNAPSHOT` (independent-review
    /// round 4, Blocker 2). An ordinary wire `PLAYBACK_STATE` carries `nil`: nobody outside is
    /// waiting on it, so it has no terminal result to report.
    case playbackState(
        PlaybackStateSnapshotFields, generation: Int64, reconciliation: Int64?, ride: RideAdmission
    )

    var generation: Int64 {
        switch self {
        case .command(_, let generation, _): return generation
        case .queueSnapshot(_, _, _, let generation): return generation
        case .playbackState(_, let generation, _, _): return generation
        }
    }

    /// The ride lifetime that admitted this event, or nil for the one case that is not ride-scoped.
    var ride: RideAdmission? {
        switch self {
        case .command(_, _, let ride): return ride
        case .queueSnapshot: return nil
        case .playbackState(_, _, _, let ride): return ride
        }
    }

    /// The reconciliation obligation this held event owes a terminal result to, if any.
    var reconciliation: Int64? {
        switch self {
        case .command, .queueSnapshot: return nil
        case .playbackState(_, _, let reconciliation, _): return reconciliation
        }
    }
}

/// PROTOCOL §10's `STATE_REQUEST`, retained because it arrived before this device's own
/// `.connected` had been applied (independent-review round 8's CI investigation).
///
/// Carries the generation that authorised it, so the replay compares rather than re-derives — the
/// same discipline `ReadFrameBinding`, `RideAdmission` and `DeferredEvent` already follow.
struct PendingStateSnapshotReply: Sendable {
    let generation: Int64
    let leaderPeerId: PeerId
    let manifestRevision: Int64
    let transfersInFlight: [ResyncTransferInFlight]
}

/// What an End Ride boundary did when it reached the one owner of ride-segment playback authority
/// (independent-review round 4, Blocker 1). See `SyncPlaybackCoordinator.endRideSegment`.
public enum RideBoundaryOutcome: Sendable, Equatable {
    /// The boundary owned what was standing and retired it.
    case cleared
    /// A strictly newer ride had already established synchronisation authority of its own, so this
    /// boundary belongs to a ride that is over and touched nothing.
    case supersededByLiveRideAuthority
}

/// What actually happened to a `STATE_SNAPSHOT`'s playback portion (independent review, Blocker 2E)
/// — a snapshot *received* is not a snapshot *applied*, and `ResyncCoordinator` needs to tell the two
/// apart rather than assuming reconciliation completed the instant `onStateSnapshot` returns.
enum StateSnapshotOutcome: Sendable, Equatable {
    /// Reconciliation genuinely completed — the follower now conforms to the leader's authoritative
    /// playback state (or the leader authoritatively has nothing loaded).
    case applied
    /// A trustworthy clock was not available. The snapshot is retained, generation-owned, in
    /// `deferredEvents` and will be applied automatically once the clock recovers (or discarded if
    /// this generation retires first) — this is not a failure to retry by resending `STATE_REQUEST`.
    case deferredClock
    /// Independent review §22: the clock was ready and the snapshot's queue/manifest/identity
    /// portions were accepted, but the authoritative track itself is not locally playable —
    /// `applyPlay` already requested the transfer through the existing Phase 4 mechanism (PROTOCOL
    /// §5 rule 4: only the leader may reschedule, so nothing here retries on its own; the leader's
    /// next authoritative frame — a fresh `PLAY` once both sides verify the content, exactly as an
    /// ordinary wire `PLAY` for missing content already behaves — is what completes this). Distinct
    /// from `.deferredClock`: nothing is enqueued into `deferredEvents`, because a content transfer
    /// completing is not a clock-readiness event the drain loop polls for.
    case deferredContent
    /// ADR-024 Amendment A11: the reconciliation is genuinely needed and this device has no capacity
    /// left to represent the local work it would create. The snapshot is retained in
    /// `deferredEvents` carrying the same obligation id and re-attempted by the drain, exactly as
    /// `.deferredClock` is — so ADR-028 Amendment A4's invariant ("every `deferred*` corresponds to
    /// actual retained work carrying the same obligation id") holds here too. Distinct from
    /// `.deferredClock` because the clock is fine and saying otherwise would send a future reader to
    /// the estimator.
    case deferredCapacity
    /// The snapshot's generation is no longer live — refused, nothing mutated.
    case rejectedStale
    /// This device is not a follower — refused, nothing mutated.
    case rejectedRole
    /// Independent-review round 4, §17: the snapshot arrived for the **live generation** and was
    /// refused because the **ride segment** that authorised its reconciliation has ended — a
    /// different fact from `.rejectedStale`, and the two must not be conflated.
    ///
    /// The distinction is load-bearing at the outer owner: a `.rejectedStale` snapshot never
    /// answered the `STATE_REQUEST` that is still outstanding, so that request must stay pending;
    /// this one **did** arrive for the live generation, so the wire round trip is satisfied and only
    /// the reconciliation is cancelled. Reporting it as `.rejectedStale` left `requestPending` true
    /// with nothing that could ever clear it.
    case rejectedRide
}

/// `PLAYBACK_STATE`'s seven payload fields as one value, so a held snapshot is one case rather than
/// eight associated values (PROTOCOL §5; ADR-024 §4 fixed the payload this mirrors).
struct PlaybackStateSnapshotFields: Sendable {
    let commandSeq: Int64
    let queueRevision: Int64
    let trackHash: ContentHash?
    let queueItemId: String?
    let positionMs: Int64
    let playing: Bool
    let atSessionUs: Int64
}
