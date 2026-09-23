import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// ADR-024 Amendment A14: **completing previously distributed authority after End Ride may satisfy
/// that old obligation, but it never reopens admission of fresh synchronised transport authority.**
///
/// Amendment A13 correctly lets accepted and delivered commands finish after End Ride. While one
/// does, production legitimately publishes `.scheduled` (from `scheduleAt`) and then `.synced` (from
/// `markSynced`), and the role survives End Ride because the control connection does. The presenter
/// used to reconstruct "a synchronised session owns transport control" as
/// `diagnostics.role != nil && diagnostics.syncState != .inactive` — true again at that point — and
/// `RideLinkApp` handed that value to `SyncPlaybackGateAdapter`. So a lock-screen Pause was
/// intercepted after End Ride and a fresh synchronised `PAUSE` went out. Reproduced against the
/// unmodified sources (moved into this package, unchanged, by the preceding commit): the presenter
/// read active at `.scheduled` and at `.synced`, `interceptPause()` returned true, the forwarded press
/// reached the wire, and a direct `next()` reached the wire — while the coordinator's own
/// `isSynchronizedModeActive()` said false throughout.
///
/// Every test drives production through the fake session on a virtual clock; the only waits are
/// polls for a state the production code reaches, never a sleep that decides an outcome. The real
/// `SyncPlaybackGateAdapter` and the real `SyncPlaybackPresenter` are used wherever a gate or a
/// presenter answer is asserted.
@MainActor
final class SyncPlaybackTransportOwnershipTests: XCTestCase {
    private static let anchorUs: Int64 = 50_000_000
    private static let hashA = SyncTestValues.hash(0xA1)
    private static let hashB = SyncTestValues.hash(0xB2)
    /// C1's effective instant: far enough ahead that the drain's first retry schedules it rather than
    /// applying it late, so `.scheduled` is really published after End Ride.
    private static let c1LeadUs = 10 * Phase5GateBounds.deferredRetryIntervalUs

    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var coordinator: SyncPlaybackCoordinator!
    private var presenter: SyncPlaybackPresenter?
    /// Every diagnostics snapshot the presenter received, with the presenter's own ownership answer
    /// read at the instant it received it.
    private var presented: [PresentedSnapshot] = []

    private struct PresentedSnapshot {
        let syncState: SyncState
        let role: PlaybackRole?
        let presenterActive: Bool
    }

    override func tearDown() async throws {
        await player?.releaseGate()
        await session?.releaseClockGate()
        await coordinator?.shutdown()
        coordinator = nil
        presenter = nil
    }

    // MARK: - Regression A: accepted debt finishes after End Ride; ownership stays local

