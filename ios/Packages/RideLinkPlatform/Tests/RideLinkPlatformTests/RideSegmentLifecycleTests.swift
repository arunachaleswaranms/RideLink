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
    ///
    /// Waits on the **condition**, not on a fixed number of yields: a leader's play crosses the
    /// outbound consumer, the commit hook and the apply chain, and how many scheduling hops that
    /// takes is not fixed. A fixed budget made this helper the flakiest thing in the suite under CI
    /// load — and a bigger fixed budget would only have made the flake rarer, which §16 forbids.
    /// There is no sleep here: each round advances the fake clock and yields, and the loop ends on
    /// the state the test actually cares about.
    private func playAsLeader(_ track: ContentHash, file: StaticString = #filePath, line: UInt = #line) async {
        await content.addLocal(track)
        await content.addPeer(track)
        await sync.playSynchronized(track)
        for _ in 0 ..< 200 {
            if await sync.diagnostics.currentTrackHash == track { return }
            clock.advance(to: clock.now() + 100_000)
            await settle(5)
        }
        let observed = await sync.diagnostics.currentTrackHash
        XCTFail("the leader never converged on \(track): \(String(describing: observed))", file: file, line: line)
    }

    /// The one statement `SessionCoordinator.startRide()` performs after its FSM transition
    /// (independent-review round 5, Blocker 1: the accepted ride epoch is published synchronously
    /// and Start Ride defers nothing).
    private func startRide() {
        _ = lifecycle.nextRideEpoch()
    }

    /// The two statements `SessionCoordinator.endRide()` performs after its FSM transition.
    private func endRide() async {
        let epoch = lifecycle.nextRideEpoch()
        await lifecycle.endRide(epoch: epoch)
    }

    /// The production End Ride path clears ride-segment playback identity — so a `STATE_SNAPSHOT`
    /// built in ride 2, before ride 2 has any authoritative playback of its own, cannot report
    /// ride 1's track.
    func testStateRequestArrivingInsideConnectionResetIsAnsweredAfterResetCompletes() async {
        await build()
        await sync.handleLinkLost()
        await session.setGeneration(2)
        await session.armGenerationGate()
        let connecting = Task { await self.sync.handleConnected(isLocalLeader: true) }
        await expect("connection reset parked at generation read") { await self.session.isGenerationGateParked }
        await sync.enqueueStateSnapshotReply(
            generation: 2, leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 0, transfersInFlight: []
        )
        let held = await sync.diagnostics.heldStateSnapshotReplyCount
        XCTAssertEqual(held, 1)
        await session.releaseGenerationGate()
        await connecting.value
        await expect("request admitted during reset was answered") { await self.resyncChannel.sent.count == 1 }
        await sync.shutdown()
    }

    func testRetiredRequestInsideResetCannotDisplaceALiveRequestHeldBeforeReset() async {
        await build()
        await sync.handleLinkLost()
        await session.setGeneration(2)
        await sync.enqueueStateSnapshotReply(
            generation: 2, leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 0, transfersInFlight: []
        )
        await session.armGenerationGate()
        let connecting = Task { await self.sync.handleConnected(isLocalLeader: true) }
        await expect("reset parked") { await self.session.isGenerationGateParked }
        await sync.enqueueStateSnapshotReply(
            generation: 1, leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 99, transfersInFlight: []
        )
        await session.releaseGenerationGate()
        await connecting.value
        await expect("original live request answered") { await self.resyncChannel.sent.count == 1 }
        let replies = await resyncChannel.sent
        guard case .stateSnapshot(_, _, _, _, _, _, let revision, _) = replies.first else {
            return XCTFail("missing snapshot")
        }
        XCTAssertEqual(revision, 0, "the retired request did not replace the retained live request")
        await sync.shutdown()
    }

    func testRetiredRequestInsideResetIsDroppedAndTheFollowingLiveRequestStillWorks() async {
        await build()
        await sync.handleLinkLost()
        await session.setGeneration(2)
        await session.armGenerationGate()
        let connecting = Task { await self.sync.handleConnected(isLocalLeader: true) }
        await expect("reset parked") { await self.session.isGenerationGateParked }
        await sync.enqueueStateSnapshotReply(
            generation: 1, leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 99, transfersInFlight: []
        )
        await session.releaseGenerationGate()
        await connecting.value
        let dropped = await sync.diagnostics.droppedStateSnapshotReplyCount
        let retiredReplies = await resyncChannel.sent
        XCTAssertEqual(dropped, 1)
        XCTAssertTrue(retiredReplies.isEmpty)
        await sync.enqueueStateSnapshotReply(
            generation: 2, leaderPeerId: SyncTestValues.leaderPeerId,
            manifestRevision: 0, transfersInFlight: []
        )
        await expect("live request after retired refusal") { await self.resyncChannel.sent.count == 1 }
        await sync.shutdown()
    }

    func testEndRideClearsRideSegmentIdentityAndRideTwoCannotReportRideOnesTrack() async {
        await build()
        let trackX = SyncTestValues.hash(1)
        let trackY = SyncTestValues.hash(2)

        startRide()
        await playAsLeader(trackX)
        let duringRideOne = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, duringRideOne, "ride 1 established authoritative track X")

        await endRide()
        let afterEndRide = await sync.diagnostics.currentTrackHash
        XCTAssertNil(afterEndRide, "ride-segment playback identity must not survive the ride that created it")

        // Ride 2, with no new authoritative playback yet, then an ordinary control reconnect.
        startRide()
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

        startRide()
        // Ride 1's End Ride takes its epoch — and then, before its cleanup runs, ride 2 begins.
        let staleEpoch = lifecycle.nextRideEpoch()
        startRide()
        await playAsLeader(trackX)
        let beforeStaleCleanup = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, beforeStaleCleanup)

        await lifecycle.endRide(epoch: staleEpoch)
        await settle()

        let afterStaleCleanup = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, afterStaleCleanup, "ride 1's late cleanup cleared ride 2's playback identity")
        let staleIdentity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackX, staleIdentity?.trackHash, "…including the identity a STATE_SNAPSHOT would report")
        // Independent-review round 4: refused by the *coordinator*, which is the only place that can
        // see that ride 2 owns live authority. `RideSegmentLifecycle` no longer decides this — it
        // counts the answer, which is what keeps this assertion meaningful.
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "…and said so, rather than silently")
    }

    // MARK: - Independent-review round 4, Blocker 1

    /// **The defect round 3 left open, and the exact scenario §6 asks for.**
    ///
    /// Round 3 refused a superseded End Ride cleanup outright, which protects ride 2 (Property A) and
    /// breaks Property B in the same statement: `startRide` deliberately establishes nothing, so a
    /// Start Ride pressed before ride 1's cleanup ran made that cleanup "stale" while leaving ride 1's
    /// `currentPlaybackIdentity` standing as the only thing ride 2 had to report.
    ///
    /// Deterministic by construction, with no sleeps and no barrier needed: the production ordering is
    /// *exactly* "both epochs are assigned synchronously, in order, and the async work then runs in
    /// the other order", because `SessionCoordinator.endRide()`/`startRide()` each take their epoch on
    /// the main actor and then hand the call to `launchInSession`. Taking the two epochs and running
    /// the two effects in the opposite order reproduces the parked cleanup precisely.
    ///
    /// Deliberately **no Play Y**: ride 2 establishing nothing is the whole point. A version of this
    /// test that played first would pass against the pre-fix code for the wrong reason, because the
    /// Play would have overwritten X on its own.
    func testASupersededEndRideStillClearsRideOneWhenRideTwoHasEstablishedNothing() async {
        await build()
        let trackX = SyncTestValues.hash(50)
        let trackY = SyncTestValues.hash(51)

        startRide()
        await playAsLeader(trackX)
        let established = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, established, "ride 1 established authoritative track X")

        // End Ride is accepted and takes epoch 2 — and then, before its cleanup runs, Start Ride 2
        // takes epoch 3 and runs. This is the parked-cleanup ordering, stated rather than raced.
        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        // Release the parked End Ride work.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let afterRelease = await sync.currentPlaybackIdentity
        XCTAssertNil(afterRelease, "ride 2 inherited ride 1's playback identity: \(String(describing: afterRelease))")
        let afterReleaseHash = await sync.diagnostics.currentTrackHash
        XCTAssertNil(afterReleaseHash)
        XCTAssertEqual(0, lifecycle.supersededEndRideCount, "this boundary owned ride 1's state and had to clear it")

        // An ordinary reconnect inside ride 2, and the leader's STATE_SNAPSHOT must not report X.
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
            return XCTFail("the leader answered no STATE_SNAPSHOT")
        }
        // §6 asks "preferably `playback == nil`". Production's existing shape is a `playback` object
        // whose `track_hash`/`queue_item_id` are nil — PROTOCOL §10's "nothing loaded" — which is the
        // same claim and is what the round-3 tests already pin. What must hold is that it is not X.
        XCTAssertNil(playback?.trackHash, "ride 2's snapshot reported ride 1's track: \(String(describing: playback))")
        XCTAssertNil(playback?.queueItemId)

        // …and ride 2's own authority works normally afterwards, and survives a reconnect.
        await playAsLeader(trackY)
        let rideTwoIdentity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackY, rideTwoIdentity?.trackHash)
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
        let laterSnapshots = await resyncChannel.sent
        guard case .stateSnapshot(_, _, _, let laterPlayback, _, _, _, _) = laterSnapshots.last else {
            return XCTFail("the leader answered no second STATE_SNAPSHOT")
        }
        XCTAssertEqual(trackY, laterPlayback?.trackHash, "ride 2 reports its own track once it has one")
    }

    /// Property A, at the same seam and in the same parked-cleanup ordering as the test above — the
    /// two must both hold, and neither may be bought by weakening the other. Here ride 2 *does*
    /// establish Y before the parked ride 1 cleanup is released.
    func testASupersededEndRideReleasedAfterRideTwoEstablishedItsOwnTrackClearsNothing() async {
        await build()
        let trackX = SyncTestValues.hash(52)
        let trackY = SyncTestValues.hash(53)

        startRide()
        await playAsLeader(trackX)

        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()
        // Ride 2 establishes its own authority while ride 1's cleanup is still parked.
        await playAsLeader(trackY)
        let beforeRelease = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackY, beforeRelease?.trackHash)

        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let afterRelease = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackY, afterRelease?.trackHash, "ride 1's late cleanup cleared ride 2's track")
        let afterReleaseHash = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackY, afterReleaseHash)
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "…and said so, rather than silently")
        let liveTimeline = await sync.timeline
        XCTAssertNotNil(liveTimeline, "ride 2's synchronised timeline was retired by ride 1's boundary")
    }

    /// §24 item 5, on the ordering Blocker 1 is about: fifty End/Start races with the cleanup parked
    /// across the successor's start, alternating whether ride 2 establishes anything of its own.
    /// Both properties are asserted every cycle, on a fresh harness.
    func testFiftySupersededEndRideCyclesSatisfyBothRideBoundaryProperties() async {
        for cycle in 0 ..< 50 {
            await build()
            let trackX = SyncTestValues.hash(60)
            let trackY = SyncTestValues.hash(61)
            let rideTwoEstablishes = cycle.isMultiple(of: 2)

            startRide()
            await playAsLeader(trackX)

            let endRideEpoch = lifecycle.nextRideEpoch()
            _ = lifecycle.nextRideEpoch()
            if rideTwoEstablishes { await playAsLeader(trackY) }

            await lifecycle.endRide(epoch: endRideEpoch)
            await settle()

            let identity = await sync.currentPlaybackIdentity
            if rideTwoEstablishes {
                XCTAssertEqual(trackY, identity?.trackHash, "cycle \(cycle): Property A — ride 2's own track was cleared")
                XCTAssertEqual(1, lifecycle.supersededEndRideCount, "cycle \(cycle)")
            } else {
                XCTAssertNil(identity, "cycle \(cycle): Property B — ride 2 inherited ride 1's track")
                XCTAssertEqual(0, lifecycle.supersededEndRideCount, "cycle \(cycle)")
            }
        }
    }

    /// The coordinator re-proves the epoch for itself across the actor hop, rather than trusting the
    /// caller — ADR-024 Amendment A3 Finding B's rule, applied to the ride lifetime. Calling
    /// `endRideSegment` directly with a superseded epoch is what a reordered hop would look like.
    func testTheCoordinatorRefusesASupersededRideEpochOnItsOwn() async {
        await build()
        let trackX = SyncTestValues.hash(4)
        startRide()
        await endRide()
        startRide()
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

            startRide()
            await playAsLeader(trackX)
            await endRide()
            let afterEnd = await sync.diagnostics.currentTrackHash
            XCTAssertNil(afterEnd, "cycle \(cycle): End Ride must clear ride-segment identity")

            startRide()
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

    // MARK: - Independent-review round 4, §17's audit of the ride-lifetime proof

    /// **The §17 audit's own finding, and the one that could stop the music.**
    ///
    /// Round 3 closed the "authorised before End Ride, resumes after" class in `applyPlay` alone.
    /// `applyStep` had **no** ride proof at all, and `stillCurrent` suspends — so a `NEXT` that runs
    /// off the end of the queue could take its `selected == nil` branch after an End Ride, call
    /// `epoch.begin()` (minting a *fresh, live* playback epoch over the one `leaveSynchronizedMode`
    /// had just superseded) and schedule `[.stop, .clearSelection]`, which the new token makes owned.
    /// End Ride's whole contract is that local music keeps playing (FR-025); this stopped it, and
    /// cleared the local selection with it.
    ///
    /// Deterministic, and the parked suspension is pinned **by construction rather than by
    /// counting**. `applyAuthoritative` is the production capture point — it is where the ride
    /// lifetime is taken, on the real coordinator — so the test enters there and arms the generation
    /// gate with no skips: the very first generation read that follows is that function's own
    /// `stillCurrent`, immediately after the capture. Driving this through `onPlaybackMessage`
    /// instead would make the test depend on how many generation reads the admission path happens to
    /// take first, which is exactly the "counting calls does not pin it" mistake this suite's own
    /// `applyPlay` regression records.
    func testAStepRunningOffTheQueueParkedAcrossEndRideCannotStopLocalPlayback() async {
        await build()
        let trackX = SyncTestValues.hash(90)
        startRide()
        await playAsLeader(trackX)

        // Become a follower of a peer whose NEXT will run off the end of the shared queue.
        await sync.handleLinkLost()
        await sync.handleConnected(isLocalLeader: false)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await sync.onQueueMessage(
            .snapshot(queueRevision: 1, items: [], currentIndex: nil),
            generation: await session.currentAuthGeneration()
        )
        await settle()
        await player.clearCalls()

        let generation = await session.currentAuthGeneration()
        let estimate = SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true)
        // Independent-review round 7: the ride provenance is captured by the **admission** now, not
        // by `applyAuthoritative` itself, so the test captures it exactly where
        // `admitAuthoritativeCommand` does — before the suspension the gate below parks in.
        let ride = await sync.admitRide()
        // Armed immediately before the call, so the first generation read it takes — which is
        // `applyAuthoritative`'s own `stillCurrent` — is the one that parks.
        await session.armGenerationGate()
        let apply = Task {
            await self.sync.applyAuthoritative(
                .next(header: PlaybackCommandHeader(
                    commandSeq: 1, effectiveAtSessionUs: 0, issuedBy: SyncTestValues.leaderPeerId, queueRevision: 1
                )),
                generation: generation,
                ride: ride,
                estimate: estimate
            )
        }
        var parked = false
        for _ in 0 ..< 500 where !parked {
            parked = await session.isGenerationGateParked
            await Task.yield()
        }
        XCTAssertTrue(parked, "the apply never reached a generation-read suspension")
        let callsWhileParked = await player.calls
        XCTAssertFalse(callsWhileParked.contains(.stop), "parked after the player was stopped — too late to be applyStep")

        await endRide()
        await session.releaseGenerationGate()
        _ = await apply.value
        await settle()

        let calls = await player.calls
        XCTAssertFalse(calls.contains(.stop), "a NEXT authorised before End Ride stopped local playback afterwards: \(calls)")
        XCTAssertFalse(calls.contains(.clearSelection), "…and cleared the local selection: \(calls)")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "End Ride's retired timeline was replaced by a retired step")
    }

    /// **The §17 audit's second finding.** `applyPlay` reached *through* `applyStep` or
    /// `restoreFromPlaybackState` used to capture the ride lifetime at its **own** entry — which, when
    /// the End Ride had already landed during the outer operation's suspension, was already the
    /// post-End-Ride value, so its guard compared the new value with itself and passed. It then
    /// re-established `currentPlaybackIdentity`, the timeline and a fresh playback epoch for a ride
    /// that was over: round 3's own defect, reached one function further along.
    func testAStepSelectingATrackParkedAcrossEndRideCannotReestablishPlayback() async {
        await build()
        let trackX = SyncTestValues.hash(91)
        let trackY = SyncTestValues.hash(92)
        await content.addLocal(trackY)
        await content.addPeer(trackY)
        startRide()
        await playAsLeader(trackX)

        await sync.handleLinkLost()
        await sync.handleConnected(isLocalLeader: false)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        let itemId = SyncTestValues.ulid(92)
        await sync.onQueueMessage(
            .snapshot(
                queueRevision: 1,
                items: [SharedQueueItem(queueItemId: itemId, trackHash: trackY, addedBy: SyncTestValues.leaderPeerId, order: 0)],
                currentIndex: nil
            ),
            generation: await session.currentAuthGeneration()
        )
        await settle()
        await player.clearCalls()

        let generation = await session.currentAuthGeneration()
        let estimate = SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true)
        // Round 7: captured at the admission, as production does.
        let ride = await sync.admitRide()
        await session.armGenerationGate()
        let apply = Task {
            await self.sync.applyAuthoritative(
                .next(header: PlaybackCommandHeader(
                    commandSeq: 1, effectiveAtSessionUs: 0, issuedBy: SyncTestValues.leaderPeerId, queueRevision: 1
                )),
                generation: generation,
                ride: ride,
                estimate: estimate
            )
        }
        var parked = false
        for _ in 0 ..< 500 where !parked {
            parked = await session.isGenerationGateParked
            await Task.yield()
        }
        XCTAssertTrue(parked, "the apply never reached a generation-read suspension")

        await endRide()
        await session.releaseGenerationGate()
        _ = await apply.value
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "a step authorised before End Ride re-established ride playback identity: \(String(describing: identity))")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "…and a synchronised timeline the ride no longer has")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(trackY)), "…and reached the player: \(calls)")
    }

    /// **The defect CI found in this suite's own 50-cycle run.** `applyPlay` proves the *control*
    /// generation before it writes `currentPlaybackIdentity`, `timeline` and a fresh playback epoch —
    /// and End Ride deliberately does not move that generation, because the session stays alive. So
    /// an apply suspended in `content.resolve` when the ride ends resumed afterwards and wrote all of
    /// it back over the state `leaveSynchronizedMode` had just retired.
    ///
    /// Deterministic rather than load-dependent: the resolve gate parks the apply at exactly the
    /// suspension that matters, End Ride runs while it is provably parked, and the gate is then
    /// released. No sleeps and no repetition are needed to reach the ordering.
    func testAnApplyParkedAcrossEndRideWritesNothingBack() async {
        await build()
        let track = SyncTestValues.hash(40)
        await content.addLocal(track)
        await content.addPeer(track)
        startRide()

        // `applyPlay`'s own `content.resolve` is the suspension that matters, and it is the only one
        // that happens **after** the leader has committed its `PLAY` to the wire. Stating that as the
        // gate's own predicate pins the parked frame by construction; counting calls does not, because
        // how many resolves run before it depends on scheduling.
        let wire = session!
        await content.armResolveGate {
            await wire.playbackMessages().contains { if case .play = $0 { return true } else { return false } }
        }
        let apply = Task { await self.sync.playSynchronized(track) }
        var parked = false
        for _ in 0 ..< 200 where !parked {
            parked = await content.isResolveGateParked
            await Task.yield()
        }
        XCTAssertTrue(parked, "the apply never reached the resolve suspension")
        // **Which** suspension we parked on is the whole validity of this test. `applyPlay`'s
        // `content.resolve` is the only one that happens *after* the leader has committed its `PLAY`
        // to the wire and *before* it touches the player — so asserting both pins the parked frame to
        // `applyPlay` and nothing earlier. Without this the test could park in `resolvePendingPlay`
        // instead, where `PendingPlayGate` already cancels correctly, and pass for the wrong reason.
        let issued = await session.playbackMessages().contains { if case .play = $0 { return true } else { return false } }
        XCTAssertTrue(issued, "parked before the PLAY was issued — this is not applyPlay's resolve")
        let callsWhileParked = await player.calls
        XCTAssertFalse(callsWhileParked.contains(.select(track)), "parked after the player was touched — too late to be applyPlay's resolve")

        await endRide()
        let clearedByEndRide = await sync.diagnostics.currentTrackHash
        XCTAssertNil(clearedByEndRide, "End Ride clears ride-segment identity")

        await content.releaseResolveGate()
        _ = await apply.value
        await settle()

        let afterRelease = await sync.diagnostics.currentTrackHash
        XCTAssertNil(afterRelease, "an apply authorised before End Ride wrote its track back afterwards")
        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "…including the ride-segment identity a STATE_SNAPSHOT would report")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "…and the synchronised timeline End Ride had retired")
    }

    // MARK: - Independent-review round 5, Blocker 1

    /// **The defect round 4 left open, at the exact production ordering.**
    ///
    /// Round 4's `rideAuthorityEpoch` rule was right; the value it stamped was stale.
    /// `recordRideAuthority` read `lastRideLifecycleEpoch`, and only a `beginRideSegment` that had
    /// crossed `launchInSession`'s actor hop could move that field — so between "`SessionFsm`
    /// accepted ride 2 and minted epoch 3" and "the coordinator learned about epoch 3" there was a
    /// window, and every piece of authority ride 2 established inside it was labelled **ride 1**:
    ///
    /// ```
    /// ride 1 established X                 rideAuthorityEpoch = 1
    /// End Ride  accepted, epoch 2          cleanup parked
    /// Start Ride accepted, epoch 3         beginRideSegment(3) parked too
    /// ride 2 established Y                 rideAuthorityEpoch = 1   ← the defect
    /// End Ride(2) released                 1 <= 2, so ride 1's cleanup cleared Y
    /// ```
    ///
    /// The existing round-4 tests could not see it because every one of them ran
    /// `lifecycle.startRide(epoch:)` **before** ride 2 played, forcing the safe ordering that
    /// production does not guarantee. This one establishes Y while ride 2's propagation is still
    /// outstanding — which, since the fix, is not a state that exists at all: `nextRideEpoch()`
    /// publishes, and Start Ride defers nothing. Both orderings therefore collapse to this one, and
    /// the test is written in the terms that survive the fix — the two epoch allocations, which are
    /// exactly what `SessionCoordinator.endRide()`/`startRide()` do synchronously.
    ///
    /// Property A. Its Property B twin is
    /// `testASupersededEndRideStillClearsRideOneWhenRideTwoHasEstablishedNothing`, above, unchanged.
    func testAuthorityEstablishedUnderTheAcceptedRideSurvivesThePredecessorsLateCleanup() async {
        await build()
        let trackX = SyncTestValues.hash(70)
        let trackY = SyncTestValues.hash(71)

        startRide()
        await playAsLeader(trackX)

        // End Ride is accepted and takes epoch 2; its cleanup is parked. Start Ride is then accepted
        // and takes epoch 3. Both allocations are synchronous and in this order in production.
        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        // Ride 2 establishes Y with ride 1's cleanup still outstanding.
        await playAsLeader(trackY)

        // Release ride 1's parked cleanup.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackY, identity?.trackHash, "ride 1's late cleanup cleared ride 2's authority")
        let hash = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackY, hash, "…and the diagnostics mirror a STATE_SNAPSHOT reports from")
        let liveTimeline = await sync.timeline
        XCTAssertNotNil(liveTimeline, "ride 2's synchronised timeline was retired by ride 1's boundary")
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "…and the boundary said so, rather than silently")

        // The leader's STATE_SNAPSHOT must report Y, not X and not "nothing loaded".
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
            return XCTFail("the leader answered no STATE_SNAPSHOT")
        }
        XCTAssertEqual(trackY, playback?.trackHash, "ride 2's snapshot did not report ride 2's own track")
    }

    /// The structural half of the fix, stated on its own so a future change that reintroduces a
    /// deferred installation fails here rather than only in the ordering test above: an accepted
    /// ride's epoch is visible to the one owner of ride-scoped authority **before** `nextRideEpoch()`
    /// returns, with no `await` anywhere in between.
    func testAnAcceptedRideEpochIsPublishedSynchronously() async {
        await build()
        XCTAssertEqual(0, sync.rideEpochs.current)
        XCTAssertEqual(1, lifecycle.nextRideEpoch())
        XCTAssertEqual(1, sync.rideEpochs.current, "a Start Ride's epoch was not published before it returned")
        XCTAssertEqual(2, lifecycle.nextRideEpoch())
        XCTAssertEqual(2, sync.rideEpochs.current, "an End Ride's epoch was not published before it returned")
    }

    /// §24 item 5 at the new ordering: fifty cycles alternating whether ride 2 establishes anything,
    /// with ride 1's cleanup released last in both cases. Both properties, every cycle.
    func testFiftyLateCleanupCyclesAtTheProductionOrderingSatisfyBothProperties() async {
        for cycle in 0 ..< 50 {
            await build()
            let trackX = SyncTestValues.hash(72)
            let trackY = SyncTestValues.hash(73)
            let rideTwoEstablishes = cycle.isMultiple(of: 2)

            startRide()
            await playAsLeader(trackX)

            let endRideEpoch = lifecycle.nextRideEpoch()
            _ = lifecycle.nextRideEpoch()
            if rideTwoEstablishes { await playAsLeader(trackY) }

            await lifecycle.endRide(epoch: endRideEpoch)
            await settle()

            let identity = await sync.currentPlaybackIdentity
            if rideTwoEstablishes {
                XCTAssertEqual(trackY, identity?.trackHash, "cycle \(cycle): Property A")
            } else {
                XCTAssertNil(identity, "cycle \(cycle): Property B")
            }
        }
    }

    // MARK: - Independent-review round 6

    /// **Regression 1.** Round 5's `recordRideAuthority` read `rideEpochs.current` *live, at the
    /// moment of the write* — reconstructing ownership from current state after a suspension, exactly
    /// the class this repository keeps finding and fixing elsewhere. An operation admitted under
    /// ride 1, parked at its own `content.resolve` (after its ride-authority provenance would have
    /// been captured but before it writes anything), could resume after *both* an accepted End Ride
    /// *and* a further accepted Start Ride, and be stamped as the new ride's authority. Ride 1's own
    /// late cleanup then found a *newer* owner standing and refused to touch it — stale ride-1 work,
    /// relabelled as ride 2's, left standing forever.
    ///
    /// Deterministic: `content.armResolveGate` parks Y at exactly the suspension that matters, both
    /// ride-boundary epochs are minted — not merely raced — while Y is provably parked, and only then
    /// is Y released, followed by ride 1's still-delayed cleanup. This must fail against the
    /// round-5 head, where `recordRideAuthority()` took no parameter.
    func testAnOldRideOnesOperationParkedAcrossEndAndStartCannotBecomeRideTwosAuthority() async {
        await build()
        let trackX = SyncTestValues.hash(100)
        let trackY = SyncTestValues.hash(101)
        await content.addLocal(trackY)
        await content.addPeer(trackY)

        startRide()
        await playAsLeader(trackX)
        let established = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, established, "ride 1 established authoritative track X")

        // Y is a second authoritative Play, admitted under ride 1, parked at its own
        // `content.resolve` — after its ride-authority provenance is captured (`applyAuthoritative`'s
        // first statement) and before it writes anything.
        let wire = session!
        await content.armResolveGate {
            await wire.playbackMessages().contains {
                if case .play(_, let hash, _, _) = $0 { return hash == trackY } else { return false }
            }
        }
        let apply = Task { await self.sync.playSynchronized(trackY) }
        var parked = false
        for _ in 0 ..< 200 where !parked {
            parked = await content.isResolveGateParked
            await Task.yield()
        }
        XCTAssertTrue(parked, "Y never reached the resolve suspension")
        let issued = await session.playbackMessages().contains {
            if case .play(_, let hash, _, _) = $0 { return hash == trackY } else { return false }
        }
        XCTAssertTrue(issued, "parked before Y's PLAY was issued — this is not applyPlay's resolve")
        let callsWhileParked = await player.calls
        XCTAssertFalse(callsWhileParked.contains(.select(trackY)), "parked after the player was touched — too late to be applyPlay's resolve")

        // End Ride 1 is accepted and takes its epoch — its cleanup is deliberately not run yet.
        let endRideEpoch = lifecycle.nextRideEpoch()
        // Start Ride 2 is accepted before ride 1's cleanup ever ran.
        _ = lifecycle.nextRideEpoch()

        // Release Y. It resumes with ride 2 already current and ride 1's cleanup still outstanding.
        await content.releaseResolveGate()
        _ = await apply.value
        await settle()

        // Only now does ride 1's delayed cleanup run.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "ride 1's stale Y survived, mislabelled as ride 2's authority: \(String(describing: identity))")
        let hash = await sync.diagnostics.currentTrackHash
        XCTAssertNil(hash, "…and a STATE_SNAPSHOT would still report it")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "…including a live playback epoch/timeline Y should never have reached")
        XCTAssertEqual(0, lifecycle.supersededEndRideCount, "ride 1's cleanup must not have been fooled into standing down")
    }

    /// **Regression 2.** The inverse of Regression 1, and the fix must not overcorrect into it:
    /// authority genuinely established *after* an accepted End Ride but before that End Ride's own
    /// delayed cleanup ever runs must survive that cleanup. Both share the same live `rideEpochs
    /// .current` value at admission — the End Ride's own freshly minted epoch — which is exactly why
    /// `endRideSegment`'s comparison had to become strict (`<`, not `<=`): under `<=` the two were
    /// indistinguishable and Z would be destroyed along with ride 1's genuine residue. This must fail
    /// against a fix that stops at threading `admittedRideEpoch` without also tightening the compare.
    func testGenuinelyNewAuthorityEstablishedAfterEndRideSurvivesThatSameEndRidesDelayedCleanup() async {
        await build()
        let trackZ = SyncTestValues.hash(102)

        startRide()

        // End Ride 1 is accepted and takes its epoch — its cleanup is deliberately not run yet.
        let endRideEpoch = lifecycle.nextRideEpoch()

        // While already CONNECTED (no further Start Ride has happened), genuinely new authoritative
        // state Z arrives and is established — authorised strictly after the End Ride boundary.
        await playAsLeader(trackZ)
        let established = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackZ, established?.trackHash, "Z was established while already CONNECTED")

        // Only now does ride 1's own delayed cleanup run.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackZ, identity?.trackHash, "ride 1's own delayed cleanup destroyed genuinely new post-End authority")
        let hash = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackZ, hash)
        let timeline = await sync.timeline
        XCTAssertNotNil(timeline, "Z's synchronised timeline was retired by ride 1's own boundary")
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "the boundary must have recognised Z as not its own, and said so")
    }

    /// Property A and Property B (round 4) re-run once more at the new comparison, so a future change
    /// that loosens `<` back to `<=` fails here rather than only in the regression above.
    func testASupersededEndRideStillClearsRideOneWhenRideTwoHasEstablishedNothingAtTheStrictCompare() async {
        await build()
        let trackX = SyncTestValues.hash(103)

        startRide()
        await playAsLeader(trackX)

        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "ride 2 inherited ride 1's playback identity: \(String(describing: identity))")
        XCTAssertEqual(0, lifecycle.supersededEndRideCount)
    }

    /// §24 item 5, applied to round 6: fifty cycles alternating between an old ride's operation
    /// parked across an End/Start boundary (Regression 1) and genuinely new post-End authority
    /// surviving its own boundary's delayed cleanup (Regression 2), on a fresh harness each time.
    func testFiftyCyclesOfRegression1AndRegression2SatisfyBothNewProperties() async {
        for cycle in 0 ..< 50 {
            await build()
            let trackX = SyncTestValues.hash(110)
            let trackY = SyncTestValues.hash(111)
            let regression1 = cycle.isMultiple(of: 2)

            startRide()
            if regression1 {
                await playAsLeader(trackX)
                await content.addLocal(trackY)
                await content.addPeer(trackY)
                let wire = session!
                await content.armResolveGate {
                    await wire.playbackMessages().contains {
                        if case .play(_, let hash, _, _) = $0 { return hash == trackY } else { return false }
                    }
                }
                let apply = Task { await self.sync.playSynchronized(trackY) }
                var parked = false
                for _ in 0 ..< 200 where !parked {
                    parked = await content.isResolveGateParked
                    await Task.yield()
                }
                XCTAssertTrue(parked, "cycle \(cycle): Y never reached the resolve suspension")

                let endRideEpoch = lifecycle.nextRideEpoch()
                _ = lifecycle.nextRideEpoch()

                await content.releaseResolveGate()
                _ = await apply.value
                await settle()

                await lifecycle.endRide(epoch: endRideEpoch)
                await settle()

                let identity = await sync.currentPlaybackIdentity
                XCTAssertNil(identity, "cycle \(cycle): Regression 1 — ride 1's stale op survived as ride 2's authority")
                XCTAssertEqual(0, lifecycle.supersededEndRideCount, "cycle \(cycle)")
            } else {
                let endRideEpoch = lifecycle.nextRideEpoch()
                await playAsLeader(trackX)

                await lifecycle.endRide(epoch: endRideEpoch)
                await settle()

                let identity = await sync.currentPlaybackIdentity
                XCTAssertEqual(trackX, identity?.trackHash, "cycle \(cycle): Regression 2 — genuinely new post-End authority was destroyed")
                XCTAssertEqual(1, lifecycle.supersededEndRideCount, "cycle \(cycle)")
            }
        }
    }

    // MARK: - Independent-review round 7 (retained work's own ride provenance)

    /// Waits on a condition rather than on a fixed number of yields — the inbound queue, the deferred
    /// drain and the apply chain are each their own task, and how many hops they take is not fixed.
    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    /// Becomes a follower with an untrustworthy clock — the only configuration in which an
    /// authoritative command is *held* rather than applied (`PendingCommandGate`'s `.defer_`).
    private func deferringFollower() async {
        await sync.handleLinkLost()
        await sync.handleConnected(isLocalLeader: false)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: false))
        // `handleConnected` restores rate 1.0 through the player (ADR-024 Amendment A4 §D's one
        // unfenced call); every assertion below is about *restoration* effects only.
        await player.clearCalls()
    }

    /// The leader's authoritative `PLAY`, arriving on the wire at a follower.
    private func deliverPlay(_ track: ContentHash, queueItemId: String, commandSeq: Int64 = 1) async {
        await session.deliver(.play(
            header: PlaybackCommandHeader(
                commandSeq: commandSeq, effectiveAtSessionUs: 0, issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
            ),
            trackHash: track,
            positionMs: 0,
            queueItemId: queueItemId
        ))
    }

    /// **Round 7, Regression 1 — Bug A.** A `PLAY` admitted under ride 1 and *held* for an
    /// untrustworthy clock used to be replayed by `drainDeferredEvents` into `applyAuthoritative`,
    /// which captured a **fresh** `RideAdmission` at that moment. Round 6's provenance therefore
    /// existed only while an operation was directly executing: the instant it became retained work it
    /// was dropped, and the drain minted a replacement.
    ///
    /// The ordering is fully deterministic and needs no sleep. Both ride-boundary epochs are minted —
    /// an accepted End Ride and then an accepted Start Ride — while the command is provably sitting in
    /// `deferredEvents`, and ride 1's own cleanup is **not released** until after the drain has run.
    /// That is the reachable production shape: `SessionCoordinator.endRide()` publishes its epoch
    /// synchronously and hands `leaveSynchronizedMode` — the only thing that empties this stream — to
    /// `launchInSession`.
    ///
    /// It must fail against the round-6 head, where `DeferredEvent.command` carried no ride at all.
    func testADeferredRideOneCommandCannotBecomeRideTwosAuthority() async {
        await build()
        await deferringFollower()
        let track = SyncTestValues.hash(120)
        let itemId = SyncTestValues.ulid(120)
        await content.addLocal(track)

        startRide()
        await deliverPlay(track, queueItemId: itemId)
        await expect("the command was accepted and held") { await self.sync.deferredEvents.count == 1 }

        // It was admitted under ride 1, and the retained event says so itself.
        let held = await sync.deferredEvents
        XCTAssertEqual(
            RideAdmission(synchronizedModeEpoch: 0, rideEpoch: 1), held.first?.ride,
            "the retained command does not carry the ride that admitted it"
        )
        let receivedSeq = await sync.diagnostics.lastReceivedCommandSeq
        XCTAssertEqual(1, receivedSeq, "the command was not accepted for ordering, so this proves nothing")

        // End Ride 1 is accepted and takes its epoch — its cleanup is deliberately not run yet.
        let endRideEpoch = lifecycle.nextRideEpoch()
        // Start Ride 2 is accepted before ride 1's cleanup ever ran.
        _ = lifecycle.nextRideEpoch()

        // The only thing that changes: the clock becomes trustworthy, and the drain runs — still
        // before ride 1's delayed cleanup.
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await sync.drainDeferredEvents()
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "ride 1's held command established ride 2's playback identity: \(String(describing: identity))")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "…and a synchronised timeline for a ride that had ended")
        let hash = await sync.diagnostics.currentTrackHash
        XCTAssertNil(hash, "…which a STATE_SNAPSHOT would then have reported")
        let authority = await sync.rideAuthorityEpoch
        XCTAssertEqual(0, authority, "…and stamped ride-scoped authority for a successor ride")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(track)), "a retired ride's held command reached the player: \(calls)")
        let remaining = await sync.deferredEvents
        XCTAssertTrue(remaining.isEmpty, "the retired-ride event must be discarded, not left to wedge the stream")
        let discarded = await sync.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(1, discarded, "…and counted rather than dropped silently")

        // Only now does ride 1's own delayed cleanup run. It must find nothing newer standing.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()
        XCTAssertEqual(0, lifecycle.supersededEndRideCount, "ride 1's cleanup was fooled into standing down")
        let finalIdentity = await sync.currentPlaybackIdentity
        XCTAssertNil(finalIdentity, "nothing from the retired ride's held command may survive")
    }

    /// **Round 7, Regression 4 (command half): the fix must not refuse valid retained work.** The same
    /// deferral with **no** ride boundary at all — the ordinary "the clock wobbled for 200 ms" case —
    /// still applies from the drain, against the very same `RideAdmission` it was admitted under.
    func testAValidSameRideDeferredCommandStillAppliesWhenTheClockRecovers() async {
        await build()
        await deferringFollower()
        let track = SyncTestValues.hash(121)
        let itemId = SyncTestValues.ulid(121)
        await content.addLocal(track)

        startRide()
        await deliverPlay(track, queueItemId: itemId)
        await expect("the command was accepted and held") { await self.sync.deferredEvents.count == 1 }
        let admitted = await sync.deferredEvents.first?.ride

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await sync.drainDeferredEvents()
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertEqual(track, identity?.trackHash, "valid same-ride retained work no longer applies")
        let timeline = await sync.timeline
        XCTAssertEqual(track, timeline?.trackHash, "…including its synchronised timeline")
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.select(track)), "…and the real player: \(calls)")
        let authority = await sync.rideAuthorityEpoch
        XCTAssertEqual(1, authority, "…stamped with the ride that admitted it, not a later one")
        XCTAssertEqual(RideAdmission(synchronizedModeEpoch: 0, rideEpoch: 1), admitted)
        let discarded = await sync.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(0, discarded, "valid work was discarded as retired")
        let remaining = await sync.deferredEvents
        XCTAssertTrue(remaining.isEmpty)
    }

    /// **Round 7's fresh-fix audit, pinning the one liveness claim the drain's retired-ride rule
    /// makes in prose.** That rule **pops** a dead item and continues rather than returning, because
    /// the stream is in arrival order and an item behind a dead one may have been admitted under a
    /// newer, still-live ride. Leaving the dead one at the head would wedge the stream exactly as
    /// independent-review round 3's Blocker A did — the deadlock this repository has already had to
    /// remove once, reintroduced by a fix rather than by the original code.
    ///
    /// A's ride is retired while A is held; B is then admitted under the live ride and queued behind
    /// it. Both are real inbound frames through the real ingress.
    func testARetiredRideEventAtTheHeadDoesNotBlockLiveWorkQueuedBehindIt() async {
        await build()
        await deferringFollower()
        let trackA = SyncTestValues.hash(123)
        let trackB = SyncTestValues.hash(124)
        await content.addLocal(trackA)
        await content.addLocal(trackB)

        startRide()
        await deliverPlay(trackA, queueItemId: SyncTestValues.ulid(123), commandSeq: 1)
        await expect("A held under ride 1") { await self.sync.deferredEvents.count == 1 }

        // End Ride 1 accepted (cleanup parked) and Start Ride 2 accepted — A's ride is now retired.
        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        // B arrives under the live ride and queues *behind* the now-dead A.
        await deliverPlay(trackB, queueItemId: SyncTestValues.ulid(124), commandSeq: 2)
        await expect("B queued behind A") { await self.sync.deferredEvents.count == 2 }
        let held = await sync.deferredEvents
        XCTAssertEqual(RideAdmission(synchronizedModeEpoch: 0, rideEpoch: 1), held.first?.ride, "A must be the dead one")
        XCTAssertEqual(RideAdmission(synchronizedModeEpoch: 0, rideEpoch: 3), held.last?.ride, "B must be the live one")

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await sync.drainDeferredEvents()
        await settle()

        let identity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackB, identity?.trackHash, "a retired item at the head wedged the live work behind it")
        let authority = await sync.rideAuthorityEpoch
        XCTAssertEqual(3, authority, "B's authority must be stamped with B's own ride")
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.select(trackB)), "B never reached the player: \(calls)")
        XCTAssertFalse(calls.contains(.select(trackA)), "A applied under a ride that had ended: \(calls)")
        let discarded = await sync.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(1, discarded, "exactly A should have been discarded as retired")
        let remaining = await sync.deferredEvents
        XCTAssertTrue(remaining.isEmpty, "the stream must have fully drained")

        // Ride 1's delayed cleanup now finds ride 2's own authority and leaves it alone.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()
        XCTAssertEqual(1, lifecycle.supersededEndRideCount, "ride 1's cleanup did not recognise ride 2's authority")
        let survives = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackB, survives?.trackHash, "ride 1's delayed cleanup destroyed ride 2's own authority")
    }

    // MARK: - Independent-review round 8 (a proof taken before a suspension authorises nothing after it)

    /// Becomes a follower whose clock **is** trustworthy — the configuration in which an authoritative
    /// command is admitted and applied straight away (`PendingCommandGate`'s `.apply`), which is the
    /// branch that publishes `lastAppliedSeq`.
    private func readyFollower() async {
        await sync.handleLinkLost()
        await sync.handleConnected(isLocalLeader: false)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await player.clearCalls()
    }

    /// **Round 8, Regression 1 — Blocker A.** A retained command whose ride retires *after* the
    /// drain's own first ride proof, inside a real suspension, must not be published as applied.
    ///
    /// Round 7 put the retained `RideAdmission` on the event and proved it once, at the top of the
    /// drain loop. Everything after that proof suspends — `estimate()` is two cross-actor reads, and
    /// the `stillCurrent` proof behind it is a third — and on an actor every `await` is a re-entrancy
    /// point. An accepted End Ride and an accepted Start Ride can both land inside those windows
    /// while ride 1's cleanup is still parked in `launchInSession`: the control generation does not
    /// move, `deferredEvents` is not emptied, and so `stillCurrentNow` and the `heldCount` witness
    /// both still pass on resume. The pre-fix code then popped the item, wrote `lastAppliedSeq`,
    /// published `lastAppliedCommandSeq` and counted a recovery — and only *afterwards* did
    /// `applyPlay`'s own `rideStillLive` refuse the frame as `.rejectedRide`.
    ///
    /// The refusal is correct and it is too late: `lastAppliedSeq` is what `PLAYBACK_STATE
    /// .command_seq` and `STATE_SNAPSHOT.command_seq` publish as "this command is reflected in my
    /// authoritative playback state", so a command that never touched playback was announced on the
    /// wire as having done so.
    ///
    /// **The park proves the window rather than approximating it.** The only `sessionClockEstimate()`
    /// read in `drainDeferredEvents` is the `.command` branch's, which sits *after* the top-of-loop
    /// ride proof, the per-item desync rule, both generation proofs and the `heldCount` witness — so
    /// the gate being parked is structural proof that the first ride check has already passed. The
    /// two assertions taken while parked prove the other end: nothing has been popped and nothing has
    /// been booked yet. No sleep anywhere, and ride 1's cleanup is deliberately not released until
    /// every assertion has been made, so it cannot be what saves the test.
    func testARideRetiringInsideTheDrainsClockReadNeverPublishesTheCommandAsApplied() async {
        await build()
        await deferringFollower()
        let track = SyncTestValues.hash(140)
        let itemId = SyncTestValues.ulid(140)
        await content.addLocal(track)

        startRide()
        await deliverPlay(track, queueItemId: itemId, commandSeq: 7)
        await expect("the command was accepted and held") { await self.sync.deferredEvents.count == 1 }
        let heldRide = await sync.deferredEvents.first?.ride
        XCTAssertEqual(RideAdmission(synchronizedModeEpoch: 0, rideEpoch: 1), heldRide)
        let receivedBefore = await sync.diagnostics.lastReceivedCommandSeq
        XCTAssertEqual(7, receivedBefore, "the command was not accepted for ordering, so this proves nothing")

        // The clock recovers, and the drain parks inside its own read of it.
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await session.armClockGate()
        let drain = Task { await self.sync.drainDeferredEvents() }
        await expect("the drain parked inside its clock read") { await self.session.isClockGateParked }
        let heldWhileParked = await sync.deferredEvents.count
        XCTAssertEqual(1, heldWhileParked, "parked after the pop — this is not the window under test")
        let appliedWhileParked = await sync.lastAppliedSeq
        XCTAssertNil(appliedWhileParked, "parked after the bookkeeping — this is not the window under test")

        // End Ride 1 accepted, then Start Ride 2 accepted. Ride 1's cleanup is **not** released.
        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        await session.releaseClockGate()
        await drain.value
        await settle()

        let applied = await sync.lastAppliedSeq
        XCTAssertNil(applied, "a command the ride fence refused was recorded as applied")
        let publishedApplied = await sync.diagnostics.lastAppliedCommandSeq
        XCTAssertNil(publishedApplied, "…and published as this device's authoritative command_seq")
        let recovered = await sync.diagnostics.recoveredCommandCount
        XCTAssertEqual(0, recovered, "a refused command was counted as a successful recovery")
        let discarded = await sync.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(1, discarded, "the retired-ride discard was not counted")
        let remaining = await sync.deferredEvents
        XCTAssertTrue(remaining.isEmpty, "the retired event must be removed cleanly, not left to wedge the stream")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(track)), "a retired ride's held command reached the player: \(calls)")
        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity, "a retired ride's held command established playback identity")
        let timeline = await sync.timeline
        XCTAssertNil(timeline, "…and a synchronised timeline")
        let authority = await sync.rideAuthorityEpoch
        XCTAssertEqual(0, authority, "…and stamped ride-scoped authority for a successor ride")

        // Ride 1's own delayed cleanup runs last and must find nothing newer standing.
        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()
        XCTAssertEqual(0, lifecycle.supersededEndRideCount, "ride 1's cleanup was fooled into standing down")
    }

    /// **Round 8, Regression 2 — Blocker B.** The same class at the *immediate* admission point.
    ///
    /// `admitAuthoritativeCommand` captures its `RideAdmission` correctly (round 7) and then awaits
    /// `estimate()`. The `.apply` branch on the far side of that suspension wrote `lastReceivedSeq`,
    /// `lastAppliedSeq` and `diagnostics.lastAppliedCommandSeq` with no ride proof adjacent to them,
    /// relying on `applyAuthoritative` to refuse a stale ride — which it does, one call later, after
    /// the bookkeeping is already published.
    ///
    /// **`lastReceivedSeq` must not move either, and that is a traced decision rather than a
    /// symmetry.** `CommandOrderGate` reads it as the ordering floor and returns `.accept` for a
    /// *gap*, so leaving the floor where it was refuses nothing the leader sends afterwards; whereas
    /// advancing it for a command that will never apply would make the leader's own re-statement of
    /// that command a `.duplicate`. Not spending the sequence number of a command that was refused is
    /// ADR-024 Amendment A1 Finding C's existing rule, applied to the ride lifetime rather than to
    /// the desynchronisation latch.
    func testARideRetiringInsideTheAdmissionsClockReadNeverPublishesTheCommandAsApplied() async {
        await build()
        await readyFollower()
        let track = SyncTestValues.hash(141)
        let itemId = SyncTestValues.ulid(141)
        await content.addLocal(track)

        startRide()
        await session.armClockGate()
        await deliverPlay(track, queueItemId: itemId, commandSeq: 9)
        await expect("the admission parked inside its clock read") { await self.session.isClockGateParked }
        let receivedWhileParked = await sync.diagnostics.lastReceivedCommandSeq
        XCTAssertNil(receivedWhileParked, "parked after the admission wrote its bookkeeping — not the window under test")

        let endRideEpoch = lifecycle.nextRideEpoch()
        _ = lifecycle.nextRideEpoch()

        await session.releaseClockGate()
        await settle()

        let applied = await sync.lastAppliedSeq
        XCTAssertNil(applied, "a command the ride fence refused was recorded as applied")
        let publishedApplied = await sync.diagnostics.lastAppliedCommandSeq
        XCTAssertNil(publishedApplied, "…and published as this device's authoritative command_seq")
        let received = await sync.lastReceivedSeq
        XCTAssertNil(received, "a refused command spent its command_seq and moved the ordering floor")
        let publishedReceived = await sync.diagnostics.lastReceivedCommandSeq
        XCTAssertNil(publishedReceived)
        let refused = await sync.diagnostics.retiredRideAdmissionCount
        XCTAssertEqual(1, refused, "the retired-ride admission refusal was not counted")
        let held = await sync.deferredEvents
        XCTAssertTrue(held.isEmpty, "a refused command was retained instead")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.select(track)), "a retired ride's command reached the player: \(calls)")
        let identity = await sync.currentPlaybackIdentity
        XCTAssertNil(identity)
        let timeline = await sync.timeline
        XCTAssertNil(timeline)
        let authority = await sync.rideAuthorityEpoch
        XCTAssertEqual(0, authority)

        await lifecycle.endRide(epoch: endRideEpoch)
        await settle()
        XCTAssertEqual(0, lifecycle.supersededEndRideCount)
    }

    /// **Round 8, Regression 3 — the fix must not over-reject.** Both windows above, parked in exactly
    /// the same place, with **no** ride boundary at all: the ordinary "the clock read was slow"
    /// case. Everything must apply, against the very `RideAdmission` that admitted it.
    ///
    /// Without this, a fix that simply refused anything that had suspended would pass Regressions 1
    /// and 2 and break synchronised playback outright.
    func testValidSameRideWorkStillAppliesThroughBothParkedWindows() async {
        // The drain half.
        await build()
        await deferringFollower()
        let trackOne = SyncTestValues.hash(142)
        await content.addLocal(trackOne)
        startRide()
        await deliverPlay(trackOne, queueItemId: SyncTestValues.ulid(142), commandSeq: 7)
        await expect("held") { await self.sync.deferredEvents.count == 1 }
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await session.armClockGate()
        let drain = Task { await self.sync.drainDeferredEvents() }
        await expect("the drain parked inside its clock read") { await self.session.isClockGateParked }
        await session.releaseClockGate()
        await drain.value
        await settle()

        let drainReceived = await sync.lastReceivedSeq
        XCTAssertEqual(7, drainReceived)
        let drainApplied = await sync.lastAppliedSeq
        XCTAssertEqual(7, drainApplied, "valid same-ride retained work no longer applies")
        let drainPublished = await sync.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(7, drainPublished)
        let drainRecovered = await sync.diagnostics.recoveredCommandCount
        XCTAssertEqual(1, drainRecovered, "a genuine recovery stopped being counted")
        let drainDiscarded = await sync.diagnostics.retiredRideDeferredCount
        XCTAssertEqual(0, drainDiscarded, "valid work was discarded as retired")
        let drainIdentity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackOne, drainIdentity?.trackHash)
        let drainTimeline = await sync.timeline
        XCTAssertEqual(trackOne, drainTimeline?.trackHash)
        let drainCalls = await player.calls
        XCTAssertTrue(drainCalls.contains(.select(trackOne)), "the held command never reached the player: \(drainCalls)")
        let drainAuthority = await sync.rideAuthorityEpoch
        XCTAssertEqual(1, drainAuthority, "stamped with a ride other than the one that admitted it")

        // The immediate-admission half, on a fresh harness.
        await build()
        await readyFollower()
        let trackTwo = SyncTestValues.hash(143)
        await content.addLocal(trackTwo)
        startRide()
        await session.armClockGate()
        await deliverPlay(trackTwo, queueItemId: SyncTestValues.ulid(143), commandSeq: 9)
        await expect("the admission parked inside its clock read") { await self.session.isClockGateParked }
        await session.releaseClockGate()
        await expect("the admitted command reached the player") {
            await self.player.calls.contains(.select(trackTwo))
        }
        await settle()

        let admitReceived = await sync.lastReceivedSeq
        XCTAssertEqual(9, admitReceived)
        let admitApplied = await sync.lastAppliedSeq
        XCTAssertEqual(9, admitApplied, "valid same-ride work no longer applies")
        let admitPublished = await sync.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(9, admitPublished)
        let admitRefused = await sync.diagnostics.retiredRideAdmissionCount
        XCTAssertEqual(0, admitRefused, "valid work was refused as a retired-ride admission")
        let admitIdentity = await sync.currentPlaybackIdentity
        XCTAssertEqual(trackTwo, admitIdentity?.trackHash)
        let admitAuthority = await sync.rideAuthorityEpoch
        XCTAssertEqual(1, admitAuthority)
    }

    /// §24's cadence applied to round 8: fifty deterministic cycles alternating Regression 1 (the
    /// boundary lands inside the parked clock read) and Regression 3 (it does not), on a fresh
    /// harness each time — so neither a fix that happens to pass once nor one that over-corrects into
    /// refusing everything survives.
    func testFiftyCyclesOfTheParkedDrainWindow() async {
        for cycle in 0 ..< 50 {
            await build()
            await deferringFollower()
            let track = SyncTestValues.hash(144)
            await content.addLocal(track)
            let boundaryHappens = cycle.isMultiple(of: 2)

            startRide()
            await deliverPlay(track, queueItemId: SyncTestValues.ulid(144), commandSeq: 11)
            await expect("held, cycle \(cycle)") { await self.sync.deferredEvents.count == 1 }
            await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
            await session.armClockGate()
            let drain = Task { await self.sync.drainDeferredEvents() }
            await expect("parked, cycle \(cycle)") { await self.session.isClockGateParked }
            if boundaryHappens {
                _ = lifecycle.nextRideEpoch()
                _ = lifecycle.nextRideEpoch()
            }
            await session.releaseClockGate()
            await drain.value
            await settle()

            let applied = await sync.lastAppliedSeq
            let recovered = await sync.diagnostics.recoveredCommandCount
            let discarded = await sync.diagnostics.retiredRideDeferredCount
            if boundaryHappens {
                XCTAssertNil(applied, "cycle \(cycle): refused work was published as applied")
                XCTAssertEqual(0, recovered, "cycle \(cycle)")
                XCTAssertEqual(1, discarded, "cycle \(cycle)")
            } else {
                XCTAssertEqual(11, applied, "cycle \(cycle): valid work stopped applying")
                XCTAssertEqual(1, recovered, "cycle \(cycle)")
                XCTAssertEqual(0, discarded, "cycle \(cycle)")
            }
        }
    }

    /// §24 item 5: fifty deterministic cycles alternating Regression 1 (a boundary while the command
    /// is held) and Regression 4 (no boundary at all), on a fresh harness each time — so a fix that
    /// happens to pass once, or one that over-corrects into refusing everything, fails here.
    func testFiftyCyclesOfDeferredCommandProvenance() async {
        for cycle in 0 ..< 50 {
            await build()
            await deferringFollower()
            let track = SyncTestValues.hash(122)
            let itemId = SyncTestValues.ulid(122)
            await content.addLocal(track)
            let boundaryHappens = cycle.isMultiple(of: 2)

            startRide()
            await deliverPlay(track, queueItemId: itemId)
            await expect("cycle \(cycle): held") { await self.sync.deferredEvents.count == 1 }

            var endRideEpoch: Int64?
            if boundaryHappens {
                endRideEpoch = lifecycle.nextRideEpoch()
                _ = lifecycle.nextRideEpoch()
            }

            await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
            await sync.drainDeferredEvents()
            await settle()

            let identity = await sync.currentPlaybackIdentity
            if boundaryHappens {
                XCTAssertNil(identity, "cycle \(cycle): ride 1's held command became ride 2's authority")
                if let endRideEpoch {
                    await lifecycle.endRide(epoch: endRideEpoch)
                    await settle()
                }
                XCTAssertEqual(0, lifecycle.supersededEndRideCount, "cycle \(cycle)")
            } else {
                XCTAssertEqual(track, identity?.trackHash, "cycle \(cycle): valid same-ride retained work was refused")
            }
        }
    }
}
