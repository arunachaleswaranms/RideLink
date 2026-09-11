import Foundation
import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// The six regressions ADR-024 Amendment A1 — the Phase 5 closure audit — exists for. Each test
/// fails on the code as Phase 5 shipped and passes on the code as amended; the comment on each names
/// the exact defect it pins.
///
/// **Deterministic throughout** (Amendment A1's own test rule). `FakeMonotonicClock` is the only
/// clock, the ingress bound is *injected* rather than raced, `FakeSyncPlayer.gateCalls` lands a
/// supersession strictly inside a suspension, and every wait is on a counter the coordinator
/// publishes rather than on a fixed number of yields.
///
/// The mirror is `com.ridelink.app.sync.SyncPlaybackClosureAuditTest`, asserting the same six
/// properties against the same pure tables (`protocol/vectors/phase5-gates/`).
final class SyncPlaybackClosureAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 700

    private func build(inboundCapacity: Int = 256, deferredCommandCapacity: Int = 16) async {
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
            inboundCapacity: inboundCapacity,
            deferredCommandCapacity: deferredCommandCapacity
        )
        await coordinator.start()
    }

    private func connect(asLeader: Bool, clockReady: Bool = true) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: clockReady))
        await coordinator.handleConnected(isLocalLeader: asLeader)
        await awaitOutboundQuiescent()
        await player.clearCalls()
        await session.clearSent()
    }

    override func tearDown() async throws {
        await coordinator?.shutdown()
        coordinator = nil
    }

    // MARK: - Finding A — a follower's first Play must not lose a race with its own queue add

    /// **The defect.** `playSynchronized` awaited `ensureQueued`, which sent a `QUEUE_ADD` intent and
    /// returned immediately, and then issued the `PLAY` **carrying the revision it still held** —
    /// revision 0. The leader accepted the add (revision → 1) and then refused the `PLAY` for a stale
    /// revision. The user's first press did nothing.
    func testAFollowersPlayForAnUnqueuedTrackWaitsForTheAuthoritativeRevision() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))

        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        let adds = await session.queueMessages().compactMap { message -> (QueueCommandHeader, [QueueAddItem])? in
            guard case .add(let header, let items) = message else { return nil }
            return (header, items)
        }
        XCTAssertEqual(adds.count, 1)
        XCTAssertEqual(adds[0].0.queueRevision, 0, "the add legitimately carries the revision the follower holds")
        var plays = await playsSent()
        XCTAssertTrue(plays.isEmpty, "the PLAY must not be sent against a revision the leader has already moved past")
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .waitingForQueue)

        // The leader accepts the add and broadcasts revision 1.
        let queueItemId = adds[0].1[0].queueItemId
        await deliverAndAwait(snapshot(revision: 1, items: [item(queueItemId, SyncTestValues.hash(1))]))

        plays = await playsSent()
        XCTAssertEqual(plays.count, 1)
        XCTAssertEqual(plays[0].header.commandSeq, PlaybackBounds.unassignedCommandSeq, "still an intent")
        XCTAssertEqual(plays[0].header.queueRevision, 1, "the intent carries the authoritative revision")
        XCTAssertEqual(plays[0].queueItemId, queueItemId, "the issuer-minted id survives the wait")
        let settled = await coordinator.diagnostics
        XCTAssertEqual(settled.resumedPendingPlayCount, 1, "one press produced one Play")
        XCTAssertEqual(settled.staleRevisionCount, 0, "nothing in this valid flow is stale")
    }

    func testASnapshotThatDoesNotNameTheTrackLeavesThePlayWaiting() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        await deliverAndAwait(snapshot(revision: 1, items: [item(SyncTestValues.ulid(99), SyncTestValues.hash(2))]))
        let stillWaiting = await playsSent()
        XCTAssertTrue(stillWaiting.isEmpty, "the Play's own item is still not authoritative")
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .waitingForQueue)

        let queueItemId = await firstAddedQueueItemId()
        await deliverAndAwait(snapshot(revision: 2, items: [item(queueItemId, SyncTestValues.hash(1))]))
        let plays = await playsSent()
        XCTAssertEqual(plays.count, 1)
        XCTAssertEqual(plays[0].header.queueRevision, 2)
    }

    /// brief §18: a session boundary cancels the retained Play, and the snapshot that would have
    /// settled it is inert.
    func testASessionBoundaryWhileAPlayWaitsForTheQueueLeavesItInertForever() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()
        let queueItemId = await firstAddedQueueItemId()

        await coordinator.handleLinkLost()
        let cancelled = await coordinator.diagnostics.cancelledPendingPlayCount
        XCTAssertEqual(cancelled, 1)

        await session.setGeneration(2)
        await connect(asLeader: false)
        await deliverAndAwait(snapshot(revision: 1, items: [item(queueItemId, SyncTestValues.hash(1))]))
        let plays = await playsSent()
        XCTAssertTrue(plays.isEmpty, "a dead session's Play may never execute")
        let resumed = await coordinator.diagnostics.resumedPendingPlayCount
        XCTAssertEqual(resumed, 0)
    }

    // MARK: - Finding B — the leader's semantic order is the wire-visible order

    /// **The defect.** `await session.channel.send(…)` sat between the step that stamped a frame and
    /// the frame reaching the transport — an actor **re-entrancy point**. A `PLAY` stamped for
    /// revision *n* could therefore reach the wire ahead of the `QUEUE_SNAPSHOT` that created
    /// revision *n*, and the peer would refuse the valid command for a revision it had not been told
    /// about yet. Actor isolation alone did not prevent it; that is the point of the finding.
    ///
    /// The assertion is the strongest available: **replay the leader's own outbound stream through the
    /// follower's stale-revision rule.** If the order the leader chose is a valid order, nothing in
    /// that replay is ever rejected. Repeated, because on this platform the interleaving is a genuine
    /// race rather than a scheduler the test controls.
    func testAQueueMutationAndAPlaybackCommandNeverCrossTheWireInAnOrderThePeerWouldReject() async {
        for repetition in 0 ..< 60 {
            await build()
            await connect(asLeader: true)
            for seed in 1 ... 3 {
                await content.addLocal(SyncTestValues.hash(seed))
                await content.addPeer(SyncTestValues.hash(seed))
            }
            await coordinator.enqueue(SyncTestValues.hash(1))
            await coordinator.enqueue(SyncTestValues.hash(2))
            await awaitOutboundQuiescent()
            let baseline = await coordinator.queueState.revision
            await session.clearSent()

            let doomed = await coordinator.queueState.items[0].queueItemId
            let target = coordinator!
            // Genuinely concurrent: two unstructured tasks racing into the same actor, which is the
            // interleaving the defect needed.
            async let first: Void = {
                if repetition % 2 == 0 {
                    await target.removeFromQueue(doomed)
                } else {
                    await target.next()
                }
            }()
            async let second: Void = {
                if repetition % 2 == 0 {
                    await target.next()
                } else {
                    await target.removeFromQueue(doomed)
                }
            }()
            async let third: Void = target.seek(positionMs: 12_000)
            async let fourth: Void = target.enqueue(SyncTestValues.hash(3))
            _ = await (first, second, third, fourth)
            await awaitOutboundQuiescent()

            await assertWireOrderAcceptable(label: "repetition \(repetition)", baselineRevision: baseline)
            await coordinator.shutdown()
        }
    }

    /// Replays this leader's outbound stream through the follower's own PROTOCOL §5 rule 3 / §9
    /// check: a snapshot sets the revision the peer holds, and an authoritative playback command must
    /// name exactly that revision or the peer refuses it.
    private func assertWireOrderAcceptable(label: String, baselineRevision: Int64) async {
        var revisionKnownToPeer = baselineRevision
        var sawAuthoritative = false
        for frame in await session.sent {
            if let queueMessage = frame as? QueueMessage, case .snapshot(let revision, _, _) = queueMessage {
                revisionKnownToPeer = revision
            }
            guard let playback = frame as? PlaybackMessage,
                  let header = SyncPlaybackCoordinator.headerOf(playback),
                  header.commandSeq != PlaybackBounds.unassignedCommandSeq else { continue }
            sawAuthoritative = true
            XCTAssertEqual(
                header.queueRevision, revisionKnownToPeer,
                "\(label): command_seq \(header.commandSeq) names revision \(header.queueRevision) but the peer "
                    + "has only been told about \(revisionKnownToPeer) — the peer would refuse a valid command"
            )
        }
        XCTAssertTrue(sawAuthoritative, "\(label): the test must actually have produced an authoritative command")
    }

    // MARK: - Finding C — a frame accepted from TCP cannot vanish from the local handoff

    /// **The defect.** The inbound handoff was `.bufferingNewest(256)`, which evicts the *oldest*
    /// element. A `PLAY` could be evicted while the `PAUSE` behind it survived; `CommandOrderGate`
    /// would legitimately accept the `PAUSE`, and the follower would pause a track it never loaded.
    ///
    /// This is Amendment A1's option **A**: with room for both, both are applied, in order.
    func testTwoAuthoritativeCommandsBehindAStalledConsumerAreBothAppliedInOrder() async {
        await build(inboundCapacity: 2)
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))

        // Stall the consumer strictly inside the decoder pre-roll, holding the PLAY in flight.
        await player.gateCalls { call in
            if case .load = call { return true }
            return false
        }
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now()))
        await expect("the consumer parks inside the decoder load") { await self.player.isGateParked }

        await session.deliver(pauseCommand(seq: 2, effectiveAt: clock.now(), positionMs: 5_000))
        await session.deliver(resumeCommand(seq: 3, effectiveAt: clock.now(), positionMs: 6_000))
        await settle()

        await player.releaseGate()
        // The RESUME's own *effect* is the signal: `lastAppliedSeq` moves before the scheduled action
        // runs, so waiting on the counter would assert before the player had been driven.
        await expect("all three commands reach the player") { await self.player.calls.contains(.seek(6_000)) }

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 3, "all three applied")
        XCTAssertEqual(diagnostics.inboundOverflowCount, 0, "nothing was refused")
        XCTAssertFalse(diagnostics.ingressDesynchronized)
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.load(SyncTestValues.hash(1))), "the PLAY was not lost")
        guard let pauseIndex = calls.firstIndex(of: .pause),
              let resumeSeekIndex = calls.firstIndex(of: .seek(6_000)) else {
            return XCTFail("expected a pause and a resume seek, got \(calls)")
        }
        XCTAssertLessThan(pauseIndex, resumeSeekIndex, "arrival order survived: PAUSE(2) before RESUME(3)")
    }

    /// Amendment A1's option **B**: when the bound is genuinely reached by frames that cannot be
    /// superseded, the refusal is **explicit** and synchronisation halts. The `PAUSE` that followed
    /// the refused frame is **not** applied — a `PAUSE` treated as coherent state without the `PLAY`
    /// that preceded it is precisely the incoherence this finding is about.
    func testAnIngressOverflowIsExplicitHaltsSynchronisationAndAppliesNothingIncoherent() async {
        await build(inboundCapacity: 1)
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))

        await player.gateCalls { call in
            if case .load = call { return true }
            return false
        }
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now()))
        await expect("the consumer parks inside the decoder load") { await self.player.isGateParked }

        // One fits; the second has nowhere to go and is refused rather than evicting the first.
        await session.deliver(pauseCommand(seq: 2, effectiveAt: clock.now(), positionMs: 5_000))
        await session.deliver(resumeCommand(seq: 3, effectiveAt: clock.now(), positionMs: 6_000))
        await settle()

        await player.releaseGate()
        await expect("the halt is latched") { await self.coordinator.diagnostics.ingressDesynchronized }

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.inboundOverflowCount, 1, "the refusal is counted")
        XCTAssertEqual(diagnostics.syncState, .desynchronized)
        XCTAssertEqual(diagnostics.lastReceivedCommandSeq, 1, "the halt spends no sequence number")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "a PAUSE behind a refused frame is never applied as coherent state")
    }

    /// And the halt ends only on authoritative full state, which restores what the halt could not
    /// apply.
    func testAuthoritativeFullStateReconcilesAHaltedFollowerAndRestoresPlayback() async {
        await build(inboundCapacity: 1)
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await player.gateCalls { call in
            if case .load = call { return true }
            return false
        }
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now()))
        await expect("parked") { await self.player.isGateParked }
        await session.deliver(pauseCommand(seq: 2, effectiveAt: clock.now(), positionMs: 5_000))
        await session.deliver(resumeCommand(seq: 3, effectiveAt: clock.now(), positionMs: 6_000))
        await settle()
        await player.releaseGate()
        await expect("halted") { await self.coordinator.diagnostics.ingressDesynchronized }

        // A further incremental command changes nothing while the halt is in force.
        await deliverAndAwait(pauseCommand(seq: 4, effectiveAt: clock.now(), positionMs: 9_000))
        let halted = await coordinator.diagnostics.lastReceivedCommandSeq
        XCTAssertEqual(halted, 1, "still halted, still spending nothing")

        // One at a time: this coordinator's ingress bound is **1**, so two reconciliation frames
        // offered back to back would have the second refused for want of room. That is a real
        // property of a bounded queue; at the production bound of 256 (with latest-wins coalescing)
        // any non-pathological link provides the space.
        await player.clearCalls()
        await deliverAndAwait(snapshot(revision: 3, items: [item(SyncTestValues.ulid(1), SyncTestValues.hash(1))]))
        await deliverAndAwait(
            .playbackState(
                commandSeq: 7, queueRevision: 3, trackHash: SyncTestValues.hash(1),
                queueItemId: SyncTestValues.ulid(1), positionMs: 12_000, playing: true, atSessionUs: clock.now()
            )
        )

        let diagnostics = await coordinator.diagnostics
        XCTAssertFalse(diagnostics.ingressDesynchronized, "authoritative full state is what ends the halt")
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 7, "ordering resumes from the authoritative value")
        XCTAssertEqual(diagnostics.queueRevision, 3)
        let prepared = await player.calls
        XCTAssertTrue(
            prepared.contains(.load(SyncTestValues.hash(1))) && prepared.contains(.seek(12_000)),
            "the snapshot loads what the halt could not — PROTOCOL §5 rule 2 applies its past instant immediately"
        )
        // The *start* leaves by the ordered scheduled-action chain (Finding G), which is asynchronous
        // relative to the frame's processing — deliberately, since that chain is what preserves
        // authoritative order — so it is waited for rather than read.
        await expect("the restored track starts") { await self.player.calls.contains(.start) }

        // And incremental commands are trusted again, at the revision reconciliation established.
        await deliverAndAwait(pauseCommand(seq: 8, effectiveAt: clock.now(), positionMs: 15_000, queueRevision: 3))
        let resumedSeq = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(resumedSeq, 8)
    }

    /// Coalescing is what keeps the halt above from firing under a peer's ordinary cadence.
    func testLatestWinsFramesCoalesceInsteadOfRefusingAnAuthoritativeCommand() async {
        await build(inboundCapacity: 1)
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await player.gateCalls { call in
            if case .load = call { return true }
            return false
        }
        await session.deliver(playCommand(seq: 1, effectiveAt: clock.now()))
        await expect("parked") { await self.player.isGateParked }

        for index in 0 ..< 4 {
            await session.deliver(
                .positionReport(
                    trackHash: SyncTestValues.hash(1), positionMs: Int64(1_000 * index),
                    atSessionUs: clock.now() + Int64(index), playing: true, playbackRate: 1.0
                )
            )
        }
        await settle()
        await player.releaseGate()
        // The coalescing is *observed by the consumer* at the top of its next iteration (that is what
        // makes a halt take effect before the frame that follows a refusal), so the counter is the
        // signal to wait on rather than something to read immediately.
        await expect("the coalescing is observed") { await self.coordinator.diagnostics.inboundCoalescedCount == 3 }

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 1, "the PLAY applied")
        XCTAssertEqual(diagnostics.inboundOverflowCount, 0, "no authoritative frame was refused")
        XCTAssertFalse(diagnostics.ingressDesynchronized)
    }

    // MARK: - Finding G — the apply path must preserve authoritative order too

    /// **The defect, found by stress-running this suite's own Finding C regression** — 2 failures in
    /// 100, reproduced twice with the same shape.
    ///
    /// Every accepted command's audible effect was armed as its own `Task`, and Swift makes no
    /// guarantee that independently created tasks run in creation order — the *same* fact that made
    /// the inbound `Task`-per-frame shape a defect. `PAUSE(n)` and `RESUME(n+1)`, a pair the leader
    /// stamps microseconds apart so both deadlines have passed on arrival, could therefore take
    /// effect in **either** order: the exact opposite of what `command_seq` is for.
    ///
    /// The gate makes it deterministic: the `PAUSE`'s player call is parked, the `RESUME` is
    /// delivered and fully processed while it is parked, and only then is the `PAUSE` released.
    func testTwoCommandsWhoseDeadlinesHaveBothPassedTakeEffectInAuthoritativeOrder() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await deliverAndAwait(playCommand(seq: 1, effectiveAt: clock.now()))
        await expect("the PLAY started") { await self.player.calls.contains(.start) }
        await player.clearCalls()

        // Park the PAUSE strictly inside the player, then let the RESUME arrive behind it.
        await player.gateCalls { $0 == .pause }
        await deliverAndAwait(pauseCommand(seq: 2, effectiveAt: clock.now(), positionMs: 5_000))
        await expect("the PAUSE's effect began") { await self.player.isGateParked }

        await deliverAndAwait(resumeCommand(seq: 3, effectiveAt: clock.now(), positionMs: 6_000))
        let midFlight = await player.calls
        XCTAssertFalse(
            midFlight.contains(.seek(6_000)),
            "the RESUME may not overtake a PAUSE whose effect is still in flight, got \(midFlight)"
        )

        await player.releaseGate()
        await expect("the RESUME completed") { await self.player.calls.contains(.seek(6_000)) }

        let calls = await player.calls
        guard let pauseSeek = calls.firstIndex(of: .seek(5_000)),
              let resumeSeek = calls.firstIndex(of: .seek(6_000)) else {
            return XCTFail("expected both seeks, got \(calls)")
        }
        XCTAssertLessThan(pauseSeek, resumeSeek, "authoritative order held all the way to the player: \(calls)")
        let applied = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(applied, 3)
    }

    /// A superseded link never holds the chain up: its ownership proof fails and it returns at once.
    func testASupersededScheduledActionDoesNotStallTheOnesBehindIt() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addLocal(SyncTestValues.hash(2))

        let firstAt = clock.now() + 400_000
        await deliverAndAwait(playCommand(seq: 1, effectiveAt: firstAt, seed: 1))
        let secondAt = clock.now() + 500_000
        await deliverAndAwait(playCommand(seq: 2, effectiveAt: secondAt, seed: 2))
        await player.clearCalls()

        clock.advance(to: secondAt)
        await expect("the surviving epoch started on time") { await self.player.calls.contains(.start) }
        let current = await coordinator.diagnostics.currentTrackHash
        XCTAssertEqual(current, SyncTestValues.hash(2))
    }

    // MARK: - Finding D — an accepted command is not an applied command

    /// **The defect.** `onInboundCommand` set `lastAppliedSeq` and *then* consulted the clock. An
    /// estimator that was momentarily untrusted therefore spent the sequence number and applied
    /// nothing — and the leader's replay of that command was then correctly dropped as a duplicate.
    /// The command was lost **permanently**, on a condition that resolves itself in milliseconds.
    func testACommandAcceptedWhileTheClockIsUnreadyIsHeldRatherThanLost() async {
        await build()
        await connect(asLeader: false, clockReady: false)
        await content.addLocal(SyncTestValues.hash(1))

        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now() + 200_000))

        var diagnostics = await coordinator.diagnostics
        let untouched = await player.calls
        XCTAssertTrue(untouched.isEmpty, "nothing is scheduled against an untrusted clock")
        XCTAssertNil(diagnostics.lastAppliedCommandSeq, "the command is not recorded as applied")
        XCTAssertEqual(diagnostics.lastReceivedCommandSeq, 10, "it *is* recorded as accepted — that is the distinction")
        XCTAssertEqual(diagnostics.deferredCommandCount, 1)
        XCTAssertEqual(diagnostics.syncState, .clockUnready)

        // A replay while it is held is a duplicate, not a second copy.
        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now() + 200_000))
        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.duplicateCommandCount, 1)
        XCTAssertEqual(diagnostics.deferredCommandCount, 1)

        // The estimator recovers; the held command is applied exactly once.
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
        // The effect, not the counter that precedes it: `lastAppliedSeq` moves before
        // `applyAuthoritative` drives the player, so waiting on it would assert too early.
        await expect("the held PLAY reaches the player") { await self.player.calls.contains(.load(SyncTestValues.hash(1))) }

        diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.lastAppliedCommandSeq, 10, "applied at the point it actually took effect")
        XCTAssertEqual(diagnostics.deferredCommandCount, 0)
        XCTAssertEqual(diagnostics.recoveredCommandCount, 1)
        let prepares = await player.calls.filter { call in
            if case .load = call { return true }
            return false
        }
        XCTAssertEqual(prepares.count, 1, "exactly once, never twice")
    }

    /// Authoritative order survives the wait: `PLAY(n)` then `PAUSE(n+1)` may not become `PAUSE`
    /// alone.
    func testCommandsHeldForTheClockAreAppliedInAuthoritativeOrder() async {
        await build()
        await connect(asLeader: false, clockReady: false)
        await content.addLocal(SyncTestValues.hash(1))

        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now()))
        await deliverAndAwait(pauseCommand(seq: 11, effectiveAt: clock.now(), positionMs: 4_000))
        let heldCount = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(heldCount, 2)
        let untouched = await player.calls
        XCTAssertTrue(untouched.isEmpty)

        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
        // Wait for both *effects*, in order — the PAUSE is the later one, so it is the signal.
        await expect("both held commands reach the player") { await self.player.calls.contains(.pause) }

        let calls = await player.calls
        let prepared = calls.firstIndex { call in
            if case .load = call { return true }
            return false
        }
        let paused = calls.firstIndex(of: .pause)
        guard let prepared, let paused else {
            return XCTFail("expected both a load and a pause, got \(calls)")
        }
        XCTAssertGreaterThan(paused, prepared, "the PAUSE took effect after the PLAY, exactly as ordered")
        let recovered = await coordinator.diagnostics.recoveredCommandCount
        XCTAssertEqual(recovered, 2)
    }

    func testASessionBoundaryWhileCommandsAreHeldLeavesThemInertForever() async {
        await build()
        await connect(asLeader: false, clockReady: false)
        await content.addLocal(SyncTestValues.hash(1))
        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now()))
        await deliverAndAwait(pauseCommand(seq: 11, effectiveAt: clock.now(), positionMs: 4_000))
        let held = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(held, 2)

        await coordinator.handleLinkLost()
        await player.clearCalls()
        let cleared = await coordinator.diagnostics.deferredCommandCount
        XCTAssertEqual(cleared, 0)

        await session.setGeneration(2)
        await connect(asLeader: false)
        clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs * 4)
        await settle()
        let calls = await player.calls
        XCTAssertTrue(calls.isEmpty, "an old session's held commands may never touch the new session's player")
        let applied = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertNil(applied)
    }

    func testOverflowingTheHeldCommandBufferHaltsRatherThanDropping() async {
        await build(deferredCommandCapacity: 1)
        await connect(asLeader: false, clockReady: false)
        await content.addLocal(SyncTestValues.hash(1))

        await deliverAndAwait(playCommand(seq: 10, effectiveAt: clock.now()))
        await deliverAndAwait(pauseCommand(seq: 11, effectiveAt: clock.now(), positionMs: 4_000))

        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.deferredCommandCount, 1, "the buffer is bounded and the bound is real")
        XCTAssertEqual(diagnostics.inboundOverflowCount, 1)
        XCTAssertTrue(diagnostics.ingressDesynchronized, "the refusal halts rather than losing the command quietly")
        XCTAssertEqual(diagnostics.lastReceivedCommandSeq, 10, "the refused command spends no sequence number")
    }

    // MARK: - Finding E — one Play request survives a Phase 4 transfer

    /// **The defect.** `gateContent` requested the transfer and returned false, and the user action
    /// then simply ended. When the transfer verified, nothing was retained to reschedule.
    func testOnePlayForContentOnlyThePeerHoldsBecomesAPlayByItselfOnceTheTransferVerifies() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))

        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        var requests = await content.transferRequests
        XCTAssertEqual(requests, [SyncTestValues.hash(1)], "asked once, through Phase 4")
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .waitingForContent)
        let before = await playsSent()
        XCTAssertTrue(before.isEmpty, "nothing may play before it is verified here")

        await content.completeTransfer(SyncTestValues.hash(1))
        await expect("the retained Play issues by itself") { await self.playsSent().count == 1 }
        await awaitOutboundQuiescent()

        let plays = await playsSent()
        XCTAssertEqual(plays[0].trackHash, SyncTestValues.hash(1))
        XCTAssertEqual(plays[0].header.commandSeq, PlaybackBounds.firstCommandSeq, "a new authoritative command")
        XCTAssertGreaterThan(plays[0].header.effectiveAtSessionUs, clock.now(), "and a fresh instant, never a reused one")
        let resumed = await coordinator.diagnostics.resumedPendingPlayCount
        XCTAssertEqual(resumed, 1)
        requests = await content.transferRequests
        XCTAssertEqual(requests, [SyncTestValues.hash(1)], "Phase 4 was still only asked once")
    }

    func testASupersededPendingPlayNeverResurrects() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))
        await content.addPeer(SyncTestValues.hash(2))
        await content.addLocal(SyncTestValues.hash(2))

        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()
        await coordinator.playSynchronized(SyncTestValues.hash(2))
        await awaitOutboundQuiescent()
        var hashes = await playsSent().map(\.trackHash)
        XCTAssertEqual(hashes, [SyncTestValues.hash(2)], "the newer request plays")

        await content.completeTransfer(SyncTestValues.hash(1))
        await settle()
        hashes = await playsSent().map(\.trackHash)
        XCTAssertEqual(
            hashes, [SyncTestValues.hash(2)],
            "a superseded request may never resurrect, even for a track that is now available"
        )
    }

    func testLeavingSynchronizedModeCancelsAPendingPlay() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        await coordinator.leaveSynchronizedMode()
        let cancelled = await coordinator.diagnostics.cancelledPendingPlayCount
        XCTAssertEqual(cancelled, 1)

        await content.completeTransfer(SyncTestValues.hash(1))
        await settle()
        let plays = await playsSent()
        XCTAssertTrue(plays.isEmpty, "a transfer completing must not start music the user has stopped asking for")
    }

    func testASessionBoundaryCancelsAPendingPlayAwaitingContent() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        await coordinator.handleLinkLost()
        await session.setGeneration(2)
        await connect(asLeader: true)

        await content.completeTransfer(SyncTestValues.hash(1))
        await settle()
        let plays = await playsSent()
        XCTAssertTrue(plays.isEmpty, "a dead session's Play may never execute")
    }

    /// brief §20 case 5: a failed transfer starts nothing and says so, rather than pretending.
    func testAFailedTransferStartsNothingAndKeepsSayingItIsWaiting() async {
        await build()
        await connect(asLeader: true)
        await content.addPeer(SyncTestValues.hash(1))
        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()

        await content.failTransfer()
        await settle()

        let plays = await playsSent()
        XCTAssertTrue(plays.isEmpty, "nothing may start")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.syncState, .waitingForContent, "and the state stays honest")
        XCTAssertEqual(diagnostics.resumedPendingPlayCount, 0)
    }

    func testTheLeadersPlayWaitsForThePeerToHoldTheTrackThenIssuesByItself() async {
        await build()
        await connect(asLeader: true)
        await content.addLocal(SyncTestValues.hash(1))

        await coordinator.playSynchronized(SyncTestValues.hash(1))
        await awaitOutboundQuiescent()
        let before = await playsSent()
        XCTAssertTrue(before.isEmpty, "REQUIREMENTS §9.4: a track the peer lacks cannot start")
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .waitingForContent)

        // ADR-024 §7: the peer reports having verified the transfer we served it.
        await content.peerVerified(SyncTestValues.hash(1))
        await expect("the retained Play issues") { await self.playsSent().count == 1 }
        let plays = await playsSent()
        XCTAssertEqual(plays[0].trackHash, SyncTestValues.hash(1))
    }

    // MARK: - Finding F — a superseded correction has no side effects at all

    /// **The defect, on this platform specifically.** `runIfCurrent` returned `Void`, and
    /// `applyCorrection` mutated `diagnostics.lastCorrection`, incremented `hardSeekCount`, set
    /// `.syncFailed` and awaited `emitPlaybackState()` **after** it — unconditionally. A correction
    /// the guard had refused still had four visible side effects, one of them on the wire.
    ///
    /// Here the supersession lands strictly *inside* the player call, which is the harder half: the
    /// action was authorised when it began and is not when it returns.
    func testACorrectionSupersededInsideItsOwnPlayerCallHasNoStateOrWireEffect() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        await content.addLocal(SyncTestValues.hash(2))
        let anchor = clock.now()
        await deliverAndAwait(playCommand(seq: 1, effectiveAt: anchor))
        await player.clearCalls()
        await session.clearSent()

        // Park the drift ladder's hard seek inside the player.
        await player.gateCalls { call in
            if case .seek = call { return true }
            return false
        }
        await awaitTickArmed()
        let tickAt = clock.pendingDeadlines().max() ?? clock.now()
        await player.setState(
            PlayerState(positionMs: (tickAt - anchor) / 1_000 + 400, durationMs: 600_000, playing: true, rate: 1.0)
        )
        clock.advance(to: tickAt)
        await expect("the correction began while it was still current") { await self.player.isGateParked }
        let before = await coordinator.diagnostics

        // A new PLAY supersedes the epoch while the seek is still in flight.
        await deliverAndAwait(playCommand(seq: 2, effectiveAt: clock.now() + 400_000, seed: 2))
        await player.releaseGate()
        await settle()

        let after = await coordinator.diagnostics
        XCTAssertEqual(after.hardSeekCount, before.hardSeekCount, "a superseded correction may not spend the seek budget")
        XCTAssertEqual(after.lastCorrection, before.lastCorrection, "nor claim to have corrected anything")
        let snapshots = await session.playbackMessages().filter { message in
            if case .playbackState = message { return true }
            return false
        }
        XCTAssertTrue(snapshots.isEmpty, "nor emit authoritative state — and a follower never emits one at all")
    }

    /// The easier half, for completeness: refused *before* the player call, so the player is
    /// untouched too.
    func testACorrectionRefusedBeforeItsPlayerCallTouchesNothing() async {
        await build()
        await connect(asLeader: false)
        await content.addLocal(SyncTestValues.hash(1))
        let anchor = clock.now()
        await deliverAndAwait(playCommand(seq: 1, effectiveAt: anchor))
        await player.clearCalls()

        await coordinator.leaveSynchronizedMode()
        await player.clearCalls()
        let before = await coordinator.diagnostics

        await awaitTickArmed()
        let tickAt = clock.pendingDeadlines().max() ?? clock.now()
        await player.setState(
            PlayerState(positionMs: (tickAt - anchor) / 1_000 + 400, durationMs: 600_000, playing: true, rate: 1.0)
        )
        clock.advance(to: tickAt)
        await settle()

        let after = await coordinator.diagnostics
        let seeks = await player.calls.filter { call in
            if case .seek = call { return true }
            return false
        }
        XCTAssertTrue(seeks.isEmpty, "no player action")
        XCTAssertEqual(after.hardSeekCount, before.hardSeekCount)
        XCTAssertEqual(after.lastCorrection, before.lastCorrection)
    }

    // MARK: - Helpers

    private func playsSent() async -> [(header: PlaybackCommandHeader, trackHash: ContentHash, queueItemId: String)] {
        await session.playbackMessages().compactMap { message in
            guard case .play(let header, let trackHash, _, let queueItemId) = message else { return nil }
            return (header, trackHash, queueItemId)
        }
    }

    private func firstAddedQueueItemId() async -> String {
        for message in await session.queueMessages() {
            if case .add(_, let items) = message, let first = items.first { return first.queueItemId }
        }
        XCTFail("no QUEUE_ADD was sent")
        return ""
    }

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

    /// Waits until the ingress is genuinely **idle** — the consumer parked, which (because a waiter
    /// is only ever stored while the buffer is empty) means everything offered so far has been
    /// dispatched and counted.
    ///
    /// Without this, reading `inboundProcessedCount` before delivering could capture a value the
    /// *previous* frame's in-flight processing was about to advance, and the wait afterwards would be
    /// satisfied by that frame rather than by this one — while this one had actually been refused for
    /// want of room. A 2-in-100 stress failure was exactly that.
    private func awaitIngressIdle() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.isIngressIdle() { return }
            await Task.yield()
        }
        XCTFail("the ingress never became idle")
    }

    /// Waits until every frame the coordinator has enqueued on its one ordered outbound path has
    /// actually been handed to the transport. The signal is the coordinator's own counters.
    private func awaitOutboundQuiescent() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let diagnostics = await coordinator.diagnostics
            // Amendment A2: "attempted", not "sent" — a refused or stale frame is a
            // completed attempt, and the drain is quiescent once it has considered every frame.
            if diagnostics.outboundAttemptCount == diagnostics.outboundEnqueuedCount { return }
            await Task.yield()
        }
        XCTFail("the ordered outbound path never drained")
    }

    /// Waits until the position-report cadence loop has actually **armed** its next deadline in the
    /// fake sleeper.
    ///
    /// `handleConnected` *starts* the loop; the loop computes `now + interval` and parks one task hop
    /// later. A test that advances the clock inside that hop moves time out from under it, so the
    /// deadline it then computes is a whole interval past where the test is looking and the tick
    /// never fires — the wait times out with nothing to show. Found by ADR-024 Amendment A3's stress
    /// run (1 failure in 13 on `testACorrectionSupersededBeforeItsSnapshotEnqueueEmitsNothing`);
    /// `SyncPlaybackDriftTests` already had this helper from A1's harness pass, and this is the same
    /// fix in the two harnesses that lacked it. The production loop is correct throughout — a real
    /// monotonic clock cannot be wound forward out from under a sleeper, and only a fake can.
    /// `PlaybackBounds.positionReportIntervalMs`, in microseconds — the cadence loop's own interval.
    private static let positionReportIntervalUs: Int64 = PlaybackBounds.positionReportIntervalMs * 1_000

    private func awaitTickArmed() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let now = clock.now()
            if clock.pendingDeadlines().contains(where: { $0 <= now + Self.positionReportIntervalUs }) { return }
            await Task.yield()
        }
        XCTFail("the position-report cadence loop never armed a deadline")
    }

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }

    private func snapshot(revision: Int64, items: [SharedQueueItem]) -> QueueMessage {
        .snapshot(queueRevision: revision, items: items, currentIndex: nil)
    }

    private func item(_ queueItemId: String, _ hash: ContentHash) -> SharedQueueItem {
        SharedQueueItem(
            queueItemId: queueItemId, trackHash: hash, addedBy: SyncTestValues.leaderPeerId,
            order: PlaybackBounds.queueOrderStep
        )
    }

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

    private func pauseCommand(seq: Int64, effectiveAt: Int64, positionMs: Int64, queueRevision: Int64 = 0) -> PlaybackMessage {
        .pause(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: queueRevision
            ),
            positionMs: positionMs
        )
    }

    private func resumeCommand(seq: Int64, effectiveAt: Int64, positionMs: Int64) -> PlaybackMessage {
        .resume(
            header: PlaybackCommandHeader(
                commandSeq: seq, effectiveAtSessionUs: effectiveAt,
                issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
            ),
            positionMs: positionMs
        )
    }
}
