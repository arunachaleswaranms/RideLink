import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// Coordinator-level Phase 5 behaviour: ordering, the intent hop, scheduling, the availability gate,
/// session binding and playback-epoch binding. The mirror is
/// `com.ridelink.app.sync.SyncPlaybackCoordinatorTest`, asserting the same properties.
///
/// **The only clock is `FakeMonotonicClock`** — time moves when a test moves it and never otherwise,
/// so a failure here is a statement about the algorithm rather than about how busy the machine was.
/// `settle()` drains the actor's queued work; it waits on the cooperative pool, not on wall time.
///
/// The pure tables these tests drive (`CommandOrderGate`, `DriftController`, `SharedQueue`,
/// `SessionClock`) are pinned separately and identically on both platforms by `protocol/vectors/`.
/// What is asserted *here* is the wiring: that the coordinator consults them and honours the answer.
final class SyncPlaybackCoordinatorTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 100

    private func build() async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeState = FakeRouteState()
        let clock = clock!
        var seed = idSeed
        idSeed += 50
        let ids = IdSequence(start: seed)
        seed = 0
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: SyncTestValues.leaderPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: routeState,
            nextQueueItemId: { ids.next() }
        )
        await coordinator.start()
    }

    private func connect(asLeader: Bool, clockReady: Bool = true) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: clockReady))
        await coordinator.handleConnected(isLocalLeader: asLeader)
        await settle()
        // Establishing a session legitimately restores the rate to exactly 1.0 (brief §38) — real
        // behaviour, asserted on its own in the link-loss test below. Cleared here so the scheduling
        // tests can assert the *exact* call sequence a command produces.
        await player.clearCalls()
        await session.clearSent()
    }

    private var lead: Int64 { SessionClock.leadUs(rttP95Us: 8_000) }

    // MARK: - Role and clock readiness

    func testTheRoleComesFromTheElectionNotFromWhoDialled() async {
        await build()
        await connect(asLeader: false)
        var diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.role, .follower)
        await connect(asLeader: true)
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.role, .leader)
    }

    func testALeaderWithAnUnreadyClockIssuesNothingAndSaysSo() async {
        await build()
        await connect(asLeader: true, clockReady: false)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await settle()
        let playback = await session.playbackMessages()
        XCTAssertTrue(playback.isEmpty, "no command may be scheduled against a dubious clock")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .clockUnready)
    }

    // MARK: - The availability gate (brief §19, REQUIREMENTS §9.4)

    func testATrackThePeerLacksCannotBeginSynchronizedPlayback() async {
        await build()
        await connect(asLeader: true)
        await content.addLocal(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await settle()
        let playback = await session.playbackMessages()
        XCTAssertTrue(playback.isEmpty, "a remote-only track must not start")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .waitingForContent)
    }

    func testATrackThisDeviceLacksRequestsTheTransferThroughPhase4() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await settle()
        let requests = await content.transferRequests
        XCTAssertEqual(requests, [SyncTestValues.hash(1)])
        let calls = await player.calls
        XCTAssertTrue(calls.isEmpty, "nothing may be prepared for content that is not here")
    }

    func testAPlayForContentThisDeviceLacksDoesNotStartAndRequestsTheTransfer() async {
        await build()
        await connect(asLeader: false)
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now() + 200_000))
        await settle()
        let calls = await player.calls
        XCTAssertTrue(calls.isEmpty, "PROTOCOL §5 rule 4: never start a track that is not present")
        let requests = await content.transferRequests
        XCTAssertEqual(requests, [SyncTestValues.hash(1)])
    }

    // MARK: - Scheduling (PROTOCOL §5 rule 2, ARCHITECTURE §7.2)

    func testAFutureDeadlinePreRollsNowAndStartsExactlyAtTheDeadline() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        let effectiveAt = clock.now() + 500_000
        await session.deliver(playCommand(seq: 1, effectiveAt: effectiveAt))
        await settle()
        var calls = await player.calls
        XCTAssertEqual(calls, [.prepare(SyncTestValues.hash(1), 0)])
        XCTAssertTrue(clock.pendingDeadlines().contains(effectiveAt), "the command waits for its own deadline")

        clock.advance(to: effectiveAt - 1)
        await settle()
        calls = await player.calls
        XCTAssertEqual(calls.count, 1, "nothing may start before the deadline")

        clock.advance(to: effectiveAt)
        await settle()
        calls = await player.calls
        XCTAssertEqual(calls.last, .start)
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .synced)
    }

    func testADeadlineAlreadyPastAppliesImmediatelyAndCountsTheLateness() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now() - 250_000))
        await settle()
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.start), "a late command applies, it is never skipped")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lateCommandCount, 1)
        XCTAssertEqual(diagnostics.lastScheduleErrorUs, 250_000)
        XCTAssertTrue(
            clock.pendingDeadlines().allSatisfy { $0 > clock.now() },
            "PROTOCOL §5 rule 2: never schedule into the past — the only wait outstanding is the 5 s report tick"
        )
    }

    // MARK: - Ordering (PROTOCOL §2.1/§5)

    func testDuplicateAndStaleCommandSeqAreDroppedAndCounted() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        let at = clock.now() + 100_000
        await session.deliver(playCommand(seq: 5, effectiveAt: at))
        await settle()
        let afterFirst = await player.calls.count

        await session.deliver(playCommand(seq: 5, effectiveAt: at))
        await session.deliver(playCommand(seq: 4, effectiveAt: at))
        await settle()
        let calls = await player.calls
        XCTAssertEqual(calls.count, afterFirst, "neither a duplicate nor a stale command may touch the player")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.duplicateCommandCount, 1)
        XCTAssertEqual(diagnostics.staleCommandCount, 1)
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 5)
    }

    func testAFollowerRefusesAnIntentAndALeaderRefusesAnAuthoritativeCommand() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await session.deliver(playCommand(seq: PlaybackBounds.unassignedCommandSeq, effectiveAt: clock.now()))
        await settle()
        var diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.roleViolationCount, 1)
        var calls = await player.calls
        XCTAssertTrue(calls.isEmpty)

        await build()
        await connect(asLeader: true)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(1))
        await session.deliver(playCommand(seq: 9, effectiveAt: clock.now()))
        await settle()
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.roleViolationCount, 1, "a follower cannot fabricate an authoritative command_seq")
        calls = await player.calls
        XCTAssertTrue(calls.isEmpty)
    }

    // MARK: - The intent hop (ADR-010, ADR-024 §3)

    func testAFollowerSendsAnIntentWithCommandSeqZeroAndNeverAllocatesOne() async {
        await build()
        await connect(asLeader: false)
        await coordinator.pause()
        await settle()
        let sent = await session.playbackMessages()
        guard case .pause(let header, _)? = sent.first else { return XCTFail("expected one PAUSE intent, got \(sent)") }
        XCTAssertEqual(header.commandSeq, PlaybackBounds.unassignedCommandSeq)
        XCTAssertEqual(header.effectiveAtSessionUs, 0, "a follower has no authority to choose an audible instant")
        let calls = await player.calls
        XCTAssertTrue(calls.isEmpty, "a follower changes no audio until the leader's broadcast returns")
    }

    func testTheLeaderStampsAFollowerIntentAndBroadcastsItAuthoritatively() async {
        await build()
        await connect(asLeader: true)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(1))
        await session.deliver(intentPause())
        await settle()
        let sent = await session.playbackMessages()
        guard case .pause(let header, _)? = sent.first else { return XCTFail("expected a stamped PAUSE, got \(sent)") }
        XCTAssertEqual(header.commandSeq, PlaybackBounds.firstCommandSeq)
        XCTAssertEqual(header.effectiveAtSessionUs, clock.now() + lead)
        XCTAssertEqual(header.issuedBy, SyncTestValues.leaderPeerId)
    }

    func testTwoSimultaneousFollowerIntentsReceiveConsecutiveSequenceNumbers() async {
        await build()
        await connect(asLeader: true)
        await session.deliver(intentPause())
        await session.deliver(intentPause())
        await settle()
        let seqs = await session.playbackMessages().compactMap { message -> Int64? in
            guard case .pause(let header, _) = message else { return nil }
            return header.commandSeq
        }
        XCTAssertEqual(seqs, [1, 2], "the leader's arrival order is the only thing that decides")
    }

    // MARK: - The shared queue

    func testTheLeaderBroadcastsASnapshotAfterEveryAcceptedMutation() async {
        await build()
        await connect(asLeader: true)
        await coordinator.enqueue(SyncTestValues.hash(1))
        await settle()
        var snapshots = await session.queueMessages()
        guard case .snapshot(let revision, let items, _)? = snapshots.last else { return XCTFail("expected a snapshot") }
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(items.count, 1)

        await coordinator.enqueue(SyncTestValues.hash(1))
        await settle()
        snapshots = await session.queueMessages()
        guard case .snapshot(let revision2, _, _)? = snapshots.last else { return XCTFail("expected a snapshot") }
        XCTAssertEqual(revision2, 2)
        let queue = await coordinator.queueState
        XCTAssertEqual(queue.items.count, 2, "the same track twice is two independent entries")
    }

    func testAFollowerAdoptsTheSnapshotWholesaleAndNeverIncrementsARevisionItself() async {
        await build()
        await connect(asLeader: false)
        await coordinator.enqueue(SyncTestValues.hash(1))
        await settle()
        var queue = await coordinator.queueState
        XCTAssertEqual(queue.revision, 0, "a follower's own add is only an intent")
        let adds = await session.queueMessages().filter { if case .add = $0 { return true } else { return false } }
        XCTAssertEqual(adds.count, 1)

        await session.deliver(
            .snapshot(
                queueRevision: 42,
                items: [SharedQueueItem(
                    queueItemId: SyncTestValues.ulid(1), trackHash: SyncTestValues.hash(1),
                    addedBy: SyncTestValues.leaderPeerId, order: 1024
                )],
                currentIndex: 0
            )
        )
        await settle()
        queue = await coordinator.queueState
        XCTAssertEqual(queue.revision, 42)
        XCTAssertEqual(queue.currentItemId, SyncTestValues.ulid(1))
    }

    func testAStaleRevisionIntentIsRefusedAndAnsweredWithAFreshSnapshot() async {
        await build()
        await connect(asLeader: true)
        await coordinator.enqueue(SyncTestValues.hash(1))
        await settle()
        let before = await session.queueMessages().count

        await session.deliver(
            .add(
                header: QueueCommandHeader(commandSeq: PlaybackBounds.unassignedCommandSeq, queueRevision: 0),
                items: [QueueAddItem(
                    queueItemId: SyncTestValues.ulid(7), trackHash: SyncTestValues.hash(2),
                    addedBy: SyncTestValues.followerPeerId, position: PlaybackBounds.queuePositionEnd
                )]
            )
        )
        await settle()
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.staleRevisionCount, 1)
        let after = await session.queueMessages().count
        XCTAssertEqual(after, before + 1, "the leader re-broadcasts rather than waiting to be asked")
        let queue = await coordinator.queueState
        XCTAssertEqual(queue.items.count, 1, "the stale mutation was not applied")
    }

    // MARK: - Session binding (ADR-023 §3's lesson)

    func testACommandDispatchedUnderASessionThatHasSinceEndedIsInert() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now() + 100_000))
        await session.setGeneration(2)
        await settle()
        let calls = await player.calls
        XCTAssertTrue(calls.isEmpty, "an old session's command may never touch the new session's player")
    }

    func testALinkLossRestoresTheRateToExactlyOneAndStopsCorrecting() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now()))
        await settle()
        await player.clearCalls()

        await coordinator.handleLinkLost()
        await settle()
        let calls = await player.calls
        XCTAssertEqual(calls.last, .setRate(DriftController.rateNormal))
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .inactive)
        XCTAssertNil(diagnostics.role)
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active)
    }

    func testAScheduledStartBelongingToASupersededEpochNeverFires() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addLocal(SyncTestValues.hash(2))
        let firstAt = clock.now() + 400_000
        await session.deliver(playCommand(seq: 1, effectiveAt: firstAt, seed: 1))
        await settle()

        let secondAt = clock.now() + 600_000
        await session.deliver(playCommand(seq: 2, effectiveAt: secondAt, seed: 2))
        await settle()

        clock.advance(to: firstAt)
        await settle()
        var calls = await player.calls
        XCTAssertFalse(calls.contains(.start), "track A's timer must not start track B")

        clock.advance(to: secondAt)
        await settle()
        calls = await player.calls
        XCTAssertEqual(calls.last, .start)
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.currentTrackHash, SyncTestValues.hash(2))
    }

    // MARK: - Helpers

    private func playCommand(seq: Int64, effectiveAt: Int64, seed: Int = 1) -> PlaybackMessage {
        .play(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
            ),
            trackHash: SyncTestValues.hash(seed),
            positionMs: 0,
            queueItemId: SyncTestValues.ulid(seed)
        )
    }

    private func intentPause() -> PlaybackMessage {
        .pause(
            header: PlaybackCommandHeader(
                commandSeq: PlaybackBounds.unassignedCommandSeq, effectiveAtSessionUs: 0,
                issuedBy: SyncTestValues.followerPeerId, queueRevision: 0
            ),
            positionMs: 1_000
        )
    }
}

/// A deterministic `queue_item_id` source — the tests assert on exact ids, so a ULID generator would
/// make them unreadable.
final class IdSequence: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int

    init(start: Int) { value = start }

    func next() -> String {
        lock.withLock {
            value += 1
            return SyncTestValues.ulid(value)
        }
    }
}
