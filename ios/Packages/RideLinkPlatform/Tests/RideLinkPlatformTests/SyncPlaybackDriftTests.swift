import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// The drift half of the coordinator's wiring: that a tick reads the *one* player, measures against
/// the **authoritative timeline** (never one phone's position minus the other's — brief §33), asks
/// `DriftController`, and does what it says. The mirror is
/// `com.ridelink.app.sync.SyncPlaybackDriftTest`.
///
/// The ladder's own boundaries, hysteresis and seek budget are pinned by `protocol/vectors/drift/` on
/// both platforms and are deliberately not re-asserted here; what is asserted here is that this class
/// consults that table and honours its answer, including the two guards that only exist at this
/// layer — route-transition suspension and epoch/session binding.
final class SyncPlaybackDriftTests: XCTestCase {
    /// `FakeMonotonicClock`'s starting instant, which is also the PLAY's `effective_at`.
    private let anchorUs: Int64 = 1_000_000

    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!

    private func startPlaying() async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeState = FakeRouteState()
        let clock = clock!
        let ids = IdSequence(start: 300)
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: SyncTestValues.followerPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: routeState,
            nextQueueItemId: { ids.next() }
        )
        await coordinator.start()
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await coordinator.handleConnected(isLocalLeader: false)
        await settle()
        await content.addLocal(SyncTestValues.hash(1))
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(
            .play(
                header: PlaybackCommandHeader(
                    commandSeq: 1, effectiveAtSessionUs: clock.now(),
                    issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
                ),
                trackHash: SyncTestValues.hash(1),
                positionMs: 0,
                queueItemId: SyncTestValues.ulid(1)
            )
        )
        // The `PLAY` must be fully applied before the first cadence tick is driven: a tick that
        // finds no timeline yet does nothing at all — correctly — and would leave the wait below
        // with nothing to observe. A 1-in-100 stress failure found exactly that.
        await expect("the PLAY was applied") { [coordinator] in
            await coordinator!.diagnostics.inboundProcessedCount > before
        }
        await expect("playback started") { [player] in await player!.calls.contains(.start) }
        await player.clearCalls()
        await session.clearSent()
    }

    private func deliverAndAwait(_ message: PlaybackMessage) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message)
        await expect("the frame was considered") { [coordinator] in
            await coordinator!.diagnostics.inboundProcessedCount > before
        }
    }

    /// Waits for a condition rather than for a fixed number of scheduler yields.
    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    /// Advances to the next 5 s report tick with the player reporting `expected + driftMs`, then
    /// waits for that tick to actually **finish**.
    ///
    /// The wait is on `correctionTickCount`, not on a number of scheduler yields. A tick spans
    /// several actor hops — sending the report, re-proving the session and epoch, reading the route
    /// state, applying the correction — and "yield 40 times and hope" is a race that fails roughly
    /// 7 % of the time on a loaded machine. It was found by stress-running this suite, reproduced on
    /// the first attempt, and fixed here in the harness rather than by re-running: the production
    /// behaviour was correct throughout, and the counter it now publishes is a real FR-023 figure.
    private func tick(driftMs: Int64, playing: Bool = true) async {
        // The cadence loop re-arms its next sleep *after* a tick completes, so a second tick driven
        // straight after the first can arrive before the deadline exists. Waited for, not assumed —
        // this is the loop's own observable state, not a yield count.
        await awaitTickArmed()
        guard let nextTickUs = clock.pendingDeadlines().max() else { return XCTFail("no tick is armed") }
        let before = await coordinator.diagnostics.correctionTickCount
        let elapsedMs = (nextTickUs - anchorUs) / 1_000
        await player.setState(PlayerState(positionMs: elapsedMs + driftMs, durationMs: 600_000, playing: playing))
        clock.advance(to: nextTickUs)
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.diagnostics.correctionTickCount > before {
                // ADR-024 Amendment A1 Finding B made the outbound send asynchronous relative to the
                // step that produced the frame — which is exactly what makes the leader's order
                // provable — so "the tick completed" and "its POSITION_REPORT reached the wire" are
                // two facts. A 3-in-100 stress failure was reading the wire before the second one.
                await awaitOutboundQuiescent()
                return
            }
            await Task.yield()
        }
        XCTFail("the cadence tick never completed")
    }

    func testATickReportsOurOwnPositionAgainstTheAuthoritativeTimeline() async {
        await startPlaying()
        await tick(driftMs: 0)
        let reports = await session.playbackMessages().compactMap { message -> (ContentHash, Int64, Bool)? in
            guard case .positionReport(let hash, _, let at, let playing, _) = message else { return nil }
            return (hash, at, playing)
        }
        XCTAssertEqual(reports.count, 1)
        XCTAssertEqual(reports.first?.0, SyncTestValues.hash(1))
        XCTAssertEqual(reports.first?.1, clock.now(), "the report is stamped in session time, never wall-clock")
        XCTAssertEqual(reports.first?.2, true)
    }

    func testDriftInsideTheNudgeBandSetsTheRateOnTheOnePlayer() async {
        await startPlaying()
        // Ahead of the timeline by 40 ms: slow down.
        await tick(driftMs: 40)
        let calls = await player.calls
        XCTAssertEqual(calls.last, .setRate(DriftController.rateSlower))
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lastCorrection, .nudge)
        XCTAssertEqual(diagnostics.localDriftMs, 40)
    }

    func testAConvergedNudgeIsRestoredToExactlyOnePointZero() async {
        await startPlaying()
        await tick(driftMs: 40)
        await player.clearCalls()
        await tick(driftMs: 5)
        let calls = await player.calls
        XCTAssertEqual(calls.last, .setRate(1.0))
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lastCorrection, .restoreRate)
    }

    func testDriftPastTheNudgeBandHardSeeksToTheExpectedPositionAndCountsIt() async {
        await startPlaying()
        await tick(driftMs: 400)
        let seeks = await player.calls.compactMap { call -> Int64? in
            if case .seek(let position) = call { return position }
            return nil
        }
        XCTAssertEqual(seeks.count, 1)
        XCTAssertEqual(seeks.first, (clock.now() - anchorUs) / 1_000, "the seek target is the authoritative timeline's position")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.hardSeekCount, 1)
    }

    func testARouteTransitionSuspendsCorrectionEntirelyAndDoesNotSpendTheSeekBudget() async {
        await startPlaying()
        await routeState.set(true)
        await tick(driftMs: 400)
        await tick(driftMs: 400)
        await tick(driftMs: 400)
        var calls = await player.calls
        XCTAssertFalse(calls.contains { if case .seek = $0 { return true } else { return false } }, "no seek while transitioning")
        XCTAssertFalse(calls.contains { if case .setRate = $0 { return true } else { return false } }, "no nudge while transitioning")
        var diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.hardSeekCount, 0)
        XCTAssertTrue(diagnostics.routeTransitioning)

        // Three seeks would have declared failure by now had the transition counted; it must not.
        await routeState.set(false)
        await tick(driftMs: 400)
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.hardSeekCount, 1)
        XCTAssertEqual(diagnostics.lastCorrection, .hardSeek)
        calls = await player.calls
        XCTAssertTrue(calls.contains { if case .seek = $0 { return true } else { return false } })
    }

    func testCatastrophicDriftDeclaresSyncFailureRestoresTheRateAndLeavesMusicPlaying() async {
        await startPlaying()
        await tick(driftMs: 5_000)
        var diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .syncFailed)
        var calls = await player.calls
        XCTAssertEqual(calls.last(where: { if case .setRate = $0 { return true } else { return false } }), .setRate(1.0))
        XCTAssertFalse(calls.contains(.stop), "FR-025: local music keeps playing")

        await player.clearCalls()
        await tick(driftMs: 5_000)
        calls = await player.calls
        XCTAssertFalse(
            calls.contains { if case .seek = $0 { return true } else { return false } },
            "correction is over once sync has failed"
        )
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .syncFailed)
    }

    func testLeavingSynchronizedModeRestoresExactlyOnePointZeroAndStopsCorrecting() async {
        await startPlaying()
        await tick(driftMs: 40)
        await player.clearCalls()
        await coordinator.leaveSynchronizedMode()
        let calls = await player.calls
        XCTAssertEqual(calls.last, .setRate(1.0))
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active)
        let diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.localDriftMs)
    }

    func testAPeerPositionReportForADifferentTrackIsIgnored() async {
        await startPlaying()
        await deliverAndAwait(.positionReport(trackHash: SyncTestValues.hash(9), positionMs: 1_000, atSessionUs: clock.now(), playing: true, playbackRate: 1.0))
        let diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.peerDriftMs, "a report for another track says nothing about this one")
    }

    func testAPeerPositionReportFromBeforeThisEpochsAnchorIsIgnored() async {
        await startPlaying()
        // The same track_hash, but stamped before this play of it began — brief §32's exact case,
        // and why content_hash alone is not enough to identify a playback epoch.
        await deliverAndAwait(.positionReport(trackHash: SyncTestValues.hash(1), positionMs: 55_000, atSessionUs: anchorUs - 1, playing: true, playbackRate: 1.0))
        var diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.peerDriftMs)

        await deliverAndAwait(.positionReport(trackHash: SyncTestValues.hash(1), positionMs: 40, atSessionUs: anchorUs + 1_000_000, playing: true, playbackRate: 1.0))
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.peerDriftMs, -960, "the peer is 960 ms behind the timeline")
    }

    private func awaitTickArmed() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if !clock.pendingDeadlines().isEmpty { return }
            await Task.yield()
        }
    }

    /// Waits until every frame the coordinator has enqueued on its **one ordered outbound path** has
    /// actually been handed to the transport. The signal is the coordinator's own counters, never a
    /// yield count.
    private func awaitOutboundQuiescent() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let diagnostics = await coordinator.diagnostics
            if diagnostics.outboundSentCount == diagnostics.outboundEnqueuedCount { return }
            await Task.yield()
        }
        XCTFail("the ordered outbound path never drained")
    }

}
