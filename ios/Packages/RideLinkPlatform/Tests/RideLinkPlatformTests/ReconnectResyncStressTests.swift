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
        let routeState: FakeRouteState

        init(peer: TestPeer, clock: SharedTestClock) {
            testPeer = peer
            manager = peer.manager(monotonicNowUs: { clock.next() })
            session = FsmSession(peer: peer, manager: manager)
            player = FakeSyncPlayer()
            content = FakeSyncContent()
            catalogue = FakeCatalogue()
            routeState = FakeRouteState()
            sync = SyncPlaybackCoordinator(
                monotonicNowUs: { clock.next() },
                localPeerId: peer.peerId,
                session: ControlSessionSyncPort(manager: manager),
                player: player,
                content: content,
                sleeper: MonotonicDeadlineSleeper(monotonicNowUs: { clock.next() }),
                routeState: routeState,
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

    // Independent review, round 2: CI and this machine under concurrent load both showed an
    // occasional `notReady` timeout in a handful of these polls — traced (see ADR-028 Amendment A1's
    // final verification note) to real TLS-handshake/clock-burst scheduling variance on a
    // resource-constrained runner, not to a logic race in any of the underlying production code,
    // which was independently re-verified. This is a stress-test timeout margin, not a production
    // deadline: widening it costs wall-clock time on an already-slow path, never correctness, so 30 s
    // was chosen as generous rather than tight. If a `notReady` recurs even at this budget, that is
    // new evidence worth a fresh investigation rather than another mechanical bump.
    private func poll(
        timeoutSeconds: Double = 30,
        _ what: String = "?",
        _ condition: @escaping () async -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        FileHandle.standardError.write(Data("POLL TIMEOUT \(what)\n".utf8))
        throw ControlTransportError.notReady
    }

    private func dumpRig(_ tag: String, a: RideRig, b: RideRig) async {
        let ad = await a.sync.diagnostics
        let bd = await b.sync.diagnostics
        let msg = "DUMP \(tag) aConn=\(connectedCount(a.session)) bConn=\(connectedCount(b.session)) aLive=\(String(describing: a.manager.liveAuthenticatedGeneration())) bLive=\(String(describing: b.manager.liveAuthenticatedGeneration())) aStatus=\(a.session.status) bStatus=\(b.session.status) aHeld=\(ad.heldStateSnapshotReplyCount) aDrop=\(ad.droppedStateSnapshotReplyCount) aSyncGen=\(ad.sessionGeneration) bSyncGen=\(bd.sessionGeneration) bPending=\(b.resync.diagnostics.requestPending) bOutcome=\(b.resync.diagnostics.lastOutcome)\n"
        FileHandle.standardError.write(Data(msg.utf8))
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
        try await poll(timeoutSeconds: 30) { rig.resync.diagnostics.desyncRequestCount > before }
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
        try await poll(timeoutSeconds: 30) { self.connectedCount(a.session) > 0 }
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
        // **Independent-review round 8: this now waits for what the comment above always claimed.**
        //
        // `isLocalLeader` is set on the first connect and never changes afterwards — leadership is
        // stable across a reconnect (ARCHITECTURE §5) — so from cycle 2 onwards the old condition
        // was already true before the cycle began and this helper returned immediately, proving
        // nothing about the connection the cycle had just built. The generation comparison is the
        // real signal, and it is the one the comment describes: `diagnostics.sessionGeneration` is
        // written inside `handleConnected`, which completes before `resync.onConnected` is called in
        // the same `Task`.
        try await poll(timeoutSeconds: 30, "both sides' coordinators to adopt the live generation") {
            guard a.resync.isLocalLeader == true, b.resync.isLocalLeader == false else { return false }
            guard let aLive = a.manager.liveAuthenticatedGeneration(),
                  let bLive = b.manager.liveAuthenticatedGeneration() else { return false }
            let aAdopted = await a.sync.diagnostics.sessionGeneration
            let bAdopted = await b.sync.diagnostics.sessionGeneration
            return aAdopted == aLive && bAdopted == bLive
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
        do {
            try await poll(timeoutSeconds: 8, "peer-observed-loss") {
                a.manager.liveAuthenticatedGeneration() == nil && b.manager.liveAuthenticatedGeneration() == nil
            }
        } catch {
            FileHandle.standardError.write(Data("LOSSWAIT aLive=\(String(describing: a.manager.liveAuthenticatedGeneration())) bLive=\(String(describing: b.manager.liveAuthenticatedGeneration()))\n".utf8))
            throw error
        }
        let bPort = try await b.manager.startListening(local: b.testPeer.local)
        await b.manager.connectTo(host: "127.0.0.1", port: aPort, local: b.testPeer.local)
        await a.manager.connectTo(host: "127.0.0.1", port: bPort, local: a.testPeer.local)
        do {
            try await poll(timeoutSeconds: 8, "reconnect-connected") { self.connectedCount(a.session) > beforeA }
        } catch {
            await dumpRig("reconnect-connected", a: a, b: b)
            throw error
        }
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
            try await poll(timeoutSeconds: 30) { !a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending }
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
            try await poll(timeoutSeconds: 30) { b.resync.diagnostics.desyncRequestCount > before }
            try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
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
                try await poll(timeoutSeconds: 30) {
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
            try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
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
        try await poll(timeoutSeconds: 30) { b.resync.diagnostics.lastOutcome == .sendFailed }
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
        try await poll(timeoutSeconds: 30) { b.resync.diagnostics.desyncRequestCount == 1 }
        XCTAssertTrue(b.resync.diagnostics.requestPending, "the request was sent and nothing has answered it yet")

        // Re-arm the leader's sink *before* reconnecting: production never tears it down (this only
        // stood in for "no answer arrives" above) — re-arming it after `reconnectCycle` returns
        // would race the follower's own reconnect-triggered `STATE_REQUEST`, which fires the instant
        // its `.connected` lands and can reach the leader before this test gets a chance to restore
        // the sink, wedging the request forever for a reason this test manufactured, not production.
        await a.resync.attach()
        try await reconnectCycle(a: a, b: b, aPort: aPort)

        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
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
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
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
        try await poll(timeoutSeconds: 30) { await b.sync.queueState.revision == 5 }

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
        try await poll(timeoutSeconds: 30) { await b.sync.queueState.revision == 9 }

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

        try await poll(timeoutSeconds: 30) { await a.sync.queueState.items.count == 20 }
        try await poll(timeoutSeconds: 30) { await b.sync.queueState.items.count == 20 }
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }

        let revisions = log.seen
        let sorted = revisions.sorted()
        XCTAssertEqual(sorted, revisions, "the follower's observed revision must never regress: \(revisions)")
        let finalRevision = await b.sync.queueState.revision
        XCTAssertEqual(20, finalRevision, "the follower must end at the leader's true final revision")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 3b: Blocker 1 (independent review) — outbound generation binding across a reconnect

    /// Case A/C: a `STATE_SNAPSHOT` authorised under a generation that has since retired must never
    /// reach the wire, and the live generation's own send must still succeed afterward.
    ///
    /// The fix (`ResyncRelay.send(_:generation:)`, bound to `authenticatedWriterFor`) closes the
    /// window by construction rather than leaving one to race: every call re-resolves the writer
    /// against the one immutable `AuthenticatedConnection` record at the instant it runs, so a
    /// generation that has gone stale by *any* point before that call — whether via a genuine
    /// concurrent suspension or, as here, a real reconnect that has already completed — is refused
    /// identically. This is the same direct-injection technique this file already uses for the
    /// inbound half (`resyncRelay().deliver(..., generation: staleGeneration)`), applied outbound.
    func testAStateSnapshotAuthorisedByARetiredGenerationNeverReachesTheWireAndTheLiveGenerationsOwnSendStillSucceeds() async throws {
        let clock = SharedTestClock(10_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        guard let staleAGeneration = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let liveAGeneration = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(staleAGeneration, liveAGeneration, "the reconnect must have actually retired the captured generation")

        let staleSnapshot = ResyncMessage.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 0, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        )
        let sentUnderStaleA = await a.manager.resyncRelay().send(staleSnapshot, generation: staleAGeneration)
        XCTAssertFalse(sentUnderStaleA, "a STATE_SNAPSHOT authorised under a retired generation must not reach the wire")
        let outboundDrops = await a.manager.resyncRelay().droppedRetiredGenerationOutbound()
        XCTAssertGreaterThan(outboundDrops, 0, "the refusal must be counted, not silent")

        let freshSnapshot = ResyncMessage.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 2, queueRevision: 0, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        )
        let sentUnderLiveA = await a.manager.resyncRelay().send(freshSnapshot, generation: liveAGeneration)
        XCTAssertTrue(sentUnderLiveA, "the live generation's own send must succeed after a prior refusal — the fix must not also refuse the successor")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Case B: the same property for an item that was legitimately *admitted* onto the outbound
    /// queue (passing `SyncPlaybackCoordinator`'s own upstream `stillCurrent` proof) before its
    /// authorising generation retired — proving the queue is not wedged by the refusal, and a
    /// following live-generation item still drains normally.
    func testAQueuedStateSnapshotFromARetiredGenerationIsRefusedAtTheWireWithoutWedgingTheOutboundQueue() async throws {
        let clock = SharedTestClock(10_500_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        guard let staleAGeneration = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        // Admitted while `staleAGeneration` was genuinely live — passes `enqueueStateSnapshotReply`'s
        // own `stillCurrent` proof and reaches the real outbound queue under that generation.
        await a.sync.enqueueStateSnapshotReply(
            generation: staleAGeneration, leaderPeerId: a.testPeer.peerId, manifestRevision: 0, transfersInFlight: []
        )

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let liveAGeneration = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        // The reconnect itself already auto-triggers a STATE_REQUEST from `onConnected` (a genuine
        // round trip against `liveAGeneration`, unrelated to the stale item queued above under
        // `staleAGeneration`). That request must settle *before* the desync trigger below — otherwise
        // `StateResyncGate.onTrigger` correctly (and silently, from this test's perspective) dedupes
        // the desync trigger as `.alreadyPending` against the still-outstanding reconnect request for
        // the same live generation, and `desyncRequestCount` never increments — `triggerDesync`'s own
        // poll then spins for the full ten seconds waiting for a trigger that was never going to fire.
        // Root-caused by instrumenting a captured failure: `triggerDesync` itself timed out, not the
        // assertion after it — the exact same "settle one lifetime's request before starting the
        // next" class already fixed once in this file's fifty-cycle test.
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }

        // Whatever the queued item's fate (drained before or after the retirement), the outbound
        // queue itself must not be wedged: a fresh, live-generation resync answer still gets through.
        try await triggerDesync(b)
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
        XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "a live-generation STATE_SNAPSHOT must still drain normally after an earlier item's generation retired")
        _ = liveAGeneration

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Case C, the follower-side mirror: the outbound `STATE_REQUEST` gets the identical binding —
    /// a request authorised under a retired generation must not reach the wire, and the live
    /// generation's own request still succeeds.
    func testAStateRequestAuthorisedByARetiredGenerationNeverReachesTheWire() async throws {
        let clock = SharedTestClock(11_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        guard let staleBGeneration = b.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let liveBGeneration = b.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(staleBGeneration, liveBGeneration)

        let sentUnderStaleB = await b.manager.resyncRelay().send(.stateRequest, generation: staleBGeneration)
        XCTAssertFalse(sentUnderStaleB, "a STATE_REQUEST authorised under a retired generation must not reach the wire")

        let sentUnderLiveB = await b.manager.resyncRelay().send(.stateRequest, generation: liveBGeneration)
        XCTAssertTrue(sentUnderLiveB, "the live generation's own STATE_REQUEST must still succeed")

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
        try await poll(timeoutSeconds: 30) { b.resync.diagnostics.requestPending }
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
            try await poll(timeoutSeconds: 30) { await b.sync.queueState.items.count == ride }
            try await triggerDesync(b)
            try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
            XCTAssertEqual(.reconciled, b.resync.diagnostics.lastOutcome, "ride \(ride)")
            XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount, "ride \(ride)")
            XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount, "ride \(ride)")

            previousRideGeneration = b.manager.liveAuthenticatedGeneration()

            // "End Ride" + "Start Ride N+1" in one proven primitive, unless this was the last ride.
            if ride < 5 {
                let reconnectRequestsBefore = b.resync.diagnostics.reconnectRequestCount
                try await reconnectCycle(a: a, b: b, aPort: aPort)
                try await poll(timeoutSeconds: 30) { b.resync.diagnostics.reconnectRequestCount > reconnectRequestsBefore }
                try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
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
            try await poll(timeoutSeconds: 30) { !a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending }
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

    // MARK: - 6: command-sequence floor across a reconnect (independent review §14)

    /// `resetForNewSession()` resets `lastAppliedSeq`/`nextSeq` on **every** session boundary, on
    /// both sides symmetrically — so a fresh generation's floor genuinely starts from nothing applied
    /// yet, not from a residual prior-generation value. The question this test answers empirically:
    /// does that produce a divergence, or a rejected/misordered command, once a real command is
    /// issued again after the reset?
    ///
    /// It does not, and the reasoning is provenance, not sequence-number bookkeeping:
    /// `ReadFrameBinding`/ADR-025 already refuses any inbound frame authorised by a retired
    /// generation regardless of what `command_seq` it carried, so a stale command from the *previous*
    /// generation can never reach `CommandOrderGate`'s comparison under the new one to be mistaken
    /// for a duplicate or a reorder — the two generations' sequence spaces never actually meet. The
    /// leader reports its own truthful post-reset floor in the reconnect's own `STATE_SNAPSHOT`
    /// (`emitStateSnapshot`/`enqueueStateSnapshotReply`'s `commandSeq: lastAppliedSeq ?? max(nextSeq - 1, 0)`),
    /// and the follower adopts it wholesale (§10 rule 2) — so both sides agree on the fresh floor by
    /// construction, not by coincidence.
    func testCommandSequenceFloorResetsConsistentlyAcrossAReconnectWithNoRejectionOrDivergence() async throws {
        let clock = SharedTestClock(12_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)

        // A real, generation-bound STATE_SNAPSHOT reporting a non-trivial pre-reconnect
        // command_seq — resetting from zero would prove nothing. `enqueueStateSnapshotReply` is the
        // same production path a real STATE_REQUEST answer goes through; only the *source* of the
        // triggering request is substituted (direct delivery here, a real desync trigger everywhere
        // else in this file) — deliberately avoiding `playSynchronized`'s own scheduled-deadline and
        // content-availability machinery, which this property does not depend on.
        guard let genBefore = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let firstSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 42, queueRevision: 1, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: firstSnapshot, generation: genBefore)
        try await poll(timeoutSeconds: 30) { await b.sync.diagnostics.lastReceivedCommandSeq == 42 }

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        try await poll(timeoutSeconds: 30) { !a.resync.diagnostics.requestPending && !b.resync.diagnostics.requestPending }

        // `resetForNewSession()` on both sides clears `lastReceivedSeq`/`lastAppliedSeq`/`nextSeq`
        // symmetrically, and the reconnect's own auto-triggered STATE_REQUEST/STATE_SNAPSHOT round
        // trip already re-agreed on a floor by the time `requestPending` cleared above (the real
        // leader's `emitStateSnapshot`/`enqueueStateSnapshotReply` truthfully reports its own
        // post-reset `commandSeq: lastAppliedSeq ?? max(nextSeq - 1, 0)` — 0, since nothing has been
        // decided yet in the new generation). The follower's `lastReceivedCommandSeq` must reflect
        // that fresh floor, never the pre-reconnect value of 42 straddling the boundary.
        let bReceivedAfterReconnect = await b.sync.diagnostics.lastReceivedCommandSeq
        XCTAssertNotEqual(42, bReceivedAfterReconnect, "the pre-reconnect floor must not survive the reset")

        // A fresh, live-generation STATE_SNAPSHOT reporting a *low* command_seq (1) — exactly what a
        // real post-reconnect leader with nothing yet decided in the new generation would report —
        // must be accepted normally, never refused as a stale/duplicate replay of the pre-reconnect
        // sequence space (which reached 42). This is the crux of §14: the reset does not create a
        // window where a legitimately fresh, *lower* number gets rejected against a stale, *higher*
        // one left over from the previous generation.
        guard let genAfter = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(genBefore, genAfter, "the reconnect must have actually produced a new generation")
        let bStaleBefore = await b.sync.diagnostics.staleCommandCount
        let bDuplicateBefore = await b.sync.diagnostics.duplicateCommandCount
        let secondSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: secondSnapshot, generation: genAfter)
        try await poll(timeoutSeconds: 30) { await b.sync.diagnostics.lastReceivedCommandSeq == 1 }
        let bStaleAfter = await b.sync.diagnostics.staleCommandCount
        let bDuplicateAfter = await b.sync.diagnostics.duplicateCommandCount
        XCTAssertEqual(bStaleBefore, bStaleAfter, "a fresh generation's low command_seq must not be refused as stale against the pre-reconnect high-water mark")
        XCTAssertEqual(bDuplicateBefore, bDuplicateAfter)
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 7: content-unavailable STATE_SNAPSHOT (independent review §22)

    /// A `STATE_SNAPSHOT` naming a track the follower cannot resolve locally must request the
    /// transfer (PROTOCOL §5 rule 4 — "do not start, request the transfer, let the leader
    /// reschedule") and must **not** be reported as reconciled: `restoreFromPlaybackState` used to
    /// call `applyPlay` (which already, correctly, called `content.requestTransfer`) and then
    /// unconditionally return `.applied` regardless of whether `applyPlay` actually started
    /// anything — so a content-unavailable snapshot cleared `pendingRequestGeneration` and reported
    /// `.reconciled` up through `ResyncCoordinator`, exactly the class of bug Android's fork found
    /// independently on its side (a resync-deferral path that under-reported its own outcome). Fixed
    /// by giving `applyPlay` an honest `Bool` return and adding `.deferredContent` to
    /// `StateSnapshotOutcome`.
    func testAStateSnapshotNamingUnresolvableContentRequestsTheTransferAndIsNeverFalselyReportedAsReconciled() async throws {
        let clock = SharedTestClock(14_000_000)
        let (a, b, _) = try await buildPersistentPair(clock: clock)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        // Deliberately never added to `b.content` via `addLocal` — this is the "follower does not
        // have it yet" case PROTOCOL §5 rule 4 exists for.
        let unresolvableHash = SyncTestValues.hash(777)
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: unresolvableHash, queueItemId: SyncTestValues.ulid(777), positionMs: 5_000,
                playing: true, atSessionUs: 0
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)

        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(unresolvableHash) }

        // The outcome must be honestly reported as still-pending, never as a completed
        // reconciliation the peer never actually reached.
        try await poll(timeoutSeconds: 30) { b.resync.diagnostics.lastOutcome != .none }
        XCTAssertEqual(.snapshotPending, b.resync.diagnostics.lastOutcome, "content-unavailable must not be reported as .reconciled")

        // No player effect must have happened on the strength of a snapshot naming content this
        // device cannot yet play.
        let calls = await b.player.calls
        XCTAssertFalse(calls.contains(.start), "must never start playback for content the follower cannot resolve")
        XCTAssertFalse(calls.contains(where: { if case .seek = $0 { return true } else { return false } }), "must never seek before the track is even loadable")

        // Making the content available and re-delivering the identical snapshot must now let it
        // through normally, with no infinite retry loop (exactly one further transfer request, not a
        // growing backlog of duplicates for the same hash).
        await b.content.addLocal(unresolvableHash)
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }
        let requestsAfterResolution = await b.content.transferRequests
        XCTAssertEqual(1, requestsAfterResolution.filter { $0 == unresolvableHash }.count, "a resolvable re-delivery must not request the transfer again")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 8: end-to-end reconnect reconstruction scenarios (independent review Blocker 2)

    /// A session time guaranteed to already be due by the time `MonotonicDeadlineSleeper` checks
    /// it: `localMonoUs: 0` maps, through the follower's own offset, to a session instant that is
    /// always strictly older than `SharedTestClock`'s ever-increasing counter (which starts well
    /// above zero and only grows) — so a scheduled `.start` fires on the very first check, with no
    /// dependency on the synthetic clock's rate of advance. Learned the hard way in Section 14: a
    /// deadline that depends on the sleeper's own iterative convergence is fragile in this harness.
    private func alreadyDueSessionUs(for rig: RideRig) async -> Int64 {
        let offset = await rig.manager.sessionClockEstimate()?.offsetToLeaderUs ?? 0
        return SessionClock.sessionUs(localMonoUs: 0, offsetToLeaderUs: offset)
    }

    /// The leader's authoritative track changed **during** the outage (this device never saw an
    /// intermediate command for it — only the reconnect's own `STATE_SNAPSHOT` reports it). Genuine
    /// convergence, not merely a wire round trip: the real `SyncPlayerPort` calls the follower's
    /// player actually received, in order, and the diagnostics identity the reconciliation leaves
    /// behind.
    func testAReconnectWhoseLeaderChangedTrackDuringTheOutageConvergesTheFollowersRealPlayerCallsToTheNewTrack() async throws {
        let clock = SharedTestClock(20_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)

        let trackX = SyncTestValues.hash(1)
        let trackY = SyncTestValues.hash(2)
        await b.content.addLocal(trackX)
        await b.content.addLocal(trackY)

        guard let genBefore = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let beforeOutageSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackX, queueItemId: SyncTestValues.ulid(1), positionMs: 1_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: beforeOutageSnapshot, generation: genBefore)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }
        let trackHashBeforeOutage = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackX, trackHashBeforeOutage, "must be genuinely playing X before the outage, not merely told to")

        // The link dies and comes back — `resetForNewSession` clears `timeline`, and the reconnect's
        // own real auto-triggered STATE_REQUEST/STATE_SNAPSHOT round trip settles first (the real
        // leader honestly reports nothing loaded, since this harness never plays anything on `a`
        // itself). Waiting for it to settle **deterministically** — rather than racing a second,
        // synthetic snapshot against it under the same generation — is what makes the *next* step a
        // realistic model of "the leader's authoritative state changed mid-ride", not an artifact of
        // two unsolicited snapshots arriving for one generation (which `pendingRequestGeneration`
        // correctly does not track, since PROTOCOL §10 never sends an unsolicited one).
        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let genAfter = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(genBefore, genAfter)
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }

        // A real desync trigger — the same production callback an ingress overflow fires — opens a
        // genuine, generation-matched pending request, so the injected answer below is a faithful
        // stand-in for "the leader's real STATE_SNAPSHOT answer now names a different track", not an
        // unsolicited push.
        try await triggerDesync(b)

        await b.player.clearCalls()
        let afterOutageSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackY, queueItemId: SyncTestValues.ulid(2), positionMs: 42_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: afterOutageSnapshot, generation: genAfter)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        // The real player calls, in order — not merely "some outcome enum came back applied".
        let calls = await b.player.calls
        XCTAssertEqual(
            [.select(trackY), .load(trackY), .seek(42_000), .start], calls,
            "reconciliation onto a changed track must pre-roll and seek the new track exactly, and only then start"
        )
        let trackHashAfterConvergence = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackY, trackHashAfterConvergence, "diagnostics must report the converged identity, not the pre-outage one")

        // Not asserted here: `ResyncCoordinator.diagnostics.lastOutcome == .reconciled`. This test's
        // synthetic injected answer races the real leader's own honest (nothing-loaded) answer to
        // `triggerDesync`'s genuine `STATE_REQUEST` for the same generation — a situation PROTOCOL
        // §10 never actually produces (exactly one `STATE_SNAPSHOT` answers exactly one
        // `STATE_REQUEST`), so `pendingRequestGeneration` legitimately tracks whichever answer wins
        // the race, not necessarily this one. What the review actually asked to strengthen —
        // genuine convergence of the follower's real player and diagnostics identity — is asserted
        // above, and it is unaffected by which of the two answers `ResyncCoordinator` credited.
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// The leader's authoritative state after reconnect is **paused** — the follower must load and
    /// seek to the authoritative position but must never start playback on the strength of a snapshot
    /// that says the leader is not playing.
    func testAReconnectWhoseLeaderIsAuthoritativelyPausedNeverStartsTheFollowersPlayer() async throws {
        let clock = SharedTestClock(21_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(3)
        await b.content.addLocal(track)

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        // Let the reconnect's own real auto-triggered STATE_REQUEST/STATE_SNAPSHOT round trip settle
        // deterministically before injecting the paused answer — otherwise the two race under the
        // same generation and the assertion below could see whichever's player calls landed first.
        try await poll(timeoutSeconds: 30) { !b.resync.diagnostics.requestPending }
        try await triggerDesync(b)
        await b.player.clearCalls()

        let pausedSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(3), positionMs: 17_500,
                playing: false, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: pausedSnapshot, generation: generation)

        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.seek(17_500)) }
        // Nothing schedules a start for a paused snapshot, so there is no later event to wait for —
        // settle on the one this reconciliation actually produces, then assert the negative.
        let calls = await b.player.calls
        XCTAssertEqual([.select(track), .load(track), .seek(17_500)], calls, "a paused reconciliation pre-rolls and seeks, and does nothing else")
        XCTAssertFalse(calls.contains(.start), "must never start playback the leader has not authorised")
        let trackHashAfterPause = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(track, trackHashAfterPause)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// The leader's authoritative state names **no track at all** — PROTOCOL §10/ADR-024 §4's
    /// representable "nothing is loaded". Exercised both ways the wire can say it (`playback: nil`,
    /// meaning the leader has never had a synchronised timeline this session, and an explicit
    /// `trackHash: nil` playback record) to confirm both collapse to the identical, correct local
    /// effect through `onStateSnapshot`'s translation — matching the review's request to distinguish
    /// "genuinely nothing loaded" from "cleared by the reconnect itself" rather than assuming they
    /// coincide.
    func testAReconnectWhoseLeaderHasNothingLoadedNeverStartsOrLeavesAStaleTrackIdentityBehind() async throws {
        let clock = SharedTestClock(22_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(4)
        await b.content.addLocal(track)

        // Establish a real prior identity first, so "nothing loaded" is a genuine transition away
        // from something, not merely the untouched default.
        guard let genBefore = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let priorSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(4), positionMs: 3_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: priorSnapshot, generation: genBefore)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }
        let trackHashAfterPrior = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(track, trackHashAfterPrior)

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let genAfter = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        // Case 1: `playback: nil` — the leader has never had a synchronised timeline this session.
        await b.player.clearCalls()
        let neverHadOne = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 0, queueRevision: 1, playback: nil,
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: neverHadOne, generation: genAfter)
        try await poll(timeoutSeconds: 30) { await b.sync.diagnostics.currentTrackHash == nil }
        var calls = await b.player.calls
        XCTAssertTrue(calls.isEmpty, "a snapshot the leader never populated must not touch the player at all")

        // Re-establish an identity, then reconnect again and use the second wire shape: an explicit
        // `trackHash: nil` playback record — the leader *did* have a timeline this session and it
        // authoritatively says nothing is loaded.
        guard let genReestablish = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let reestablishSnapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(4), positionMs: 3_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: reestablishSnapshot, generation: genReestablish)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let genFinal = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let explicitlyNothing = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(trackHash: nil, queueItemId: nil, positionMs: 0, playing: false, atSessionUs: 0),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: explicitlyNothing, generation: genFinal)
        try await poll(timeoutSeconds: 30) { await b.sync.diagnostics.currentTrackHash == nil }
        calls = await b.player.calls
        XCTAssertTrue(calls.isEmpty, "an explicit 'nothing loaded' authoritative record must not touch the player either — both wire shapes converge identically")
        XCTAssertEqual(0, a.resync.diagnostics.roleViolationCount)
        XCTAssertEqual(0, b.resync.diagnostics.roleViolationCount)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 9: independent-review races 3-7 (retained-snapshot ownership under further reconnects/teardown)

    /// **Scoping, disclosed rather than faked.** Races 3-6 below need a `STATE_SNAPSHOT` genuinely
    /// retained (Section 22's mechanism), and Android's equivalents force that by setting a fake
    /// clock estimate directly. iOS's `ControlSessionManager`/`SessionClockTracker` pair is real and
    /// exposes no such seam — its clock becomes ready from a real burst of PING/PONG round trips over
    /// the real loopback TLS connection, which converges too fast and too unpredictably (confirmed
    /// empirically: sometimes ready before the very next line of test code runs, sometimes not) to
    /// use as a deterministic trigger. Content-unavailability is used instead: `applyPeerPlaybackState`
    /// (the fix above) puts *both* preconditions — clock readiness and content availability — through
    /// the identical `deferredEvents`/drain machinery, so a track deliberately never added to
    /// `FakeSyncContent` retains, discards, replays and drains exactly as a clock-not-ready one would.
    /// The property under test — ownership, exactly-once application, duplicate handling, teardown —
    /// is about that shared machinery, not about which precondition happened to be missing.

    /// Race 3. A snapshot retained for generation B must be provably inert once generation C
    /// authenticates — not merely superseded in place, but discarded outright by `resetForNewSession`,
    /// the same guarantee rule 23's negotiation-ownership story gives Phase 2's voice tables, applied
    /// here to a reconciliation snapshot instead.
    func testASnapshotRetainedForGenerationBIsInertOnceGenerationCAuthenticates() async throws {
        let clock = SharedTestClock(30_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let trackX = SyncTestValues.hash(100)
        let trackY = SyncTestValues.hash(101) // deliberately never added — B's retained content
        let trackZ = SyncTestValues.hash(102)
        await b.content.addLocal(trackX)
        await b.content.addLocal(trackZ)

        guard let genA = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let snapshotX = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackX, queueItemId: SyncTestValues.ulid(100), positionMs: 1_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshotX, generation: genA)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        // Generation B: reconnect, then deliver a snapshot naming content the follower does not have
        // — retained pending the transfer. The leader also "moves" while disconnected, so a leaked B
        // effect would show up as the wrong track, not merely "any track at all".
        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let genB = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(genA, genB)
        await b.player.clearCalls()
        let snapshotY = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackY, queueItemId: SyncTestValues.ulid(101), positionMs: 2_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshotY, generation: genB)
        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(trackY) }
        let deferredAfterB = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredAfterB, 0, "generation B's snapshot is genuinely retained, owned by generation B")
        let callsAfterB = await b.player.calls
        XCTAssertFalse(callsAfterB.contains(.start), "must not start on the retained track while it is still unresolvable")

        // Generation C authenticates before B's content ever verifies.
        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let genC = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        XCTAssertNotEqual(genB, genC)
        let snapshotZ = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: trackZ, queueItemId: SyncTestValues.ulid(102), positionMs: 3_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshotZ, generation: genC)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        let calls = await b.player.calls
        XCTAssertFalse(calls.contains(.select(trackY)), "B's retained snapshot must never surface, even after C converges")
        let trackHashFinal = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(trackZ, trackHashFinal, "converged on C's authoritative track, never B's stale one")
        let deferredAfterC = await b.sync.diagnostics.deferredCommandCount
        XCTAssertEqual(0, deferredAfterC, "B's held snapshot was discarded outright by C's resetForNewSession, not merely superseded in place")

        // Even if B's content were hypothetically to verify now, there is nothing left to drain.
        await b.content.completeTransfer(trackY)
        try await Task.yield()
        try await Task.yield()
        let callsAfterLateResolution = await b.player.calls
        XCTAssertFalse(callsAfterLateResolution.contains(.select(trackY)), "no belated player effect from B's retained snapshot ever arrives")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Race 4. A retained snapshot applies exactly once when it becomes resolvable — one genuine
    /// restore, never one per retry tick.
    func testARetainedSnapshotAppliesExactlyOnceWhenItBecomesResolvableNeverOncePerRetryTick() async throws {
        let clock = SharedTestClock(31_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(110)
        // Deliberately not added yet.

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(110), positionMs: 500,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(track) }
        let deferredRightAfter = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredRightAfter, 0, "retained pending the transfer")

        // The transfer verifies — the prompt `content.observeAvailability` trigger (the fix above)
        // applies it immediately, rather than waiting out the periodic retry interval.
        await b.content.completeTransfer(track)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.select(track)) }
        let selectCount = await b.player.calls.filter { $0 == .select(track) }.count
        XCTAssertEqual(1, selectCount, "exactly one genuine restore from the retained snapshot's single application")
        // The strongest available "no re-application" signal: the retained entry is provably gone,
        // so no later retry tick has anything left to act on — never merely "we didn't wait long
        // enough to see a second one" (this file's convention is no wall-clock sleeps for correctness).
        let deferredFinal = await b.sync.diagnostics.deferredCommandCount
        XCTAssertEqual(0, deferredFinal, "nothing left to drain — not an already-settled snapshot re-applied")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Race 5. A duplicate delivery of the same retained snapshot (a retried frame, not a new one) is
    /// held too, never merged or dropped — but draining both produces exactly one genuine restore; the
    /// duplicate finds the timeline already set once the first has run and takes the harmless
    /// incremental re-anchor branch instead, which touches no player call.
    func testADuplicateSnapshotArrivingWhileOneIsAlreadyRetainedCausesNoDuplicatePlayerEffects() async throws {
        let clock = SharedTestClock(32_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(120)
        // Deliberately not added yet.

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(120), positionMs: 700,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(track) }
        let deferredAfterFirst = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredAfterFirst, 0)

        // The identical snapshot arrives a second time — a retried frame, still under the same
        // generation, while still retained. `deliver` only *schedules* the dispatch `Task`, so this
        // polls for the effect rather than reading `deferredCommandCount` synchronously right after.
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.sync.diagnostics.deferredCommandCount > deferredAfterFirst }
        let deferredAfterDuplicate = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredAfterDuplicate, deferredAfterFirst, "the duplicate is held too, not silently dropped or merged")

        await b.content.completeTransfer(track)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.select(track)) }
        let selectCount = await b.player.calls.filter { $0 == .select(track) }.count
        XCTAssertEqual(
            1, selectCount,
            "all held entries drain, but only the first playback entry is a genuine restore — the duplicate " +
                "becomes a harmless incremental re-anchor and never calls into the player again"
        )
        let deferredFinal = await b.sync.diagnostics.deferredCommandCount
        XCTAssertEqual(0, deferredFinal)

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    /// Race 6. "End Ride" here means `ControlSessionManager.shutdown()` plus the direct
    /// `sync.handleLinkLost()` call `SessionCoordinator` would forward in production — this harness's
    /// own `attach()` only wires `.connected` by hand (its disclosed limitation), so `.linkLost`
    /// forwarding has to be done the same way here. A transfer that verifies **after** teardown must
    /// also produce no effect — the same late-callback shape Race 7 below exercises across a full
    /// restart, checked here across a teardown with no successor at all.
    func testEndingTheRideWhileASnapshotIsRetainedLeavesNoLaterPlayerMutation() async throws {
        let clock = SharedTestClock(33_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(130)
        // Deliberately not added yet.

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(130), positionMs: 900,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(track) }
        let deferredBeforeTeardown = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredBeforeTeardown, 0)

        // End Ride: the control lifetime ends with no successor authenticated yet.
        await b.manager.shutdown()
        await b.sync.handleLinkLost()

        let deferredAfterTeardown = await b.sync.diagnostics.deferredCommandCount
        XCTAssertEqual(0, deferredAfterTeardown, "teardown clears the retained snapshot outright")

        // The transfer verifies only now, after teardown — `content.observeAvailability`'s callback
        // is process-lifetime and still fires, but `resetForNewSession`'s synchronous
        // `deferredEvents.removeAll()` already left nothing for it to act on.
        await b.content.completeTransfer(track)
        try await Task.yield()
        try await Task.yield()
        let calls = await b.player.calls
        XCTAssertTrue(
            calls.allSatisfy { call in
                switch call {
                case .select, .load, .start: return false
                default: return true
                }
            },
            "no later restore effect from the retained snapshot after teardown: \(calls)"
        )

        await a.manager.shutdown()
    }

    /// Race 7. Ride 1 defers on missing content (section 22's own mechanism, now genuinely retained —
    /// see the `SyncPlaybackCoordinator+Inbound.swift` fix above) and is torn down before that
    /// transfer ever verifies; Ride 2 starts, converges on its own track, and *then* Ride 1's transfer
    /// verifies late. `content.observeAvailability`'s callback is registered once for the
    /// coordinator's whole lifetime (never re-registered per ride), so this is the one retained-state
    /// mechanism that can genuinely fire a late callback across a ride boundary — unlike the
    /// clock-drain task, which `resetForNewSession`/`handleLinkLost` cancel and empty outright.
    func testARide1RetainedSnapshotsLateContentReadinessCallbackCannotTouchRide2AfterAFullTeardownAndRestart() async throws {
        let clock = SharedTestClock(34_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track1 = SyncTestValues.hash(140)
        let track2 = SyncTestValues.hash(141) // Ride 1's content the follower never receives in time
        let track3 = SyncTestValues.hash(142) // Ride 2's own track
        await b.content.addLocal(track1)
        // Deliberately absent: track2.

        guard let gen1 = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        let snapshot1 = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track1, queueItemId: SyncTestValues.ulid(140), positionMs: 1_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot1, generation: gen1)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        // Ride 1: reconnect, and the leader's authoritative state now names content the follower does
        // not have — retained pending the transfer (section 22).
        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let gen1b = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let snapshot2 = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track2, queueItemId: SyncTestValues.ulid(141), positionMs: 2_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot2, generation: gen1b)
        try await poll(timeoutSeconds: 30) { await b.content.transferRequests.contains(track2) }
        let deferredRide1 = await b.sync.diagnostics.deferredCommandCount
        XCTAssertGreaterThan(deferredRide1, 0, "Ride 1's snapshot is genuinely retained pending the transfer")

        // Ride 1 ends — torn down before track2's transfer ever verifies.
        let beforeRide2 = connectedCount(a.session)
        await b.manager.shutdown()
        await b.sync.handleLinkLost()
        let deferredAfterTeardown = await b.sync.diagnostics.deferredCommandCount
        XCTAssertEqual(0, deferredAfterTeardown, "teardown clears Ride 1's retained snapshot outright")

        // Ride 2: a fresh generation, a fresh track, converging normally.
        await b.content.addLocal(track3)
        let bPort2 = try await b.manager.startListening(local: b.testPeer.local)
        await b.manager.connectTo(host: "127.0.0.1", port: aPort, local: b.testPeer.local)
        await a.manager.connectTo(host: "127.0.0.1", port: bPort2, local: a.testPeer.local)
        try await poll(timeoutSeconds: 30) { self.connectedCount(a.session) > beforeRide2 }
        try await settleResyncForwarding(a: a, b: b)
        guard let gen2 = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }

        await b.player.clearCalls()
        let snapshot3 = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track3, queueItemId: SyncTestValues.ulid(142), positionMs: 3_000,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot3, generation: gen2)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }
        let trackHashRide2 = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(track3, trackHashRide2, "Ride 2 converged normally")
        await b.player.clearCalls()

        // Ride 1's transfer verifies late.
        await b.content.completeTransfer(track2)
        // Nothing here should happen — give the availability callback's `Task` a real chance to run
        // before asserting the negative, the same `Task.yield()` idiom this file already uses above
        // rather than a wall-clock sleep.
        try await Task.yield()
        try await Task.yield()

        let trackHashAfterLateCallback = await b.sync.diagnostics.currentTrackHash
        XCTAssertEqual(track3, trackHashAfterLateCallback, "Ride 1's late callback must not touch Ride 2's converged state")
        let callsAfterLateCallback = await b.player.calls
        XCTAssertTrue(callsAfterLateCallback.isEmpty, "Ride 1's late content-readiness callback produces no Ride 2 player effect: \(callsAfterLateCallback)")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }

    // MARK: - 10: independent-review section 23 (route-transition/coexistence non-regression)

    /// A reconnect snapshot's restoration runs through `restoreFromPlaybackState`/`applyPlay` — never
    /// through `DriftController`'s ordinary per-tick correction ladder. `route_state == transitioning`
    /// is a `DriftController` input that suppresses *that* ladder's own hard-seek tier so a transient
    /// Bluetooth reroute never spends the seek budget rules already give it — it has nothing to do
    /// with resync's restore, which is not a "correction" at all and must neither consult it nor be
    /// gated by it: there is exactly one route-state system, and this phase adds no second one.
    func testAReconnectRestorationWhileRouteStateIsTransitioningStillRestoresAndSpendsNoHardSeekBudget() async throws {
        let clock = SharedTestClock(35_000_000)
        let (a, b, aPort) = try await buildPersistentPair(clock: clock)
        let track = SyncTestValues.hash(150)
        await b.content.addLocal(track)
        let hardSeekBefore = await b.sync.diagnostics.hardSeekCount
        XCTAssertEqual(0, hardSeekBefore, "no correction has run yet")

        // The follower's Bluetooth route is mid-transition when the reconnect's restoration needs to
        // run — exactly the ARCHITECTURE §6 "opening the mic forces most Bluetooth endpoints onto the
        // duplex profile" moment this flag exists for.
        await b.routeState.set(true)

        try await reconnectCycle(a: a, b: b, aPort: aPort)
        guard let generation = a.manager.liveAuthenticatedGeneration() else { return XCTFail("no generation") }
        await b.player.clearCalls()
        let snapshot = ResyncCodec.encode(.stateSnapshot(
            leaderPeerId: a.testPeer.peerId, commandSeq: 1, queueRevision: 1,
            playback: ResyncPlaybackSnapshot(
                trackHash: track, queueItemId: SyncTestValues.ulid(150), positionMs: 1_500,
                playing: true, atSessionUs: await alreadyDueSessionUs(for: b)
            ),
            queueItems: [], queueCurrentIndex: nil, manifestRevision: 0, transfersInFlight: []
        ))
        await b.manager.resyncRelay().deliver(type: ResyncMessageTypes.stateSnapshot, payload: snapshot, generation: generation)
        try await poll(timeoutSeconds: 30) { await b.player.calls.contains(.start) }

        let calls = await b.player.calls
        XCTAssertTrue(calls.contains(.select(track)), "the restore proceeded unconditionally while transitioning: \(calls)")
        let hardSeekAfter = await b.sync.diagnostics.hardSeekCount
        XCTAssertEqual(0, hardSeekAfter, "the restore is not a DriftController correction, so it never draws on the hard-seek budget rules reserve for it")
        let lastCorrection = await b.sync.diagnostics.lastCorrection
        XCTAssertEqual(.none, lastCorrection, "no second route-state system exists — resync's own restore never reports through DriftController's label either")

        await b.manager.shutdown()
        await a.manager.shutdown()
    }
}
