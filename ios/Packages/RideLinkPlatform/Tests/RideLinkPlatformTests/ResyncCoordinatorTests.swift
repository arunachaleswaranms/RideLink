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
