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

    /// Amendment A1 Finding D: authoritative commands held, in order, until the clock is trustworthy.
    var deferredCommands: [DeferredCommand] = []
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
        deferredCommandCapacity: Int = Phase5GateBounds.defaultDeferredCommandCapacity
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
            capacity: Self.outboundCapacity,
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
    }

    static let outboundCapacity = 256

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
        deferredCommands.removeAll()
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

    /// Synchronous by design. Every caller stamps a `command_seq` or a `queue_revision` and calls
    /// this with **no `await` in between**, which is what makes actor isolation cover the pair.
    func enqueueOutbound(_ frame: Phase5Outbound) {
        if outbound.offer(frame) == .overflow {
            diagnostics.outboundOverflowCount += 1
            return
        }
        diagnostics.outboundEnqueuedCount += 1
    }

    /// The single writer. Enqueue order is wire order, which is the whole of Finding B's invariant.
    private func drainOutbound() async {
        while let frame = await outbound.take() {
            switch frame {
            case .playback(let message): await session.channel.send(message)
            case .queue(let message): await session.channel.send(message)
            }
            diagnostics.outboundSentCount += 1
            publishDiagnostics()
        }
    }

    // MARK: - Issuing

    /// ADR-010's whole design in one function. The leader stamps and broadcasts; a follower sends the
    /// **same message type** with `command_seq: 0` — ADR-024 §3's intent marker — and waits for the
    /// leader's authoritative broadcast to arrive back.
    ///
    /// `effective_at_session_us` on an intent is `0` and is ignored by the leader: a follower has no
    /// authority to choose when something becomes audible.
    func issue(_ build: (PlaybackCommandHeader) -> PlaybackMessage) async {
        guard let currentRole = role else { return }
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
            enqueueOutbound(.playback(build(header)))
            return
        }
        guard let estimate = await readyEstimate() else { return }
        guard await stillCurrent(generation) else { return }
        // No `await` from here to the enqueue.
        let seq = nextSeq
        nextSeq += 1
        let header = PlaybackCommandHeader(
            commandSeq: seq,
            effectiveAtSessionUs: estimate.sessionUs(localMonoUs: monotonicNowUs()) + estimate.leadUs,
            issuedBy: localPeerId,
            queueRevision: queueState.revision
        )
        let message = build(header)
        enqueueOutbound(.playback(message))
        // The leader is the assigner, so its own command cannot be lost between accepting and
        // applying it: there is no inbound path that could replay it (an authoritative command
        // arriving at the leader is a role violation), so received and applied move together here.
        // Finding D's split matters on the receiving side.
        lastReceivedSeq = seq
        lastAppliedSeq = seq
        diagnostics.nextCommandSeq = nextSeq
        diagnostics.lastAppliedCommandSeq = seq
        diagnostics.lastReceivedCommandSeq = seq
        publishDiagnostics()
        // The leader applies its own command exactly as the follower will: same header, same
        // effective instant, same code path. There is no "issuer applies immediately" shortcut,
        // because that shortcut is precisely how two phones end up on two timelines.
        await applyAuthoritative(message, generation: generation, estimate: estimate)
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
        deferredCommands.removeAll()
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

/// An authoritative command accepted for ordering but not yet applied, because the clock is not
/// trusted (Amendment A1 Finding D).
struct DeferredCommand: Sendable {
    let message: PlaybackMessage
    let generation: Int64
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

/// One outbound Phase 5 frame, waiting its turn on the one ordered outbound path.
enum Phase5Outbound: Sendable {
    case playback(PlaybackMessage)
    case queue(QueueMessage)
}
