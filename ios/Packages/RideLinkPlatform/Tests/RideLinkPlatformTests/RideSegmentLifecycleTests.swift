import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// Independent-review round 3, Blocker C, iOS half: the ride-segment lifetime.
///
/// **The defect.** `SessionCoordinator.endRide()` produced `RIDE_ACTIVE -> CONNECTED` and nothing
/// else. `SyncPlaybackCoordinator.currentPlaybackIdentity` deliberately survives an ordinary
/// control-link loss (round-2 Blocker 2B), so it also survived the end of the ride: the track ride 1
/// was playing was still what a leader's `STATE_SNAPSHOT` reported after ride 2 had begun. The only
/// production caller of `leaveSynchronizedMode()` was the "Play locally" button; the End Ride order
/// existed **only in tests that called it by hand**.
///
/// **Disclosed limitation, deliberately not papered over.** `ios/RideLink.xcodeproj` declares one
/// application target and **no unit-test bundle**, so `SessionCoordinator.endRide()` itself is
/// unreachable from every test in this repository (`docs/STATUS.md` §4 problem 20; the same gap
/// `ReconnectResyncStressTests` already records). The fix therefore puts every ride-lifetime
/// *decision* in `RideSegmentLifecycle`, inside this package, and leaves `SessionCoordinator` with
/// two calls that contain no logic: `guard applyEvent(.endRide)`, take the epoch, hand it to
/// `launchInSession`. This suite drives `RideSegmentLifecycle` and the real `SyncPlaybackCoordinator`
/// exactly as those two lines do. Android's `ResyncRecoveryTest` exercises the genuine
/// `SessionCoordinator.endRide()` entry point, because Android's coordinator *is* unit-testable —
/// so the production ordering is proved end to end on one platform and at the highest reachable seam
/// on the other.
@MainActor
final class RideSegmentLifecycleTests: XCTestCase {
    /// Records what the leader's ordered outbound writer actually put on the resync wire.
    private actor RecordingResyncChannel: ResyncChannel {
        private(set) var sent: [ResyncMessage] = []

        func setSink(_ sink: (any ResyncSink)?) async {}

        @discardableResult
        func send(_ message: ResyncMessage, generation: Int64) async -> Bool {
            sent.append(message)
            return true
        }

        func clear() { sent.removeAll() }
    }

    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var sync: SyncPlaybackCoordinator!
    private var lifecycle: RideSegmentLifecycle!
    private var resyncChannel: RecordingResyncChannel!

