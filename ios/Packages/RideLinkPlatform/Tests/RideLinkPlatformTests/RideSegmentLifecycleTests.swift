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

        await startRide()
        await playAsLeader(trackX)
        let established = await sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, established, "ride 1 established authoritative track X")

        // End Ride is accepted and takes epoch 2 — and then, before its cleanup runs, Start Ride 2
        // takes epoch 3 and runs. This is the parked-cleanup ordering, stated rather than raced.
        let endRideEpoch = lifecycle.nextRideEpoch()
        let startRideEpoch = lifecycle.nextRideEpoch()
        await lifecycle.startRide(epoch: startRideEpoch)
        await settle()

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

        await startRide()
        await playAsLeader(trackX)

        let endRideEpoch = lifecycle.nextRideEpoch()
        let startRideEpoch = lifecycle.nextRideEpoch()
        await lifecycle.startRide(epoch: startRideEpoch)
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

            await startRide()
            await playAsLeader(trackX)

            let endRideEpoch = lifecycle.nextRideEpoch()
            let startRideEpoch = lifecycle.nextRideEpoch()
            await lifecycle.startRide(epoch: startRideEpoch)
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
        await startRide()
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
        // Armed immediately before the call, so the first generation read it takes — which is
        // `applyAuthoritative`'s own `stillCurrent`, one statement after the ride-lifetime capture —
        // is the one that parks.
        await session.armGenerationGate()
        let apply = Task {
            await self.sync.applyAuthoritative(
                .next(header: PlaybackCommandHeader(
                    commandSeq: 1, effectiveAtSessionUs: 0, issuedBy: SyncTestValues.leaderPeerId, queueRevision: 1
                )),
                generation: generation,
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
        await startRide()
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
        await session.armGenerationGate()
        let apply = Task {
            await self.sync.applyAuthoritative(
                .next(header: PlaybackCommandHeader(
                    commandSeq: 1, effectiveAtSessionUs: 0, issuedBy: SyncTestValues.leaderPeerId, queueRevision: 1
                )),
                generation: generation,
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
        await startRide()

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
}
