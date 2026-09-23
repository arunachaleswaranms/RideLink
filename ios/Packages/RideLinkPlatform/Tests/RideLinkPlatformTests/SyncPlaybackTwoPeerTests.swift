import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// Two real `SyncPlaybackCoordinator`s over a **real, authenticated TLS 1.3 control connection**,
/// with the real `ClockSync` estimator running real `PING`/`PONG` bursts and the real monotonic
/// sleeper doing the waiting.
///
/// This is the wire half of this phase's brief §64. Nothing about the control plane is faked: the
/// two peers pair with a real six-digit exchange, the leader is whichever ADR-010 elected, every
/// frame is encoded by `PlaybackCodec`/`QueueCodec` and framed and encrypted, and the follower maps
/// `effective_at_session_us` through an offset its own estimator measured. Only the *player* and the
/// *content* are fakes, because a decoder is not what this test is about.
///
/// **What it proves and what it does not.** It proves that the Phase 5 message types survive a real
/// authenticated connection, that the leader's scheduling lead is computed from a real `rtt_p95`,
/// and that both coordinators start their (fake) players at the same session instant to within a
/// **software** tolerance. It proves nothing whatsoever about audible alignment: there is no decoder,
/// no mixer, no speaker and no Bluetooth here, and the <100 ms product target (REQUIREMENTS §7) can
/// only be claimed from the real-device gate.
///
/// **Why the offset here is near zero, and why the Android mirror is different.** Both peers share
/// one process and therefore one monotonic clock, so the measured offset is a few microseconds of
/// scheduling noise rather than the unrelated epochs two phones have. The offset *arithmetic* is
/// exercised instead by `com.ridelink.app.sync.SyncPlaybackTwoPeerTest`, which joins two
/// coordinators in-process on clocks 7.5 s apart. The two tests are deliberately complementary: this
/// one is real-wire with a trivial offset, that one is fake-wire with a real offset.
final class SyncPlaybackTwoPeerTests: XCTestCase {
    func testALeadersPlayStartsBothPeersAtTheSameSessionInstantOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            await leader.content.addLocal(SyncTestValues.hash(1))
            await leader.content.addPeer(SyncTestValues.hash(1))
            await follower.content.addLocal(SyncTestValues.hash(1))

            await leader.coordinator.playSynchronized(SyncTestValues.hash(1))

            // The wait is on the *outcome*, not on a fixed duration: the deadline is
            // `now + max(120 ms, 4 x rtt_p95)` and the real sleeper is what honours it.
            try await Self.expect("both peers started") {
                let leaderStarted = await leader.player.calls.contains(.start)
                let followerStarted = await follower.player.calls.contains(.start)
                return leaderStarted && followerStarted
            }

            let leaderPrepared = await leader.player.calls.contains { if case .load = $0 { return true } else { return false } }
            let followerPrepared = await follower.player.calls.contains { if case .load = $0 { return true } else { return false } }
            XCTAssertTrue(leaderPrepared, "ARCHITECTURE §7.2: the decoder is pre-rolled before the deadline")
            XCTAssertTrue(followerPrepared)

            guard let leaderStart = await leader.startedAtSessionUs,
                  let followerStart = await follower.startedAtSessionUs
            else { return XCTFail("one peer never recorded a start") }
            let errorUs = abs(leaderStart - followerStart)
            // A generous software bound: this measures two `Task.sleep` wake-ups on one loaded CI
            // machine, not two phones. It exists to catch a scheduling *bug* — an unmapped instant,
            // a missing lead, a start fired on receipt — not to certify alignment.
            XCTAssertLessThan(
                errorUs, Self.softwareToleranceUs,
                "mapped session start error was \(errorUs) us — software scheduling only, never an audio claim"
            )
            print("two-peer mapped session start error: \(errorUs) us (software scheduling only)")

