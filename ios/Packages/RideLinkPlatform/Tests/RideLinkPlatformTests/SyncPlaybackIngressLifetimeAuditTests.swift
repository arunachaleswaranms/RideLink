import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// The regressions ADR-024 **Amendment A6** — the sixth Phase 5 closure audit — exists for.
///
/// Two findings, one theme: **something that outlives a session must not carry that session's
/// verdict into the next one.**
///
/// ## Finding A — an ingress loss belongs to the generation that caused it
///
/// A1 made the inbound handoff lossless and bounded, and made a refusal *explicit*: a follower that
/// loses an unsupersedable frame stops trusting incremental state until authoritative full state
/// arrives. `Phase5FrameQueue` deliberately survives an authentication boundary — a boundary is
/// expressed by the generation each frame carries, not by tearing the pipe down — but its loss
/// accounting was two cumulative counters the consumer diffed against its own baseline. That
/// difference carried no generation at all, so a frame refused under Session A and observed after
/// Session B activated told Session B that *it* had lost a frame. On a follower that sets
/// `playbackDesynchronized`/`queueDesynchronized`, which decide whether incremental authoritative
/// commands are applied at all — a correctness failure, not a diagnostics one.
///
/// ## Finding B — `failClosedOutbound`'s writes after the rate restore
///
/// `restoreRate` is the one player call in this phase that is deliberately **unfenced** (A4 §D): it
/// is the ending of an authority, it names an absolute 1.0, and ADR-004 says the music keeps
/// playing. What was wrong was reading that exemption as covering everything *after* the call.
/// `failClosedOutbound` awaited it and then wrote seven diagnostics fields, so a boundary landing
/// inside `player.setRate` had Session A's fail-closed verdict overwrite Session B's live state:
/// `.transportFailed` and `outboundAuthorityLost` on a session whose transport was working.
///
/// ## Pre-fix values, recorded against unmodified `2836695e`
///
/// - Finding A: Session B's `ingressDesynchronized` **false → true**, `inboundOverflowCount`
///   **0 → 1**, `syncState` **→ .desynchronized**, caused entirely by Session A's refusal.
/// - Finding B: Session B's `syncState` **.synced → .transportFailed** and `outboundAuthorityLost`
///   **false → true**, when Session A's parked `setRate(1.0)` resumed.
///
/// The mirror is `com.ridelink.app.sync.SyncPlaybackIngressLifetimeAuditTest`. Android is
/// **structurally safe** for Finding B — all three `restoreRate` callers *launch* it rather than
/// awaiting it, so the verdict is one uninterrupted synchronous block — and its mirror of that case
/// asserts the property rather than the fix.
final class SyncPlaybackIngressLifetimeAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 5_100

    private static let hashA = SyncTestValues.hash(61)
    private static let hashB = SyncTestValues.hash(62)

    private func build(inboundCapacity: Int = 1) async {
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
            deferredCommandCapacity: 16
        )
        await coordinator.start()
    }

    override func tearDown() async throws {
        await coordinator?.shutdown()
        coordinator = nil
    }

    // MARK: - Finding A

    /// **The defect, in full.** Session A's one ingress consumer is parked inside a decoder load, so
    /// the read loop keeps filling a bounded queue behind it and one authoritative frame is genuinely
    /// refused. Session A then ends and Session B authenticates as a follower, clean. Only *then*
    /// does the old parked work release and the consumer reach the refusal.
    ///
    /// Session B never lost anything. It must not be halted, must not be counted against, and must
    /// go on accepting its own authority normally.
    ///
    /// The refusal is *proved*, not assumed: `inboundRetiredLossCount` rising by exactly one is a
    /// statement that a frame was refused **and** that the refusal was attributed to a generation
    /// that is no longer live. Nothing else can move that counter.
    func testASessionAIngressOverflowNeverDesynchronizesSessionB() async {
        await build()
        await connect(asLeader: false, generation: 1)
        await content.addLocal(Self.hashA)

        await parkConsumer(on: Self.hashA)
        await overflow(under: 1)

        await boundary(toFollower: 2)
        let before = await coordinator.diagnostics
        XCTAssertFalse(before.ingressDesynchronized, "Session B starts clean")

        await releaseParkedConsumer()

        let after = await coordinator.diagnostics
        XCTAssertFalse(after.ingressDesynchronized, "Session A's loss is not Session B's loss")
        XCTAssertNotEqual(after.syncState, .desynchronized, "and Session B is not shown as halted")
        XCTAssertEqual(
            after.inboundOverflowCount, before.inboundOverflowCount,
            "nor does it increment the live session's loss figure"
        )
        XCTAssertEqual(
            after.inboundRetiredLossCount, before.inboundRetiredLossCount + 1,
            "the evidence is kept, attributed to the session that is gone"
        )
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "and no Session A command took effect in Session B")
        let revision = await coordinator.queueState.revision
        XCTAssertEqual(revision, 0, "nor mutated its queue")

        // And Session B's own authority still applies, from its own sequence floor.
        await deliverAndAwait(playCommand(seq: 1, hash: Self.hashA), generation: 2)
        let applied = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(applied, 1, "Session B commands normally")
    }

    /// The property the fix above must not weaken (A1 Finding C). A loss in the **live** session is
    /// still observed strictly before any later incremental frame of that session is dispatched — a
    /// `PAUSE` applied as coherent state without the `PLAY` that preceded it is the exact incoherence
    /// the halt exists to prevent — and authoritative full state is still the only thing that ends it.
    func testASameGenerationOverflowStillHaltsBeforeLaterIncrementalAuthorityApplies() async {
        await build()
        await connect(asLeader: false, generation: 1)
        await content.addLocal(Self.hashA)

        await parkConsumer(on: Self.hashA)
        await overflow(under: 1)
        await releaseParkedConsumer()

        await expect("the halt is latched") { await self.coordinator.diagnostics.ingressDesynchronized }
        let halted = await coordinator.diagnostics
        XCTAssertEqual(halted.syncState, .desynchronized)
        XCTAssertEqual(halted.inboundOverflowCount, 1, "counted against the session that lost it")
        XCTAssertEqual(halted.inboundRetiredLossCount, 0, "and not as a retired one")
        XCTAssertEqual(halted.lastReceivedCommandSeq, 1, "the halt spends no sequence number")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "the PAUSE behind the refusal is never applied as coherent state")

        // A further incremental command still changes nothing while the halt is in force.
        await deliverAndAwait(pauseCommand(seq: 4, positionMs: 9_000), generation: 1)
        let stillHalted = await coordinator.diagnostics.lastReceivedCommandSeq
        XCTAssertEqual(stillHalted, 1, "still halted")

        // And authoritative full state still ends it, exactly as A1 established.
        await deliverAndAwait(snapshot(revision: 3), generation: 1)
        await deliverAndAwait(
            .playbackState(
                commandSeq: 7, queueRevision: 3, trackHash: Self.hashA, queueItemId: SyncTestValues.ulid(1),
                positionMs: 12_000, playing: true, atSessionUs: clock.now()
            ),
            generation: 1
        )
        let reconciled = await coordinator.diagnostics
        XCTAssertFalse(reconciled.ingressDesynchronized, "authoritative full state is what ends the halt")
        XCTAssertEqual(reconciled.lastAppliedCommandSeq, 7, "ordering resumes from the authoritative value")
    }

    /// The proof that A6 **scopes** losses rather than merely suppressing old counters: each
    /// generation's own loss is accounted for, exactly once, to itself.
    func testGenerationAAndGenerationBEachOwnTheirLossExactlyOnce() async {
        await build()
        await connect(asLeader: false, generation: 1)
        await content.addLocal(Self.hashA)
        await content.addLocal(Self.hashB)

        await parkConsumer(on: Self.hashA)
        await overflow(under: 1)
        await boundary(toFollower: 2)
        await releaseParkedConsumer()

        let afterA = await coordinator.diagnostics
        XCTAssertFalse(afterA.ingressDesynchronized, "A's loss did not reach B")
        XCTAssertEqual(afterA.inboundOverflowCount, 0)
        XCTAssertEqual(afterA.inboundRetiredLossCount, 1)

        // Now Session B loses one of its own, the same way.
        await parkConsumer(on: Self.hashB)
        await overflow(under: 2)
        await releaseParkedConsumer()

        await expect("B's own halt is latched") { await self.coordinator.diagnostics.ingressDesynchronized }
        let afterB = await coordinator.diagnostics
        XCTAssertEqual(afterB.inboundOverflowCount, 1, "counted once, for B")
        XCTAssertEqual(afterB.inboundRetiredLossCount, 1, "and A's stays A's — no double counting")
    }

    /// Coalescing is not a loss of authority and never halts — but it is still an event, and it still
    /// belongs to the generation whose frame caused it. Session B must not inherit Session A's.
    func testCoalescingAccountingIsGenerationBoundToo() async {
        await build()
        await connect(asLeader: false, generation: 1)
        await content.addLocal(Self.hashA)
        await content.addLocal(Self.hashB)

        await parkConsumer(on: Self.hashA)
        for index in 0 ..< 3 { await session.deliver(positionReport(index), generation: 1) }
        await settle()

        await boundary(toFollower: 2)
        let before = await coordinator.diagnostics
        await releaseParkedConsumer()

        let afterA = await coordinator.diagnostics
        XCTAssertEqual(
            afterA.inboundCoalescedCount, before.inboundCoalescedCount,
            "Session B does not inherit Session A's coalescing as its own"
        )
        XCTAssertEqual(
            afterA.inboundRetiredLossCount, before.inboundRetiredLossCount + 2,
            "the two superseded reports are surfaced as the retired events they are"
        )
        XCTAssertFalse(afterA.ingressDesynchronized, "coalescing never halts, in either session")

        // Session B's own coalescing is its own.
        await parkConsumer(on: Self.hashB)
        for index in 0 ..< 3 { await session.deliver(positionReport(index), generation: 2) }
        await settle()
        await releaseParkedConsumer()

        let afterB = await coordinator.diagnostics
        XCTAssertEqual(afterB.inboundCoalescedCount, 2, "and B accounts for its own, exactly once")
        XCTAssertEqual(afterB.inboundRetiredLossCount, afterA.inboundRetiredLossCount, "A's stays A's")
        XCTAssertFalse(afterB.ingressDesynchronized)
    }

    // MARK: - Finding B

    /// **The defect.** Session A's authoritative frame is refused by the transport, so it fails
    /// closed — and parks inside the unfenced `setRate(1.0)` that ends its correction. Session A then
    /// ends, Session B authenticates as a follower and starts playing normally. When Session A's rate
    /// call finally returns, the seven writes that followed it landed on Session B.
    ///
    /// The rate restore itself is deliberately *not* fenced away: an old authority ending must still
    /// leave the music at exactly 1.0. What may not survive is everything after it.
    func testARetiredFailClosedFinishesItsRateRestoreAndWritesNothingIntoTheNewSession() async {
        await build(inboundCapacity: 256)
        // A leader, because only an **authoritative** frame the transport refuses fails closed: a
        // follower's PLAY is an intent, and `OutboundCommitGate` quietly abandons those.
        await connect(asLeader: true, generation: 1)
        await content.addLocal(Self.hashA)
        await content.addLocal(Self.hashB)

        await player.gateCalls { call in
            if case .setRate = call { return true }
            return false
        }
        await session.setSendResult(false)
        await coordinator.enqueue(Self.hashA)
        await expect("the fail-closed path parked inside its rate restore") { await self.player.isGateParked }

        await session.setSendResult(true)
        await boundary(toFollower: 2)
        await deliverAndAwait(playCommand(seq: 1, hash: Self.hashB), generation: 2)
        // The command is *considered* as soon as the consumer dispatches it, but its deadline has
        // already passed, so the start and the `.synced` transition it publishes are one more hop
        // away. Waiting on the transition rather than on the frame is what makes the baseline below
        // a settled one — a full-suite run caught the difference.
        await expect("Session B reached synchronised playback") {
            await self.coordinator.diagnostics.syncState == .synced
        }

        let before = await coordinator.diagnostics
        XCTAssertFalse(before.outboundAuthorityLost, "Session B starts with its authority intact")
        XCTAssertEqual(before.syncState, .synced, "and synchronised")

        let ratesBefore = await player.calls.filter { call in
            if case .setRate = call { return true }
            return false
        }.count
        await player.releaseGate()
        await settle(400)

        let after = await coordinator.diagnostics
        XCTAssertEqual(after.syncState, before.syncState, "no retired fail-closed verdict reached Session B")
        XCTAssertEqual(after.outboundAuthorityLost, before.outboundAuthorityLost, "nor its authority latch")
        XCTAssertEqual(after.deferredCommandCount, before.deferredCommandCount)
        XCTAssertEqual(after.localDriftMs, before.localDriftMs)
        XCTAssertEqual(after.peerDriftMs, before.peerDriftMs)
        XCTAssertEqual(after.cancelledPendingPlayCount, before.cancelledPendingPlayCount)
        XCTAssertEqual(after.playbackRate, before.playbackRate)
        XCTAssertEqual(after.lastAppliedCommandSeq, before.lastAppliedCommandSeq)
        let timeline = await coordinator.timeline
        XCTAssertNotNil(timeline, "Session B's timeline is untouched")

        // The exemption still holds: the absolute rate restore was allowed to complete.
        let ratesAfter = await player.calls.filter { call in
            if case .setRate = call { return true }
            return false
        }.count
        XCTAssertGreaterThanOrEqual(ratesAfter, ratesBefore, "the old authority's 1.0 restore is not fenced away")

        // And Session B still commands.
        await coordinator.pause()
        await awaitOutboundQuiescent()
        let paused = await session.playbackMessages().contains { message in
            if case .pause = message { return true }
            return false
        }
        XCTAssertTrue(paused, "Session B's own transport control still reaches the wire")
    }

    /// The same-session control: without a boundary, fail-closed still does everything A2 requires.
    func testASameSessionFailClosedStillLatchesLeavesSynchronizedModeAndRestoresTheRate() async {
        await build(inboundCapacity: 256)
        await connect(asLeader: true, generation: 1)
        await content.addLocal(Self.hashA)

        await session.setSendResult(false)
        await coordinator.enqueue(Self.hashA)
        await awaitOutboundQuiescent()
        await settle()

        let diagnostics = await coordinator.diagnostics
        XCTAssertTrue(diagnostics.outboundAuthorityLost, "the divergence is surfaced, never silent")
        XCTAssertEqual(diagnostics.syncState, .transportFailed)
        XCTAssertEqual(diagnostics.playbackRate, DriftController.rateNormal, "and the rate is back at exactly 1.0")
        let active = await coordinator.isSynchronizedModeActive()
        XCTAssertFalse(active, "synchronised mode is left")
        let calls = await player.calls
        XCTAssertTrue(calls.contains(.setRate(DriftController.rateNormal)), "the unfenced restore still reaches the player")
        XCTAssertFalse(calls.contains(.stop), "ADR-004: local music is not stopped")
    }

    // MARK: - The queue on its own

    /// A consumer parked across many boundaries cannot grow the ledger without bound, and the bound
    /// is not a silent hole: an evicted bucket's counts are folded into the next oldest, so the total
    /// is preserved exactly.
    func testTheLossLedgerIsBoundedAndFoldsRatherThanDrops() {
        let queue = lossQueue(capacity: 1)
        XCTAssertEqual(queue.offer(LossFrame(name: "fill", generation: 0)), .admit)
        for generation in Int64(1) ... 40 {
            XCTAssertEqual(queue.offer(LossFrame(name: "refused", generation: generation)), .overflow)
        }
        let losses = queue.drainLosses()
        XCTAssertLessThanOrEqual(losses.count, 8, "bounded: \(losses.count) buckets")
        XCTAssertEqual(losses.reduce(0) { $0 + $1.overflowCount }, 40, "and nothing was silently discarded")
        XCTAssertEqual(losses.last?.generation, 40, "the newest generation keeps its own identity")
    }

    /// The permutations A6 §16 asks for, taken on the queue itself so each iteration is a whole
    /// producer/consumer interleaving rather than a coordinator fixture: capacity 1 and 2, an
    /// overflow and a coalescing, generations that keep increasing, and an observation that lands
    /// either while the causing generation is still live or after it has been replaced.
    ///
    /// What every iteration asserts is the one thing A6 rests on — **a loss carries the generation of
    /// the frame that caused it, and draining it does not consult anything else.**
    func testStressTwoHundredGenerationScopedIngressPermutations() {
        var live = 0
        var retired = 0
        for iteration in 0 ..< 200 {
            let capacity = iteration % 3 == 0 ? 1 : 2
            let coalescing = iteration % 2 == 0
            let generation = Int64(1 + iteration)
            let queue = lossQueue(capacity: capacity)

            // Fill the bound. When the newcomer is a latest-wins frame it needs a sibling to
            // supersede, so the oldest filler is one of its own family.
            for index in 0 ..< capacity {
                let name = coalescing && index == 0 ? "report-seed" : "fill\(index)"
                XCTAssertEqual(queue.offer(LossFrame(name: name, generation: generation)), .admit)
            }

            let newcomer = coalescing ? "report-new" : "refused"
            let expected: IngressAdmission = coalescing ? .coalesce : .overflow
            XCTAssertEqual(
                queue.offer(LossFrame(name: newcomer, generation: generation)), expected, "iteration \(iteration)"
            )

            // The boundary lands before the consumer looks on half the iterations, and not at all on
            // the other half.
            let liveWhenObserved = (iteration % 4 == 1 || iteration % 4 == 2) ? generation : generation + 1
            let losses = queue.drainLosses()
            XCTAssertEqual(losses.count, 1, "one generation, one bucket")
            guard let loss = losses.first else { return XCTFail("no loss recorded") }
            XCTAssertEqual(loss.generation, generation, "attributed to the causing frame's own generation")
            XCTAssertEqual(loss.overflowCount, coalescing ? 0 : 1)
            XCTAssertEqual(loss.coalescedCount, coalescing ? 1 : 0)
            if loss.generation == liveWhenObserved { live += 1 } else { retired += 1 }
            XCTAssertTrue(queue.drainLosses().isEmpty, "draining clears")
        }
        XCTAssertEqual(live, 100, "half the iterations observed while the causing generation was live")
        XCTAssertEqual(retired, 100)
    }

    // MARK: - Helpers

    /// A bare queue frame: `report…` names are the latest-wins family.
    private struct LossFrame: Sendable {
        let name: String
        let generation: Int64
    }

    private func lossQueue(capacity: Int) -> Phase5FrameQueue<LossFrame> {
        Phase5FrameQueue(
            capacity: capacity,
            kindOf: { $0.name.hasPrefix("report") ? .latestWins : .command },
            coalesceKeyOf: { $0.name.hasPrefix("report") ? "REPORT" : nil },
            generationOf: { $0.generation }
        )
    }

    private func connect(asLeader: Bool, generation: Int64) async {
        await session.setGeneration(generation)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await coordinator.handleConnected(isLocalLeader: asLeader)
        await awaitOutboundQuiescent()
    }

    private func boundary(toFollower generation: Int64) async {
        await coordinator.handleLinkLost()
        await connect(asLeader: false, generation: generation)
    }

    /// Parks the one ingress consumer inside a decoder load, so the read loop runs on without it.
    private func parkConsumer(on hash: ContentHash) async {
        await player.gateCalls { call in
            if case .load = call { return true }
            return false
        }
        let generation = await session.currentAuthGeneration()
        await session.deliver(playCommand(seq: 1, hash: hash), generation: generation)
        await expect("the ingress consumer parked inside the decoder load") { await self.player.isGateParked }
    }

    private func releaseParkedConsumer() async {
        await player.releaseGate()
        await settle(400)
    }

    /// Fills the bound behind the parked consumer and offers one more authoritative command, which
    /// the queue has nowhere to put.
    private func overflow(under generation: Int64) async {
        await session.deliver(pauseCommand(seq: 2, positionMs: 5_000), generation: generation)
        await session.deliver(resumeCommand(seq: 3, positionMs: 6_000), generation: generation)
        await settle()
    }

    private func deliverAndAwait(_ message: PlaybackMessage, generation: Int64) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message, generation: generation)
        await expect("the frame was considered") { await self.coordinator.diagnostics.inboundProcessedCount > before }
        await awaitOutboundQuiescent()
    }

    private func deliverAndAwait(_ message: QueueMessage, generation: Int64) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message)
        _ = generation
        await expect("the frame was considered") { await self.coordinator.diagnostics.inboundProcessedCount > before }
        await awaitOutboundQuiescent()
    }

    private func awaitOutboundQuiescent() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let diagnostics = await coordinator.diagnostics
            if diagnostics.outboundAttemptCount == diagnostics.outboundEnqueuedCount { return }
            if await player.isGateParked { return }
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

    private func header(seq: Int64) -> PlaybackCommandHeader {
        PlaybackCommandHeader(
            commandSeq: seq, effectiveAtSessionUs: clock.now(),
            issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
        )
    }

    private func playCommand(seq: Int64, hash: ContentHash) -> PlaybackMessage {
        .play(header: header(seq: seq), trackHash: hash, positionMs: 0, queueItemId: SyncTestValues.ulid(1))
    }

    private func pauseCommand(seq: Int64, positionMs: Int64) -> PlaybackMessage {
        .pause(header: header(seq: seq), positionMs: positionMs)
    }

    private func resumeCommand(seq: Int64, positionMs: Int64) -> PlaybackMessage {
        .resume(header: header(seq: seq), positionMs: positionMs)
    }

    private func positionReport(_ index: Int) -> PlaybackMessage {
        .positionReport(
            trackHash: Self.hashA, positionMs: Int64(index) * 1_000,
            atSessionUs: clock.now() + Int64(index), playing: true, playbackRate: 1.0
        )
    }

    private func snapshot(revision: Int64) -> QueueMessage {
        .snapshot(
            queueRevision: revision,
            items: [
                SharedQueueItem(
                    queueItemId: SyncTestValues.ulid(1), trackHash: Self.hashA,
                    addedBy: SyncTestValues.leaderPeerId, order: PlaybackBounds.queueOrderStep
                ),
            ],
            currentIndex: nil
        )
    }
}
