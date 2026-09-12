import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// The coordinator half of ADR-024 **Amendment A7**.
///
/// A7's control-layer fix — `ControlSessionManager` binding every inbound frame to the connection
/// that authorised its read, pinned by `StaleReadGenerationTests` — has a consequence this layer has
/// to answer for. **Once a frame keeps its own session's generation instead of inheriting the live
/// one, generations no longer arrive at `Phase5FrameQueue` in increasing order.** A read loop whose
/// session has ended still dispatches the one frame it had already read, and it does so after the
/// successor session's read loop has begun offering, so `A, B, A` reaches `offer`.
///
/// A6's loss ledger assumed the opposite in writing: it opened a new bucket whenever the incoming
/// generation differed from the *newest* one, and once past its eight-bucket bound it evicted the
/// oldest **by arrival** and folded those counts into the next oldest by arrival — justified by
/// "generations strictly increase, so both of the two oldest are retired". Under an alternating run
/// that fold target is the newest generation, which may be **live**.
///
/// That is not a diagnostics defect. A follower answers a *live*-generation loss by latching
/// `playbackDesynchronized`/`queueDesynchronized`, which decide whether incremental authoritative
/// commands are applied at all. So the A6 defect — **Session B halted because Session A dropped
/// something** — comes straight back through the ledger's own compaction, and this file is what
/// stops it.
///
/// `Phase5FrameQueueTests` pins the ledger's mechanics directly. This file pins the consequence the
/// user would actually feel, through the real coordinator.
///
/// The Kotlin mirror is `com.ridelink.app.sync.SyncPlaybackReadGenerationAuditTest`.
final class SyncPlaybackReadGenerationAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 5_700

    private static let hashA = SyncTestValues.hash(71)

    /// The shortest alternating run that exceeds `Phase5FrameQueue.maxLossGenerations` under A6's
    /// per-adjacency-run bucketing: `n` refusals interleaved with `n - 1` coalesces is `2n - 1`
    /// buckets, so `n = 5` is the first value that forces a compaction at all.
    private static let retiredRefusals = 5

    /// Comfortably past the bound, so the A6 bucketing would have compacted several times over.
    /// Under A7's one-bucket-per-generation there are two buckets however long this runs.
    private static let alternations = 12

    private func build() async {
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
            inboundCapacity: 1,
            deferredCommandCapacity: 16
        )
        await coordinator.start()
    }

    override func tearDown() async throws {
        await coordinator?.shutdown()
        coordinator = nil
    }

    /// **The defect, in full, at the exact point it first bites.** The dead session's read loop keeps
    /// delivering refused commands while the live session's own frames are merely being *coalesced*
    /// — which never halts anything, because PROTOCOL §5 makes the newest `POSITION_REPORT` subsume
    /// its predecessors by definition.
    ///
    /// Session B lost nothing at all. It must end this completely un-halted, and every one of
    /// Session A's refusals must still be surfaced as the retired events they are.
    ///
    /// Measured against the A6 fold restored into `Phase5FrameQueue`: `ingressDesynchronized`
    /// **true**, `syncState` **.desynchronized** and `inboundOverflowCount` **1** — on a session
    /// whose own ingress refused nothing — because the one eviction folds generation 1's refusal
    /// into the generation 2 bucket that follows it, and generation 2 is live.
    func testTheFirstLedgerCompactionNeverHandsTheLiveSessionARefusalItDidNotHave() async {
        await build()
        await connect(generation: 1)
        await boundary(toFollower: 2)
        await content.addLocal(Self.hashA)

        await parkConsumer(on: Self.hashA)
        // One live-generation report occupies the bound, so the alternation below is exactly
        // "a retired command is refused / a live report supersedes its own predecessor".
        await session.deliver(positionReport(0), generation: 2)
        await settle()

        for index in 0 ..< Self.retiredRefusals {
            await session.deliver(pauseCommand(seq: Int64(2 + index), positionMs: Int64(5_000 + index)), generation: 1)
            if index < Self.retiredRefusals - 1 {
                await session.deliver(positionReport(index + 1), generation: 2)
            }
            await settle()
        }

        let before = await coordinator.diagnostics
        XCTAssertFalse(before.ingressDesynchronized, "Session B starts clean")

        await releaseParkedConsumer()

        let after = await coordinator.diagnostics
        XCTAssertFalse(
            after.ingressDesynchronized,
            "Session B refused nothing of its own — a retired session's refusal must never halt it"
        )
        XCTAssertEqual(
            after.inboundOverflowCount, 0,
            "nor be counted against it: every refusal here belonged to the session that has ended"
        )
        XCTAssertNotEqual(after.syncState, .desynchronized, "and Session B is not shown as halted")
        XCTAssertEqual(
            after.inboundRetiredLossCount, Self.retiredRefusals,
            "every one of the dead session's refusals is still surfaced, attributed to it"
        )
        XCTAssertEqual(
            after.inboundCoalescedCount, Self.retiredRefusals - 1,
            "and Session B owns exactly its own coalescing, once each"
        )
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "no retired command took effect in the live session")

        // And Session B's own authority still applies, from its own sequence floor.
        await session.deliver(playCommand(seq: 1, hash: Self.hashA), generation: 2)
        await settle()
        let applied = await coordinator.diagnostics.lastAppliedCommandSeq
        XCTAssertEqual(applied, 1, "Session B commands normally")
    }

    /// The same alternation run long past the bound, where A6 would compact many times over: the
    /// accounting must stay exact in both directions rather than merely avoiding the halt. Under the
    /// A6 fold the counts slosh between the two generations on every eviction, and this run ends
    /// with `inboundRetiredLossCount` **20** for a session that caused twelve refusals.
    func testALongAlternatingRunKeepsEveryEventWithTheGenerationThatCausedIt() async {
        await build()
        await connect(generation: 1)
        await boundary(toFollower: 2)
        await content.addLocal(Self.hashA)

        await parkConsumer(on: Self.hashA)
        await session.deliver(positionReport(0), generation: 2)
        await settle()
        for index in 0 ..< Self.alternations {
            await session.deliver(pauseCommand(seq: Int64(2 + index), positionMs: Int64(5_000 + index)), generation: 1)
            await session.deliver(positionReport(index + 1), generation: 2)
            await settle()
        }

        await releaseParkedConsumer()

        let after = await coordinator.diagnostics
        XCTAssertFalse(after.ingressDesynchronized, "still not the live session's loss, however long the run")
        XCTAssertEqual(after.inboundOverflowCount, 0)
        XCTAssertEqual(after.inboundRetiredLossCount, Self.alternations, "exactly the retired session's refusals")
        XCTAssertEqual(after.inboundCoalescedCount, Self.alternations, "exactly the live session's coalesces")
    }

    /// The half the fix must not weaken (A1 Finding C, restated under A7's arrival order): the live
    /// session's **own** refusal still halts it, and still does so before any later incremental
    /// command of that session is applied — even when a retired generation's events are interleaved
    /// with it.
    func testTheLiveSessionsOwnRefusalStillHaltsItAmidARetiredSessionsNoise() async {
        await build()
        await connect(generation: 1)
        await boundary(toFollower: 2)
        await content.addLocal(Self.hashA)

        await parkConsumer(on: Self.hashA)
        await session.deliver(positionReport(0), generation: 2)
        await settle()
        for index in 0 ..< Self.alternations {
            await session.deliver(pauseCommand(seq: Int64(2 + index), positionMs: Int64(5_000 + index)), generation: 1)
            await settle()
        }
        await session.deliver(resumeCommand(seq: 3, positionMs: 8_000), generation: 2)
        await settle()

        await releaseParkedConsumer()

        let after = await coordinator.diagnostics
        XCTAssertTrue(after.ingressDesynchronized, "the live session's own loss still halts it")
        XCTAssertEqual(after.syncState, .desynchronized)
        XCTAssertEqual(after.inboundOverflowCount, 1, "counted once, against the session that lost it")
        XCTAssertEqual(after.inboundRetiredLossCount, Self.alternations, "and the retired session's stay its own")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "the command queued behind the refusal is never applied")
    }

    // MARK: - Helpers (mirroring SyncPlaybackIngressLifetimeAuditTests')

    private func connect(generation: Int64) async {
        await session.setGeneration(generation)
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        await coordinator.handleConnected(isLocalLeader: false)
        await settle()
    }

    private func boundary(toFollower generation: Int64) async {
        await coordinator.handleLinkLost()
        await connect(generation: generation)
    }

    /// Parks the one ingress consumer inside a decoder load, so the read loops run on without it.
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
}