            let diagnostics = await leader.coordinator.diagnostics
            XCTAssertEqual(diagnostics.role, .leader)
            XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 1)
            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(followerDiagnostics.role, .follower)
            XCTAssertEqual(followerDiagnostics.lastAppliedCommandSeq, 1, "the follower applied the leader's command_seq")
        }
    }

    func testAFollowersIntentComesBackAsTheLeadersAuthoritativeCommandOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            await follower.coordinator.pause()

            // The follower changed no audio of its own (ARCHITECTURE §5's optimistic-feedback rule);
            // what it did was ask, and the leader's broadcast is what returns.
            try await Self.expect("the leader stamped and broadcast the intent") {
                await leader.coordinator.diagnostics.lastAppliedCommandSeq == 1
            }
            try await Self.expect("the follower applied the leader's command") {
                await follower.coordinator.diagnostics.lastAppliedCommandSeq == 1
            }
            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(followerDiagnostics.roleViolationCount, 0)
            XCTAssertEqual(followerDiagnostics.staleRevisionCount, 0)
        }
    }

    func testAQueueMutationOnEitherSideConvergesBothPeersOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            await leader.coordinator.enqueue(SyncTestValues.hash(1))
            try await Self.expect("the snapshot reached the follower") {
                await follower.coordinator.queueState.revision == 1
            }

            await follower.coordinator.enqueue(SyncTestValues.hash(2))
            try await Self.expect("the leader serialised the follower's intent") {
                let leaderRevision = await leader.coordinator.queueState.revision
                let followerRevision = await follower.coordinator.queueState.revision
                return leaderRevision == 2 && followerRevision == 2
            }
            let leaderItems = await leader.coordinator.queueState.items.map(\.queueItemId)
            let followerItems = await follower.coordinator.queueState.items.map(\.queueItemId)
            XCTAssertEqual(leaderItems, followerItems, "both peers hold the identical queue, in the identical order")
        }
    }

    // MARK: - ADR-024 Amendment A2 (the delivery audit), over real TLS

    /// Amendment A2 Finding C over a **real authenticated TLS connection**, with no fake standing in
    /// for the transport: the leader's control session is shut down, so `PlaybackRelay.send`
    /// genuinely returns false — no authenticated writer, exactly as on a dropped link.
    ///
    /// What must then be true is the whole of A2: the leader does **not** apply a command the
    /// follower can never receive, the failure is explicit rather than counted-and-ignored, and the
    /// follower's applied `command_seq` is unchanged.
    func testACommandTheRealTransportCouldNotSendIsNeverAppliedLocallyOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            await leader.content.addLocal(SyncTestValues.hash(1))
            await leader.content.addPeer(SyncTestValues.hash(1))
            await follower.content.addLocal(SyncTestValues.hash(1))
            await leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            try await Self.expect("both peers started") {
                let leaderStarted = await leader.player.calls.contains(.start)
                let followerStarted = await follower.player.calls.contains(.start)
                return leaderStarted && followerStarted
            }
            let appliedBefore = await leader.coordinator.diagnostics.lastAppliedCommandSeq
            let followerAppliedBefore = await follower.coordinator.diagnostics.lastAppliedCommandSeq
            await leader.player.clearCalls()

            // The real control session goes away. Nothing is mocked: the relay now has no
            // authenticated writer, so its `send` answers false the way it does on a dead link.
            await leader.manager.shutdown()
            await leader.coordinator.pause()

            try await Self.expect("the leader observed the failed write") {
                await leader.coordinator.diagnostics.outboundAuthorityLost
            }
            let diagnostics = await leader.coordinator.diagnostics
            XCTAssertTrue(diagnostics.outboundAuthorityLost, "the failure is explicit, never a counter nobody reads")
            XCTAssertEqual(diagnostics.syncState, .transportFailed)
            XCTAssertEqual(diagnostics.lastAppliedCommandSeq, appliedBefore,
                           "the leader committed nothing for a frame the transport refused")
            let calls = await leader.player.calls
            XCTAssertFalse(calls.contains(.pause), "and paused nothing the follower will never hear about")
            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(followerDiagnostics.lastAppliedCommandSeq, followerAppliedBefore,
                           "the follower is exactly where the leader is")
        }
    }

    func testNoOutcomeDDeliveredRide1PlayCompletesOnBothPeersAfterEndAndStartOverRealTls() async throws {
        try await deliveredRideBoundary(restart: true, successor: false)
    }

    func testNoOutcomeDEndRideWithoutAnotherStartStillHonoursDeliveredPlayOverRealTls() async throws {
        try await deliveredRideBoundary(restart: false, successor: false)
    }

    func testRide2CommandAdmittedWhileC1IsParkedWinsOnBothPeersOverRealTls() async throws {
        try await deliveredRideBoundary(restart: true, successor: true)
    }

    private func deliveredRideBoundary(restart: Bool, successor: Bool) async throws {
        try await twoPairedPhones { leader, follower in
            let hash = SyncTestValues.hash(1)
            await leader.content.addLocal(hash)
            await leader.content.addPeer(hash)
            await follower.content.addLocal(hash)
            let newer = SyncTestValues.hash(2)
            await leader.content.addLocal(newer)
            await leader.content.addPeer(newer)
            await follower.content.addLocal(newer)
            // Both tracks already share one revision, so C2 cannot change C1's queue context.
            await leader.coordinator.enqueue(hash)
            await leader.coordinator.enqueue(newer)
            try await Self.expect("both peers hold the two-track queue") {
                await follower.coordinator.queueState.items.count == 2
            }
            let origin = await leader.coordinator.rideEpochs.next()
            _ = await follower.coordinator.rideEpochs.next()
            await leader.player.gateCalls { if case .load = $0 { true } else { false } }
            await leader.coordinator.playSynchronized(hash)
            try await Self.expect("C1 local apply parked inside player load") { await leader.player.isGateParked }
            try await Self.expect("F actually accepted and played C1") {
                let received = await follower.coordinator.lastReceivedSeq
                let started = await follower.player.calls.contains(.start)
                return received == 1 && started
            }
            let ending = await leader.coordinator.rideEpochs.next()
            _ = await leader.coordinator.endRideSegment(rideEpoch: ending)
            if restart { _ = await leader.coordinator.rideEpochs.next() }
            if successor {
                let followerEnd = await follower.coordinator.rideEpochs.next()
                _ = await follower.coordinator.endRideSegment(rideEpoch: followerEnd)
                _ = await follower.coordinator.rideEpochs.next()
                await leader.coordinator.playSynchronized(newer)
                try await Self.expect("C2 delivered while L remains parked in C1") {
                    await follower.coordinator.lastReceivedSeq == 2
                }
                let appliedWhileParked = await leader.coordinator.lastAppliedSeq
                XCTAssertEqual(appliedWhileParked, 1, "SENT C2 is accepted, not yet represented locally")
            }
            await leader.player.releaseGate()
            try await Self.expect("C1 local work completed") { await leader.coordinator.retainedWorkCount == 0 }
            if successor {
                try await Self.expect("both peers completed C2") {
                    let a = await leader.coordinator.retainedWorkCount
                    let b = await follower.coordinator.retainedWorkCount
                    return a == 0 && b == 0
                }
            }
            let calls = await leader.player.calls
            XCTAssertTrue(calls.contains(.start), "No Outcome D: F applied C1; L cannot refuse solely because Ride 1 ended")
            for peer in [leader, follower] {
                let received = await peer.coordinator.lastReceivedSeq
                let applied = await peer.coordinator.lastAppliedSeq
                let current = await peer.coordinator.diagnostics.currentTrackHash
                XCTAssertEqual(received, successor ? 2 : 1)
                XCTAssertEqual(applied, successor ? 2 : 1)
                XCTAssertEqual(current, successor ? newer : hash)
                let peerCalls = await peer.player.calls
                let lastLoad = peerCalls.compactMap { call -> ContentHash? in
                    if case .load(let hash) = call { return hash }; return nil
                }.last
                XCTAssertEqual(lastLoad, successor ? newer : hash)
                let receivedDiagnostic = await peer.coordinator.diagnostics.lastReceivedCommandSeq
                let appliedDiagnostic = await peer.coordinator.diagnostics.lastAppliedCommandSeq
                XCTAssertEqual(receivedDiagnostic, received)
                XCTAssertEqual(appliedDiagnostic, applied)
            }
            let owner = await leader.coordinator.rideAuthorityEpoch
            XCTAssertEqual(owner, successor ? origin + 2 : origin, "only C2 can establish Ride 2 ownership")
            let leaderQueue = await leader.coordinator.queueState
            let followerQueue = await follower.coordinator.queueState
            XCTAssertEqual(leaderQueue, followerQueue)
            let leaderTimeline = await leader.coordinator.timeline
            let followerTimeline = await follower.coordinator.timeline
            XCTAssertEqual(leaderTimeline?.trackHash, followerTimeline?.trackHash)
            XCTAssertEqual(leaderTimeline?.anchorSessionUs, followerTimeline?.anchorSessionUs)
            let healthy = await leader.coordinator.stillCurrent(await leader.coordinator.liveGeneration)
            XCTAssertTrue(healthy, "End Ride leaves the authenticated generation healthy")
        }
    }

    /// A later command cannot overtake the issuer's apply chain. The separate authenticated
    /// resync consumer CAN establish successor state while an older player call is suspended.
    /// Exercise that reachable path, with C2 and its snapshot both coming from the real leader.
    func testRide2SnapshotEstablishesAuthorityBeforeC1ReturnsAndOldCompletionChangesNothingOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            let old = SyncTestValues.hash(1), new = SyncTestValues.hash(2)
            for hash in [old, new] {
                await leader.content.addLocal(hash)
                await leader.content.addPeer(hash)
                await follower.content.addLocal(hash)
                await leader.coordinator.enqueue(hash)
            }
            try await Self.expect("queue replicated") { await follower.coordinator.queueState.items.count == 2 }
            _ = await leader.coordinator.rideEpochs.next()
            _ = await follower.coordinator.rideEpochs.next()
            await follower.player.gateCalls { $0 == .load(old) }
            await leader.coordinator.playSynchronized(old)
            try await Self.expect("F's accepted C1 is in a real player suspension") { await follower.player.isGateParked }
            try await Self.expect("L completed C1") { await leader.coordinator.retainedWorkCount == 0 }
            for peer in [leader, follower] {
                let end = await peer.coordinator.rideEpochs.next()
                _ = await peer.coordinator.endRideSegment(rideEpoch: end)
                _ = await peer.coordinator.rideEpochs.next()
            }
            await leader.coordinator.playSynchronized(new)
            try await Self.expect("leader completed genuine Ride 2 C2") {
                let applied = await leader.coordinator.lastAppliedSeq
                let held = await leader.coordinator.retainedWorkCount
                return applied == 2 && held == 0
            }
            await leader.player.setState(PlayerState(positionMs: 0, durationMs: 200_000, playing: true))
            // An ingress loss is what requests full reconciliation in production. Resync has
            // its own consumer, so this does not wait behind the suspended playback consumer.
            await follower.coordinator.latchDesynchronized()
            let result = SnapshotResult()
            let sink = SnapshotTestSink { message, generation in
                let outcome = await follower.coordinator.onStateSnapshot(message, generation: generation, reconciliation: 77)
                await result.record(outcome)
            }
            await follower.manager.resyncRelay().setSink(sink)
            await leader.coordinator.setResyncChannel(ControlSessionResyncChannel(manager: leader.manager))
            await leader.coordinator.enqueueStateSnapshotReply(
                generation: await leader.coordinator.liveGeneration,
                leaderPeerId: await leader.coordinator.localPeerId, manifestRevision: 0, transfersInFlight: []
            )
            try await Self.expect("real TLS snapshot established C2 before old load returned") { await result.applied }
            try await Self.expect("C2's player effect completed while only C1 remains parked") {
                let held = await follower.coordinator.retainedWorkCount
                let started = await follower.player.calls.contains(.start)
                return held == 1 && started
            }
            let identity = await follower.coordinator.currentPlaybackIdentity
            let timeline = await follower.coordinator.timeline
            let owner = await follower.coordinator.rideAuthorityEpoch
            let mode = await follower.coordinator.synchronizedModeEpoch
            let reconciliation = await follower.coordinator.deferredEvents.compactMap { $0.reconciliation }
            // A real pending play, waiting for unavailable content, must survive the old return.
            await follower.coordinator.playSynchronized(SyncTestValues.hash(3))
            let pending = await follower.coordinator.pendingPlay?.token
            XCTAssertNotNil(pending)
            XCTAssertEqual(identity?.trackHash, new)
            XCTAssertEqual(owner, 3)
            let callsBefore = await follower.player.calls
            await follower.player.releaseGate()
            try await Self.expect("old reservation released; snapshot scheduling also completed") {
                await follower.coordinator.retainedWorkCount == 0
            }
            let identityAfter = await follower.coordinator.currentPlaybackIdentity
            let timelineAfter = await follower.coordinator.timeline
            let ownerAfter = await follower.coordinator.rideAuthorityEpoch
            let modeAfter = await follower.coordinator.synchronizedModeEpoch
            let pendingAfter = await follower.coordinator.pendingPlay?.token
            let reconciliationAfter = await follower.coordinator.deferredEvents.compactMap { $0.reconciliation }
            let currentHash = await follower.coordinator.diagnostics.currentTrackHash
            XCTAssertEqual(identityAfter, identity)
            XCTAssertEqual(timelineAfter, timeline)
            XCTAssertEqual(ownerAfter, owner)
            XCTAssertEqual(modeAfter, mode)
            XCTAssertEqual(pendingAfter, pending)
            XCTAssertEqual(reconciliationAfter, reconciliation)
            XCTAssertEqual(currentHash, new)
            let callsAfter = await follower.player.calls
            XCTAssertEqual(callsAfter, callsBefore, "old C1 dispatched no seek/start after successor authority")
            for peer in [leader, follower] {
                let received = await peer.coordinator.lastReceivedSeq
                let applied = await peer.coordinator.lastAppliedSeq
                XCTAssertEqual(received, 2)
                XCTAssertEqual(applied, 2)
            }
        }
    }

    func testTransportSentAfterEndRideKeepsOriginalObligationOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            let hash = SyncTestValues.hash(1)
            await leader.content.addLocal(hash)
            await leader.content.addPeer(hash)
            await follower.content.addLocal(hash)
            let origin = await leader.coordinator.rideEpochs.next()
            await leader.transport.arm()
            await leader.coordinator.playSynchronized(hash)
            try await Self.expect("TLS write succeeded but SENT callback is parked") { await leader.transport.parked }
            try await Self.expect("F accepted and applied the frame before L received its outcome") {
                await follower.player.calls.contains(.start)
            }
            let receivedBefore = await leader.coordinator.lastReceivedSeq
            let appliedBefore = await leader.coordinator.lastAppliedSeq
            XCTAssertNil(receivedBefore)
            XCTAssertNil(appliedBefore, "delivery outcome has not represented a local apply yet")
            let end = await leader.coordinator.rideEpochs.next()
            _ = await leader.coordinator.endRideSegment(rideEpoch: end)
            _ = await leader.coordinator.rideEpochs.next()
            await leader.transport.release()
            try await Self.expect("issuer completed its exact delivered obligation") {
                let started = await leader.player.calls.contains(.start)
                let retained = await leader.coordinator.retainedWorkCount
                return started && retained == 0
            }
            for peer in [leader, follower] {
                let received = await peer.coordinator.lastReceivedSeq
                let applied = await peer.coordinator.lastAppliedSeq
                XCTAssertEqual(received, 1)
                XCTAssertEqual(applied, 1)
            }
            let owner = await leader.coordinator.rideAuthorityEpoch
            XCTAssertEqual(owner, origin)
        }
    }

    // MARK: - ADR-024 Amendment A13: a clock-held command is already accepted distributed authority

    /// **Regression A.** F accepts C1 over the real wire while its clock is untrusted, so C1 advances
    /// `lastReceivedSeq` and is retained. F's user then ends the ride, and no other ride starts. The
    /// pre-fix End Ride emptied the held stream: L applied C1, F had taken responsibility for C1, and
    /// F discarded it — the terminal state this test forbids by name.
    func testClockHeldAcceptedC1SurvivesFollowerEndRideAndCompletesOnBothPeersOverRealTls() async throws {
        try await clockHeldAcceptedCommand(startAnotherRide: false)
    }

    /// **Regression B.** As A, and F then starts Ride 2, which establishes no playback authority.
    /// C1 still completes, keeps its original Ride-1 provenance, and is never relabelled Ride 2.
    func testClockHeldAcceptedC1SurvivesEndAndNominalStartWithOriginalProvenanceOverRealTls() async throws {
        try await clockHeldAcceptedCommand(startAnotherRide: true)
    }

    private func clockHeldAcceptedCommand(startAnotherRide: Bool) async throws {
        try await twoPairedPhones { leader, follower in
            let hash = SyncTestValues.hash(1)
            await leader.content.addLocal(hash)
            await leader.content.addPeer(hash)
            await follower.content.addLocal(hash)
            _ = await leader.coordinator.rideEpochs.next()
            let followerOrigin = await follower.coordinator.rideEpochs.next()
            await follower.clock.set(untrusted: true)

            await leader.coordinator.playSynchronized(hash)
            try await Self.expect("F accepted C1 and retained it for its clock") {
                let received = await follower.coordinator.lastReceivedSeq
                let held = await follower.coordinator.deferredEvents.count
                return received == 1 && held == 1
            }
            try await Self.expect("L represented and started C1") {
                let applied = await leader.coordinator.lastAppliedSeq
                let started = await leader.player.calls.contains(.start)
                let retained = await leader.coordinator.retainedWorkCount
                return applied == 1 && started && retained == 0
            }
            let appliedWhileHeld = await follower.coordinator.lastAppliedSeq
            XCTAssertNil(appliedWhileHeld, "accepted is not represented: F has not applied C1 yet")
            let callsWhileHeld = await follower.player.calls
            XCTAssertFalse(callsWhileHeld.contains(.select(hash)), "F executed C1 before its clock was trusted")
            XCTAssertFalse(callsWhileHeld.contains(.start))

            let end = await follower.coordinator.rideEpochs.next()
            _ = await follower.coordinator.endRideSegment(rideEpoch: end)
            if startAnotherRide { _ = await follower.coordinator.rideEpochs.next() }
            let receivedAfterEnd = await follower.coordinator.lastReceivedSeq
            XCTAssertEqual(receivedAfterEnd, 1, "End Ride never rolls back what F took responsibility for")
            let heldAfterEnd = await follower.coordinator.deferredEvents.count
            XCTAssertEqual(heldAfterEnd, 1, "End Ride must not erase an accepted distributed obligation")

            await follower.clock.set(untrusted: false)
            try await Self.expect("F represented and executed C1 after its clock recovered") {
                let applied = await follower.coordinator.lastAppliedSeq
                let started = await follower.player.calls.contains(.start)
                let retained = await follower.coordinator.retainedWorkCount
                let held = await follower.coordinator.deferredEvents.count
                return applied == 1 && started && retained == 0 && held == 0
            }

            try await Self.assertNoLeaderAppliedFollowerAcceptedFollowerDiscarded(leader, follower, seq: 1)
            for peer in [leader, follower] {
                let received = await peer.coordinator.lastReceivedSeq
                let applied = await peer.coordinator.lastAppliedSeq
                XCTAssertEqual(received, 1)
                XCTAssertEqual(applied, 1)
                let current = await peer.coordinator.diagnostics.currentTrackHash
                XCTAssertEqual(current, hash)
                let lastLoad = await peer.player.calls.compactMap { call -> ContentHash? in
                    if case .load(let hash) = call { return hash }; return nil
                }.last
                XCTAssertEqual(lastLoad, hash, "actual player effects reflect C1")
            }
            let owner = await follower.coordinator.rideAuthorityEpoch
            XCTAssertEqual(owner, followerOrigin, "C1 keeps Ride-1 provenance; a nominal Ride 2 cannot relabel it")
            let leaderTimeline = await leader.coordinator.timeline
            let followerTimeline = await follower.coordinator.timeline
            XCTAssertEqual(leaderTimeline?.trackHash, followerTimeline?.trackHash)
            XCTAssertEqual(leaderTimeline?.anchorSessionUs, followerTimeline?.anchorSessionUs)
            XCTAssertEqual(leaderTimeline?.anchorPositionMs, followerTimeline?.anchorPositionMs)
            let generation = await follower.coordinator.liveGeneration
            let healthy = await follower.coordinator.stillCurrent(generation)
            XCTAssertTrue(healthy, "the authenticated generation stays healthy across End Ride")
            let retiredByRide = await follower.coordinator.diagnostics.retiredRideDeferredCount
            XCTAssertEqual(retiredByRide, 0, "an accepted command is never retired by local Ride expiry")
        }
    }

    /// **Regression C.** Genuine Ride-2 authority wins over the Ride-1 obligation.
    ///
    /// The held stream is ordered, so nothing authoritative can overtake a *retained* C1 (ADR-024
    /// A2 Finding D): C2 or a snapshot arriving while C1 is held is held behind it. The reachable
    /// successor ordering is therefore C1's own first suspension after it leaves the stream — its
    /// `content.resolve`, before it has represented anything — with genuine C2 arriving on the
    /// inbound consumer and establishing Ride 2 there. C1 then resumes and may change nothing.
    func testGenuineRide2AuthorityEstablishedBeforeHeldC1RepresentsWinsOnBothPeersOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            let old = SyncTestValues.hash(1), new = SyncTestValues.hash(2)
            for hash in [old, new] {
                await leader.content.addLocal(hash)
                await leader.content.addPeer(hash)
                await follower.content.addLocal(hash)
                await leader.coordinator.enqueue(hash)
            }
            try await Self.expect("queue replicated") { await follower.coordinator.queueState.items.count == 2 }
            _ = await leader.coordinator.rideEpochs.next()
            let followerOrigin = await follower.coordinator.rideEpochs.next()
            await follower.clock.set(untrusted: true)
            await leader.coordinator.playSynchronized(old)
            try await Self.expect("F accepted and retained C1") {
                let received = await follower.coordinator.lastReceivedSeq
                let held = await follower.coordinator.deferredEvents.count
                return received == 1 && held == 1
            }
            try await Self.expect("L completed C1") {
                let applied = await leader.coordinator.lastAppliedSeq
                let retained = await leader.coordinator.retainedWorkCount
                return applied == 1 && retained == 0
            }
            for peer in [leader, follower] {
                let end = await peer.coordinator.rideEpochs.next()
                _ = await peer.coordinator.endRideSegment(rideEpoch: end)
                _ = await peer.coordinator.rideEpochs.next()
            }
            let followerRide2 = await follower.coordinator.rideEpochs.current

            // C1 leaves the held stream and parks before representing anything.
            await follower.content.armResolveGate { true }
            await follower.clock.set(untrusted: false)
            try await Self.expect("C1 popped and parked inside its content resolve") {
                let parked = await follower.content.isResolveGateParked
                let held = await follower.coordinator.deferredEvents.count
                return parked && held == 0
            }
            let appliedWhileParked = await follower.coordinator.lastAppliedSeq
            XCTAssertNil(appliedWhileParked, "C1 has not represented anything yet")

            await leader.coordinator.playSynchronized(new)
            try await Self.expect("genuine Ride-2 C2 established on both peers") {
                let leaderApplied = await leader.coordinator.lastAppliedSeq
                let followerApplied = await follower.coordinator.lastAppliedSeq
                let followerStarted = await follower.player.calls.contains(.start)
                let leaderRetained = await leader.coordinator.retainedWorkCount
                return leaderApplied == 2 && followerApplied == 2 && followerStarted && leaderRetained == 0
            }
            try await Self.expect("F's C2 work completed; only parked C1 remains") {
                await follower.coordinator.retainedWorkCount == 1
            }
            let ownerBefore = await follower.coordinator.rideAuthorityEpoch
            XCTAssertEqual(ownerBefore, followerRide2, "C2 established Ride 2 ownership")
            let identityBefore = await follower.coordinator.currentPlaybackIdentity
            let timelineBefore = await follower.coordinator.timeline
            let syncEpochBefore = await follower.coordinator.synchronizedModeEpoch
            let trackBefore = await follower.coordinator.diagnostics.currentTrackHash
            let tokenBefore = await follower.coordinator.currentEpochToken
            let callsBefore = await follower.player.calls

            await follower.content.releaseResolveGate()
            try await Self.expect("C1 finished and released its exact reservation") {
                await follower.coordinator.retainedWorkCount == 0
            }
            let identityAfter = await follower.coordinator.currentPlaybackIdentity
            let timelineAfter = await follower.coordinator.timeline
            let ownerAfter = await follower.coordinator.rideAuthorityEpoch
            let syncEpochAfter = await follower.coordinator.synchronizedModeEpoch
            let trackAfter = await follower.coordinator.diagnostics.currentTrackHash
            let tokenAfter = await follower.coordinator.currentEpochToken
            let callsAfter = await follower.player.calls
            XCTAssertEqual(identityAfter, identityBefore, "C1 overwrote C2's playback identity")
            XCTAssertEqual(timelineAfter, timelineBefore, "C1 overwrote C2's timeline")
            XCTAssertEqual(ownerAfter, ownerBefore, "C1 overwrote C2's ride authority")
            XCTAssertEqual(syncEpochAfter, syncEpochBefore, "C1 moved synchronised-mode state")
            XCTAssertEqual(trackAfter, trackBefore, "C1 altered current-track diagnostics")
            XCTAssertEqual(tokenAfter, tokenBefore, "C1 superseded C2's playback epoch")
            XCTAssertEqual(callsAfter, callsBefore, "C1 dispatched stale player steps")
            XCTAssertNotEqual(ownerAfter, followerOrigin)
            for peer in [leader, follower] {
                let received = await peer.coordinator.lastReceivedSeq
                let applied = await peer.coordinator.lastAppliedSeq
                XCTAssertEqual(received, 2, "sequence truth rolled backwards")
                XCTAssertEqual(applied, 2, "sequence truth rolled backwards")
                let current = await peer.coordinator.diagnostics.currentTrackHash
                XCTAssertEqual(current, new)
                let retained = await peer.coordinator.retainedWorkCount
                XCTAssertEqual(retained, 0)
            }
            let leaderTimeline = await leader.coordinator.timeline
            XCTAssertEqual(leaderTimeline?.trackHash, timelineAfter?.trackHash)
            XCTAssertEqual(leaderTimeline?.anchorSessionUs, timelineAfter?.anchorSessionUs)
        }
    }

    /// The one terminal state ADR-024 Amendment A13 forbids, stated as a predicate rather than implied
    /// by several equalities: the issuer represented `seq`, the peer took responsibility for `seq`,
    /// and the peer holds no representation of it and no retained obligation that could produce one.
    private static func assertNoLeaderAppliedFollowerAcceptedFollowerDiscarded(
        _ leader: SyncPeer, _ follower: SyncPeer, seq: Int64
    ) async throws {
        let leaderApplied = await leader.coordinator.lastAppliedSeq ?? 0
        let followerReceived = await follower.coordinator.lastReceivedSeq ?? 0
        let followerApplied = await follower.coordinator.lastAppliedSeq ?? 0
        let stillOwed = await follower.coordinator.deferredEvents.contains { $0.acceptedCommandSeq == seq }
        let discarded = leaderApplied >= seq && followerReceived >= seq && followerApplied < seq && !stillOwed
        XCTAssertFalse(discarded, "L applied C\(seq), F accepted C\(seq), and F discarded C\(seq)")
    }

    // MARK: - ADR-024 Amendment A11, over real TLS

    /// **The two-peer statement of Amendment A11, over a real authenticated TLS connection, and the
    /// explicit refutation of "Outcome C".**
    ///
    /// Phase 8 correctly found that bounded wire queues feed unbounded local apply/scheduled work.
    /// Its first fix refused at the point the work was created, which on a leader is reached from the
    /// outbound commit hook — **after** the real transport accepted the frame. The follower therefore
    /// had the command and applied it while the leader cancelled its own apply and wiped
    /// `lastAppliedSeq`. Nothing on the wire could repair that, because nothing on the wire said
    /// anything had happened.
    ///
    /// The bound is injected at 1 and the leader's own pre-roll is parked inside the fake player, so
    /// the one outstanding obligation is held deterministically rather than raced against the real
    /// 120 ms scheduling lead. The next command therefore meets a genuinely full ledger.
    ///
    /// - **Outcome A** — the second command is refused *before* delivery: this asserts on the
    ///   **follower**, a real second coordinator on the far end of a real socket, that it never
    ///   arrived.
    /// - **Outcome B** — the first command *was* delivered, so the leader retains and eventually
    ///   executes its obligation, and both peers converge on the same `command_seq`.
    ///
    /// There is no Outcome C.
    func testLocalWorkCapacityIsRefusedBeforeDeliveryAndLeavesBothPeersAgreeingOverRealTls() async throws {
        try await twoPairedPhones(leaderSessionWorkCapacity: 1) { leader, follower in
            await leader.content.addLocal(SyncTestValues.hash(1))
            await leader.content.addPeer(SyncTestValues.hash(1))
            await follower.content.addLocal(SyncTestValues.hash(1))
            // Park the leader's own pre-roll, so its single obligation stays outstanding.
            await leader.player.gateCalls { if case .load = $0 { return true } else { return false } }

            await leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            try await Self.expect("the PLAY crossed the real wire and the follower accepted it") {
                await follower.coordinator.diagnostics.lastReceivedCommandSeq == 1
            }
            try await Self.expect("and the leader's own apply is parked, holding its obligation") {
                await leader.coordinator.retainedWorkCount == 1
            }

            // The next authoritative command meets a full local-work ledger.
            await leader.coordinator.pause()
            try await Self.expect("the leader says why it stopped issuing") {
                await leader.coordinator.diagnostics.syncState == .localOverload
            }
            let leaderDiagnostics = await leader.coordinator.diagnostics
            XCTAssertEqual(leaderDiagnostics.workCapacityRefusedCount, 1)
            XCTAssertTrue(leaderDiagnostics.outboundAuthorityLost)
            XCTAssertEqual(leaderDiagnostics.nextCommandSeq, 2, "a refused candidate consumes no command_seq")

            // Outcome A, proved on the far end of a real socket: the refused PAUSE is nowhere.
            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(followerDiagnostics.lastReceivedCommandSeq, 1,
                           "the follower never received the command the leader refused to honour")
            let followerCalls = await follower.player.calls
            XCTAssertFalse(followerCalls.contains(.pause))

            // Outcome B for the command that *was* delivered: the leader honours it and both agree.
            await leader.player.releaseGate()
            try await Self.expect("both peers started the delivered PLAY") {
                let leaderStarted = await leader.player.calls.contains(.start)
                let followerStarted = await follower.player.calls.contains(.start)
                return leaderStarted && followerStarted
            }
            let finalLeader = await leader.coordinator.diagnostics
            let finalFollower = await follower.coordinator.diagnostics
            XCTAssertEqual(finalLeader.lastAppliedCommandSeq, 1,
                           "the leader honoured the command it delivered — it did not abandon one it had sent")
            XCTAssertEqual(finalLeader.lastAppliedCommandSeq, finalFollower.lastAppliedCommandSeq,
                           "no Outcome C: the two peers applied exactly the same authoritative commands")
        }
    }

    // MARK: - ADR-024 Amendment A1 (the closure audit), over real TLS

    /// Amendment A1 Finding A over a **real authenticated TLS connection**: the pillion presses Play
    /// once, on a track that is not yet in the shared queue, and it becomes exactly one authoritative
    /// `PLAY` — with neither peer refusing anything for a stale revision.
    ///
    /// Before the amendment the follower sent `QUEUE_ADD` (revision 0) and `PLAY` (revision 0) back to
    /// back on this very connection; the leader accepted the add, moved to revision 1, and refused the
    /// `PLAY`. The press did nothing and the rider had to press again.
    func testAFollowersFirstPlayOnAnUnqueuedTrackConvergesOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            // Both phones hold the track; only the queue is behind.
            await leader.content.addLocal(SyncTestValues.hash(1))
            await leader.content.addPeer(SyncTestValues.hash(1))
            await follower.content.addLocal(SyncTestValues.hash(1))
            await follower.content.addPeer(SyncTestValues.hash(1))

            // One press. Nothing else.
            await follower.coordinator.playSynchronized(SyncTestValues.hash(1))

            try await Self.expect("both peers started from one press") {
                let leaderStarted = await leader.player.calls.contains(.start)
                let followerStarted = await follower.player.calls.contains(.start)
                return leaderStarted && followerStarted
            }

            let leaderDiagnostics = await leader.coordinator.diagnostics
            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(leaderDiagnostics.lastAppliedCommandSeq, 1, "exactly one authoritative PLAY")
            XCTAssertEqual(followerDiagnostics.lastAppliedCommandSeq, 1)
            XCTAssertEqual(
                leaderDiagnostics.staleRevisionCount, 0,
                "the leader refused nothing — no second press was needed"
            )
            XCTAssertEqual(followerDiagnostics.staleRevisionCount, 0)
            XCTAssertEqual(followerDiagnostics.resumedPendingPlayCount, 1, "one press, one retained Play, one issue")

            let leaderItems = await leader.coordinator.queueState.items.map(\.queueItemId)
            let followerItems = await follower.coordinator.queueState.items.map(\.queueItemId)
            XCTAssertEqual(leaderItems, followerItems, "and the queue converged to one identical state")
            XCTAssertEqual(leaderItems.count, 1, "one press added exactly one item")

            guard let leaderStart = await leader.startedAtSessionUs,
                  let followerStart = await follower.startedAtSessionUs
            else { return XCTFail("one peer never recorded a start") }
            XCTAssertLessThan(
                abs(leaderStart - followerStart), Self.softwareToleranceUs,
                "software scheduling only, never an audio claim"
            )
        }
    }

    /// Amendment A1 Finding B over real TLS: a queue mutation and a playback command decided in one
    /// leader order cannot be observed by the peer in an order that makes the command invalid. The
    /// peer's own `staleRevisionCount` is the assertion — it is the exact counter the defect
    /// incremented, and here it is measured across real framing, encryption and a real socket.
    func testAQueueMutationRacingAPlaybackCommandIsNeverObservedInAnInvalidCrossOrderOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            for seed in 1 ... 3 {
                await leader.content.addLocal(SyncTestValues.hash(seed))
                await leader.content.addPeer(SyncTestValues.hash(seed))
                await follower.content.addLocal(SyncTestValues.hash(seed))
                await follower.content.addPeer(SyncTestValues.hash(seed))
            }
            await leader.coordinator.enqueue(SyncTestValues.hash(1))
            await leader.coordinator.enqueue(SyncTestValues.hash(2))
            try await Self.expect("both peers hold two items") {
                await follower.coordinator.queueState.revision == 2
            }

            // Both users act at once, from both ends. Genuinely concurrent — unstructured tasks
            // racing into the two actors and out onto one real socket.
            let doomed = await leader.coordinator.queueState.items[0].queueItemId
            let leaderCoordinator = leader.coordinator
            let followerCoordinator = follower.coordinator
            async let removal: Void = leaderCoordinator.removeFromQueue(doomed)
            async let step: Void = followerCoordinator.next()
            async let seek: Void = leaderCoordinator.seek(positionMs: 12_000)
            async let addition: Void = leaderCoordinator.enqueue(SyncTestValues.hash(3))
            _ = await (removal, step, seek, addition)

            try await Self.expect("both peers converge") {
                let leaderRevision = await leader.coordinator.queueState.revision
                let followerRevision = await follower.coordinator.queueState.revision
                let leaderSeq = await leader.coordinator.diagnostics.lastAppliedCommandSeq
                let followerSeq = await follower.coordinator.diagnostics.lastAppliedCommandSeq
                return leaderRevision == followerRevision && leaderSeq == followerSeq && leaderSeq != nil
            }

            let followerDiagnostics = await follower.coordinator.diagnostics
            XCTAssertEqual(
                followerDiagnostics.staleRevisionCount, 0,
                "the follower refused an authoritative command for a revision the leader had already "
                    + "moved past — the exact Finding B defect, on the real wire"
            )
            XCTAssertEqual(followerDiagnostics.inboundOverflowCount, 0, "and nothing was lost in the handoff")
            let leaderItems = await leader.coordinator.queueState.items.map(\.queueItemId)
            let followerItems = await follower.coordinator.queueState.items.map(\.queueItemId)
            XCTAssertEqual(leaderItems, followerItems, "and both converged to one identical queue")
        }
    }

    /// Amendment A1 Finding E over real TLS: the pillion presses Play on a track only the rider holds,
    /// the existing Phase 4 machinery is asked once, and the synchronised `PLAY` happens by itself
    /// when the verified cache reports it — with no second press.
    func testAPlayForContentOnlyThePeerHoldsBecomesASynchronizedPlayOverRealTls() async throws {
        try await twoPairedPhones { leader, follower in
            // The rider has it; the pillion does not, but knows the rider does.
            await leader.content.addLocal(SyncTestValues.hash(1))
            await follower.content.addPeer(SyncTestValues.hash(1))

            await follower.coordinator.playSynchronized(SyncTestValues.hash(1))
            try await Self.expect("Phase 4 was asked") {
                await follower.content.transferRequests == [SyncTestValues.hash(1)]
            }
            let started = await follower.player.calls.contains(.start)
            XCTAssertFalse(started, "nothing may play while one phone cannot")

            // Phase 4 commits on the pillion, and the rider learns of it the way ADR-024 §7 says.
            await follower.content.completeTransfer(SyncTestValues.hash(1))
            await leader.content.peerVerified(SyncTestValues.hash(1))

            try await Self.expect("both peers started, with no second press") {
                let leaderStarted = await leader.player.calls.contains(.start)
                let followerStarted = await follower.player.calls.contains(.start)
                return leaderStarted && followerStarted
            }
            let requests = await follower.content.transferRequests
            XCTAssertEqual(requests, [SyncTestValues.hash(1)], "and Phase 4 was still only asked once")
        }
    }

    // MARK: - Harness

    /// A generous software bound — see the call site's comment. Not an audio figure.
    private static let softwareToleranceUs: Int64 = 100_000

    private static func expect(_ description: String, _ condition: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    /// One peer's Phase 5 stack over its real `ControlSessionManager`.
    private final class SyncPeer: @unchecked Sendable {
        let coordinator: SyncPlaybackCoordinator
        let player: FakeSyncPlayer
        let content: FakeSyncContent
        /// `internal`, not `private`: the Amendment A2 scenario ends the *real* control session to
        /// make the relay's `send` genuinely answer false, which is the only honest way to reach
        /// Finding C's path without a fake standing in for the transport.
        let manager: ControlSessionManager
        let transport: OutcomeGateChannel
        let clock = ClockReadinessOverride()

        init(
            manager: ControlSessionManager,
            localPeerId: PeerId,
            monotonicNowUs: @escaping @Sendable () -> Int64,
            /// ADR-024 Amendment A11: injected only by the local-work-capacity scenario, which needs
            /// the edge forced rather than raced against a real 120 ms scheduling lead.
            sessionWorkCapacity: Int = Phase5GateBounds.defaultSessionWorkCapacity
        ) {
            self.manager = manager
            let player = FakeSyncPlayer()
            self.player = player
            content = FakeSyncContent()
            let base = ControlSessionSyncPort(manager: manager)
            let transport = OutcomeGateChannel(base: base.channel)
            self.transport = transport
            coordinator = SyncPlaybackCoordinator(
                monotonicNowUs: monotonicNowUs,
                localPeerId: localPeerId,
                session: OutcomeGateSession(base: base, channel: transport, clock: clock),
                player: player,
                content: content,
                sleeper: MonotonicDeadlineSleeper(monotonicNowUs: monotonicNowUs),
                routeState: NeverTransitioningRoute(),
                nextQueueItemId: { Ulid.generate() },
                sessionWorkCapacity: sessionWorkCapacity
            )
        }

        func prepare(monotonicNowUs: @escaping @Sendable () -> Int64) async {
            await player.stampStartsWith(monotonicNowUs)
            await coordinator.start()
        }

        /// The session instant at which this peer's player was actually told to start — its own
        /// monotonic instant mapped through the offset **its own** estimator measured, which is the
        /// only comparison that means anything across two devices.
        var startedAtSessionUs: Int64? {
            get async {
                guard let localUs = await player.firstStartAtMonoUs else { return nil }
                let offset = await manager.sessionClockEstimate()?.offsetToLeaderUs ?? 0
                return SessionClock.sessionUs(localMonoUs: localUs, offsetToLeaderUs: offset)
            }
        }
    }

    private func twoPairedPhones(
        leaderSessionWorkCapacity: Int = Phase5GateBounds.defaultSessionWorkCapacity,
        _ body: (SyncPeer, SyncPeer) async throws -> Void
    ) async throws {
        let a = try TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", name: "A")
        let b = try TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", name: "B")
        let monotonic: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000) }
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: monotonic))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: monotonic))
        await sessionA.attach()
        await sessionB.attach()

        let peerA = SyncPeer(
            manager: sessionA.manager, localPeerId: a.peerId, monotonicNowUs: monotonic,
            sessionWorkCapacity: leaderSessionWorkCapacity
        )
        let peerB = SyncPeer(
            manager: sessionB.manager, localPeerId: b.peerId, monotonicNowUs: monotonic,
            sessionWorkCapacity: leaderSessionWorkCapacity
        )
        await peerA.prepare(monotonicNowUs: monotonic)
        await peerB.prepare(monotonicNowUs: monotonic)

        let portA = try await sessionA.manager.startListening(local: a.local)
        let portB = try await sessionB.manager.startListening(local: b.local)
        for session in [sessionA, sessionB] {
            session.apply(.startDiscovery)
            session.apply(.peerSelected)
        }
        await sessionA.manager.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await sessionB.manager.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        _ = try await sessionA.awaitPairingPrompt()
        _ = try await sessionB.awaitPairingPrompt()
        await sessionA.manager.confirmPairing(accepted: true)
        await sessionB.manager.confirmPairing(accepted: true)
        try await sessionA.awaitEvent { if case .connected = $0 { return true } else { return false } }
        try await sessionB.awaitEvent { if case .connected = $0 { return true } else { return false } }

        // ADR-010 decided the leader during the handshake; the test reads it rather than assuming
        // it, because assuming it is precisely what ADR-010 Amendment A2 exists to forbid.
        guard let aIsLeader = Self.isLocalLeader(sessionA), let bIsLeader = Self.isLocalLeader(sessionB) else {
            return XCTFail("no connected event carried a leadership decision")
        }
        XCTAssertNotEqual(aIsLeader, bIsLeader, "exactly one peer leads")
        await peerA.coordinator.handleConnected(isLocalLeader: aIsLeader)
        await peerB.coordinator.handleConnected(isLocalLeader: bIsLeader)

        // Both estimators must have accepted a window before a synchronised command may be issued
        // (brief §7). This is the real 11-sample ARCHITECTURE §7.1 burst over loopback.
        try await Self.expect("both clock estimators are ready") {
            let aReady = await sessionA.manager.sessionClockEstimate()?.ready == true
            let bReady = await sessionB.manager.sessionClockEstimate()?.ready == true
            return aReady && bReady
        }
        await peerA.player.clearCalls()
        await peerB.player.clearCalls()

        let leader = aIsLeader ? peerA : peerB
        let follower = aIsLeader ? peerB : peerA
        try await body(leader, follower)

        await sessionA.manager.shutdown()
        await sessionB.manager.shutdown()
    }

    private static func isLocalLeader(_ session: FsmSession) -> Bool? {
        for event in session.events {
            if case .connected(_, _, let isLocalLeader, _) = event { return isLocalLeader }
        }
        return nil
    }
}

