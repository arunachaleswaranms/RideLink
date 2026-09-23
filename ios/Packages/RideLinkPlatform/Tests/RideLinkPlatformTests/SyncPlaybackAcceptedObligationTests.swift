import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// ADR-024 Amendment A13: every legitimate terminal route for an **accepted** clock-held command
/// other than representation and local Ride retirement (which is not one — see
/// `SyncPlaybackTwoPeerTests`' Regressions A–C, over real TLS).
///
/// A follower accepts a command when `lastReceivedSeq` advances. From then on it may wait — for its
/// clock, for local capacity, for its turn — but it leaves the held stream only by being represented,
/// by authoritative state that already accounts for it, by explicit reconciliation, or with the
/// control generation that admitted it. These are the last three, each driven by gates on a virtual
/// clock and the fake session: no sleep decides an outcome.
final class SyncPlaybackAcceptedObligationTests: XCTestCase {
    private static let anchorUs: Int64 = 50_000_000
    private static let hashA = SyncTestValues.hash(0xA1)
    private static let hashB = SyncTestValues.hash(0xB2)

    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var coordinator: SyncPlaybackCoordinator!

    override func tearDown() async throws {
        await player?.releaseGate()
        await session?.releaseClockGate()
        await session?.releaseGenerationGate()
        await coordinator?.shutdown()
        coordinator = nil
    }

    // MARK: - Regression D: control-generation retirement

