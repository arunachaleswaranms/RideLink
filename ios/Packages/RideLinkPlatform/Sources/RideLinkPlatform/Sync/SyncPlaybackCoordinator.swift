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

    /// The highest `command_seq` this device has taken responsibility for — *the* input to
    /// `CommandOrderGate`. Amendment A1 Finding D: it advances when a command is accepted, whether
    /// that command is applied immediately or held for a trustworthy clock, so a replay of a held
    /// command is correctly a duplicate rather than a second copy.
    var lastReceivedSeq: Int64?
    /// The highest `command_seq` actually applied. What `PLAYBACK_STATE` reports, and only that.
    var lastAppliedSeq: Int64?
    var nextSeq: Int64 = PlaybackBounds.firstCommandSeq
    var timeline: PlaybackTimeline?
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

    var reportedInboundOverflows = 0
    var reportedInboundCoalesces = 0

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
        outboundCapacity: Int = Phase5GateBounds.defaultOutboundCapacity
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
        inbound = Phase5FrameQueue(
            capacity: inboundCapacity,
            kindOf: { $0.kind },
            coalesceKeyOf: { $0.coalesceKey }
        )
        outbound = Phase5FrameQueue(
            capacity: outboundCapacity,
            // Never coalesced: a frame this device has already stamped may not be superseded.
            kindOf: { _ in .command },
            coalesceKeyOf: { _ in nil }
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
            Task { await self?.resolvePendingPlay() }
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
        scheduledChain?.cancel()
        scheduledChain = nil
        applyChain?.cancel()
        applyChain = nil
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

    // MARK: - Session lifecycle

    /// ADR-019: `.connected` means the trust gate passed, so this is the first instant a Phase 5
    /// message may be sent or acted on at all. The role comes straight from ADR-010's rule as the
    /// handshake already computed it.
    ///
    /// Forwarded by the app's `SessionCoordinator` rather than subscribed to here, for the reason
    /// `SyncSessionPort` records: `onEvent` is a single mutable callback slot on this platform.
    public func handleConnected(isLocalLeader: Bool) async {
        await resetForNewSession()
        role = isLocalLeader ? .leader : .follower
        diagnostics.role = role
        diagnostics.sessionGeneration = await session.currentAuthGeneration()
        publishDiagnostics()
        let generation = diagnostics.sessionGeneration
        tickTask = Task { [weak self] in await self?.tickLoop(generation: generation) }
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
        role = nil
        syncEnabled = false
        tickTask?.cancel()
        tickTask = nil
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        // Nothing new joins the previous session's chain: ordering across a session boundary is
        // meaningless, and every link still in flight is already inert by its ownership proof.
        scheduledChain = nil
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        epoch.supersede()
        // Amendment A1 Finding E: a session boundary cancels the retained Play outright. A transfer
        // that completes afterwards must never resurrect it — the token it held is already stale.
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        deferredEvents.removeAll()
        // Amendment A2 Finding B: a fresh generation retires everything the previous one authorised.
        // Frames still queued outbound stay physically queued and become inert, because each carries
        // the generation that authorised it and `outboundUsable` refuses to write them.
        outboundAuthorityLost = false
        applyChain = nil
        lastReceivedSeq = nil
        lastAppliedSeq = nil
        nextSeq = PlaybackBounds.firstCommandSeq
        timeline = nil
        driftState = DriftController.reset()
        playbackDesynchronized = false
        queueDesynchronized = false
        queueState = SharedQueueState()
        await restoreRate()
        diagnostics.syncState = .inactive
        diagnostics.lastAppliedCommandSeq = nil
        diagnostics.lastReceivedCommandSeq = nil
        diagnostics.nextCommandSeq = nil
        diagnostics.queueRevision = 0
        diagnostics.queueSize = 0
        diagnostics.currentTrackHash = nil
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.lastCorrection = .none
        diagnostics.playbackRate = DriftController.rateNormal
        diagnostics.hardSeekCount = 0
        diagnostics.lastScheduleErrorUs = nil
        diagnostics.sessionGeneration = await session.currentAuthGeneration()
        diagnostics.correctionTickCount = 0
        diagnostics.deferredCommandCount = 0
        diagnostics.ingressDesynchronized = false
        diagnostics.outboundAuthorityLost = false
        diagnostics.cancelledPendingPlayCount += cancelled
        publishQueue()
        publishDiagnostics()
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

    func readyEstimate() async -> SessionClockEstimate? {
        guard let estimate = await estimate(), estimate.ready else {
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
    func failClosedOutbound(generation: Int64) async {
        // A dead session needs no latch: its authority is already gone, and latching would then
        // survive into the session that replaced it.
        guard generation == (await session.currentAuthGeneration()) else { return }
        guard !outboundAuthorityLost else { return }
        outboundAuthorityLost = true
        syncEnabled = false
        cancelTick()
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        deferredEvents.removeAll()
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        driftState = DriftController.reset()
        await restoreRate()
        diagnostics.syncState = .transportFailed
        diagnostics.outboundAuthorityLost = true
        diagnostics.deferredCommandCount = 0
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.cancelledPendingPlayCount += cancelled
        publishDiagnostics()
    }

    /// Runs a leader's own authoritative apply, in commit order (Amendment A2 Finding A).
    ///
    /// See `applyChain`: the commit hook runs on `drainOutbound`'s single consumer, so doing the
    /// apply there would stall the outbound path behind a decoder pre-roll, and an unstructured
    /// `Task` preserves nothing at all about order — precisely the defect A1 Finding G was about.
    func chainApply(_ action: @escaping @Sendable () async -> Void) {
        let previous = applyChain
        applyChain = Task {
            await previous?.value
            await action()
        }
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
    func issue(_ build: (PlaybackCommandHeader) -> PlaybackMessage) async {
        guard let currentRole = role, !outboundAuthorityLost else { return }
        let generation = await session.currentAuthGeneration()
        if currentRole == .follower {
            guard await stillCurrent(generation) else { return }
            // No `await` from here to the enqueue: the actor makes the pair atomic (Finding B).
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
        guard let estimate = await readyEstimate() else { return }
        guard await stillCurrent(generation), !outboundAuthorityLost else { return }
        // No `await` from here to the enqueue.
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
                    seq: seq, message: message, generation: generation, estimate: estimate, outcome: outcome
                )
            }
        )
        // Amendment A2 §6: a `command_seq` becomes authoritative exactly when the frame carrying it
        // enters the outbound authority pipeline, and not a moment earlier. A refused candidate
        // leaves no gap, because it was never assigned.
        guard admitted else {
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
        estimate: SessionClockEstimate,
        outcome: OutboundOutcome
    ) async {
        switch OutboundCommitGate.decide(authority: .authoritative, outcome: outcome) {
        case .abortFailClosed:
            await failClosedOutbound(generation: generation)
            return
        case .abortQuiet:
            return
        case .commit:
            break
        }
        // Deliberately **not** gated on `outboundAuthorityLost`: this frame reached the peer, so the
        // peer will act on it, and the only consistent thing this device can do is act on it too.
        // The latch stops *new* authority; it does not un-send what was sent.
        guard await stillCurrent(generation) else { return }
        // max, not assignment: these commit on the outbound consumer, in send order, and a monotone
        // write says the same thing without depending on that ordering twice over.
        lastReceivedSeq = max(lastReceivedSeq ?? seq, seq)
        lastAppliedSeq = max(lastAppliedSeq ?? seq, seq)
        diagnostics.lastAppliedCommandSeq = lastAppliedSeq
        diagnostics.lastReceivedCommandSeq = lastReceivedSeq
        publishDiagnostics()
        chainApply { [weak self] in
            await self?.applyAuthoritative(message, generation: generation, estimate: estimate)
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
        let generation = await session.currentAuthGeneration()
        // No `await` in this block: the fence, the id and the retained request move together.
        let existing = queueState.items.first { $0.trackHash == contentHash }
        let queueItemId = existing?.queueItemId ?? nextQueueItemId()
        // begin() supersedes whatever earlier request held the slot, which is what makes "the user
        // asked for X while H was still transferring" resolve to X and only X.
        pendingPlay = PendingPlay(
            token: playRequestFence.begin(), generation: generation, contentHash: contentHash,
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

    public func pause() async {
        let position = max(await player.playerState().positionMs, 0)
        await issue { header in .pause(header: header, positionMs: position) }
    }

    public func resume() async {
        let position = max(await player.playerState().positionMs, 0)
        await issue { header in .resume(header: header, positionMs: position) }
    }

    public func seek(positionMs: Int64) async {
        await issue { header in .seek(header: header, targetPositionMs: max(positionMs, 0)) }
    }

    public func next() async { await issue { header in .next(header: header) } }

    public func previous() async { await issue { header in .previous(header: header) } }

    /// Leaves synchronised mode without ending the control session: local playback continues exactly
    /// as a Phase 3 ride, correction stops and the rate goes back to exactly 1.0 (brief §38).
    public func leaveSynchronizedMode() async {
        syncEnabled = false
        epoch.supersede()
        // Amendment A1 Finding E: leaving synchronised mode cancels the retained Play. A transfer
        // completing afterwards must not start music the user has stopped asking for.
        playRequestFence.supersede()
        let cancelled = pendingPlay == nil ? 0 : 1
        pendingPlay = nil
        transferRequestedForToken = nil
        deferredEvents.removeAll()
        deferredDrainTask?.cancel()
        deferredDrainTask = nil
        timeline = nil
        driftState = DriftController.reset()
        await restoreRate()
        diagnostics.syncState = .inactive
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        diagnostics.deferredCommandCount = 0
        diagnostics.cancelledPendingPlayCount += cancelled
        publishDiagnostics()
    }

    /// Whether a synchronised session currently owns transport control (brief §39/§40).
    public func isSynchronizedModeActive() -> Bool { syncEnabled && role != nil }

    func restoreRate() async {
        await player.setRate(DriftController.rateNormal)
        diagnostics.playbackRate = DriftController.rateNormal
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
    let contentHash: ContentHash
    let queueItemId: String
    /// The intent's own `position_ms`, so serving a follower's Play never silently rewinds it to 0.
    let positionMs: Int64
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
enum DeferredEvent: Sendable {
    /// A `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS` the order gate accepted.
    case command(PlaybackMessage, generation: Int64)
    /// PROTOCOL §9's authoritative queue state, held so it cannot change a held command's meaning.
    case queueSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?, generation: Int64)
    /// PROTOCOL §5's reconciliation anchor, held for the same reason.
    case playbackState(PlaybackStateSnapshotFields, generation: Int64)

    var generation: Int64 {
        switch self {
        case .command(_, let generation): return generation
        case .queueSnapshot(_, _, _, let generation): return generation
        case .playbackState(_, let generation): return generation
        }
    }
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