    func testAcceptedDebtFinishingAfterEndRideNeverReopensTransportOwnership() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: true)
        let endedAt = await followerFinishesAcceptedDebtAfterEndRide()

        // C1 completed correctly — as ride-1 work.
        let applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(applied, 1, "C1 was not represented")
        let held = await coordinator.deferredEvents.count
        XCTAssertEqual(held, 0, "C1 did not leave the held stream")
        let calls = await player.calls
        XCTAssertEqual(calls, FakeSyncPlayer.preRoll(Self.hashA, 0) + [.start], "C1's effect did not complete")
        let identity = await coordinator.currentPlaybackIdentity
        XCTAssertEqual(identity?.trackHash, Self.hashA)
        let owner = await coordinator.rideAuthorityEpoch
        XCTAssertEqual(owner, 1, "C1 completed as ride-1 work")

        // And the presenter — the real one, fed by the coordinator's real publications — never read a
        // single post-End snapshot as "synchronised", although those snapshots are exactly the ones
        // the pre-A14 derivation read as synchronised.
        await expectMain("the presenter received C1's SYNCED") { self.presenter?.diagnostics.syncState == .synced }
        let afterEnd = presented[endedAt...]
        XCTAssertTrue(afterEnd.contains { $0.syncState == .scheduled }, "premise: SCHEDULED was published after End Ride")
        XCTAssertTrue(afterEnd.contains { $0.syncState == .synced }, "premise: SYNCED was published after End Ride")
        XCTAssertTrue(
            afterEnd.contains { $0.role != nil && $0.syncState != .inactive },
            "premise: the pre-A14 derivation `role != nil && syncState != .inactive` is true after End Ride"
        )
        XCTAssertFalse(afterEnd.contains { $0.presenterActive }, "the presenter read synchronised ownership after End Ride")
        XCTAssertEqual(presenter?.isSynchronizedModeActive, false)
    }

    /// The delivered-authority form of Regression A, on the leader, followed by Regressions B, C and
    /// D for the leader: the real gate stays local, the synchronised entry points refuse fresh
    /// authority, and only Play-synced reopens ownership.
    func testDeliveredDebtFinishingAfterEndRideLeavesTheLeaderLocalUntilItPlaysSynchronisedAgain() async {
        await build(localPeerId: SyncTestValues.leaderPeerId, withPresenter: true)
        await connect(asLeader: true)
        _ = coordinator.rideEpochs.next()
        await coordinator.playSynchronized(Self.hashA)
        await assertOwnership(.synchronized(.leader), "Play synced is an activation")
        await expect("C1 SENT, pre-rolled and scheduled") {
            let calls = await self.player.calls
            let state = await self.coordinator.diagnostics.syncState
            return calls == FakeSyncPlayer.preRoll(Self.hashA, 0) && state == .scheduled
        }
        let playsBefore = await session.playbackMessages().count
        XCTAssertEqual(playsBefore, 1, "premise: exactly C1 is on the wire")

        let end = coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        await assertOwnership(.local, "End Ride ended transport ownership")
        let role = await coordinator.role
        XCTAssertEqual(role, .leader, "the role survives End Ride")

        // The delivered obligation finishes after End Ride.
        clock.advance(to: clock.now() + SessionClock.minLeadUs * 2)
        await expect("C1's delivered start ran and reported SYNCED") {
            let started = await self.player.calls.contains(.start)
            let state = await self.coordinator.diagnostics.syncState
            return started && state == .synced
        }
        await assertOwnership(.local, "a delivered command finishing is not an activation")
        let applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(applied, 1)

        // Regression B — the real gate.
        let gate = SyncPlaybackGateAdapter(sync: coordinator)
        let quiet = await wireState()
        XCTAssertFalse(gate.interceptPlay())
        XCTAssertFalse(gate.interceptPause())
        XCTAssertFalse(gate.interceptSeek(9_000))
        XCTAssertFalse(gate.interceptNext())
        XCTAssertFalse(gate.interceptPrevious())
        XCTAssertFalse(gate.interceptTrackEnded(), "locally, MusicCoordinator advances its own queue")
        // Regression C — the synchronised entry points, called directly as the synchronised-playback
        // controls call them, refuse to create fresh authority.
        await coordinator.pause()
        await coordinator.next()
        await coordinator.seek(positionMs: 4_000)
        await settle(400)
        await assertNoSynchronisedEffect(since: quiet)
        let nextSeq = await coordinator.nextSeq
        XCTAssertEqual(nextSeq, 2, "a refused press allocated a command_seq")

        // Regression D — the intended activation path, and only it, reopens ownership.
        await coordinator.playSynchronized(Self.hashB)
        await assertOwnership(.synchronized(.leader), "Play synced reopened ownership")
        await expect("C2, the new PLAY, is on the wire") {
            await self.session.playbackMessages().contains { $0.commandSeq == 2 && $0.isPlay }
        }
        XCTAssertTrue(gate.interceptPause(), "a synchronised leader's Pause is intercepted again")
        await expect("and becomes an authoritative PAUSE") {
            await self.session.playbackMessages().contains { $0.commandSeq == 3 && $0.isPause }
        }
    }

    // MARK: - Regression B: the real gate after End Ride and old-debt completion

    func testTheRealGateLeavesEveryControlLocalAfterEndRideAndOldDebtCompletion() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        _ = await followerFinishesAcceptedDebtAfterEndRide()
        let gate = SyncPlaybackGateAdapter(sync: coordinator)
        let quiet = await wireState()

        XCTAssertFalse(gate.interceptPlay(), "Play")
        XCTAssertFalse(gate.interceptPause(), "Pause")
        XCTAssertFalse(gate.interceptSeek(12_000), "Seek")
        XCTAssertFalse(gate.interceptNext(), "Next")
        XCTAssertFalse(gate.interceptPrevious(), "Previous")
        // Local mode's answer: MusicCoordinator dispatches `.next` to its own LocalQueue.
        XCTAssertFalse(gate.interceptTrackEnded(), "TrackEnded")

        await settle(400)
        await assertNoSynchronisedEffect(since: quiet)
    }

    // MARK: - Regression C: a local control pressed after all that stays local

    func testALocalPauseAfterEndRideAndOldDebtStaysLocalAndIssuesNoSynchronisedFrame() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        _ = await followerFinishesAcceptedDebtAfterEndRide()
        let gate = SyncPlaybackGateAdapter(sync: coordinator)
        let quiet = await wireState()

        // `MusicCoordinator.pause()` is `if syncGate?.interceptPause() == true { return }` followed by
        // Phase 3's own `player.execute(.pause)`. A declined intercept *is* the Phase 3 path; the app
        // target has no test bundle, so the player half is asserted by reading, not by running it.
        XCTAssertFalse(gate.interceptPause(), "the lock-screen Pause must reach Phase 3, not the session")
        XCTAssertFalse(gate.interceptNext(), "so must Next, which needs no player read")

        // Defence in depth: the entry points the synchronised-playback controls call directly refuse
        // to create authority for themselves — none of them asks the gate first.
        await coordinator.pause()
        await coordinator.resume()
        await coordinator.seek(positionMs: 3_000)
        await coordinator.next()
        await coordinator.previous()
        let enqueued = await coordinator.diagnostics.outboundEnqueuedCount
        XCTAssertEqual(enqueued, quiet.enqueued, "a refused press was admitted to the outbound path")
        await settle(400)
        await assertNoSynchronisedEffect(since: quiet)
    }

    // MARK: - Regression D: only a legitimate activation reopens ownership

    func testOnlyALegitimateActivationReopensTransportOwnershipAfterEndRide() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: true)
        _ = await followerFinishesAcceptedDebtAfterEndRide()
        let gate = SyncPlaybackGateAdapter(sync: coordinator)

        // A nominal Start Ride establishes nothing, and neither a surviving role nor a SYNCED display
        // is an activation.
        _ = coordinator.rideEpochs.next()
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.role, .follower, "premise: role != nil")
        XCTAssertEqual(diagnostics.syncState, .synced, "premise: syncState == .synced")
        await assertOwnership(.local, "Start Ride, a role and SYNCED together are still not ownership")
        XCTAssertFalse(gate.interceptPause())

        // The follower's user presses Play synced: the intended activation.
        await session.clearSent()
        await coordinator.playSynchronized(Self.hashB)
        await assertOwnership(.synchronized(.follower), "Play synced reopened ownership")

        XCTAssertTrue(gate.interceptPause(), "Pause is intercepted again")
        await expect("as a PAUSE intent") { await self.session.playbackMessages().contains { $0.isPause } }
        XCTAssertTrue(gate.interceptSeek(7_000), "Seek is intercepted again")
        await expect("as a SEEK intent") { await self.session.playbackMessages().contains { $0.isSeek } }
        XCTAssertTrue(gate.interceptNext(), "Next is intercepted again")
        await expect("as a NEXT intent") { await self.session.playbackMessages().contains { $0.isNext } }
        let intents = await session.playbackMessages()
        XCTAssertTrue(
            intents.allSatisfy { $0.commandSeq == PlaybackBounds.unassignedCommandSeq },
            "a follower only ever asks: \(intents)"
        )
        XCTAssertTrue(gate.interceptTrackEnded(), "a synchronised follower waits for the leader's NEXT")
    }

    // MARK: - Regression E: the role survives End Ride without ownership

    func testTheRoleSurvivesEndRideWithoutTransportOwnership() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        await connect(asLeader: false)
        _ = coordinator.rideEpochs.next()
        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashA, seq: 1, effectiveAtSessionUs: clock.now() + Self.c1LeadUs))
        await assertOwnership(.synchronized(.follower), "premise")

        let end = coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        await assertRoleWithoutOwnership("after End Ride")

        await setClock(ready: true)
        await advanceRetries(1)
        clock.advance(to: clock.now() + Self.c1LeadUs)
        await expect("C1 finished") { await self.coordinator.diagnostics.syncState == .synced }
        await assertRoleWithoutOwnership("after the old debt completed")
    }

    // MARK: - Regression F: mirror ordering

    /// The coordinator's own publications, read at the instant each is made: End Ride's ownership
    /// change is mirrored **before** End Ride publishes anything, and no later publication — the old
    /// command's SCHEDULED or SYNCED included — carries or restores the previous ownership. The mirror
    /// is not written by publication at all; this pins that it never appears to be.
    func testNoPublicationAfterEndRideRestoresOwnershipAndEndRideIsMirroredBeforeItIsPublished() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        let log = PublicationLog()
        let mirror = coordinator.transportOwnership
        await coordinator.setDiagnosticsObserver { value in log.record(value.syncState, mirror.current) }

        let endedAt = await followerFinishesAcceptedDebtAfterEndRide(log: log)
        let publications = log.entries
        let beforeEnd = publications[..<endedAt]
        XCTAssertTrue(
            beforeEnd.contains { $0.ownership == .synchronized(.follower) },
            "premise: C1's acceptance was published as synchronised"
        )
        let afterEnd = publications[endedAt...]
        XCTAssertEqual(afterEnd.first?.syncState, .inactive, "End Ride's own publication comes first")
        XCTAssertEqual(afterEnd.first?.ownership, .local, "End Ride was published before it was mirrored")
        XCTAssertTrue(afterEnd.contains { $0.syncState == .scheduled }, "premise")
        XCTAssertTrue(afterEnd.contains { $0.syncState == .synced }, "premise")
        let restored = afterEnd.filter { $0.ownership != .local }
        XCTAssertTrue(restored.isEmpty, "a post-End publication restored ownership: \(restored)")
    }

    // MARK: - Fresh-fix audit

    /// The gate's read is on the main actor and its forwarded press runs in a `Task`. A press
    /// intercepted while synchronised whose `Task` reaches the coordinator after End Ride must be
    /// refused where the authority would be created: that press would otherwise admit a brand-new
    /// ride and stamp fresh authority with the ride over.
    func testAPressInterceptedJustBeforeEndRideIsRefusedWhereTheAuthorityWouldBeCreated() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        await connect(asLeader: false)
        _ = coordinator.rideEpochs.next()
        await setClock(ready: false)
        await deliverAndAwait(play(Self.hashA, seq: 1, effectiveAtSessionUs: clock.now() + Self.c1LeadUs))
        await assertOwnership(.synchronized(.follower), "premise")
        let gate = SyncPlaybackGateAdapter(sync: coordinator)
        let quiet = await wireState()

        XCTAssertTrue(gate.interceptPause(), "premise: intercepted while synchronised")
        // No suspension between that intercept and End Ride: the forwarded `Task` inherits the main
        // actor, so it cannot begin until this test suspends — which it does only by entering the
        // coordinator, where End Ride's synchronous prefix ends ownership before its first `await`.
        let end = coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        await settle(400)
        let calls = await player.calls
        XCTAssertEqual(calls, quiet.calls + [.setRate(DriftController.rateNormal)], "End Ride's own rate restore, and nothing else")
        await player.clearCalls()
        await assertNoSynchronisedEffect(since: WireState(playback: quiet.playback, queue: quiet.queue, enqueued: quiet.enqueued, calls: []))
    }

    /// The guard is for fresh **local** authority only. An authoritative command the leader sends
    /// after this phone's End Ride is the peer's authority: it is accepted, applied, and — exactly as
    /// before A14 — an activation, which C1's completion was not.
    func testAFreshAuthoritativeCommandFromTheLeaderAfterEndRideIsStillAcceptedAndActivates() async {
        await build(localPeerId: SyncTestValues.followerPeerId, withPresenter: false)
        _ = await followerFinishesAcceptedDebtAfterEndRide()

        let pause = PlaybackMessage.pause(
            header: PlaybackCommandHeader(
                commandSeq: 2, effectiveAtSessionUs: clock.now(), issuedBy: SyncTestValues.leaderPeerId,
                queueRevision: 0
            ),
            positionMs: 500
        )
        await deliverAndAwait(pause)
        await expect("the leader's fresh PAUSE was applied") { await self.coordinator.lastAppliedSeq == 2 }
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.staleCommandCount, 0)
        XCTAssertEqual(diagnostics.duplicateCommandCount, 0)
        XCTAssertEqual(diagnostics.staleRevisionCount, 0)
        await assertOwnership(.synchronized(.follower), "the peer's fresh authority is an activation")
    }

    // MARK: - The scenario

    /// Ride 1: this follower accepts C1 while its clock is untrusted, so C1 is held as accepted debt
    /// (ADR-024 Amendment A13) and synchronised mode is active. End Ride. Then the clock recovers and
    /// C1 finishes after the ride: the drain represents it and publishes SCHEDULED, its start runs
    /// (parked, and asserted while parked), and `markSynced` publishes SYNCED. Ownership is asserted
    /// local at every one of those points.
    ///
    /// - Returns: `presented.count` (or `log.entries.count`) at the instant End Ride began, so a
    ///   caller can split what was published before it from what was published after it.
    private func followerFinishesAcceptedDebtAfterEndRide(log: PublicationLog? = nil) async -> Int {
        await connect(asLeader: false)
        _ = coordinator.rideEpochs.next()
        await setClock(ready: false)
        let deadline = clock.now() + Self.c1LeadUs
        await deliverAndAwait(play(Self.hashA, seq: 1, effectiveAtSessionUs: deadline))
        let held = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
        XCTAssertEqual(held, [1], "premise: C1 accepted and held as distributed debt")
        await assertOwnership(.synchronized(.follower), "premise: accepting C1 is an activation")
        await drainPresented()

        let endedAt = log?.entries.count ?? presented.count
        let end = coordinator.rideEpochs.next()
        _ = await coordinator.endRideSegment(rideEpoch: end)
        await assertOwnership(.local, "End Ride ended transport ownership")
        let kept = await coordinator.deferredEvents.map(\.acceptedCommandSeq)
        XCTAssertEqual(kept, [1], "End Ride kept C1")
        // End Ride's own, deliberately unfenced, return to exactly 1.0 (ADR-024 A4 §D). Cleared so
        // what follows is C1's effect and nothing else.
        let endRideCalls = await player.calls
        XCTAssertEqual(endRideCalls, [.setRate(DriftController.rateNormal)])
        await player.clearCalls()

        await setClock(ready: true)
        await advanceRetries(1)
        await expect("C1 represented and SCHEDULED after End Ride") {
            let calls = await self.player.calls
            let state = await self.coordinator.diagnostics.syncState
            return calls == FakeSyncPlayer.preRoll(Self.hashA, 0) && state == .scheduled
        }
        await assertOwnership(.local, "SCHEDULED is not an activation")

        await player.gateCalls { $0 == .start }
        clock.advance(to: deadline)
        await expect("C1's start is running") { await self.player.isGateParked }
        await assertOwnership(.local, "an old command's effect in flight is not an activation")
        await player.releaseGate()
        await expect("C1 reported SYNCED") { await self.coordinator.diagnostics.syncState == .synced }
        await assertOwnership(.local, "SYNCED is not an activation")
        await drainPresented()
        return endedAt
    }

    // MARK: - Assertions

    /// All readers of transport ownership, which must always agree: the coordinator's one derivation,
    /// its actor-isolated answer, its synchronous mirror, and the presenter.
    private func assertOwnership(
        _ expected: TransportOwnership, _ message: String, file: StaticString = #filePath, line: UInt = #line
    ) async {
        let derived = await coordinator.currentTransportOwnership
        XCTAssertEqual(derived, expected, "coordinator: \(message)", file: file, line: line)
        let answered = await coordinator.isSynchronizedModeActive()
        XCTAssertEqual(answered, expected.isSynchronizedModeActive, "isSynchronizedModeActive: \(message)", file: file, line: line)
        XCTAssertEqual(coordinator.transportOwnership.current, expected, "mirror: \(message)", file: file, line: line)
        if let presenter {
            XCTAssertEqual(
                presenter.isSynchronizedModeActive, expected.isSynchronizedModeActive, "presenter: \(message)",
                file: file, line: line
            )
        }
    }

    private func assertRoleWithoutOwnership(_ when: String, file: StaticString = #filePath, line: UInt = #line) async {
        let role = await coordinator.role
        XCTAssertEqual(role, .follower, "the role must survive End Ride (\(when))", file: file, line: line)
        let published = await coordinator.diagnostics.role
        XCTAssertEqual(published, .follower, "and be published (\(when))", file: file, line: line)
        await assertOwnership(.local, "role != nil without ownership (\(when))", file: file, line: line)
    }

    private struct WireState {
        let playback: Int
        let queue: Int
        let enqueued: Int
        let calls: [FakeSyncPlayer.Call]
    }

    private func wireState() async -> WireState {
        WireState(
            playback: await session.playbackMessages().count,
            queue: await session.queueMessages().count,
            enqueued: await coordinator.diagnostics.outboundEnqueuedCount,
            calls: await player.calls
        )
    }

    private func assertNoSynchronisedEffect(since before: WireState, file: StaticString = #filePath, line: UInt = #line) async {
        let after = await wireState()
        let sent = await session.playbackMessages()
        XCTAssertEqual(after.playback, before.playback, "a synchronised frame reached the wire: \(sent)", file: file, line: line)
        XCTAssertEqual(after.queue, before.queue, "a queue frame reached the wire", file: file, line: line)
        XCTAssertEqual(after.enqueued, before.enqueued, "a frame was admitted to the outbound path", file: file, line: line)
        XCTAssertEqual(after.calls, before.calls, "the synchronised session touched the player", file: file, line: line)
    }

    // MARK: - Fixtures

    private func build(localPeerId: PeerId, withPresenter: Bool) async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock(startUs: Self.anchorUs)
        let clock = clock!
        let ids = IdSequence(start: 900)
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: localPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: FakeRouteState(),
            nextQueueItemId: { ids.next() }
        )
        await coordinator.start()
        for hash in [Self.hashA, Self.hashB] {
            await content.addLocal(hash)
            await content.addPeer(hash)
        }
        guard withPresenter else { return }
        presenter = SyncPlaybackPresenter(coordinator: coordinator) { [weak self] value in
            guard let self else { return }
            let active = self.presenter?.isSynchronizedModeActive ?? false
            self.presented.append(PresentedSnapshot(syncState: value.syncState, role: value.role, presenterActive: active))
        }
        await expect("the presenter's observers are installed") {
            let diagnostics = await self.coordinator.onDiagnosticsChanged != nil
            let queue = await self.coordinator.onQueueChanged != nil
            return diagnostics && queue
        }
    }

    /// Lets every publication already made reach the presenter, so `presented.count` is an honest
    /// boundary. A no-op without a presenter.
    private func drainPresented() async {
        guard let presenter else { return }
        let target = await coordinator.diagnostics
        await expectMain("the presenter caught up") { presenter.diagnostics == target }
    }

    private func setClock(ready: Bool) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: ready))
    }

    private func connect(asLeader: Bool) async {
        await session.setGeneration(1)
        await setClock(ready: true)
        await coordinator.handleConnected(isLocalLeader: asLeader)
        await player.clearCalls()
    }

    private func play(_ hash: ContentHash, seq: Int64, effectiveAtSessionUs: Int64) -> PlaybackMessage {
        .play(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAtSessionUs,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
            ),
            trackHash: hash, positionMs: 0, queueItemId: SyncTestValues.ulid(Int(seq))
        )
    }

    private func deliverAndAwait(_ message: PlaybackMessage) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message, generation: 1)
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

    private func expectMain(_ description: String, _ condition: @escaping @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }
}

/// The coordinator's publications in order, each paired with the mirror read inside the same actor
/// step. Written synchronously from the observer, so it is lock-protected rather than an actor.
private final class PublicationLog: @unchecked Sendable {
    struct Entry: Equatable {
        let syncState: SyncState
        let ownership: TransportOwnership
    }

    private let lock = NSLock()
    private var recorded: [Entry] = []

    var entries: [Entry] { lock.withLock { recorded } }

    func record(_ syncState: SyncState, _ ownership: TransportOwnership) {
        lock.withLock { recorded.append(Entry(syncState: syncState, ownership: ownership)) }
    }
}

private extension PlaybackMessage {
    var commandSeq: Int64? {
        switch self {
        case .play(let header, _, _, _), .pause(let header, _), .resume(let header, _), .seek(let header, _),
             .next(let header), .previous(let header):
            return header.commandSeq
        default:
            return nil
        }
    }

    var isPlay: Bool { if case .play = self { true } else { false } }
    var isPause: Bool { if case .pause = self { true } else { false } }
    var isSeek: Bool { if case .seek = self { true } else { false } }
    var isNext: Bool { if case .next = self { true } else { false } }
}