    /// G1's accepted, clock-held C1 dies with G1 — the one local boundary that legitimately ends a
    /// distributed obligation without representing it, because the authenticated stream itself has
    /// retired and Phase 7 resynchronisation owns convergence. It must not apply under G2, must leave
    /// nothing behind, and must not stop G2 making progress.
    func testAHeldG1AcceptedCommandDiesWithG1AndNeverAppliesUnderG2() async {
        await build()
        await connectAsFollower(generation: 1)
        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashA, seq: 1), generation: 1)
        let held = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
        XCTAssertEqual(held, [1], "C1 accepted and retained under G1")
        let received = await coordinator.lastReceivedSeq
        XCTAssertEqual(received, 1)

        await coordinator.handleLinkLost()
        await session.setGeneration(2)
        await coordinator.handleConnected(isLocalLeader: false)

        let heldAfter = await coordinator.deferredEvents.count
        XCTAssertEqual(heldAfter, 0, "G1's retained metadata must disappear with G1")
        let receivedAfter = await coordinator.lastReceivedSeq
        XCTAssertNil(receivedAfter, "G2's ordering floor is G2's own")
        let retained = await coordinator.retainedWorkCount
        XCTAssertEqual(retained, 0, "no G1 reservation survives")
        let delivered = await coordinator.deliveredEffects.count
        XCTAssertEqual(delivered, 0)

        // The old clock condition resolves. G1's command has nothing left to drain from.
        await setClock(ready: true)
        await advanceRetries(4)
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(Self.hashA)), "a G1 command mutated G2: \(calls)")
        let identity = await coordinator.currentPlaybackIdentity
        XCTAssertNil(identity)

        // G2 makes progress from its own command_seq 1.
        await deliverAndAwait(play(Self.hashB, seq: 1), generation: 2)
        await expect("G2's own C1 represented") { await self.coordinator.lastAppliedSeq == 1 }
        let g2Identity = await coordinator.currentPlaybackIdentity
        XCTAssertEqual(g2Identity?.trackHash, Self.hashB)
        let stale = await coordinator.diagnostics.staleCommandCount
        XCTAssertEqual(stale, 0, "G1's floor must not have refused G2's command_seq 1")
    }

    // MARK: - Regression E: capacity temporarily unavailable

    /// An accepted command that becomes clock-ready while `SessionWorkLedger` is full stays at the head
    /// of the held stream: not popped, not lost, applied truth unmoved, and the drain parked on its
    /// cadence rather than spinning. When capacity frees, it reserves exactly once, applies, and
    /// releases exactly once.
    ///
    /// **Two bounds, two owners.** The held C1 holds no ledger reservation while it waits — the held
    /// stream's own `deferredCommandCapacity` bounds it — so a clock-held command never starves local
    /// apply capacity; the ledger is taken only at the pop.
    func testAClockReadyAcceptedCommandWaitsForCapacityWithoutLossOrSpinAndReleasesOnce() async {
        await build(sessionWorkCapacity: 1)
        await connectAsFollower(generation: 1)
        // C0 is represented now and its scheduled start is far in the future, so its one obligation
        // occupies the whole ledger until that deadline.
        let c0Deadline = clock.now() + 60_000_000
        await deliverAndAwait(play(Self.hashA, seq: 1, effectiveAtSessionUs: c0Deadline), generation: 1)
        await expect("C0 represented and holding the ledger") {
            let applied = await self.coordinator.lastAppliedSeq
            let retained = await self.coordinator.retainedWorkCount
            return applied == 1 && retained == 1
        }

        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashB, seq: 2), generation: 1)
        let receivedHeld = await coordinator.lastReceivedSeq
        XCTAssertEqual(receivedHeld, 2, "C1 accepted")
        let retainedWhileClockHeld = await coordinator.retainedWorkCount
        XCTAssertEqual(retainedWhileClockHeld, 1, "a clock-held command takes no local work capacity")

        await setClock(ready: true)
        await advanceRetries(6)
        await settle(400)
        let held = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
        XCTAssertEqual(held, [2], "C1 was popped or lost while capacity was unavailable")
        let applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(applied, 1, "applied truth moved for a command with no capacity to represent it")
        let retained = await coordinator.retainedWorkCount
        XCTAssertEqual(retained, 1, "the ledger bound was exceeded")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(Self.hashB)))
        let parkedOnCadence = clock.pendingDeadlines().contains { $0 == clock.now() + Phase5GateBounds.deferredRetryIntervalUs }
        XCTAssertTrue(parkedOnCadence, "the drain is not waiting on its retry cadence")
        let waits = await coordinator.diagnostics.heldCommandCapacityWaitCount
        XCTAssertEqual(waits, 6, "exactly one capacity wait per retry pass — anything more is a spin")
        await settle(400)
        let waitsWithoutTime = await coordinator.diagnostics.heldCommandCapacityWaitCount
        XCTAssertEqual(waitsWithoutTime, waits, "the drain retried without time passing: a busy loop")
        let refused = await coordinator.diagnostics.workCapacityRefusedCount
        XCTAssertEqual(refused, 0, "a held command waiting for capacity is not a refused admission")

        // C0's deadline passes: its effect completes and its exact reservation is released.
        clock.advance(to: c0Deadline + 1)
        await expect("C0 completed and freed the ledger") { await self.coordinator.retainedWorkCount == 0 }
        await advanceRetries(1)
        await expect("C1 reserved, represented and executed") {
            let applied = await self.coordinator.lastAppliedSeq
            let started = await self.player.calls.contains(.select(Self.hashB))
            let held = await self.coordinator.deferredEvents.isEmpty
            return applied == 2 && started && held
        }
        await expect("C1's scheduled effect completed and released exactly its reservation") {
            await self.coordinator.retainedWorkCount == 0
        }
        let finalIdentity = await coordinator.currentPlaybackIdentity
        XCTAssertEqual(finalIdentity?.trackHash, Self.hashB)
        let delivered = await coordinator.deliveredEffects.count
        XCTAssertEqual(delivered, 0, "delivered metadata outlived its reservation")
    }

    // MARK: - Regression F: authoritative state supersedes accepted debt

    /// PROTOCOL §5's existing supersession rule — "anything held that the authoritative state already
    /// accounts for is superseded by it" — reached through its real ordering: a `STATE_SNAPSHOT` whose
    /// hold check found the stream empty suspends in its own generation proof, and C1 is accepted and
    /// held inside that suspension. The snapshot then covers C1 by `command_seq`.
    ///
    /// C1 must leave through that route and no other: counted as superseded (not retired by ride),
    /// not left blocking the stream, not applied a second time on top of the snapshot — and the
    /// snapshot's state, once represented, is what `lastAppliedSeq` reports.
    func testAnAuthoritativeSnapshotCoveringAHeldAcceptedCommandSupersedesItBySequence() async {
        await build()
        await connectAsFollower(generation: 1)
        let snapshotAt = clock.now()
        // Skip the queue half's generation proof; park the playback half's.
        await session.armGenerationGate(skipping: 1)
        let coordinator = coordinator!
        let hashA = Self.hashA
        let snapshot = Task {
            await coordinator.onStateSnapshot(
                .stateSnapshot(
                    leaderPeerId: SyncTestValues.leaderPeerId, commandSeq: 1, queueRevision: 0,
                    playback: ResyncPlaybackSnapshot(
                        trackHash: hashA, queueItemId: SyncTestValues.ulid(1), positionMs: 0,
                        playing: true, atSessionUs: snapshotAt
                    ),
                    queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
                ),
                generation: 1, reconciliation: 41
            )
        }
        await expect("the snapshot parked after its hold check") { await self.session.isGenerationGateParked }
        let heldBefore = await coordinator.deferredEvents.count
        XCTAssertEqual(heldBefore, 0, "the snapshot must have passed its hold check on an empty stream")

        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashA, seq: 1), generation: 1)
        let heldSeqs = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
        XCTAssertEqual(heldSeqs, [1], "C1 accepted and held inside the snapshot's suspension")

        await session.releaseGenerationGate()
        _ = await snapshot.value
        let superseded = await coordinator.diagnostics.supersededHeldCommandCount
        XCTAssertEqual(superseded, 1, "C1 was not superseded by the snapshot's command_seq")
        let retiredByRide = await coordinator.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(retiredByRide, 0, "supersession must be decided by sequence, never by ride")
        let accepted = await coordinator.deferredEvents.compactMap(\.acceptedCommandSeq)
        XCTAssertTrue(accepted.isEmpty, "a superseded command was left blocking the stream")

        await setClock(ready: true)
        await advanceRetries(4)
        await expect("the snapshot's authoritative state is represented") {
            let identity = await self.coordinator.currentPlaybackIdentity
            let drained = await self.coordinator.deferredEvents.isEmpty
            return identity?.trackHash == Self.hashA && drained
        }
        let selects = await player.calls.filter { $0 == .select(Self.hashA) }.count
        XCTAssertEqual(selects, 1, "C1 was applied a second time on top of the snapshot that superseded it")
        let received = await coordinator.lastReceivedSeq
        let applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(received, 1)
        XCTAssertEqual(applied, 1, "represented authoritative state at command_seq 1 was not reported as applied")
        let published = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(published, 1)
    }

    // MARK: - Fresh-fix audit: End Ride partitions the held stream, it neither empties nor keeps it

    /// Held, in arrival order: accepted C1, then a `STATE_SNAPSHOT`'s queue half and its ride-scoped
    /// reconciliation anchor (both held behind C1, as ADR-024 A2 Finding D requires). End Ride must
    /// keep C1 and the control-generation queue state in order, and must still retire the
    /// reconciliation with its terminal cancellation — so neither "End Ride now preserves every
    /// deferred event" nor "an old reconciliation survives into Ride 2".
    func testEndRideKeepsAcceptedDebtAndQueueStateButCancelsTheHeldReconciliation() async {
        await build()
        await connectAsFollower(generation: 1)
        let outcomes = ReconciliationOutcomes()
        await coordinator.setReconciliationCancelledTrigger { id, _ in Task { await outcomes.cancel(id) } }
        await coordinator.setReconciliationAppliedTrigger { id, _ in Task { await outcomes.apply(id) } }
        _ = await coordinator.rideEpochs.next()
        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashA, seq: 1), generation: 1)
        let snapshot = await coordinator.onStateSnapshot(
            .stateSnapshot(
                leaderPeerId: SyncTestValues.leaderPeerId, commandSeq: 1, queueRevision: 0,
                playback: ResyncPlaybackSnapshot(
                    trackHash: Self.hashA, queueItemId: SyncTestValues.ulid(1), positionMs: 0,
                    playing: true, atSessionUs: clock.now()
                ),
                queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
            ),
            generation: 1, reconciliation: 77
        )
        XCTAssertEqual(snapshot, .deferredClock, "the snapshot was held behind C1")
        let heldBefore = await coordinator.deferredEvents.map(Self.kind)
        XCTAssertEqual(heldBefore, ["accepted:1", "queue", "playback:77"])

        let end = await coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        let heldAfter = await coordinator.deferredEvents.map(Self.kind)
        XCTAssertEqual(heldAfter, ["accepted:1", "queue"], "End Ride must keep debt and queue state, in order, and nothing else")
        await expect("the retired reconciliation got its terminal cancellation") { await outcomes.cancelled == [77] }
        let published = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(published, 2)

        await setClock(ready: true)
        await advanceRetries(2)
        await expect("the kept debt and queue state drained") {
            let applied = await self.coordinator.lastAppliedSeq
            let drained = await self.coordinator.deferredEvents.isEmpty
            return applied == 1 && drained
        }
        let applied = await outcomes.applied
        XCTAssertTrue(applied.isEmpty, "a reconciliation from the ended ride reported convergence: \(applied)")
        let owner = await coordinator.rideAuthorityEpoch
        XCTAssertEqual(owner, 1, "C1 completed as ride-1 work")
    }

    private static func kind(_ event: DeferredEvent) -> String {
        switch event {
        case .acceptedCommand(let accepted): return "accepted:\(accepted.commandSeq)"
        case .queueSnapshot: return "queue"
        case .playbackState(_, _, let reconciliation, _): return "playback:\(reconciliation.map(String.init) ?? "nil")"
        }
    }

    // MARK: - Boundedness: retained debt is owned by the held stream's own bound

    /// Accepted debt kept across End Ride is still bounded by `deferredCommandCapacity`, never by
    /// `SessionWorkLedger` and never by nothing: at the bound, one more command latches
    /// desynchronisation (the existing halt-and-reconcile) and the held accepted commands are refused
    /// by that explicit route — counted, with a resync request raised — rather than the stream growing.
    func testAcceptedDebtKeptAcrossEndRideStaysWithinTheHeldStreamsOwnBound() async {
        await build(deferredCommandCapacity: 4)
        await connectAsFollower(generation: 1)
        let resyncRequests = ResyncRequests()
        await coordinator.setDesynchronizedTrigger { Task { await resyncRequests.raise() } }
        _ = await coordinator.rideEpochs.next()
        await setClock(ready: false)
        var peak = 0
        for seq: Int64 in 1 ... 4 {
            await deliverAndAwait(play(Self.hashA, seq: seq), generation: 1)
            peak = max(peak, await coordinator.deferredEvents.count)
        }
        let end = await coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        let kept = await coordinator.deferredEvents.compactMap(\.acceptedCommandSeq)
        XCTAssertEqual(kept, [1, 2, 3, 4], "End Ride kept every accepted command")
        let reservations = await coordinator.retainedWorkCount
        XCTAssertEqual(reservations, 0, "held debt takes no local work capacity")

        await deliverAndAwait(play(Self.hashA, seq: 5), generation: 1)
        peak = max(peak, await coordinator.deferredEvents.count)
        XCTAssertEqual(peak, 4, "the held stream exceeded its own bound")
        let remaining = await coordinator.deferredEvents.compactMap(\.acceptedCommandSeq)
        XCTAssertTrue(remaining.isEmpty, "overflow must reconcile, not grow")
        let refused = await coordinator.diagnostics.refusedHeldCommandCount
        XCTAssertEqual(refused, 4, "the reconciliation route refused the held debt explicitly")
        let overflow = await coordinator.diagnostics.inboundOverflowCount
        XCTAssertEqual(overflow, 1)
        await expect("a STATE_REQUEST was asked for") { await resyncRequests.count == 1 }
        let received = await coordinator.lastReceivedSeq
        XCTAssertEqual(received, 4, "the overflowing command spent no command_seq; accepted ones were not rolled back")
    }

    // MARK: - Fresh-fix audit: the drain that owns retained debt must actually run

    /// End Ride now relies on the retained stream's own drain to finish accepted debt, so that drain
    /// must be live for **every** hold, not only the session's first. A drain task whose loop ended
    /// because the stream emptied is finished but not *cancelled*, and `startDeferredDrain` used to
    /// treat "not cancelled" as "running": a second clock-hold in the same session then got no retry
    /// cadence and waited for the 5 s position-report tick. Android's `isActive` check never had this.
    func testASecondClockHoldInOneSessionRecoversOnTheRetryCadence() async {
        await build()
        await connectAsFollower(generation: 1)
        for seq: Int64 in 1 ... 2 {
            await setClock(ready: false)
            await deliverAndAwait(play(seq == 1 ? Self.hashA : Self.hashB, seq: seq), generation: 1)
            let held = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
            XCTAssertEqual(held, [seq], "C\(seq) accepted and held")
            await setClock(ready: true)
            // Exactly one retry interval — far short of the 5 s tick.
            await advanceRetries(1)
            await expect("C\(seq) recovered on the retry cadence") {
                let applied = await self.coordinator.lastAppliedSeq
                let drained = await self.coordinator.deferredEvents.isEmpty
                return applied == seq && drained
            }
        }
    }

    // MARK: - Fixtures

    private func build(
        sessionWorkCapacity: Int = Phase5GateBounds.defaultSessionWorkCapacity,
        deferredCommandCapacity: Int = Phase5GateBounds.defaultDeferredCommandCapacity
    ) async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock(startUs: Self.anchorUs)
        let clock = clock!
        let ids = IdSequence(start: 900)
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: SyncTestValues.followerPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: FakeRouteState(),
            nextQueueItemId: { ids.next() },
            deferredCommandCapacity: deferredCommandCapacity,
            sessionWorkCapacity: sessionWorkCapacity
        )
        await coordinator.start()
        for hash in [Self.hashA, Self.hashB] {
            await content.addLocal(hash)
            await content.addPeer(hash)
        }
    }

    private func setClock(ready: Bool) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: ready))
    }

    private func connectAsFollower(generation: Int64) async {
        await session.setGeneration(generation)
        await setClock(ready: true)
        await coordinator.handleConnected(isLocalLeader: false)
        await player.clearCalls()
    }

    private func play(_ hash: ContentHash, seq: Int64, effectiveAtSessionUs: Int64? = nil) -> PlaybackMessage {
        .play(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAtSessionUs ?? clock.now(),
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
            ),
            trackHash: hash, positionMs: 0, queueItemId: SyncTestValues.ulid(Int(seq))
        )
    }

    private func deliverAndAwait(_ message: PlaybackMessage, generation: Int64) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message, generation: generation)
        await expect("the frame was considered") {
            await self.coordinator.diagnostics.inboundProcessedCount > before
        }
    }

    /// The drain's retry is on the virtual clock, so it only happens because the test moves time.
    private func advanceRetries(_ count: Int) async {
        for _ in 0 ..< count {
            clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
            await settle(50)
        }
    }

    private func settle(_ yields: Int) async {
        for _ in 0 ..< yields { await Task.yield() }
    }

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }
}

private actor ReconciliationOutcomes {
    private(set) var cancelled: [Int64] = []
    private(set) var applied: [Int64] = []
    func cancel(_ id: Int64) { cancelled.append(id) }
    func apply(_ id: Int64) { applied.append(id) }
}

private actor ResyncRequests {
    private(set) var count = 0
    func raise() { count += 1 }
}