/// The drift ladder's suspension condition, never true here: this test is about scheduling, and a
/// route transition is Phase 2b state a loopback socket has no way to produce.
private struct NeverTransitioningRoute: SyncRouteStatePort {
    func isRouteTransitioning() async -> Bool { false }
}

/// Test-only suspension at the return from a real authenticated write. Bytes, generation binding
/// and follower dispatch are production TLS; only delivery of the successful outcome is gated.
private actor OutcomeGateChannel: SyncPlaybackChannel {
    let base: any SyncPlaybackChannel
    private var armed = false
    private var continuation: CheckedContinuation<Void, Never>?
    var parked: Bool { continuation != nil }
    init(base: any SyncPlaybackChannel) { self.base = base }
    func arm() { armed = true }
    func release() {
        armed = false
        let held = continuation
        continuation = nil
        held?.resume()
    }
    func setPlaybackSink(_ sink: (any PlaybackSink)?) async { await base.setPlaybackSink(sink) }
    func setQueueSink(_ sink: (any QueueSink)?) async { await base.setQueueSink(sink) }
    func send(_ message: QueueMessage, authorizingGeneration: Int64) async -> Bool {
        await base.send(message, authorizingGeneration: authorizingGeneration)
    }
    func send(_ message: PlaybackMessage, authorizingGeneration: Int64) async -> Bool {
        let sent = await base.send(message, authorizingGeneration: authorizingGeneration)
        if sent, armed, case .play = message {
            await withCheckedContinuation { continuation = $0 }
        }
        return sent
    }
}

