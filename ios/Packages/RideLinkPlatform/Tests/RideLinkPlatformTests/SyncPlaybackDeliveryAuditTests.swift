import Foundation
import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// The five regressions ADR-024 **Amendment A2** — the second Phase 5 closure audit — exists for.
///
/// A1 made the leader's semantic order the wire-visible order and made the inbound handoff lossless.
/// A2 is about the join A1 left open: **being on the outbound queue is not being on the wire, and
/// being on the wire is not the same session's wire.** Each test below fails on the code as A1 left
/// it and passes as amended.
///
/// **Deterministic throughout.** `FakeMonotonicClock` is the only clock, the outbound bound is
/// *injected* rather than raced, `FakeSyncSession.armSendGate` parks the single outbound consumer
/// strictly inside a write, and every wait is on a counter the coordinator publishes rather than on
/// a fixed number of yields.
///
/// The mirror is `com.ridelink.app.sync.SyncPlaybackDeliveryAuditTest`.
final class SyncPlaybackDeliveryAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 1_200

    /// The injected outbound bound the wedge tests use, so the edge is forced rather than raced.
    private static let wedgeCapacity = 2
    /// Comfortably past `LEAD = max(120 ms, 4 x rtt_p95)` for the fake's 8 ms p95.
    private static let leadUs: Int64 = 200_000
    /// `PlaybackBounds.positionReportIntervalMs`, in microseconds.
    private static let positionReportUs: Int64 = 5_000_000
    /// Where `leaderPlaying` anchors position 0: the clock's initial instant plus the 120 ms lead.
    private static let anchorSessionUs: Int64 = 1_000_000 + 120_000

    private func build(outboundCapacity: Int = 256, deferredCommandCapacity: Int = 16) async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeState = FakeRouteState()
        let clock = clock!
        let ids = IdSequence(start: idSeed)
        idSeed += 50
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: SyncTestValues.followerPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: routeState,
            nextQueueItemId: { ids.next() },
            deferredCommandCapacity: deferredCommandCapacity,
            outboundCapacity: outboundCapacity
        )
        await coordinator.start()
    }

    override func tearDown() async throws {
        await session?.releaseSendGate()
        await player?.releaseGate()
        await player?.releaseStateGate()
        await coordinator?.shutdown()
        coordinator = nil
    }

    private func connect(asLeader: Bool, clockReady: Bool = true) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: clockReady))
        await coordinator.handleConnected(isLocalLeader: asLeader)
        await awaitOutboundQuiescent()
        await player.clearCalls()
        await session.clearSent()
    }

    /// A leader with one track queued, playing, and every frame so far actually on the wire.
    private func leaderPlaying() async {
        await connect(asLeader: true)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await expect("the leader's own PLAY reached the wire") { [self] in
            await !session.playbackMessages().isEmpty
        }
        await awaitOutboundQuiescent()
        clock.advance(to: clock.now() + Self.leadUs * 2)
        await settle()
        await player.clearCalls()
        await session.clearSent()
    }

    /// Parks the outbound consumer inside a write and then fills every queue slot behind it, so the
    /// **next** authoritative operation meets a genuinely full outbound path.
    private func wedgeOutbound() async {
        await session.armSendGate()
        await coordinator.pause()
        await expect("the consumer is parked inside a write") { [self] in await session.isSendGateParked }
        for index in 0 ..< Self.wedgeCapacity {
            let before = await coordinator.diagnostics.outboundEnqueuedCount
            await coordinator.seek(positionMs: Int64(1_000 * (index + 1)))
            await expect("wedge frame \(index) is queued") { [self] in
                await coordinator.diagnostics.outboundEnqueuedCount > before
            }
        }
    }

    /// Puts this device's own drift into ADR-004's hard-seek band: 120 ms < |drift| <= 2 s.
    private func driftIntoHardSeekBand() async {
        let expectedMs = (clock.now() + Self.positionReportUs - Self.anchorSessionUs) / 1_000
        await player.setState(
            PlayerState(positionMs: expectedMs + 500, durationMs: 3_600_000, playing: true, rate: 1.0)
        )
    }

    // MARK: - Finding A — admission is not delivery

    /// **The defect.** `enqueueOutbound` returned `Void`. A full outbound queue incremented
    /// `outboundOverflowCount` and returned, and `issue` carried straight on to consume the
    /// `command_seq`, record it as applied and schedule the audible effect. The leader played a
    /// command the follower had no way of ever receiving.
    func testAnAuthoritativeCommandRefusedByTheOutboundPathIsNeverAppliedLocally() async {
        await build(outboundCapacity: Self.wedgeCapacity)
        await leaderPlaying()
        await wedgeOutbound()
        let seqAfterWedge = await coordinator.diagnostics.nextCommandSeq
        let appliedBefore = await coordinator.diagnostics.lastAppliedCommandSeq

        await coordinator.next()
        await settle()

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundOverflowCount, 1, "the refusal is counted, exactly once")
        XCTAssertEqual(diagnostics.nextCommandSeq, seqAfterWedge, "a refused candidate consumes no command_seq")
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, appliedBefore, "nothing was applied for a frame never admitted")
        let calls = await player.calls
        XCTAssertTrue(
            calls.allSatisfy { $0 == .setRate(1.0) },
            "nothing became audible for a frame that was never admitted"
        )
        XCTAssertTrue(diagnostics.outboundAuthorityLost, "the failure is explicit, not a statistic")
        XCTAssertEqual(diagnostics.syncState, .transportFailed)
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active, "transport control returns to Phase 3 rather than dying")
        await session.releaseSendGate()
    }

    /// The same defect on the queue half: the revision was bumped and published inside the step that
    /// enqueued the snapshot, whether or not the snapshot was admitted.
    func testAQueueSnapshotRefusedByTheOutboundPathNeverBumpsTheAuthoritativeRevision() async {
        await build(outboundCapacity: Self.wedgeCapacity)
        await leaderPlaying()
        let revisionBefore = await coordinator.queueState.revision
        let sizeBefore = await coordinator.queueState.items.count
        await wedgeOutbound()

        await coordinator.enqueue(SyncTestValues.hash(2))
        await settle()

        let state = await coordinator.queueState
        XCTAssertEqual(state.revision, revisionBefore, "the revision did not move")
        XCTAssertEqual(state.items.count, sizeBefore, "and neither did the queue")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundOverflowCount, 1)
        XCTAssertTrue(diagnostics.outboundAuthorityLost)
        await session.releaseSendGate()
    }

    /// What happens when the wedge clears. A frame the transport **did** accept commits and takes
    /// effect; everything queued behind the failure does not.
    func testAfterARefusalTheDeliveredFrameStillAppliesAndNothingBehindItDoes() async {
        await build(outboundCapacity: Self.wedgeCapacity)
        await leaderPlaying()
        await wedgeOutbound()

        await coordinator.next()
        await settle()
        var diagnostics = await coordinator.diagnostics
        XCTAssertTrue(diagnostics.outboundAuthorityLost)
        var sent = await session.playbackMessages()
        XCTAssertTrue(sent.isEmpty, "the PAUSE is still inside the write, so it has not reached the wire yet")

        await session.releaseSendGate()
        await awaitOutboundQuiescent()
        clock.advance(to: clock.now() + Self.leadUs * 4)
        await settle()

        sent = await session.playbackMessages()
        XCTAssertEqual(sent.filter { if case .pause = $0 { return true } else { return false } }.count, 1,
                       "the accepted write completed")
        XCTAssertTrue(sent.allSatisfy { if case .seek = $0 { return false } else { return true } },
                      "the frames behind the failure were suppressed")
        let calls = await player.calls
        XCTAssertEqual(calls.filter { $0 == .pause }.count, 1, "the delivered PAUSE did take effect")
        XCTAssertFalse(calls.contains(.seek(1_000)), "the undelivered SEEKs did not")
        XCTAssertFalse(calls.contains(.seek(2_000)))
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .transportFailed)

        // And no further authority is issued under this generation.
        let sentBefore = await session.sent.count
        await coordinator.pause()
        await coordinator.enqueue(SyncTestValues.hash(3))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await settle()
        let sentAfter = await session.sent.count
        XCTAssertEqual(sentAfter, sentBefore, "a fail-closed generation issues nothing further")
    }

    /// A new session clears the latch outright: recovery is a fresh generation, never a retry.
    func testAFreshSessionClearsTheFailClosedLatchAndWorksNormally() async {
        await build(outboundCapacity: Self.wedgeCapacity)
        await leaderPlaying()
        await wedgeOutbound()
        await coordinator.next()
        await settle()
        let probe1 = await coordinator.diagnostics.outboundAuthorityLost
        XCTAssertTrue(probe1)
        await session.releaseSendGate()
        await awaitOutboundQuiescent()

        await session.setGeneration(2)
        await connect(asLeader: true)
        let probe2 = await coordinator.diagnostics.outboundAuthorityLost
        XCTAssertFalse(probe2, "the latch is scoped to its generation")

        await content.addLocal(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await expect("the new session is authoritative again") { [self] in
            await session.playbackMessages().contains { if case .play = $0 { return true } else { return false } }
        }
    }

    /// A follower's intent owns no authority, so a refusal is a button press that did not happen.
    func testARefusedFollowerIntentIsCountedButNeverFailsTheSessionClosed() async {
        await build(outboundCapacity: Self.wedgeCapacity)
        await connect(asLeader: false)
        await wedgeOutbound()

        await coordinator.next()
        await settle()

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundOverflowCount, 1)
        XCTAssertFalse(diagnostics.outboundAuthorityLost, "a follower never owned authority to fail closed on")
        XCTAssertNotEqual(diagnostics.syncState, .transportFailed)
        await session.releaseSendGate()
    }

    // MARK: - Finding B — an outbound frame belongs to the session that authorised it

    /// **The defect.** The outbound envelope was the bare message, and `PlaybackRelay.send` resolved
    /// the authenticated writer **and the `session_id`** at send time — so a frame stamped under
    /// Session A that was still queued when Session B activated was written under Session B's
    /// identity. That is the session-confusion class ADR-023 Amendments A3/A5 hardened Phase 4
    /// against, on the outbound end of the pipe.
    func testACommandAuthorisedByADeadSessionIsNeverWrittenUnderTheSessionThatReplacedIt() async {
        await build()
        await leaderPlaying()
        let sentBefore = await coordinator.diagnostics.outboundSentCount
        await session.armSendGate()

        await coordinator.pause() // enters the write and parks
        await expect("the consumer is parked inside a write") { [self] in await session.isSendGateParked }
        let queued = await coordinator.diagnostics.outboundEnqueuedCount
        await coordinator.seek(positionMs: 45_000) // queues behind it, authorised by generation 1
        await expect("the SEEK is queued behind it") { [self] in
            await coordinator.diagnostics.outboundEnqueuedCount > queued
        }
        let probe3 = await session.sent.isEmpty
        XCTAssertTrue(probe3, "nothing has reached the wire yet")

        // Session A dies and Session B authenticates while the backlog is still queued.
        await coordinator.handleLinkLost()
        await session.setGeneration(2)
        await coordinator.handleConnected(isLocalLeader: true)
        await session.releaseSendGate()
        await awaitOutboundQuiescent()

        let probe4 = await session.sent.isEmpty
        XCTAssertTrue(probe4, "not one Session A frame reached Session B")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundSentCount, sentBefore, "and neither was counted as sent")
        XCTAssertEqual(diagnostics.outboundStaleCount, 1,
                       "the frame still queued at the boundary was refused before the write was attempted")
        XCTAssertEqual(diagnostics.outboundFailedCount, 1,
                       "and the one already inside the write was refused by the relay's own generation check")

        // A legitimate Session B command sends normally.
        await content.addLocal(SyncTestValues.hash(2))
        await content.addPeer(SyncTestValues.hash(2))
        await coordinator.playSynchronized(SyncTestValues.hash(2))
        await expect("Session B is authoritative in its own right") { [self] in
            await session.playbackMessages().contains { if case .play = $0 { return true } else { return false } }
        }
        let generations = await session.sentGenerations
        XCTAssertFalse(generations.isEmpty)
        XCTAssertTrue(generations.allSatisfy { $0 == 2 },
                      "everything written since the boundary was written under generation 2")
    }

    /// The same for authoritative queue state: a Session A snapshot may not reach Session B.
    func testAQueueSnapshotAuthorisedByADeadSessionIsNeverWrittenUnderTheSessionThatReplacedIt() async {
        await build()
        await leaderPlaying()
        let sentBefore = await coordinator.diagnostics.outboundSentCount
        await session.armSendGate()

        await coordinator.pause()
        await expect("the consumer is parked inside a write") { [self] in await session.isSendGateParked }
        let queued = await coordinator.diagnostics.outboundEnqueuedCount
        await coordinator.enqueue(SyncTestValues.hash(2)) // a QUEUE_SNAPSHOT authorised by generation 1
        await expect("the snapshot is queued behind it") { [self] in
            await coordinator.diagnostics.outboundEnqueuedCount > queued
        }

        await coordinator.handleLinkLost()
        await session.setGeneration(2)
        await coordinator.handleConnected(isLocalLeader: true)
        await session.releaseSendGate()
        await awaitOutboundQuiescent()

        let probe5 = await session.queueMessages().isEmpty
        XCTAssertTrue(probe5, "no Session A snapshot under Session B")
        let probe6 = await coordinator.diagnostics.outboundSentCount
        XCTAssertEqual(probe6, sentBefore)
        let probe7 = await coordinator.queueState.revision
        XCTAssertEqual(probe7, 0, "and Session B started from an empty queue")
    }

    // MARK: - Finding C — the transport's answer is the only definition of "sent"

    /// **The defect.** The drain did `await session.channel.send(message)` and then
    /// `outboundSentCount += 1` — the `Bool` discarded, which `@discardableResult` made silent.
    func testATransportWriteThatReturnsFalseIsNotASendAndCommitsNothing() async {
        await build()
        await leaderPlaying()
        let appliedBefore = await coordinator.diagnostics.lastAppliedCommandSeq
        let sentBefore = await coordinator.diagnostics.outboundSentCount
        let failedBefore = await coordinator.diagnostics.outboundFailedCount
        await session.setSendResult(false)

        await coordinator.pause()
        await awaitOutboundQuiescent()
        clock.advance(to: clock.now() + Self.leadUs * 4)
        await settle()

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundSentCount, sentBefore, "a write that returned false is not a send")
        XCTAssertEqual(diagnostics.outboundFailedCount, failedBefore + 1, "it is counted as what it was")
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, appliedBefore, "and nothing was committed for it")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "the leader did not pause a peer that never heard")
        XCTAssertTrue(diagnostics.outboundAuthorityLost)
        XCTAssertEqual(diagnostics.syncState, .transportFailed)
        XCTAssertEqual(
            diagnostics.outboundSentCount + diagnostics.outboundFailedCount + diagnostics.outboundStaleCount,
            diagnostics.outboundAttemptCount,
            "the three outcomes account for every attempt"
        )
    }

    /// The queue half: a snapshot the transport refused may not leave the leader silently ahead.
    func testAQueueSnapshotTheTransportRefusedFailsTheSessionClosedRatherThanDiverging() async {
        await build()
        await leaderPlaying()
        let sentBefore = await coordinator.diagnostics.outboundSentCount
        await session.setSendResult(false)

        await coordinator.enqueue(SyncTestValues.hash(2))
        await awaitOutboundQuiescent()
        await settle()

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.outboundFailedCount, 1)
        XCTAssertEqual(diagnostics.outboundSentCount, sentBefore)
        XCTAssertTrue(diagnostics.outboundAuthorityLost, "the divergence is surfaced, never silent")
        XCTAssertEqual(diagnostics.syncState, .transportFailed)
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active)
    }

    // MARK: - Finding D — nothing overtakes a held authoritative command

    /// **The defect.** A1 held a command whose clock was untrusted, but held nothing else. A
    /// `QUEUE_SNAPSHOT` arriving behind a held `NEXT` was applied **immediately**, so when the clock
    /// recovered the `NEXT` stepped a queue it was never authored against and the two phones selected
    /// different tracks.
    func testAQueueSnapshotMayNotOvertakeACommandHeldForTheClock() async {
        await build()
        await connect(asLeader: false)
        for seed in 1 ... 3 { await content.addLocal(SyncTestValues.hash(seed)) }

        // Revision 5: [A, B, C], with A current.
        let items = [
            item(SyncTestValues.ulid(1), SyncTestValues.hash(1)),
            item(SyncTestValues.ulid(2), SyncTestValues.hash(2)),
            item(SyncTestValues.ulid(3), SyncTestValues.hash(3)),
        ]
        await deliverAndAwait(.snapshot(queueRevision: 5, items: items, currentIndex: 0))
        let probe8 = await coordinator.queueState.revision
        XCTAssertEqual(probe8, 5)

        // The estimator becomes untrustworthy, then an authoritative NEXT authored at revision 5
        // arrives: at revision 5, NEXT selects B.
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await deliverAndAwait(nextCommand(seq: 10, effectiveAt: clock.now(), queueRevision: 5))
        let probe9 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe9, 1)
        let probe10 = await player.calls.isEmpty
        XCTAssertTrue(probe10)

        // Then the leader removes B, and revision 6 is [A, C].
        await deliverAndAwait(.snapshot(queueRevision: 6, items: [items[0], items[2]], currentIndex: 0))
        let probe11 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe11, 2,
                       "the snapshot joined the held stream instead of overtaking the NEXT")
        let probe12 = await coordinator.queueState.revision
        XCTAssertEqual(probe12, 5,
                       "and revision 5 is still what the held NEXT will see")

        // The clock recovers; the stream replays in arrival order.
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
        await expect("the held stream drained") { [self] in
            await coordinator.diagnostics.deferredCommandCount == 0
        }
        clock.advance(to: clock.now() + Self.leadUs * 4)
        await settle()

        let prepared = await player.calls.compactMap { call -> ContentHash? in
            if case .prepare(let hash, _) = call { return hash }
            return nil
        }
        XCTAssertEqual(prepared.count, 1, "exactly one track was loaded")
        XCTAssertEqual(prepared.first, SyncTestValues.hash(2), "NEXT resolved against revision 5, exactly as authored")
        let probe13 = await coordinator.queueState.revision
        XCTAssertEqual(probe13, 6, "and the snapshot then applied, in its own turn")
    }

    /// `SEEK`, a queue mutation and `PAUSE` all keep their original arrival order.
    func testASeekAQueueMutationAndAPauseHeldTogetherReplayInArrivalOrder() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        let items = [
            item(SyncTestValues.ulid(1), SyncTestValues.hash(1)),
            item(SyncTestValues.ulid(2), SyncTestValues.hash(2)),
        ]
        await deliverAndAwait(.snapshot(queueRevision: 5, items: items, currentIndex: 0))
        await deliverAndAwait(playCommand(seq: 9, effectiveAt: clock.now(), queueRevision: 5))
        clock.advance(to: clock.now() + Self.leadUs * 2)
        await settle()
        await player.clearCalls()

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await deliverAndAwait(seekCommand(seq: 10, effectiveAt: clock.now(), positionMs: 12_000, queueRevision: 5))
        await deliverAndAwait(.snapshot(queueRevision: 6, items: [items[0]], currentIndex: 0))
        await deliverAndAwait(pauseCommand(seq: 11, effectiveAt: clock.now(), positionMs: 12_500, queueRevision: 6))

        let probe14 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe14, 3, "all three are held, in arrival order")
        let probe15 = await player.calls.isEmpty
        XCTAssertTrue(probe15)
        let probe16 = await coordinator.queueState.revision
        XCTAssertEqual(probe16, 5)

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
        await expect("the held stream drained") { [self] in
            await coordinator.diagnostics.deferredCommandCount == 0
        }
        clock.advance(to: clock.now() + Self.leadUs * 4)
        await settle()

        let calls = await player.calls
        guard let seekIndex = calls.firstIndex(of: .seek(12_000)), let pauseIndex = calls.firstIndex(of: .pause) else {
            return XCTFail("the held stream lost a command: \(calls)")
        }
        XCTAssertTrue(pauseIndex > seekIndex, "the PAUSE followed it, exactly as the leader ordered them")
        let probe17 = await coordinator.queueState.revision
        XCTAssertEqual(probe17, 6, "and the snapshot between them applied too")
        let probe18 = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(probe18, 11)
    }

    /// A held `PLAYBACK_STATE` waits its turn too.
    func testAPlaybackStateSnapshotMayNotOvertakeACommandHeldForTheClock() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await deliverAndAwait(.snapshot(
            queueRevision: 1, items: [item(SyncTestValues.ulid(1), SyncTestValues.hash(1))], currentIndex: 0
        ))

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now(), queueRevision: 1))
        let probe19 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe19, 1)

        await deliverAndAwait(.playbackState(
            commandSeq: 9, queueRevision: 1, trackHash: SyncTestValues.hash(1),
            queueItemId: SyncTestValues.ulid(1), positionMs: 1_000, playing: true, atSessionUs: clock.now()
        ))
        let probe20 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe20, 2,
                       "an older anchor may not overtake seq 10")

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
        await expect("the held stream drained") { [self] in
            await coordinator.diagnostics.deferredCommandCount == 0
        }
        clock.advance(to: clock.now() + Self.leadUs * 4)
        await settle()
        let probe21 = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(probe21, 10,
                       "the command applied, not the older anchor")
    }

    /// A session boundary while the stream is held leaves every one of its events inert.
    func testASessionBoundaryWhileAnAuthoritativeStreamIsHeldLeavesAllOfItInert() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addLocal(SyncTestValues.hash(2))
        let items = [
            item(SyncTestValues.ulid(1), SyncTestValues.hash(1)),
            item(SyncTestValues.ulid(2), SyncTestValues.hash(2)),
        ]
        await deliverAndAwait(.snapshot(queueRevision: 5, items: items, currentIndex: 0))

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await deliverAndAwait(nextCommand(seq: 10, effectiveAt: clock.now(), queueRevision: 5))
        await deliverAndAwait(.snapshot(queueRevision: 6, items: [items[0]], currentIndex: 0))
        let probe22 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe22, 2)

        await coordinator.handleLinkLost()
        await player.clearCalls()
        let probe23 = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(probe23, 0)
        let probe24 = await coordinator.queueState.revision
        XCTAssertEqual(probe24, 0, "the old session's queue went with it")

        await session.setGeneration(2)
        await connect(asLeader: false)
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 4)
        await settle()

        let probe25 = await player.calls.isEmpty
        XCTAssertTrue(probe25,
                      "not one held event of the old session touched the new one's player")
        let probe26 = await coordinator.queueState.revision
        XCTAssertEqual(probe26, 0)
        let probe27 = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertNil(probe27)
    }

    /// The hold buffer is bounded, and reaching the bound is the same explicit halt as everywhere else.
    func testOverflowingTheHeldAuthoritativeStreamHaltsRatherThanReordering() async {
        await build(deferredCommandCapacity: 1)
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await deliverAndAwait(.snapshot(
            queueRevision: 5, items: [item(SyncTestValues.ulid(1), SyncTestValues.hash(1))], currentIndex: 0
        ))

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        await deliverAndAwait(nextCommand(seq: 10, effectiveAt: clock.now(), queueRevision: 5))
        await deliverAndAwait(.snapshot(queueRevision: 6, items: [], currentIndex: nil))

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.deferredCommandCount, 1, "the bound is real")
        XCTAssertEqual(diagnostics.inboundOverflowCount, 1)
        XCTAssertTrue(diagnostics.ingressDesynchronized, "an overflow halts; it never evicts and never reorders")
        let probe28 = await coordinator.queueState.revision
        XCTAssertEqual(probe28, 5, "and the snapshot that overflowed did not apply")
    }

    // MARK: - Finding E — a correction's snapshot keeps the correction's own identity

    /// **The defect.** `emitPlaybackState()` read `session.currentAuthGeneration()` *inside itself*,
    /// so a correction that had proved ownership of generation A handed the enqueue whatever
    /// generation happened to be live by then. On an actor that window is not theoretical: the two
    /// reads before it are both `await`s, and each is a re-entrancy point.
    ///
    /// This lands the boundary **inside** `player.playerState()`, which the emit awaits — the exact
    /// interleaving the pre-A2 code would have written a Session A snapshot into Session B on.
    func testACorrectionSupersededBeforeItsSnapshotEnqueueEmitsNothing() async {
        await build()
        await leaderPlaying()
        // The cadence tick reads `playerState()` once for the drift measurement; the emit reads it
        // again. Parking the **second** read puts the boundary strictly between the correction's
        // successful ownership proof and the enqueue — the window the pre-A2 code closed by reading
        // the *live* generation there, and therefore did not close at all.
        await player.armStateGate(skipping: 1)
        await driftIntoHardSeekBand()
        let enqueuedBefore = await coordinator.diagnostics.outboundEnqueuedCount

        clock.advance(to: clock.now() + Self.positionReportUs)
        await expect("the emit is parked on its own player read") { [self] in await player.isStateGateParked }

        await session.setGeneration(2)
        await player.releaseStateGate()
        await settle()

        let snapshots = await session.playbackMessages().filter {
            if case .playbackState = $0 { return true } else { return false }
        }
        XCTAssertTrue(snapshots.isEmpty, "a snapshot caused by a correction in Session A never reaches Session B")
        let enqueued = await coordinator.diagnostics.outboundEnqueuedCount
        XCTAssertEqual(enqueued, enqueuedBefore + 1,
                       "only the tick's own POSITION_REPORT — nothing attributable to the correction")
    }

    /// The same window, closed by the *epoch* half rather than the session half: the pre-A2 emit
    /// took no token at all, so a snapshot caused by a correction belonging to a superseded playback
    /// epoch was indistinguishable from a current one.
    func testACorrectionWhoseEpochIsSupersededBeforeItsSnapshotEnqueueEmitsNothing() async {
        await build()
        await leaderPlaying()
        await player.armStateGate(skipping: 1)
        await driftIntoHardSeekBand()

        clock.advance(to: clock.now() + Self.positionReportUs)
        await expect("the emit is parked on its own player read") { [self] in await player.isStateGateParked }

        await content.addLocal(SyncTestValues.hash(2))
        await content.addPeer(SyncTestValues.hash(2))
        await coordinator.playSynchronized(SyncTestValues.hash(2))
        await settle()
        await player.releaseStateGate()
        await settle()

        let snapshots = await session.playbackMessages().filter {
            if case .playbackState = $0 { return true } else { return false }
        }
        XCTAssertTrue(snapshots.isEmpty, "a snapshot caused by a superseded epoch's correction is never emitted")
    }

    /// The epoch half, which the old `emitPlaybackState()` could not check at all: it took no token.
    func testACorrectionWhosePlaybackEpochIsSupersededEmitsNothing() async {
        await build()
        await leaderPlaying()
        await player.gateCalls { call in if case .seek = call { return true } else { return false } }
        await driftIntoHardSeekBand()

        clock.advance(to: clock.now() + Self.positionReportUs)
        await expect("the ladder reached the hard-seek tier") { [self] in await player.isGateParked }

        // A new epoch begins while the correction is inside the player.
        await content.addLocal(SyncTestValues.hash(2))
        await content.addPeer(SyncTestValues.hash(2))
        await coordinator.playSynchronized(SyncTestValues.hash(2))
        await settle()
        await player.releaseGate()
        await settle()

        let probe30 = await coordinator.diagnostics.hardSeekCount
        XCTAssertEqual(probe30, 0,
                       "the superseded correction had zero effects")
    }

    /// And the control: a correction that is still current does emit exactly one snapshot.
    func testACurrentCorrectionStillEmitsExactlyOneAuthoritativeSnapshot() async {
        await build()
        await leaderPlaying()
        await driftIntoHardSeekBand()

        clock.advance(to: clock.now() + Self.positionReportUs)
        // The snapshot itself is the signal: `hardSeekCount` moves one statement before the emit, so
        // waiting on the counter and then reading the wire is a race the A1 suites already learned
        // about the hard way.
        await expect("the correction emitted its authoritative snapshot") { [self] in
            await session.playbackMessages().contains {
                if case .playbackState = $0 { return true } else { return false }
            }
        }
        await awaitOutboundQuiescent()

        let snapshots = await session.playbackMessages().filter {
            if case .playbackState = $0 { return true } else { return false }
        }
        XCTAssertEqual(snapshots.count, 1, "the ordinary path is untouched by the ownership work")
        let finalHardSeeks = await coordinator.diagnostics.hardSeekCount
        XCTAssertEqual(finalHardSeeks, 1)
    }

    // MARK: - Helpers

    private func deliverAndAwait(_ message: PlaybackMessage) async {
        await awaitIngressIdle()
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message)
        await awaitProcessed(beyond: before)
    }

    private func deliverAndAwait(_ message: QueueMessage) async {
        await awaitIngressIdle()
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message)
        await awaitProcessed(beyond: before)
    }

    private func awaitProcessed(beyond before: Int) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.diagnostics.inboundProcessedCount > before {
                await awaitOutboundQuiescent()
                return
            }
            await Task.yield()
        }
        XCTFail("the coordinator never finished considering the frame")
    }

    private func awaitIngressIdle() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.isIngressIdle() { return }
            await Task.yield()
        }
        XCTFail("the ingress never became idle")
    }

    private func awaitOutboundQuiescent() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let diagnostics = await coordinator.diagnostics
            if diagnostics.outboundAttemptCount == diagnostics.outboundEnqueuedCount { return }
            if await session.isSendGateParked { return }
            await Task.yield()
        }
        XCTFail("the ordered outbound path never drained")
    }

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    private func item(_ queueItemId: String, _ hash: ContentHash) -> SharedQueueItem {
        SharedQueueItem(
            queueItemId: queueItemId, trackHash: hash, addedBy: SyncTestValues.leaderPeerId,
            order: PlaybackBounds.queueOrderStep
        )
    }

    private func playCommand(seq: Int64, effectiveAt: Int64, queueRevision: Int64) -> PlaybackMessage {
        .play(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: queueRevision
            ),
            trackHash: SyncTestValues.hash(1),
            positionMs: 0,
            queueItemId: SyncTestValues.ulid(1)
        )
    }

    private func nextCommand(seq: Int64, effectiveAt: Int64, queueRevision: Int64) -> PlaybackMessage {
        .next(header: PlaybackCommandHeader(
            commandSeq: seq, effectiveAtSessionUs: effectiveAt,
            issuedBy: SyncTestValues.leaderPeerId, queueRevision: queueRevision
        ))
    }

    private func seekCommand(seq: Int64, effectiveAt: Int64, positionMs: Int64, queueRevision: Int64) -> PlaybackMessage {
        .seek(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: queueRevision
            ),
            targetPositionMs: positionMs
        )
    }

    private func pauseCommand(seq: Int64, effectiveAt: Int64, positionMs: Int64, queueRevision: Int64) -> PlaybackMessage {
        .pause(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: queueRevision
            ),
            positionMs: positionMs
        )
    }
}
