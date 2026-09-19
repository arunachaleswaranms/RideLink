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

        fileprivate func record(_ message: ResyncMessage) -> Bool {
            sent.append(message)
            return sendResult
        }

        struct Channel: ResyncChannel {
            let session: FakeResyncSession
            func setSink(_ sink: (any ResyncSink)?) async { await session.setSink(sink) }
            @discardableResult
            func send(_ message: ResyncMessage) async -> Bool { await session.record(message) }
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
}