private struct OutcomeGateSession: SyncSessionPort {
    let base: any SyncSessionPort
    let channel: any SyncPlaybackChannel
    let clock: ClockReadinessOverride
    func currentAuthGeneration() async -> Int64 { await base.currentAuthGeneration() }
    func sessionClockEstimate() async -> SessionClockEstimate? {
        let measured = await base.sessionClockEstimate()
        guard await clock.untrusted, let measured else { return measured }
        return SessionClockEstimate(offsetToLeaderUs: measured.offsetToLeaderUs, rttP95Us: measured.rttP95Us, ready: false)
    }
    func rttP95Us() async -> Int64? { await base.rttP95Us() }
}

/// Test-only: reports the real estimator's own measurement as not yet trustworthy, which is the
/// production condition (a fresh or stepped window) that makes a follower hold an accepted command.
/// The offset, the wire and the drain cadence stay real; only readiness is withheld.
private actor ClockReadinessOverride {
    private(set) var untrusted = false
    func set(untrusted value: Bool) { untrusted = value }
}

private actor SnapshotResult {
    var applied = false
    func record(_ outcome: StateSnapshotOutcome) { applied = outcome == .applied }
}

private struct SnapshotTestSink: ResyncSink {
    let receive: @Sendable (ResyncMessage, Int64) async -> Void
    init(_ receive: @escaping @Sendable (ResyncMessage, Int64) async -> Void) { self.receive = receive }
    func submit(_ message: ResyncMessage, generation: Int64) {
        Task { await receive(message, generation) }
    }
}
