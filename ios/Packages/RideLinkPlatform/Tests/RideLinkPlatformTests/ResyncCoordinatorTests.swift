import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// Coordinator-level Phase 7 behaviour (PROTOCOL §10, ADR-028): request dedup, role asymmetry,
/// snapshot reconciliation wiring and manifest-revision gating. The mirror is
/// `com.ridelink.app.resync.ResyncCoordinatorTest`, asserting the same properties.
///
/// The single most important property here is the provenance regression this phase's brief §13
/// names directly: a `STATE_SNAPSHOT` whose generation no longer matches what `ResyncCoordinator`
/// is told is live must not reconcile, and a snapshot for the live generation must reconcile even
/// after a stale one was rejected.
@MainActor
final class ResyncCoordinatorTests: XCTestCase {
    /// A minimal, directly-controllable `ResyncSessionPort` — deliberately simpler than
    /// `FakeSyncSession`, because `ResyncCoordinator` does no clock or ordering work of its own.
    private actor FakeResyncSession: ResyncSessionPort {
        private(set) var sent: [ResyncMessage] = []
        /// The generation each entry of `sent` was actually sent under (independent review,
        /// Blocker 1) — kept alongside `sent` rather than replacing it, since most existing
        /// assertions only care about message shape.
        private(set) var sentGenerations: [Int64] = []
        private var sink: (any ResyncSink)?
        var generation: Int64 = 1
        /// `nonisolated(unsafe)` so `liveAuthenticatedGeneration()` can be `nonisolated` — mirroring
        /// production's `ControlSessionManager.liveAuthenticatedGeneration()`, which reads a
        /// synchronous box for the same reason (ADR-025 §1: a relay's `deliver` must never `await`
        /// into the session actor just to ask which generation is live). Only ever mutated from
        /// `setLive`, and tests never race that mutation against a read.
        private nonisolated(unsafe) var liveGeneration: Int64? = 1
        var sendResult = true

        nonisolated var channel: any ResyncChannel { Channel(session: self) }

        func currentAuthGeneration() async -> Int64 { generation }
        nonisolated func liveAuthenticatedGeneration() -> Int64? { liveGeneration }

        func setGeneration(_ value: Int64) { generation = value }
        func setLive(_ value: Int64?) { liveGeneration = value }
        func setSendResult(_ value: Bool) { sendResult = value }
        func setSink(_ value: (any ResyncSink)?) { sink = value }
        /// Mirrors `ResyncRelay.deliver`'s own `generation == liveGeneration()` gate — the production
        /// provenance check this fake stands in for, so a test can express "session A's frame,
        /// delivered after B is live" the same way the relay would refuse it.
        func deliver(_ message: ResyncMessage, generation: Int64) {
            guard generation == liveGeneration else { return }
            sink?.submit(message, generation: generation)
        }

        /// Mirrors `ResyncRelay.send`'s own generation-bound refusal (independent review, Blocker 1):
        /// a send whose `generation` no longer matches what's live is refused before it is recorded
        /// at all, exactly as the real `authenticatedWriterFor` supplier would return nil.
        fileprivate func record(_ message: ResyncMessage, generation: Int64) -> Bool {
            guard generation == liveGeneration else { return false }
            sent.append(message)
            sentGenerations.append(generation)
            return sendResult
        }

        struct Channel: ResyncChannel {
            let session: FakeResyncSession
            func setSink(_ sink: (any ResyncSink)?) async { await session.setSink(sink) }
            @discardableResult
            func send(_ message: ResyncMessage, generation: Int64) async -> Bool {
                await session.record(message, generation: generation)
            }
        }
    }

    private var session: FakeResyncSession!
    private var syncSession: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var syncCoordinator: SyncPlaybackCoordinator!
    private var manifestRevision = 0
    private var refreshCount = 0
    private var coordinator: ResyncCoordinator!
    /// Independent-review round 4: the real production ride-segment seam, so an End Ride in these
    /// tests is the same two statements `SessionCoordinator.endRide()` performs.
    private var lifecycle: RideSegmentLifecycle!

