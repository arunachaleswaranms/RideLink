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
/// `DriftController`, queue algebra by `SharedQueue`, timing by `SessionClock`. That is ADR-019's
/// direct lesson, and it is why this type is wiring and lifetime rather than policy.
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
public actor SyncPlaybackCoordinator {
    let monotonicNowUs: @Sendable () -> Int64
    private let localPeerId: PeerId
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
        nextQueueItemId: @escaping @Sendable () -> String
    ) {
        self.monotonicNowUs = monotonicNowUs
        self.localPeerId = localPeerId
        self.session = session
        self.player = player
        self.content = content
        self.sleeper = sleeper
        self.routeState = routeState
        self.nextQueueItemId = nextQueueItemId
    }

    /// Attaches the two Phase 5 sinks. Called once by the composition root, after construction, so
    /// the actor is fully initialised before anything can be delivered into it.
    public func start() async {
        await session.channel.setPlaybackSink(PlaybackForwarder(coordinator: self))
        await session.channel.setQueueSink(QueueForwarder(coordinator: self))
    }

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
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        epoch.supersede()
        lastAppliedSeq = nil
        nextSeq = PlaybackBounds.firstCommandSeq
        timeline = nil
        driftState = DriftController.reset()
        queueState = SharedQueueState()
        await restoreRate()
        diagnostics.syncState = .inactive
        diagnostics.lastAppliedCommandSeq = nil
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
        publishQueue()
        publishDiagnostics()
    }

    /// ADR-023 §3's guard, re-proved at every transition rather than once at handler entry.
    func stillCurrent(_ generation: Int64) async -> Bool {
        guard role != nil else { return false }
        let live = await session.currentAuthGeneration()
        return generation == live
    }

    /// The live session generation, read at *dispatch* time by the two sink forwarders.
    func captureGeneration() async -> Int64 { await session.currentAuthGeneration() }

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
            diagnostics.syncState = .clockUnready
            diagnostics.clockReady = false
            publishDiagnostics()
            return nil
        }
        return estimate
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
            let header = PlaybackCommandHeader(
                commandSeq: PlaybackBounds.unassignedCommandSeq,
                effectiveAtSessionUs: 0,
                issuedBy: localPeerId,
                queueRevision: queueState.revision
            )
            await session.channel.send(build(header))
            return
        }
        guard let estimate = await readyEstimate() else { return }
        guard await stillCurrent(generation) else { return }
        let seq = nextSeq
        nextSeq += 1
        diagnostics.nextCommandSeq = nextSeq
        let header = PlaybackCommandHeader(
            commandSeq: seq,
            effectiveAtSessionUs: estimate.sessionUs(localMonoUs: monotonicNowUs()) + estimate.leadUs,
            issuedBy: localPeerId,
            queueRevision: queueState.revision
        )
        let message = build(header)
        await session.channel.send(message)
        guard await stillCurrent(generation) else { return }
        // The leader applies its own command exactly as the follower will: same header, same
        // effective instant, same code path. There is no "issuer applies immediately" shortcut,
        // because that shortcut is precisely how two phones end up on two timelines.
        lastAppliedSeq = header.commandSeq
        diagnostics.lastAppliedCommandSeq = header.commandSeq
        publishDiagnostics()
        await applyAuthoritative(message, generation: generation, estimate: estimate)
    }

    // MARK: - User-facing actions (also the remote-command path, brief §39)

    /// Starts synchronised playback of `contentHash`. Both halves of brief §19's gate are checked
    /// **before** any command is issued: this device must be able to play it, and the peer must be
    /// known to hold it. A remote-only track cannot begin synchronised playback (REQUIREMENTS §9.4).
    public func playSynchronized(_ contentHash: ContentHash) async {
        guard let currentRole = role else { return }
        syncEnabled = true
        let queueItemId = await ensureQueued(contentHash)
        if currentRole == .leader, !(await gateContent(contentHash)) { return }
        await issue { header in
            .play(header: header, trackHash: contentHash, positionMs: 0, queueItemId: queueItemId)
        }
    }

    /// Both sides of brief §19's availability gate, and PROTOCOL §5 rule 4's transfer request.
    func gateContent(_ contentHash: ContentHash) async -> Bool {
        let local = await content.resolve(contentHash)
        if local == nil {
            diagnostics.syncState = .waitingForContent
            publishDiagnostics()
            if await content.peerHasContent(contentHash) { await content.requestTransfer(contentHash) }
            return false
        }
        if !(await content.peerHasContent(contentHash)) {
            diagnostics.syncState = .waitingForContent
            publishDiagnostics()
            return false
        }
        return true
    }

    private func ensureQueued(_ contentHash: ContentHash) async -> String {
        if let existing = queueState.items.first(where: { $0.trackHash == contentHash }) { return existing.queueItemId }
        let id = nextQueueItemId()
        await mutateQueue(.add(items: [
            QueueAddItem(queueItemId: id, trackHash: contentHash, addedBy: localPeerId, position: PlaybackBounds.queuePositionEnd),
        ]))
        return id
    }

    public func enqueue(_ contentHash: ContentHash) async {
        await mutateQueue(.add(items: [
            QueueAddItem(
                queueItemId: nextQueueItemId(), trackHash: contentHash, addedBy: localPeerId, position: PlaybackBounds.queuePositionEnd
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
        timeline = nil
        driftState = DriftController.reset()
        await restoreRate()
        diagnostics.syncState = .inactive
        diagnostics.localDriftMs = nil
        diagnostics.peerDriftMs = nil
        publishDiagnostics()
    }

    /// Whether a synchronised session currently owns transport control (brief §39/§40).
    public func isSynchronizedModeActive() -> Bool { syncEnabled && role != nil }

    private func restoreRate() async {
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
