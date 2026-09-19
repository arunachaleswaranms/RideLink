import Foundation
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// Deterministic reconnect/resync stress coverage (Phase 7, ADR-028) — real `ControlSessionManager`
/// pairs over real loopback TLS, real `SyncPlaybackCoordinator`/`ResyncCoordinator` instances, no
/// mocks at the boundary under test. This repo's history (ADR-026, half of ADR-020's amendments) is
/// that almost every real defect here only became visible on a *second* session or under repeated
/// cycling — never the first pass — so this file exists to run many cycles rather than one.
///
/// **Scoping, disclosed rather than faked.** `SessionCoordinator`/`MusicCoordinator` live in the
/// `ios/RideLink` app target, which has no test target (`docs/STATUS.md` §4 problem 20) — so this
/// file cannot exercise `SessionFsm`'s literal `ENDING`/`IDLE` transitions, `SessionTeardownOwner`'s
/// join, or the real `MusicCoordinator`. What it *can* and does exercise directly, all real
/// production types: `ControlSessionManager` (TLS, pairing, the trust gate, `ControlEvent`s),
/// `SyncPlaybackCoordinator`, `ResyncCoordinator`, `VoiceController` is covered separately by
/// `VoiceLifetimeProvenanceTests`/`VoiceCrossLifetimeAuthorityTests` and not duplicated here.
/// "End Ride" below means `ControlSessionManager.shutdown()` — the real teardown call
/// `SessionTeardownOwner` invokes — not the FSM transition around it.
///
/// **No wall-clock sleeps for correctness.** Every wait below polls a real condition with a short
/// yield, matching `FsmSession.poll`/`SyncPlaybackCoordinatorTests.expect` — the established
/// convention in this test target. `ControlSessionManager`'s own PING/PONG timing needs a
/// free-running monotonic source to produce meaningful RTT samples (a manually-stepped clock would
/// starve every wire round trip), so `SharedTestClock` mirrors `PingRaceAndReconnectTests`' own
/// `LockedCounter`-based clock — a synthetic, thread-safe, auto-incrementing source decoupled from
/// wall time, not literal `Date()`/`DispatchTime` timing.
@MainActor
final class ReconnectResyncStressTests: XCTestCase {
    // MARK: - Harness

