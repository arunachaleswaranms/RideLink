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
        await settle()
        await player.clearCalls()
        await session.clearSent()
    }

    /// Advances to the next 5 s report tick with the player reporting `expected + driftMs`.
    private func tick(driftMs: Int64, playing: Bool = true) async {
        guard let nextTickUs = clock.pendingDeadlines().max() else { return XCTFail("no tick is armed") }
        let elapsedMs = (nextTickUs - anchorUs) / 1_000
        await player.setState(PlayerState(positionMs: elapsedMs + driftMs, durationMs: 600_000, playing: playing))
        clock.advance(to: nextTickUs)
        await settle()
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
        await settle()
        let calls = await player.calls
        XCTAssertEqual(calls.last, .setRate(1.0))
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active)
        let diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.localDriftMs)
    }

    func testAPeerPositionReportForADifferentTrackIsIgnored() async {
        await startPlaying()
        await session.deliver(.positionReport(trackHash: SyncTestValues.hash(9), positionMs: 1_000, atSessionUs: clock.now(), playing: true, playbackRate: 1.0))
        await settle()
        let diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.peerDriftMs, "a report for another track says nothing about this one")
    }

    func testAPeerPositionReportFromBeforeThisEpochsAnchorIsIgnored() async {
        await startPlaying()
        // The same track_hash, but stamped before this play of it began — brief §32's exact case,
        // and why content_hash alone is not enough to identify a playback epoch.
        await session.deliver(.positionReport(trackHash: SyncTestValues.hash(1), positionMs: 55_000, atSessionUs: anchorUs - 1, playing: true, playbackRate: 1.0))
        await settle()
        var diagnostics = await coordinator.diagnostics
        XCTAssertNil(diagnostics.peerDriftMs)

        await session.deliver(.positionReport(trackHash: SyncTestValues.hash(1), positionMs: 40, atSessionUs: anchorUs + 1_000_000, playing: true, playbackRate: 1.0))
        await settle()
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.peerDriftMs, -960, "the peer is 960 ms behind the timeline")
    }
}