    private func build() async {
        session = FakeResyncSession()
        syncSession = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeState = FakeRouteState()
        let clockRef = clock!
        syncCoordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clockRef.now() },
            localPeerId: SyncTestValues.leaderPeerId,
            session: syncSession,
            player: player,
            content: content,
            sleeper: clockRef,
            routeState: routeState,
            nextQueueItemId: { UUID().uuidString }
        )
        await syncCoordinator.start()
        manifestRevision = 0
        refreshCount = 0
        coordinator = ResyncCoordinator(
            session: session,
            syncPlaybackCoordinator: syncCoordinator,
            currentCatalogueRevision: { [weak self] in Int64(self?.manifestRevision ?? 0) },
            requestManifestRefresh: { [weak self] in self?.refreshCount += 1 },
            localPeerId: SyncTestValues.leaderPeerId
        )
        await coordinator.attach()
        lifecycle = RideSegmentLifecycle(syncPlayback: syncCoordinator)
    }

    /// The two statements `SessionCoordinator.startRide()` performs after its FSM transition.
    private func startRide() async {
        let epoch = lifecycle.nextRideEpoch()
        await lifecycle.startRide(epoch: epoch)
    }

    /// The two statements `SessionCoordinator.endRide()` performs after its FSM transition.
    private func endRide() async {
        let epoch = lifecycle.nextRideEpoch()
        await lifecycle.endRide(epoch: epoch)
    }

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    /// Keeps the resync fake and the real `SyncPlaybackCoordinator`'s own session generation in
    /// lockstep — in production both are fed the same `ControlSessionManager.authenticationGeneration`,
    /// so a test that let them diverge would validate nothing about the actual provenance path
    /// `SyncPlaybackCoordinator.onStateSnapshot`'s `stillCurrent` re-proves.
    private func moveTo(_ generation: Int64) async {
        await session.setGeneration(generation)
        await session.setLive(generation)
        await syncSession.setGeneration(generation)
    }

    // MARK: - Trigger dedup and reconnect gating

    func testTheFirstEverConnectionNeverRequestsState() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await Task.yield()
        let sent = await session.sent
        XCTAssertTrue(sent.isEmpty, "a fresh first pairing has nothing to reconcile")
    }

    func testALeaderNeverRequestsStateOnReconnect() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: true, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: true, generation: 2)
        await Task.yield()
        let sent = await session.sent
        XCTAssertTrue(sent.isEmpty)
    }

    func testAFollowersReconnectSendsExactlyOneStateRequest() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1) // first ever: no request
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2) // reconnect: one request
        await expect("one STATE_REQUEST sent") { await self.session.sent.count == 1 }
        let sent = await session.sent
        XCTAssertEqual([.stateRequest], sent)
        let diagnostics = coordinator.diagnostics
        XCTAssertEqual(1, diagnostics.reconnectRequestCount)
        XCTAssertTrue(diagnostics.requestPending)
    }

    /// The dedup half of `StateResyncGate`: a second trigger for the same generation must not
    /// resend while one is still outstanding.
    func testASecondTriggerForTheSameGenerationDoesNotResend() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await expect("first request sent") { await self.session.sent.count == 1 }
        // A second reconnect-style trigger for the *same* generation (e.g. a duplicate event)
        // must not send again.
        await coordinator.onConnected(isLeader: false, generation: 2)
        await Task.yield()
        let sent = await session.sent
        XCTAssertEqual(1, sent.count, "a request already outstanding for the live generation must not be resent")
    }

    // MARK: - Snapshot reconciliation and provenance

    private func snapshot(commandSeq: Int64 = 5, manifestRevision: Int64 = 1) -> ResyncMessage {
        .stateSnapshot(
            leaderPeerId: SyncTestValues.leaderPeerId,
            commandSeq: commandSeq,
            queueRevision: 0,
            playback: nil,
            queueItems: [],
            queueCurrentIndex: nil,
            manifestRevision: manifestRevision,
            transfersInFlight: []
        )
    }

    func testASnapshotForTheLiveGenerationReconcilesAndClearsThePendingRequest() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await expect("request sent") { await self.session.sent.count == 1 }
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await session.deliver(snapshot(commandSeq: 9), generation: 2)
        await expect("reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertFalse(coordinator.diagnostics.requestPending)
        XCTAssertEqual(9, coordinator.diagnostics.lastSnapshotCommandSeq)
    }

    /// **The provenance regression** (brief §13): a snapshot delivered for a generation that is no
    /// longer live is refused by the relay layer (`ResyncRelayTests` proves that in isolation); this
    /// proves the coordinator-level consequence — a snapshot the fake session's own `deliver` guard
    /// lets through only for the *live* generation can never be mistaken for reconciling a different
    /// one, because `FakeResyncSession.deliver` mirrors the production relay's `generation == live`
    /// gate exactly. A `deliver` call naming a since-retired generation therefore never reaches
    /// `ResyncCoordinator.handle` at all.
    func testADelayedSnapshotForARetiredGenerationNeverReachesTheCoordinator() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await expect("request sent") { await self.session.sent.count == 1 }
        // Session A's own snapshot, delayed until after B (generation 2) is live and requesting.
        await session.deliver(snapshot(commandSeq: 111), generation: 1)
        await Task.yield()
        await Task.yield()
        XCTAssertNotEqual(.reconciled, coordinator.diagnostics.lastOutcome, "a retired-generation snapshot must never reconcile")
        XCTAssertNil(coordinator.diagnostics.lastSnapshotCommandSeq)
        // B's own snapshot still reconciles normally afterwards.
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await session.deliver(snapshot(commandSeq: 222), generation: 2)
        await expect("B reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertEqual(222, coordinator.diagnostics.lastSnapshotCommandSeq, "B's reconciliation must not be undone by A's stale delivery")
    }

    // MARK: - Role asymmetry

    func testAFollowerReceivingAStateRequestCountsARoleViolationAndSendsNothing() async {
        await build()
        await moveTo(3)
        await coordinator.onConnected(isLeader: false, generation: 3)
        await Task.yield()
        await session.deliver(.stateRequest, generation: 3)
        await expect("role violation counted") { self.coordinator.diagnostics.roleViolationCount == 1 }
        let sent = await session.sent
        XCTAssertTrue(sent.allSatisfy { if case .stateRequest = $0 { return true } else { return false } })
    }

    func testALeaderReceivingAStateRequestAnswersWithASnapshot() async {
        await build()
        await moveTo(4)
        await coordinator.onConnected(isLeader: true, generation: 4)
        await syncCoordinator.handleConnected(isLocalLeader: true)
        await session.deliver(.stateRequest, generation: 4)
        await expect("a snapshot was sent") {
            await self.session.sent.contains { if case .stateSnapshot = $0 { return true } else { return false } }
        }
    }

    // MARK: - Manifest revision gating (§20/§21: no unnecessary manifest retransmission)

    func testTheFirstSnapshotOfASessionNeverTriggersAManifestRefreshOnItsOwn() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await session.deliver(snapshot(manifestRevision: 7), generation: 2)
        await expect("reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertEqual(0, refreshCount)
    }

    func testALaterSnapshotWithADifferentManifestRevisionTriggersExactlyOneRefresh() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await session.deliver(snapshot(commandSeq: 1, manifestRevision: 7), generation: 2)
        await expect("first reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        await session.deliver(snapshot(commandSeq: 2, manifestRevision: 8), generation: 2)
        await expect("second reconciled") { self.coordinator.diagnostics.lastSnapshotCommandSeq == 2 }
        XCTAssertEqual(1, refreshCount)
    }

    func testALaterSnapshotWithTheSameManifestRevisionTriggersNoRefresh() async {
        await build()
        await moveTo(1)
        await coordinator.onConnected(isLeader: false, generation: 1)
        await moveTo(2)
        await coordinator.onConnected(isLeader: false, generation: 2)
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await session.deliver(snapshot(commandSeq: 1, manifestRevision: 7), generation: 2)
        await expect("first reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        await session.deliver(snapshot(commandSeq: 2, manifestRevision: 7), generation: 2)
        await expect("second reconciled") { self.coordinator.diagnostics.lastSnapshotCommandSeq == 2 }
        XCTAssertEqual(0, refreshCount)
    }

    // MARK: - Independent-review round 3: Blockers A and B

    /// A snapshot naming a real track, for the recovery tests below.
    private func playbackSnapshot(
        trackHash: ContentHash,
        queueItemId: String,
        commandSeq: Int64 = 5,
        queueRevision: Int64 = 1,
        manifestRevision: Int64 = 1,
        positionMs: Int64 = 4_000,
        atSessionUs: Int64 = 0
    ) -> ResyncMessage {
        .stateSnapshot(
            leaderPeerId: SyncTestValues.leaderPeerId,
            commandSeq: commandSeq,
            queueRevision: queueRevision,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackHash,
                queueItemId: queueItemId,
                positionMs: positionMs,
                playing: true,
                atSessionUs: atSessionUs
            ),
            queueItems: [
                SharedQueueItem(queueItemId: queueItemId, trackHash: trackHash, addedBy: SyncTestValues.leaderPeerId, order: 0)
            ],
            queueCurrentIndex: 0,
            manifestRevision: manifestRevision,
            transfersInFlight: []
        )
    }

    /// Brings the follower to "live under generation 2, genuinely desynchronised" — the state every
    /// Blocker A test starts from. `forceDesynchronizedForTest` is `onIngressOverflow`'s own effect on
    /// a follower, latch and trigger together, so the `STATE_REQUEST` below is production's.
    private func desynchronizedFollower(clockReady: Bool) async {
        await build()
        await moveTo(1)
        // The *first* connection deliberately asks for nothing (a fresh pairing has nothing to
        // reconcile), so the `STATE_REQUEST` below is unambiguously the desync trigger's own — a
        // reconnect-triggered one would already hold `pendingRequestGeneration` and dedup it away.
        await coordinator.onConnected(isLeader: false, generation: 1)
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: clockReady))
        // `handleConnected` restores rate 1.0 through the player (ADR-024 Amendment A4 §D's one
        // unfenced call); the recovery assertions below are about *restoration* effects only.
        await player.clearCalls()
        await syncCoordinator.forceDesynchronizedForTest()
        await expect("the desync trigger asked for state") { self.coordinator.diagnostics.desyncRequestCount == 1 }
    }

    /// **Blocker A, clock half, and Blocker B's completion half.** The drain used to begin with a
    /// blanket `if playbackDesynchronized || queueDesynchronized { return }`, and
    /// `playbackDesynchronized` clears only when the retained reconciliation applies — from that same
    /// drain. And even once it could apply, `onReconciliationApplied` compared its generation against
    /// `pendingRequestGeneration`, which `handleStateSnapshot` had already cleared, so
    /// `.snapshotPending` could never become `.reconciled`. Both halves are asserted here, and **no
    /// second snapshot is delivered** — the test simply never sends one.
    func testASnapshotDeferredForTheClockAppliesAutomaticallyWithNoSecondSnapshot() async {
        await desynchronizedFollower(clockReady: false)
        let track = SyncTestValues.hash(7)
        let itemId = SyncTestValues.ulid(7)
        await content.addLocal(track)

        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: itemId), generation: 1)
        await expect("the wire round trip completed, reconciliation did not") {
            self.coordinator.diagnostics.lastOutcome == .snapshotPending
        }
        XCTAssertFalse(coordinator.diagnostics.requestPending, "nothing is left to ask for")
        let desynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
        XCTAssertTrue(desynchronized, "the desync obligation is still owed")
        let held = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(1, held, "the snapshot is retained, not dropped")
        let callsBefore = await player.calls
        XCTAssertTrue(callsBefore.isEmpty, "no player effect before the clock is trustworthy: \(callsBefore)")
        let requestsBefore = await session.sent.count

        // The one thing that changes.
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)

        await expect("the retained snapshot reconciled by itself") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.select(track)), "the real player converged: \(calls)")
        let stillDesynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
        XCTAssertFalse(stillDesynchronized, "applying the repair is what clears the latch")
        let remaining = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(0, remaining, "the retained stream drained")
        let converged = await syncCoordinator.diagnostics.currentTrackHash
        XCTAssertEqual(track, converged)
        XCTAssertEqual(5, coordinator.diagnostics.lastSnapshotCommandSeq, "the completed outcome reports the snapshot's own values")
        let requestsAfter = await session.sent.count
        XCTAssertEqual(requestsBefore, requestsAfter, "recovery needed no further STATE_REQUEST")
    }

    /// **Blocker A, content half.** The same shape with the other precondition, resolved through
    /// `content.observeAvailability`'s own callback. The originally retained snapshot is what applies;
    /// no second snapshot is delivered and no duplicate transfer is requested.
    func testASnapshotDeferredForContentAppliesAutomaticallyWithNoSecondSnapshot() async {
        await desynchronizedFollower(clockReady: true)
        let track = SyncTestValues.hash(8)
        let itemId = SyncTestValues.ulid(8)
        // Deliberately absent from the local cache.

        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: itemId), generation: 1)
        await expect("deferred for content") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let requests = await content.transferRequests
        XCTAssertEqual(1, requests.filter { $0 == track }.count, "PROTOCOL §5 rule 4's transfer is requested exactly once")
        let desynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
        XCTAssertTrue(desynchronized)
        let held = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(1, held, "the snapshot is retained, not dropped")
        let requestsBefore = await session.sent.count

        await content.completeTransfer(track)

        await expect("the retained snapshot reconciled by itself") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.select(track)), "the real player converged: \(calls)")
        let stillDesynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
        XCTAssertFalse(stillDesynchronized)
        let remaining = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(0, remaining)
        let requestsAfterTransfer = await content.transferRequests
        XCTAssertEqual(1, requestsAfterTransfer.filter { $0 == track }.count, "no duplicate transfer request")
        let requestsAfter = await session.sent.count
        XCTAssertEqual(requestsBefore, requestsAfter, "recovery needed no further STATE_REQUEST")
    }

    /// **Blocker B's ownership rule.** A reconciliation obligation recorded under generation B may not
    /// be completed by anything a successor does. C's own snapshot supersedes it, and B's precondition
    /// resolving afterwards must produce nothing — the obligation is compared, never reconstructed
    /// from whatever generation is live when the callback happens to run.
    func testAReconciliationDeferredUnderBIsInertOnceCSupersedesItAndCStillReconciles() async {
        await desynchronizedFollower(clockReady: false)
        let trackB = SyncTestValues.hash(9)
        await content.addLocal(trackB)
        await session.deliver(playbackSnapshot(trackHash: trackB, queueItemId: SyncTestValues.ulid(9)), generation: 1)
        await expect("B deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }

        // C authenticates. `resetForNewSession` clears B's retained stream outright.
        await moveTo(2)
        await syncCoordinator.handleLinkLost()
        await coordinator.onConnected(isLeader: false, generation: 2)
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))

        // B's precondition resolving now must complete nothing.
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)
        await Task.yield()
        await Task.yield()
        XCTAssertNotEqual(.reconciled, coordinator.diagnostics.lastOutcome, "B's obligation completed under C")

        // …and C's own round trip still reconciles normally.
        let trackC = SyncTestValues.hash(10)
        await content.addLocal(trackC)
        await session.deliver(
            playbackSnapshot(trackHash: trackC, queueItemId: SyncTestValues.ulid(10), commandSeq: 11, queueRevision: 2),
            generation: 2
        )
        await expect("C reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        let convergedC = await syncCoordinator.diagnostics.currentTrackHash
        XCTAssertEqual(trackC, convergedC)
    }

    // MARK: - Independent-review round 4, Blocker 2: the obligation's own identity

    /// **§15.** S1 is accepted and deferred for the clock, the user ends the ride, and the fresh clock
    /// then becomes ready. The obligation was **discarded**, not applied, and nothing about the clock
    /// recovering may resurrect it.
    ///
    /// Before this fix, `leaveSynchronizedMode` cleared the inner retained snapshot and the outer
    /// obligation survived, waiting. End Ride deliberately does not move the control generation, so
    /// the next completion signal under that generation would have completed ride 1's snapshot.
    func testEndRideWhileClockDeferredCancelsTheObligationAndAReadyClockCannotResurrectIt() async {
        await desynchronizedFollower(clockReady: false)
        await startRide()
        let track = SyncTestValues.hash(70)
        await content.addLocal(track)

        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: SyncTestValues.ulid(70)), generation: 1)
        await expect("S1 deferred for the clock") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let heldBefore = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(1, heldBefore, "the outer obligation is backed by a retained inner snapshot")
        XCTAssertNotNil(coordinator.pendingObligationIdForTest, "the outer obligation exists")
        await player.clearCalls()

        await endRide()
        await expect("the obligation was explicitly cancelled") { self.coordinator.diagnostics.lastOutcome == .cancelled }
        XCTAssertNil(coordinator.pendingObligationIdForTest, "the outer obligation was released")
        let heldAfter = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(0, heldAfter, "the inner retained snapshot was discarded by End Ride")

        // The precondition resolves. Nothing may happen.
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 4)
        for _ in 0 ..< 50 { await Task.yield() }

        XCTAssertEqual(.cancelled, coordinator.diagnostics.lastOutcome, "a discarded reconciliation reported success")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(track)), "a cancelled reconciliation reached the player: \(calls)")
        let identity = await syncCoordinator.currentPlaybackIdentity
        XCTAssertNil(identity, "a cancelled reconciliation restored ride 1's identity")
        XCTAssertEqual(0, refreshCount, "a cancelled reconciliation triggered a manifest refresh")
    }

    /// **§16.** The same, for the other precondition: S1 deferred for content, End Ride, then the
    /// transfer completes. Phase 4's own cache behaviour is untouched — only the synchronisation
    /// obligation is cancelled — so the completion callback still fires and must simply find nothing.
    func testEndRideWhileContentDeferredCancelsTheObligationAndACompletedTransferCannotResurrectIt() async {
        await desynchronizedFollower(clockReady: true)
        await startRide()
        let track = SyncTestValues.hash(71)
        // Deliberately absent from the local cache.

        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: SyncTestValues.ulid(71)), generation: 1)
        await expect("S1 deferred for content") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let requested = await content.transferRequests
        XCTAssertEqual(1, requested.filter { $0 == track }.count, "PROTOCOL §5 rule 4's transfer was requested")
        await player.clearCalls()

        await endRide()
        await expect("the obligation was explicitly cancelled") { self.coordinator.diagnostics.lastOutcome == .cancelled }
        let heldAfter = await syncCoordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(0, heldAfter)

        // Phase 4 finishes the transfer it was legitimately asked for. That is the cache's business;
        // it must not restart synchronised playback the ride no longer has.
        await content.completeTransfer(track)
        for _ in 0 ..< 50 { await Task.yield() }

        XCTAssertEqual(.cancelled, coordinator.diagnostics.lastOutcome, "a discarded reconciliation reported success")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(track)), "a cancelled reconciliation reached the player: \(calls)")
        let identity = await syncCoordinator.currentPlaybackIdentity
        XCTAssertNil(identity, "a cancelled reconciliation restored ride 1's identity")
        let resolvable = await content.resolve(track)
        XCTAssertNotNil(resolvable, "Phase 4's own cache behaviour must be untouched")
    }

    /// **§13/§14: the case the existing B→C tests cannot reach.** S1 and S2 share control generation
    /// B, because End Ride deliberately does not move it. S1 is cancelled by the End Ride; S2 is
    /// accepted in ride 2, deferred, and later applies. Only S2 may ever be reported reconciled, and
    /// the values published must be S2's.
    func testTwoObligationsUnderOneGenerationCompleteOnlyThemselves() async {
        await desynchronizedFollower(clockReady: false)
        await startRide()
        let trackOne = SyncTestValues.hash(72)
        let trackTwo = SyncTestValues.hash(73)
        await content.addLocal(trackOne)
        await content.addLocal(trackTwo)

        await session.deliver(
            playbackSnapshot(trackHash: trackOne, queueItemId: SyncTestValues.ulid(72), commandSeq: 41, manifestRevision: 3),
            generation: 1
        )
        await expect("S1 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let s1 = coordinator.pendingObligationIdForTest
        XCTAssertNotNil(s1)

        await endRide()
        await expect("S1 cancelled") { self.coordinator.diagnostics.lastOutcome == .cancelled }

        // Ride 2, under the **same** control generation — nothing about the link changed.
        await startRide()
        let generationNow = await session.currentAuthGeneration()
        XCTAssertEqual(1, generationNow, "the control generation must be unchanged across End Ride")
        // A ride needs a follower role again: End Ride left synchronised mode, and Phase 5's own
        // `handleConnected` is what a fresh ride's first authoritative frame arrives under.
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await syncCoordinator.forceDesynchronizedForTest()

        await session.deliver(
            playbackSnapshot(
                trackHash: trackTwo, queueItemId: SyncTestValues.ulid(73), commandSeq: 42, queueRevision: 2, manifestRevision: 3
            ),
            generation: 1
        )
        await expect("S2 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let s2 = coordinator.pendingObligationIdForTest
        XCTAssertNotNil(s2)
        XCTAssertNotEqual(s1, s2, "two obligations under one generation must not share an identity")

        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)
        await expect("S2 reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertEqual(42, coordinator.diagnostics.lastSnapshotCommandSeq, "S1's command_seq was published as S2's reconciliation")
        let converged = await syncCoordinator.currentPlaybackIdentity
        XCTAssertEqual(trackTwo, converged?.trackHash, "ride 2 converged on ride 1's track")
        XCTAssertNil(coordinator.pendingObligationIdForTest, "S2's obligation was discharged")
    }

    /// **§23's fresh-fix audit.** With S1 cancelled and S2 live under the same generation, a late
    /// terminal signal naming S1 — applied *or* cancelled — may not alter S2. Both are fired directly
    /// at the production callbacks, which is exactly what a delayed drain or a delayed discard would
    /// do, and the obligation id is read from the coordinator rather than assumed.
    func testALateTerminalSignalForACancelledObligationCannotAlterTheLiveOne() async {
        await desynchronizedFollower(clockReady: false)
        await startRide()
        let trackOne = SyncTestValues.hash(74)
        let trackTwo = SyncTestValues.hash(75)
        await content.addLocal(trackOne)
        await content.addLocal(trackTwo)

        await session.deliver(playbackSnapshot(trackHash: trackOne, queueItemId: SyncTestValues.ulid(74), commandSeq: 51), generation: 1)
        await expect("S1 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        guard let s1 = coordinator.pendingObligationIdForTest else { return XCTFail("S1 has no obligation id") }

        await endRide()
        await expect("S1 cancelled") { self.coordinator.diagnostics.lastOutcome == .cancelled }

        await startRide()
        await syncCoordinator.handleConnected(isLocalLeader: false)
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await syncCoordinator.forceDesynchronizedForTest()
        await session.deliver(
            playbackSnapshot(trackHash: trackTwo, queueItemId: SyncTestValues.ulid(75), commandSeq: 52, queueRevision: 2),
            generation: 1
        )
        await expect("S2 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
        let s2 = coordinator.pendingObligationIdForTest

        // Late S1 applied, then late S1 cancelled. Neither names S2.
        let applied = await syncCoordinator.onReconciliationApplied
        applied?(s1, 1)
        let cancelled = await syncCoordinator.onReconciliationCancelled
        cancelled?(s1, 1)
        for _ in 0 ..< 50 { await Task.yield() }

        XCTAssertEqual(s2, coordinator.pendingObligationIdForTest, "a late S1 signal altered S2's obligation")
        XCTAssertEqual(.snapshotPending, coordinator.diagnostics.lastOutcome, "a late S1 signal changed S2's reported outcome")
        XCTAssertEqual(52, coordinator.diagnostics.lastSnapshotCommandSeq, "S2's own pending values must stand")

        // …and S2 still completes normally afterwards.
        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)
        await expect("S2 reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertEqual(52, coordinator.diagnostics.lastSnapshotCommandSeq)
    }

    /// **§24 item 10.** A terminal teardown with an obligation outstanding cancels it — the control
    /// lifetime that authorised it has ended — and no late completion may follow.
    func testATerminalTeardownWithAPendingObligationProducesNoLateCompletion() async {
        await desynchronizedFollower(clockReady: false)
        let track = SyncTestValues.hash(76)
        await content.addLocal(track)
        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: SyncTestValues.ulid(76)), generation: 1)
        await expect("deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }

        // The session ends and does not come back: `SessionCoordinator` forwards `.linkLost`, whose
        // `resetForNewSession` discards the retained stream.
        await syncCoordinator.handleLinkLost()
        await expect("cancelled by the lifetime boundary") { self.coordinator.diagnostics.lastOutcome == .cancelled }
        XCTAssertNil(coordinator.pendingObligationIdForTest)

        await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 4)
        for _ in 0 ..< 50 { await Task.yield() }
        XCTAssertEqual(.cancelled, coordinator.diagnostics.lastOutcome, "a torn-down obligation completed late")
    }

    /// **Round 4's own fresh-fix defect, found by CI at the exact head — the wire obligation is not
    /// the reconciliation obligation.**
    ///
    /// Two versions of this mistake were made and both are pinned here. The obligation-identity guard
    /// was briefly placed *before* `StateResyncGate.onSnapshotObserved`, making the **wire** request's
    /// clear conditional on the **reconciliation** obligation surviving the apply; and a ride-lifetime
    /// refusal was briefly reported as `.rejectedStale`, which by §21 must *not* clear an outstanding
    /// request because such a snapshot never answered it. Either way a snapshot that genuinely arrived
    /// for the live generation left `requestPending` true with nothing that could ever clear it, and
    /// `ReconnectResyncStressTests`' 100-cycle sweep timed out waiting for it to drop. That is exactly
    /// the conflation round 3's Blocker B removed, re-created by the fix written to strengthen it.
    ///
    /// Deterministic and with no parking at all: the ride is ended **first**, so the snapshot that
    /// follows is unambiguously one whose reconciliation the ride has already cancelled. An earlier
    /// draft of this test parked on the content gate instead and **passed vacuously** — the gate armed
    /// on an unrelated resolve, so the snapshot completed normally before End Ride ran. Pinning the
    /// ordering by construction is the lesson this suite's own `applyPlay` regression already records.
    func testASnapshotRefusedBecauseTheRideEndedStillClearsTheWireRequest() async {
        await desynchronizedFollower(clockReady: true)
        await startRide()
        let track = SyncTestValues.hash(77)
        await content.addLocal(track)
        XCTAssertTrue(coordinator.diagnostics.requestPending, "the desync trigger left a wire request outstanding")

        // Park the reconciliation immediately **after** `applyPeerPlaybackState` captures the ride
        // lifetime — `skipping: 1` steps over `adoptSnapshot`'s own generation read, and the outcome
        // asserted below is what proves we landed there rather than somewhere harmless.
        await syncSession.armGenerationGate(skipping: 1)
        let deliver = Task { await self.session.deliver(self.playbackSnapshot(trackHash: track, queueItemId: SyncTestValues.ulid(77)), generation: 1) }
        var parked = false
        for _ in 0 ..< 500 where !parked {
            parked = await syncSession.isGenerationGateParked
            await Task.yield()
        }
        XCTAssertTrue(parked, "the reconciliation never reached a generation-read suspension")

        // End Ride while it is provably parked, then let it resume.
        await endRide()
        await syncSession.releaseGenerationGate()
        _ = await deliver.value
        for _ in 0 ..< 200 { await Task.yield() }

        XCTAssertEqual(
            .cancelled, coordinator.diagnostics.lastOutcome,
            "the ride ended mid-apply, so the reconciliation is cancelled — and that is not the same as the snapshot being stale"
        )
        XCTAssertFalse(
            coordinator.diagnostics.requestPending,
            "a snapshot arrived for the live generation, so the wire request must clear whatever happened to the reconciliation"
        )
        let identity = await syncCoordinator.currentPlaybackIdentity
        XCTAssertNil(identity, "a reconciliation the ride cancelled may not restore ride 1's playback")
    }

    /// The deliberate counterpart, recorded so the rule above is not read more widely than it is: a
    /// `STATE_SNAPSHOT` that **arrives** after End Ride, under the same still-live control generation,
    /// is ordinary new authoritative traffic and is applied — exactly as a newly arriving `PLAY` is
    /// (`onInboundCommand` sets `syncEnabled` back to true). The ride lifetime refuses work the ended
    /// ride *authorised*; it is not a filter on the peer, who is still riding. Unchanged by round 4
    /// and asserted here so a future reader does not "fix" it.
    func testASnapshotArrivingAfterEndRideIsOrdinaryNewAuthoritativeTrafficAndApplies() async {
        await desynchronizedFollower(clockReady: true)
        await startRide()
        let track = SyncTestValues.hash(78)
        await content.addLocal(track)
        await endRide()

        await session.deliver(playbackSnapshot(trackHash: track, queueItemId: SyncTestValues.ulid(78)), generation: 1)
        await expect("reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
        XCTAssertFalse(coordinator.diagnostics.requestPending)
    }

    /// **§24 item 11.** Fifty same-generation cancel/apply cycles on a fresh harness each time: S1
    /// deferred and cancelled by End Ride, S2 deferred and applied in ride 2 under the same control
    /// generation. Only S2 may ever reconcile, and it must report its own `command_seq`.
    func testFiftySameGenerationCancelThenApplyCyclesCompleteOnlyTheLiveObligation() async {
        for cycle in 0 ..< 50 {
            await desynchronizedFollower(clockReady: false)
            await startRide()
            let trackOne = SyncTestValues.hash(80)
            let trackTwo = SyncTestValues.hash(81)
            await content.addLocal(trackOne)
            await content.addLocal(trackTwo)

            await session.deliver(
                playbackSnapshot(trackHash: trackOne, queueItemId: SyncTestValues.ulid(80), commandSeq: 61), generation: 1
            )
            await expect("cycle \(cycle): S1 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
            await endRide()
            await expect("cycle \(cycle): S1 cancelled") { self.coordinator.diagnostics.lastOutcome == .cancelled }

            await startRide()
            await syncCoordinator.handleConnected(isLocalLeader: false)
            await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
            await syncCoordinator.forceDesynchronizedForTest()
            await session.deliver(
                playbackSnapshot(trackHash: trackTwo, queueItemId: SyncTestValues.ulid(81), commandSeq: 62, queueRevision: 2),
                generation: 1
            )
            await expect("cycle \(cycle): S2 deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
            await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
            clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)
            await expect("cycle \(cycle): S2 reconciled") { self.coordinator.diagnostics.lastOutcome == .reconciled }
            XCTAssertEqual(62, coordinator.diagnostics.lastSnapshotCommandSeq, "cycle \(cycle): S1's values were published")
            let converged = await syncCoordinator.currentPlaybackIdentity
            XCTAssertEqual(trackTwo, converged?.trackHash, "cycle \(cycle)")
        }
    }

    /// The two Blocker A recovery scenarios, fifty times each, on a fresh harness per iteration.
    /// This repo's standing lesson is that almost every real defect here surfaced only under repeated
    /// cycling; a recovery path that converges once is not yet evidence that it converges.
    /// Deterministic and in-process — no sleeps.
    func testFiftyCyclesOfEveryRoundThreeRecoveryScenarioConvergeEveryTime() async {
        for cycle in 0 ..< 50 {
            await desynchronizedFollower(clockReady: false)
            let clockTrack = SyncTestValues.hash(20)
            await content.addLocal(clockTrack)
            await session.deliver(playbackSnapshot(trackHash: clockTrack, queueItemId: SyncTestValues.ulid(20)), generation: 1)
            await expect("cycle \(cycle) (clock): deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
            await syncSession.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
            clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 2)
            await expect("cycle \(cycle) (clock): converged") { self.coordinator.diagnostics.lastOutcome == .reconciled }
            let clockDesynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
            XCTAssertFalse(clockDesynchronized, "cycle \(cycle) (clock): latch cleared")

            await desynchronizedFollower(clockReady: true)
            let contentTrack = SyncTestValues.hash(21)
            await session.deliver(playbackSnapshot(trackHash: contentTrack, queueItemId: SyncTestValues.ulid(21)), generation: 1)
            await expect("cycle \(cycle) (content): deferred") { self.coordinator.diagnostics.lastOutcome == .snapshotPending }
            await content.completeTransfer(contentTrack)
            await expect("cycle \(cycle) (content): converged") { self.coordinator.diagnostics.lastOutcome == .reconciled }
            let contentDesynchronized = await syncCoordinator.diagnostics.ingressDesynchronized
            XCTAssertFalse(contentDesynchronized, "cycle \(cycle) (content): latch cleared")
        }
    }
}