    private func build() async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeState = FakeRouteState()
        let clockRef = clock!
        sync = SyncPlaybackCoordinator(
            monotonicNowUs: { clockRef.now() },
            localPeerId: SyncTestValues.leaderPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clockRef,
            routeState: routeState,
            nextQueueItemId: { UUID().uuidString }
        )
        await sync.start()
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await sync.handleConnected(isLocalLeader: true)
        resyncChannel = RecordingResyncChannel()
        await sync.setResyncChannel(resyncChannel)
        lifecycle = RideSegmentLifecycle(syncPlayback: sync)
    }

    /// Establishes authoritative track X as the leader, exactly as a real `playSynchronized` does.
    private func playAsLeader(_ track: ContentHash) async {
        await content.addLocal(track)
        await content.addPeer(track)
        await sync.playSynchronized(track)
        await settle()
        clock.advance(to: clock.now() + 500_000)
        await settle()
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

    /// The production End Ride path clears ride-segment playback identity — so a `STATE_SNAPSHOT`
    /// built in ride 2, before ride 2 has any authoritative playback of its own, cannot report
    /// ride 1's track.
    func testEndRideClearsRideSegmentIdentityAndRideTwoCannotReportRideOnesTrack() async {
        await build()
        let trackX = SyncTestValues.hash(1)
        let trackY = SyncTestValues.hash(2)

        await startRide()
        await playAsLeader(trackX)
        let duringRideOne = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, duringRideOne, "ride 1 established authoritative track X")

        await endRide()
        let afterEndRide = await sync.diagnostics.currentTrackHash
        XCTAssertNil(afterEndRide, "ride-segment playback identity must not survive the ride that created it")

        // Ride 2, with no new authoritative playback yet, then an ordinary control reconnect.
        await startRide()
        await sync.handleLinkLost()
        await sync.handleConnected(isLocalLeader: true)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await resyncChannel.clear()

        await sync.enqueueStateSnapshotReply(
            generation: await session.currentAuthGeneration(),
            leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 0,
            transfersInFlight: []
        )
        await settle()

        let snapshots = await resyncChannel.sent
        guard case .stateSnapshot(_, _, _, let playback, _, _, _, _) = snapshots.last else {
            return XCTFail("the leader answered no STATE_SNAPSHOT: \(snapshots)")
        }
        XCTAssertNil(playback?.trackHash, "ride 2's snapshot reported ride 1's track: \(String(describing: playback))")

        // …and ride 2's own track works normally afterwards.
        await playAsLeader(trackY)
        let duringRideTwo = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackY, duringRideTwo, "ride 2's track Y works normally")

        await resyncChannel.clear()
        await sync.enqueueStateSnapshotReply(
            generation: await session.currentAuthGeneration(),
            leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 0,
            transfersInFlight: []
        )
        await settle()
        let laterSnapshots = await resyncChannel.sent
        guard case .stateSnapshot(_, _, _, let laterPlayback, _, _, _, _) = laterSnapshots.last else {
            return XCTFail("the leader answered no second STATE_SNAPSHOT: \(laterSnapshots)")
        }
        XCTAssertEqual(trackY, laterPlayback?.trackHash, "ride 2 reports its own track once it has one")
    }

    /// Section 12's own audit. `SessionCoordinator.endRide()` cannot `await`, so the cleanup crosses a
    /// scheduling hop and ride 2 may have started by the time it resumes. Ride 1's cleanup must then
    /// touch nothing — "old work + current state = successor mutation" is precisely the class this
    /// repository keeps re-finding, and the epoch is what stops it.
    func testARideOneCleanupApplyingAfterRideTwoHasBegunClearsNothing() async {
        await build()
        let trackX = SyncTestValues.hash(3)

        await startRide()
        // Ride 1's End Ride takes its epoch — and then, before its cleanup runs, ride 2 begins.
        let staleEpoch = lifecycle.nextRideEpoch()
        await startRide()
        await playAsLeader(trackX)
        let beforeStaleCleanup = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, beforeStaleCleanup)

        await lifecycle.endRide(epoch: staleEpoch)
        await settle()

        let afterStaleCleanup = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, afterStaleCleanup, "ride 1's late cleanup cleared ride 2's playback identity")
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "…and said so, rather than silently")
    }

    /// The coordinator re-proves the epoch for itself across the actor hop, rather than trusting the
    /// caller — ADR-024 Amendment A3 Finding B's rule, applied to the ride lifetime. Calling
    /// `endRideSegment` directly with a superseded epoch is what a reordered hop would look like.
    func testTheCoordinatorRefusesASupersededRideEpochOnItsOwn() async {
        await build()
        let trackX = SyncTestValues.hash(4)
        await startRide()
        await endRide()
        await startRide()
        await playAsLeader(trackX)

        let staleBefore = await sync.diagnostics.staleRideLifecycleCount
        await sync.endRideSegment(rideEpoch: 2) // ride 1's End Ride epoch; ride 2's Start took 3.
        await settle()

        let track = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, track, "a superseded ride epoch must not clear the live ride's identity")
        let staleAfter = await sync.diagnostics.staleRideLifecycleCount
        XCTAssertEqual(staleBefore + 1, staleAfter)
    }

    /// The ride-lifetime scenarios, fifty times each, on a fresh harness per iteration — the same
    /// repetition discipline `ResyncCoordinatorTests` applies to the recovery paths.
    func testFiftyRideCyclesClearIdentityAndNeverLeakTheEarlierRidesTrack() async {
        for cycle in 0 ..< 50 {
            await build()
            let trackX = SyncTestValues.hash(30)
            let trackY = SyncTestValues.hash(31)

            await startRide()
            await playAsLeader(trackX)
            await endRide()
            let afterEnd = await sync.diagnostics.currentTrackHash
            XCTAssertNil(afterEnd, "cycle \(cycle): End Ride must clear ride-segment identity")

            await startRide()
            await sync.handleLinkLost()
            await sync.handleConnected(isLocalLeader: true)
            await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
            await resyncChannel.clear()
            await sync.enqueueStateSnapshotReply(
                generation: await session.currentAuthGeneration(),
                leaderPeerId: SyncTestValues.leaderPeerId,
                manifestRevision: 0,
                transfersInFlight: []
            )
            await settle()
            let snapshots = await resyncChannel.sent
            guard case .stateSnapshot(_, _, _, let playback, _, _, _, _) = snapshots.last else {
                return XCTFail("cycle \(cycle): the leader answered no STATE_SNAPSHOT")
            }
            XCTAssertNil(playback?.trackHash, "cycle \(cycle): ride 2 reported ride 1's track")

            await playAsLeader(trackY)
            let rideTwo = await sync.diagnostics.currentTrackHash
            XCTAssertEqual(trackY, rideTwo, "cycle \(cycle): ride 2's own track must still work")
        }
    }
}
