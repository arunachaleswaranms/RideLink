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

            let leaderPrepared = await leader.player.calls.contains { if case .prepare = $0 { return true } else { return false } }
            let followerPrepared = await follower.player.calls.contains { if case .prepare = $0 { return true } else { return false } }
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

    // MARK: - Harness

    /// A generous software bound — see the call site's comment. Not an audio figure.
    private static let softwareToleranceUs: Int64 = 100_000

    private static func expect(_ description: String, _ condition: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("timed out waiting for: \(description)")
    }

    /// One peer's Phase 5 stack over its real `ControlSessionManager`.
    private final class SyncPeer: @unchecked Sendable {
        let coordinator: SyncPlaybackCoordinator
        let player: FakeSyncPlayer
        let content: FakeSyncContent
        private let manager: ControlSessionManager

        init(manager: ControlSessionManager, localPeerId: PeerId, monotonicNowUs: @escaping @Sendable () -> Int64) {
            self.manager = manager
            let player = FakeSyncPlayer()
            self.player = player
            content = FakeSyncContent()
            coordinator = SyncPlaybackCoordinator(
                monotonicNowUs: monotonicNowUs,
                localPeerId: localPeerId,
                session: ControlSessionSyncPort(manager: manager),
                player: player,
                content: content,
                sleeper: MonotonicDeadlineSleeper(monotonicNowUs: monotonicNowUs),
                routeState: NeverTransitioningRoute(),
                nextQueueItemId: { Ulid.generate() }
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

    private func twoPairedPhones(_ body: (SyncPeer, SyncPeer) async throws -> Void) async throws {
        let a = try TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", name: "A")
        let b = try TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", name: "B")
        let monotonic: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000) }
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: monotonic))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: monotonic))
        await sessionA.attach()
        await sessionB.attach()

        let peerA = SyncPeer(manager: sessionA.manager, localPeerId: a.peerId, monotonicNowUs: monotonic)
        let peerB = SyncPeer(manager: sessionB.manager, localPeerId: b.peerId, monotonicNowUs: monotonic)
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
            if case .connected(_, _, let isLocalLeader) = event { return isLocalLeader }
        }
        return nil
    }
}

/// The drift ladder's suspension condition, never true here: this test is about scheduling, and a
/// route transition is Phase 2b state a loopback socket has no way to produce.
private struct NeverTransitioningRoute: SyncRouteStatePort {
    func isRouteTransitioning() async -> Bool { false }
}