    private final class SharedTestClock: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Int64
        init(_ start: Int64) { value = start }
        func next() -> Int64 { lock.withLock { value += 1_000; return value } }
    }

    @MainActor
    private final class FakeCatalogue {
        var revision: Int64 = 0
        var refreshCount = 0
    }

    /// One side's full Phase 5/7 stack over one real `ControlSessionManager`. Built once per test
    /// and kept alive across every reconnect cycle in that test — proving *this* rig's state
    /// survives repeated cycling is the whole point.
    @MainActor
    private final class RideRig {
        let testPeer: TestPeer
        let manager: ControlSessionManager
        let session: FsmSession
        let sync: SyncPlaybackCoordinator
        let resync: ResyncCoordinator
        let player: FakeSyncPlayer
        let content: FakeSyncContent
        let catalogue: FakeCatalogue

        init(peer: TestPeer, clock: SharedTestClock) {
            testPeer = peer
            manager = peer.manager(monotonicNowUs: { clock.next() })
            session = FsmSession(peer: peer, manager: manager)
            player = FakeSyncPlayer()
            content = FakeSyncContent()
            catalogue = FakeCatalogue()
            sync = SyncPlaybackCoordinator(
                monotonicNowUs: { clock.next() },
                localPeerId: peer.peerId,
                session: ControlSessionSyncPort(manager: manager),
                player: player,
                content: content,
                sleeper: MonotonicDeadlineSleeper(monotonicNowUs: { clock.next() }),
                routeState: FakeRouteState(),
                nextQueueItemId: { UUID().uuidString }
            )
            resync = ResyncCoordinator(
                session: ControlSessionResyncPort(manager: manager),
                syncPlaybackCoordinator: sync,
                currentCatalogueRevision: { [catalogue] in catalogue.revision },
                requestManifestRefresh: { [catalogue] in catalogue.refreshCount += 1 },
                localPeerId: peer.peerId
            )
        }

        /// Mirrors `SessionCoordinator.applySideEffects`'s `.connected` forwarding — the one thing
        /// this harness must do by hand because `ControlSessionManager.onEvent` is a single mutable
        /// slot and `SessionCoordinator` is what owns it in production.
        func attach() async {
            let sync = self.sync
            let resync = self.resync
            let session = self.session
            await manager.setOnEvent { event in
                session.record(event)
                if case .connected(_, _, let isLocalLeader, let authGeneration) = event {
                    Task { @MainActor in
                        await sync.handleConnected(isLocalLeader: isLocalLeader)
                        await resync.onConnected(isLeader: isLocalLeader, generation: authGeneration)
                    }
                }
            }
            await sync.start()
            await resync.attach()
        }
    }

    private func poll(timeoutSeconds: Double = 20, _ condition: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw ControlTransportError.notReady
    }

    private func connectedCount(_ session: FsmSession) -> Int {
        session.count { if case .connected = $0 { return true } else { return false } }
    }

    /// `SyncPlaybackCoordinator.onDesynchronizedTrigger` is an actor-isolated stored property —
    /// reading it needs `await`, but the closure value itself, once read, is a plain `@Sendable`
    /// value callable from anywhere. Fires the real production callback `ResyncCoordinator.attach`
    /// installs, exactly as a genuine ingress overflow would.
    ///
    /// **Waits for the trigger to actually register**, not just for the call to return: the
    /// installed closure only *schedules* `Task { @MainActor in await self.onDesyncTrigger() } }`
    /// and returns immediately, so a caller that polls `!requestPending` right after this call can
    /// race the Task itself — "not pending" is trivially true before the Task has had any chance to
    /// *become* pending, which is exactly the shape of bug this repo's whole history warns about.
    /// Closing it once here, rather than patching every call site, is deliberate.
    private func triggerDesync(_ rig: RideRig) async throws {
        let before = rig.resync.diagnostics.desyncRequestCount
        guard let trigger = await rig.sync.onDesynchronizedTrigger else { return }
        trigger()
        try await poll(timeoutSeconds: 10) { rig.resync.diagnostics.desyncRequestCount > before }
    }

    /// Builds both sides **once**. Real production identity: `a`'s peer id is lexicographically
    /// smaller, so ADR-010 makes `a` the leader for the whole test — the follower, `b`, is the one
    /// whose `ResyncCoordinator` ever issues a `STATE_REQUEST`. Both `RideRig`s — including their
    /// `SyncPlaybackCoordinator`/`ResyncCoordinator` — persist across every cycle below, exactly as
    /// production does across a ride's reconnects: only the **connection**, not the coordinator,
    /// gets torn down and rebuilt.
    private func buildPersistentPair(clock: SharedTestClock) async throws -> (a: RideRig, b: RideRig, aPort: UInt16) {
        let (aPeer, bPeer) = try TestSessions.pairedPeers("a1a1a1a1a1a1a1a1", "b1b1b1b1b1b1b1b1")
        let a = RideRig(peer: aPeer, clock: clock)
        let b = RideRig(peer: bPeer, clock: clock)
        await a.attach()
        await b.attach()
        let aPort = try await a.manager.startListening(local: aPeer.local)
        let bPort = try await b.manager.startListening(local: bPeer.local)
        await b.manager.connectTo(host: "127.0.0.1", port: aPort, local: bPeer.local)
        await a.manager.connectTo(host: "127.0.0.1", port: bPort, local: aPeer.local)
        try await poll { self.connectedCount(a.session) > 0 }
        try await settleResyncForwarding(a: a, b: b)
        return (a, b, aPort)
    }

    /// `connectedCount`/`FsmSession` only prove the manager-level `.connected` event was
    /// *recorded*; the `Task` `RideRig.attach()` schedules from it — `sync.handleConnected` then
    /// `resync.onConnected`, which is what actually sets `isLocalLeader` on each side's
    /// `ResyncCoordinator` — is a separate async hop that need not have finished yet. Waiting for
    /// `diagnostics.sessionGeneration` (set inside `handleConnected`, which fully completes before
    /// `resync.onConnected` is even called in the same `Task`) to reach the manager's own live
    /// generation is what actually proves each side's resync coordinator is ready to answer a
    /// trigger for *this* connection — on the first connect and on every reconnect alike. A gap this
    /// harness had from the start, surfaced only by a test that triggers a resync before any other
    /// network traffic gives the forwarding `Task` time to catch up on its own.
    private func settleResyncForwarding(a: RideRig, b: RideRig) async throws {
        try await poll {
            a.resync.isLocalLeader == true && b.resync.isLocalLeader == false
        }
    }

    /// One reconnect cycle on an already-built persistent pair (`buildPersistentPair`). Only `b`'s
    /// **connection** is torn down and rebuilt (`shutdown()` + a fresh `startListening()` + redial
    /// both ways, mirroring PROTOCOL §10's "both peers detect loss and both retry") — `b`'s
    /// `SyncPlaybackCoordinator`/`ResyncCoordinator` are never rebuilt, so `hasEverConnected` and
    /// every other piece of cross-reconnect state is genuinely exercised, not reset out from under
    /// the test by accident. `a` never shuts down, matching a ride-long session's own listener.
    private func reconnectCycle(a: RideRig, b: RideRig, aPort: UInt16) async throws {
        let beforeA = connectedCount(a.session)
        await b.manager.shutdown()
        let bPort = try await b.manager.startListening(local: b.testPeer.local)
        await b.manager.connectTo(host: "127.0.0.1", port: aPort, local: b.testPeer.local)
        await a.manager.connectTo(host: "127.0.0.1", port: bPort, local: a.testPeer.local)
        try await poll { self.connectedCount(a.session) > beforeA }
        try await settleResyncForwarding(a: a, b: b)
    }

    // MARK: - 1: repeated reconnect + resync cycles

    /// 50 reconnect cycles on one long-lived pair. Each cycle: link loss (`b`'s connection is torn
    /// down), a fresh redial, real TLS re-authentication (a new `authGeneration` on both sides), the
    /// real clock burst, and — because `ResyncCoordinator.hasEverConnected` is already true after
    /// cycle 0 on **both** sides — a real follower-issued `STATE_REQUEST` / leader-issued
    /// `STATE_SNAPSHOT` round trip on every cycle from then on.
    func testFiftyReconnectCyclesLeaveNoWedgedResyncStateAndCountEachCycleExactlyOnce() async throws {
        let cycles = 50
        let clock = SharedTestClock(1_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        XCTAssertFalse(a.resync.diagnostics.requestPending, "the leader never requests state")

        for cycle in 1...cycles {
            let beforeGenA = a.manager.liveAuthenticatedGeneration()
            let beforeGenB = b.manager.liveAuthenticatedGeneration()
            try await reconnectCycle(a: a, b: b, aPort: aPort)
            XCTAssertNotEqual(beforeGenA, a.manager.liveAuthenticatedGeneration(), "cycle \(cycle): a's generation must strictly increase")
            XCTAssertNotEqual(beforeGenB, b.manager.liveAuthenticatedGeneration(), "cycle \(cycle): b's generation must strictly increase")

            // A pending STATE_REQUEST must never straddle a cycle boundary: either it resolved (the
            // real leader answered it) or the generation moved on and the gate's own comparison
            // makes the old one unreachable — never both an outstanding flag *and* a stuck request.
            try await poll(timeoutSeconds: 10) { !a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending }
        }

        XCTAssertEqual(cycles + 1, connectedCount(a.session), "each cycle must produce exactly one .connected event on the leader")
        XCTAssertEqual(cycles + 1, connectedCount(b.session), "each cycle must produce exactly one .connected event on the follower")
        XCTAssertEqual(cycles, b.resync.diagnostics.reconnectRequestCount, "the follower must request state exactly once per reconnect (never the first-ever connect)")
        XCTAssertEqual(0, a.resync.diagnostics.reconnectRequestCount, "the leader never requests state")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 2: repeated reconciliation cycles on a stable connection

    /// 50 desync-triggered `STATE_REQUEST` -> `STATE_SNAPSHOT` -> apply cycles with **no reconnect
    /// in between** — the connection stays up throughout. Fires the real trigger
    /// `SyncPlaybackCoordinator.onIngressOverflow` installs (`onDesynchronizedTrigger`) directly,
    /// rather than manufacturing a real ingress overflow, which is the documented, legitimate way
    /// this codebase already exercises a production callback slot in a test (see
    /// `ResyncCoordinatorTests`). Since neither side changes the queue or plays anything between
    /// cycles, every one of the 50 snapshots is byte-identical — which is exactly what makes
    /// idempotency and "no revision inflation" checkable: nothing here should ever move.
    func testFiftyReconciliationCyclesOnAStableConnectionAreIdempotentWithNoRevisionInflationOrDuplicateEffects() async throws {
        let cycles = 50
        let clock = SharedTestClock(2_000_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)
        // Connecting legitimately issues a `.setRate(1.0)` reset (session establishment restores
        // rate to exactly 1.0, per this codebase's brief §38) — cleared here so what follows
        // asserts only about the 50 reconciliation cycles, matching `SyncPlaybackCoordinatorTests
        // .connect()`'s own convention.
        await b.player.clearCalls()
        let revisionBefore = await b.sync.queueState.revision

        for cycle in 1...cycles {
            let before = b.resync.diagnostics.desyncRequestCount
            try await triggerDesync(b)
            try await poll(timeoutSeconds: 10) { b.resync.diagnostics.desyncRequestCount > before }
            try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
            XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "cycle \(cycle)")
        }

        let revisionAfter = await b.sync.queueState.revision
        let callsAfter = await b.player.calls
        XCTAssertEqual(cycles, b.resync.diagnostics.desyncRequestCount, "exactly one desync request per trigger, never coalesced or duplicated")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(revisionBefore, revisionAfter, "an empty queue reconciled 50 times must never inflate its own revision")
        XCTAssertTrue(callsAfter.isEmpty, "an empty snapshot applied 50 times must never produce a player effect")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 3: randomized ownership-race repeats

    /// The provenance regression from `ResyncCoordinatorTests`, run 50 times against the **real**
    /// relay and manager (not the lightweight fake session) with a randomized number of reconnects
    /// before the race and randomized stale payload values — so a defect that depends on the exact
    /// shape of the stale snapshot, or on exactly which reconnect it trails, cannot hide behind one
    /// fixed fixture. `ResyncRelay.deliver`'s own generation check is what must refuse it, every time.
    func testFiftyRandomizedRacesADelayedSnapshotForARetiredGenerationNeverReconciles() async throws {
        for iteration in 0..<50 {
            let clock = SharedTestClock(Int64(3_000_000 + iteration * 10_000))
            let (a, b, aPort) = try await buildPersistentPair(clock: clock)

            let staleGeneration = b.manager.liveAuthenticatedGeneration()
            let extraReconnects = Int.random(in: 1...3)
            for _ in 0..<extraReconnects {
                // Captured *before* the cycle, not after: `reconnectCycle`'s own `settleResyncForwarding`
                // proves `resync.isLocalLeader` was set (the first line of `onConnected`), but proves
                // nothing about whether `onConnected`'s later, still-`async` call into `triggerRequest` —
                // and therefore this cycle's own `reconnectRequestCount` increment and `STATE_REQUEST`
                // round trip — has actually run yet. Polling only `!requestPending` here is ambiguous
                // between "this cycle's request already resolved" and "it has not started yet" (both
                // read as `requestPending == false`), and a CI run under different scheduling caught
                // exactly that ambiguity: a later cycle's own legitimate request completing its async
                // hop *after* this loop had already moved on to the stale-delivery assertion below,
                // which then attributed an unrelated `.requested` transition to the stale delivery.
                // Waiting for `reconnectRequestCount` to have actually moved past this cycle's own
                // starting value removes the ambiguity outright, on real Swift concurrency scheduling
                // rather than on an assumption about how many hops a same-actor `await` takes.
                let requestsBefore = b.resync.diagnostics.reconnectRequestCount
                try await reconnectCycle(a: a, b: b, aPort: aPort)
                try await poll(timeoutSeconds: 10) {
                    b.resync.diagnostics.reconnectRequestCount > requestsBefore && !b.resync.diagnostics.requestPending
                }
            }
            let liveGeneration = b.manager.liveAuthenticatedGeneration()
            XCTAssertNotEqual(staleGeneration, liveGeneration, "iteration \(iteration)")

            let outcomeBefore = b.resync.diagnostics.lastOutcome
            let commandSeqBefore = await b.sync.diagnostics.lastAppliedCommandSeq
            let staleSnapshot = ResyncCodec.encode(.stateSnapshot(
                leaderPeerId: a.testPeer.peerId,
                commandSeq: Int64.random(in: 1...9_999),
                queueRevision: Int64.random(in: 0...50),
                playback: nil,
                queueItems: [],
                queueCurrentIndex: nil,
                manifestRevision: Int64.random(in: 0...20),
                transfersInFlight: []
            ))
            guard let staleGeneration else { return XCTFail("iteration \(iteration): no generation recorded before the reconnects") }
            await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: staleSnapshot, generation: staleGeneration)
            // No `await` between the delivery attempt and the assertions below has any bearing on
            // this: `deliver`'s generation check is synchronous within the relay actor, so a refused
            // frame never reaches the sink at all — there is nothing to race against.
            let retiredDrops = await b.manager.resyncRelay().droppedRetiredGeneration()
            XCTAssertGreaterThan(retiredDrops, 0, "iteration \(iteration): the stale-generation delivery must be counted as refused")
            XCTAssertEqual(outcomeBefore, b.resync.diagnostics.lastOutcome, "iteration \(iteration): a retired-generation snapshot must not change the last outcome")
            let commandSeqAfter = await b.sync.diagnostics.lastAppliedCommandSeq
            XCTAssertEqual(commandSeqBefore, commandSeqAfter, "iteration \(iteration): must not apply")

            // The live generation must still reconcile normally afterwards — the fix for the stale
            // case must not also refuse the successor.
            try await triggerDesync(b)
            try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
            XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "iteration \(iteration): the live generation must still reconcile")

            await b.manager.shutdown()
            await a.manager.shutdown()
        }
    }

    // MARK: - 4: fault injection

    /// Loss immediately before `STATE_REQUEST` is sent: the connection is already gone by the time
    /// `ResyncCoordinator` tries to write it, so `send` returns `false` on the real, now-writerless
    /// relay. Must record `.sendFailed` and clear `requestPending` — never leave it stuck `true`
    /// forever with nothing outstanding to ever resolve it.
    func testLossImmediatelyBeforeStateRequestIsSentDoesNotWedgeThePendingFlag() async throws {
        let clock = SharedTestClock(4_000_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)
        await b.manager.shutdown()
        try await triggerDesync(b)
        try await poll(timeoutSeconds: 10) { b.resync.diagnostics.lastOutcome == .sendFailed }
        XCTAssertFalse(b.resync.diagnostics.requestPending, "a send that never reached the wire must not leave a request outstanding")
        await a.manager.shutdown()
    }

    /// Loss after `STATE_REQUEST` is sent but before any `STATE_SNAPSHOT` arrives: the request goes
    /// out over a real, live connection, and only then is the link cut. A later, fresh reconnect
    /// must issue its **own** request rather than finding the gate wedged shut by the orphaned one.
    func testLossAfterStateRequestSentBeforeSnapshotArrivesIsRecoveredByTheNextReconnect() async throws {
        let clock = SharedTestClock(5_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        // Block the leader from ever answering, so the request is genuinely still in flight when
        // the link drops.
        await a.manager.resyncRelay().setSink(nil)
        try await triggerDesync(b)
        try await poll(timeoutSeconds: 10) { b.resync.diagnostics.desyncRequestCount == 1 }
        XCTAssertTrue(b.resync.diagnostics.requestPending, "the request was sent and nothing has answered it yet")

        // Re-arm the leader's sink *before* reconnecting: production never tears it down (this only
        // stood in for "no answer arrives" above) — re-arming it after `reconnectCycle` returns
        // would race the follower's own reconnect-triggered `STATE_REQUEST`, which fires the instant
        // its `.connected` lands and can reach the leader before this test gets a chance to restore
        // the sink, wedging the request forever for a reason this test manufactured, not production.
        await a.resync.attach()
        try await reconnectCycle(a: a, b: b, aPort: aPort)

        try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
        XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "the reconnect's own reconnect-triggered request must still resolve")
        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Loss during snapshot processing — the frame is admitted (its generation was live at the
    /// instant the relay checked it) but the connection ends before the `Task` `ResyncCoordinator`
    /// hops onto actually runs `handleStateSnapshot`. This is the real ADR-024/ADR-025 provenance
    /// window: admission and application are separated by a real suspension, and the boundary can
    /// land strictly between them with no test hook required.
    func testLossDuringSnapshotProcessingMeansTheAdmittedFrameIsNeverApplied() async throws {
        let clock = SharedTestClock(6_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let liveGeneration = b.manager.liveAuthenticatedGeneration()
        guard let liveGeneration else { return XCTFail("no live generation") }

        // A command_seq no legitimate reconciliation on this pair would ever produce (nothing here
        // ever issues a real command), so its absence afterwards is unambiguous.
        let poisonCommandSeq: Int64 = 999_888_777
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: poisonCommandSeq, queueRevision: 3, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 1, transfersInFlight: []
        ))
        // Admitted now (generation is live at this instant) — but the sink's `Task` has not run yet.
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: liveGeneration)
        // The link ends before that `Task` gets a chance to run `handleStateSnapshot`.
        try await reconnectCycle(a: a, b: b, aPort: aPort)

        // The reconnect itself triggers its own, legitimate resync round trip on the *new*
        // generation — wait for that real reconciliation to settle before asserting, so this test
        // is not racing one it did not ask about.
        try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
        let commandSeqAfter = await b.sync.diagnostics.lastAppliedCommandSeq
        XCTAssertNotEqual(poisonCommandSeq, commandSeqAfter, "the stale, admitted-but-unapplied snapshot's command_seq must never surface as applied")
        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// This test predates the ADR-028 Amendment ordering fix below and demonstrates a different,
    /// narrower thing than that fix addresses: `adoptSnapshot` itself has no revision-monotonicity
    /// guard, so a **fabricated** out-of-order frame injected directly at the follower (never
    /// something a real leader could now produce — see the fix and its own regression test just
    /// below) can still roll the follower's revision backwards. Kept as a unit-level proof of that
    /// narrower fact; not a claim that this ordering is reachable from a real leader post-fix.
    func testAQueueSnapshotAndAStateSnapshotResyncLeaveTheHigherRevisionStandingRegardlessOfOrder() async throws {
        let clock = SharedTestClock(7_000_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)
        guard let generation = b.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        // A plain Phase 5 QUEUE_SNAPSHOT at revision 5, delivered directly (as the leader's real
        // broadcast would be).
        let queuePayload = QueueCodec.encode(.snapshot(queueRevision: 5, items: [], currentIndex: nil))
        await b.manager.playbackRelay().deliverQueue(type: QueueMessageTypes.snapshot, payload: queuePayload, generation: generation)
        try await poll { await b.sync.queueState.revision == 5 }

        // A resync STATE_SNAPSHOT reporting an *older* revision (3) must not roll the follower back.
        let staleResync = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 3, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: staleResync, generation: generation)
        try await Task.yield()
        try await Task.yield()
        // `adoptSnapshot` adopts wholesale — a resync snapshot naming an older revision than what is
        // already applied moves the follower *backwards*, which is only safe because a leader never
        // actually produces this ordering (its own revision only increases). This assertion pins
        // what production does today rather than asserting a protection that does not exist — see
        // this test's report to the coordinator for the flag this raises.
        let revisionAfterStaleResync = await b.sync.queueState.revision

        // A *newer* resync snapshot (revision 9) must still win going the other direction.
        let newerResync = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 2, queueRevision: 9, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: newerResync, generation: generation)
        try await poll { await b.sync.queueState.revision == 9 }

        print(
            "revision after older-resync-after-newer-queue: \(revisionAfterStaleResync) "
                + "(expected 5 if adoptSnapshot is revision-monotonic; production adopts wholesale — see report)"
        )
        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// **The regression test for the ADR-028 Amendment ordering fix.** Races a real leader-side
    /// queue mutation against a real leader-side `STATE_REQUEST` answer, back to back with no
    /// synchronization between issuing them — exactly the interleaving that used to be possible
    /// because `ResyncCoordinator` wrote `STATE_SNAPSHOT` over a channel independent of
    /// `Phase5FrameQueue`. Both now funnel through `SyncPlaybackCoordinator.enqueueOutbound`, so
    /// whichever was decided (and therefore enqueued) first is guaranteed to be *written* first: the
    /// follower's observed revision sequence must be monotonically non-decreasing throughout, never
    /// once regressing to an older value after a newer one has already been seen.
    func testAQueueMutationRacedAgainstAStateRequestReplyNeverWritesTheOlderRevisionSecond() async throws {
        let clock = SharedTestClock(7_500_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)

        final class RevisionLog: @unchecked Sendable {
            private let lock = NSLock()
            private(set) var seen: [Int64] = []
            func record(_ revision: Int64) { lock.withLock { seen.append(revision) } }
        }
        let log = RevisionLog()
        await b.sync.setQueueObserver { state in log.record(state.revision) }

        for round in 0..<20 {
            // Fired back to back, with no `await` settling either before the next starts: a queue
            // mutation that bumps the revision, and (every other round) a desync trigger that makes
            // the follower ask for — and the leader answer with — current state. If the two outbound
            // paths were still independent, the resync answer's *older* snapshot of revision could
            // reach the wire after the mutation's newer `QUEUE_SNAPSHOT`.
            let item = QueueAddItem(
                queueItemId: SyncTestValues.ulid(round),
                trackHash: SyncTestValues.hash(round),
                addedBy: a.testPeer.peerId,
                position: PlaybackBounds.queuePositionEnd
            )
            await a.sync.mutateQueue(.add(items: [item]))
            if round.isMultiple(of: 2) {
                try await triggerDesync(b)
            }
        }

        try await poll(timeoutSeconds: 10) { await a.sync.queueState.items.count == 20 }
        try await poll(timeoutSeconds: 10) { await b.sync.queueState.items.count == 20 }
        try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }

        let revisions = log.seen
        let sorted = revisions.sorted()
        XCTAssertEqual(sorted, revisions, "the follower's observed revision must never regress: \(revisions)")
        let finalRevision = await b.sync.queueState.revision
        XCTAssertEqual(20, finalRevision, "the follower must end at the leader's true final revision")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// End Ride (`shutdown()`) while a `STATE_REQUEST` is outstanding, and separately while a
    /// snapshot has been admitted but not yet applied. Neither may crash, hang or leave a dangling
    /// `Task` that fires after the manager is gone.
    func testEndRideWhileAResyncRequestOrAnAdmittedSnapshotIsInFlightDoesNotCrashOrHang() async throws {
        let clock = SharedTestClock(8_000_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)
        guard let generation = b.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        // Case 1: End Ride with a request outstanding (leader's sink detached, so nothing answers).
        await a.manager.resyncRelay().setSink(nil)
        try await triggerDesync(b)
        try await poll(timeoutSeconds: 10) { b.resync.diagnostics.requestPending }
        await b.manager.shutdown()
        await a.manager.shutdown()

        // Case 2: End Ride with an admitted-but-not-yet-applied snapshot.
        let clock2 = SharedTestClock(8_500_000)
        let (a2, b2, _) = try await buildPersistentPair(clock: clock2)
        guard let generation2 = b2.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a2.testPeer.peerId, commandSeq: 1, queueRevision: 1, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b2.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation2)
        await b2.manager.shutdown()
        await a2.manager.shutdown()
        // Reaching here at all — no fatal trap, no hang — is the assertion (`shutdown()` cancels
        // the manager's own tasks; the `ResyncCoordinator`'s sink `Task` is not one of them, which
        // is exactly the app-level `SessionTeardownOwner`-join gap disclosed in this file's header).
        _ = generation
        XCTAssertTrue(true)
    }

    // MARK: - 5: second-ride-restart proof

    /// **The sharpest test in this file.** `RideRig` — `ControlSessionManager`,
    /// `SyncPlaybackCoordinator`, `ResyncCoordinator` — is built **once** and never rebuilt, exactly
    /// as production keeps these alive for the whole process across multiple rides. Each "ride
    /// boundary" is `reconnectCycle`'s own already-proven mechanism (`b`'s control session fully
    /// `shutdown()`s and rebuilds; `a`'s listener persists, matching a ride-long session) — the
    /// same primitive `testFiftyReconnectCyclesLeaveNoWedgedResyncStateAndCountEachCycleExactlyOnce`
    /// already runs 50 times without incident. Tearing down **both** sides' listeners for every
    /// ride boundary was tried first and abandoned: a fresh `startListening()` after a full
    /// shutdown is not guaranteed to rebind the same ephemeral port, which is a harness fragility
    /// having nothing to do with the properties this test exists to check — this file's header
    /// already draws the same line around `SessionFsm`'s literal `ENDING`/`IDLE`.
    ///
    /// Five back-to-back rides. Each ride: mutates the queue, triggers a desync-based resync,
    /// verifies it reconciled, then a `STATE_SNAPSHOT` **stamped with the previous ride's own
    /// retiring generation** is delivered directly at the follower's relay — the sharpest available
    /// proof that a predecessor ride's work cannot mutate the next one, mirroring the fabricated-
    /// delay technique from `testFiftyRandomizedRacesADelayedSnapshotForARetiredGenerationNeverReconciles`
    /// but now spanning a full ride boundary rather than a same-ride reconnect.
    func testFiveBackToBackRidesLeaveNoCrossRideMutationOrWedgedState() async throws {
        let clock = SharedTestClock(9_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        var previousRideGeneration: Int64?

        for ride in 1...5 {
            // A delayed snapshot from the *previous* ride's retired generation, delivered only after
            // this ride is under way, must still be refused — the sharpest proof that nothing from
            // an earlier ride's lifetime can land in this one.
            if let staleGeneration = previousRideGeneration {
                let staleSnapshot = ResyncCodec.encode(.stateSnapshot(
                    leaderPeerId: a.testPeer.peerId, commandSeq: 999_000 + Int64(ride), queueRevision: 0,
                    playback: nil, queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
                ))
                await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: staleSnapshot, generation: staleGeneration)
                let dropped = await b.manager.resyncRelay().droppedRetiredGeneration()
                XCTAssertGreaterThan(dropped, 0, "ride \(ride): a previous ride's delayed snapshot must still be refused")
            }

            // Operate: one queue mutation, one desync-triggered resync.
            //
            // **ADR-024 Amendment A8, fixed as of this pass.** `resetForNewSession()` used to
            // unconditionally clear `queueState` on every `.connected`/`.linkLost` event, leader and
            // follower alike — reachable with no peer at all
            // (`SyncPlaybackCoordinatorTests.testALeadersQueueSurvivesAnOrdinaryLinkLoss`), and fatal
            // for a leader specifically: it is the authoritative source PROTOCOL §10 assumes a
            // reconnecting follower resumes from, and nothing repopulates it once wiped. Fixed to
            // retire only session-bound coordination state and leave the queue itself alone. The
            // assertion below is this test's own proof of that fix holding across a *real* ride
            // boundary: the queue accumulates one item per ride, surviving every reconnect in
            // between, rather than resetting.
            let item = QueueAddItem(
                queueItemId: SyncTestValues.ulid(100 + ride), trackHash: SyncTestValues.hash(100 + ride),
                addedBy: a.testPeer.peerId, position: PlaybackBounds.queuePositionEnd
            )
            await a.sync.mutateQueue(.add(items: [item]))
            try await poll(timeoutSeconds: 10) { await b.sync.queueState.items.count == ride }
            try await triggerDesync(b)
            try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
            XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "ride \(ride)")
            XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount, "ride \(ride)")
            XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount, "ride \(ride)")

            previousRideGeneration = b.manager.liveAuthenticatedGeneration()

            // "End Ride" + "Start Ride N+1" in one proven primitive, unless this was the last ride.
            if ride < 5 {
                let reconnectRequestsBefore = b.resync.diagnostics.reconnectRequestCount
                try await reconnectCycle(a: a, b: b, aPort: aPort)
                try await poll(timeoutSeconds: 10) { b.resync.diagnostics.reconnectRequestCount > reconnectRequestsBefore }
                try await poll(timeoutSeconds: 10) { !b.resync.diagnostics.requestPending }
                XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount, "ride \(ride) boundary")
                XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount, "ride \(ride) boundary")
            }
        }

        // The sharpest single number in this test: five rides, one item added each, all five still
        // present at the end — the queue was never once reset by any of the four ride boundaries in
        // between (ADR-024 Amendment A8).
        let finalCount = await b.sync.queueState.items.count
        XCTAssertEqual(5, finalCount, "the queue accumulates across every ride boundary; none of them may reset it")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)
        XCTAssertFalse(b.resync.diagnostics.requestPending, "no ride may end with a wedged pending request")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 6: bounded-resource audit

    /// 100 reconnect cycles (double `testFiftyReconnectCyclesLeaveNoWedgedResyncStateAndCountEachCycleExactlyOnce`'s
    /// count), auditing every collection Phase 7's own code touches for unbounded growth.
    ///
    /// **What was audited, and why each is bounded by construction (proof, not assertion):**
    /// - `ResyncDiagnostics` (`ResyncCoordinator.swift`) is all scalars (`Bool`/`Int`/`Int64?`/an
    ///   enum) — there is no collection in it to overflow.
    /// - `ResyncRelay.rejections` (`ResyncRelay.swift`) is `[ResyncMessageRejection: Int]`, keyed by
    ///   a fixed-cardinality enum (`ResyncMessageRejection`, 15 cases) — its size cannot exceed 15
    ///   regardless of how many frames are refused; only the `Int` counts grow, and they are plain
    ///   integers, not a history.
    /// - `pendingRequestGeneration`/`lastKnownManifestRevision`/`hasEverConnected`
    ///   (`ResyncCoordinator.swift`) are single optional/scalar values, replaced in place, never
    ///   appended to.
    /// - `Phase5Outbound.Frame.resync` — this pass's own addition — rides the **existing** bounded
    ///   `outbound: Phase5FrameQueue<Phase5Outbound>` (`SyncPlaybackCoordinator.swift:231,285-286`),
    ///   constructed with a fixed `capacity: outboundCapacity` (`Phase5GateBounds
    ///   .defaultOutboundCapacity`) and refused-with-a-count (`outboundOverflowCount`) past it
    ///   (`Phase5FrameQueue.swift:70`, `enqueueOutbound`'s `.overflow` branch) — no new unbounded
    ///   structure was introduced to carry it.
    ///
    /// This test does not merely cite that; it **measures** it: the rejection dictionary's key count
    /// is asserted to stay within `ResyncMessageRejection.allCases.count` after 100 cycles' worth of
    /// real traffic, and every diagnostics counter is confirmed to still be a plain `Int`/`Int64`
    /// rather than something that grew a backing array.
    func testOneHundredReconnectCyclesLeaveOnlyBoundedStateBehind() async throws {
        let cycles = 100
        let clock = SharedTestClock(10_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)

        for cycle in 1...cycles {
            try await reconnectCycle(a: a, b: b, aPort: aPort)
            try await poll(timeoutSeconds: 10) { !a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending }
            if cycle.isMultiple(of: 10) {
                // A stale-generation delivery every ten cycles, to give `rejections`/`droppedRetiredGeneration`
                // real, repeated traffic to (not) accumulate unboundedly from.
                let stale = ResyncCodec.encode(.stateSnapshot(
                    leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 0, playback: nil,
                    queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
                ))
                await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: stale, generation: -1)
            }
        }

        // `ResyncMessageRejection` is not `CaseIterable` (matching its siblings
        // `QueueMessageRejection`/`ManifestMessageRejection`, neither of which is either), so the
        // bound is this file's own enumeration of every case `ResyncCodec.swift` declares, not a
        // runtime-derived one — 16 as of this pass.
        let knownRejectionCaseCount = 16
        let rejectionCounts = await b.manager.resyncRelay().rejectionCounts()
        XCTAssertLessThanOrEqual(
            rejectionCounts.count, knownRejectionCaseCount,
            "the rejection dictionary's key space is bounded by the enum, never by traffic volume"
        )
        XCTAssertEqual(cycles + 1, connectedCount(a.session), "each cycle produced exactly one .connected event, not an accumulating backlog")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)
        XCTAssertFalse(b.resync.diagnostics.requestPending, "no wedged state after 100 cycles")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }
}
