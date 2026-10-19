import Foundation
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// STATUS §4 problem 125, reproduced four times on two physical Android phones (Phase 9B-A); the
/// iOS `ControlSessionManager.promote` had the identical shape. **A reconnect that the peer
/// completes by dialling back in left this side's own ladder running inside the live session.**
///
/// `promote` called `ReconnectController.reset()` and never stopped the ladder, so the ladder kept
/// dialling a stale address with a freshly zeroed budget, then wrote `.disconnected` into the
/// diagnostics of a connected session and emitted `.reconnectBudgetExhausted` — and left the budget
/// spent, so the next genuine link loss got zero attempts.
///
/// Real managers over real loopback TLS 1.3, the Android regression's sequence
/// (`ReconnectLadderOrphanTest`). Only the ladder's own waits are accelerated, through the manager's
/// `reconnectDelayMs` seam, and only once the promotion has happened.
final class ReconnectLadderOrphanTests: XCTestCase {
    /// Against unmodified `promote` this fails on the exhaustion.
    func testAnInboundPromotionDuringReconnectingEndsThisSidesOwnLadder() async throws {
        try await withInboundReconnect { sut in
            let countAtPromotion = await sut.manager.reconnectCount

            sut.speed.set(Self.fast)
            try await Task.sleep(nanoseconds: Self.orphanBudgetWindowNs)

            XCTAssertEqual(sut.session.count(where: Self.isExhaustion), 0, "no exhaustion in a live session")
            let state = await sut.manager.diagnostics.controlState
            XCTAssertEqual(state, .connected, "diagnostics still say connected")
            let countNow = await sut.manager.reconnectCount
            XCTAssertEqual(countNow, countAtPromotion, "no ladder attempt after the promotion")
            XCTAssertEqual(sut.session.status, .connected)
        }
    }

    /// Against unmodified `promote` this fails on the attempt count: the next ladder makes none.
    func testTheNextGenuineLinkLossGetsAFullFreshBudget() async throws {
        try await withInboundReconnect { sut in
            sut.speed.set(Self.fast)
            try await Task.sleep(nanoseconds: Self.orphanBudgetWindowNs)

            try await sut.dropDiallerConnection()
            try await sut.session.awaitStatus(.reconnecting)
            let countBefore = await sut.manager.reconnectCount
            await sut.manager.beginReconnect(local: sut.localPeer.local, host: "127.0.0.1", port: sut.deadPort)
            try await sut.session.awaitStatus(.disconnected)

            let attempts = await sut.manager.reconnectCount - countBefore
            XCTAssertGreaterThanOrEqual(attempts, Self.minAttemptsInAFullBudget, "a fresh 120 s ladder makes a full run of attempts, not \(attempts)")
            XCTAssertEqual(sut.session.count(where: Self.isExhaustion), 1, "exactly one exhaustion: this ladder's")
            let published = await sut.manager.diagnostics.reconnectCount
            XCTAssertEqual(published - countBefore, attempts, "the card shows every attempt")
        }
    }

    private static func isExhaustion(_ event: ControlEvent) -> Bool {
        if case .reconnectBudgetExhausted = event { return true }
        return false
    }

    // MARK: - Harness

    private final class Sut: @unchecked Sendable {
        let manager: ControlSessionManager
        let session: FsmSession
        let port: UInt16
        let dialler: ControlSessionManager
        let diallerPeer: TestPeer
        let localPeer: TestPeer
        let speed: LockedSpeed
        let deadPort: UInt16

        init(
            manager: ControlSessionManager, session: FsmSession, port: UInt16, dialler: ControlSessionManager,
            diallerPeer: TestPeer, localPeer: TestPeer, speed: LockedSpeed, deadPort: UInt16
        ) {
            self.manager = manager
            self.session = session
            self.port = port
            self.dialler = dialler
            self.diallerPeer = diallerPeer
            self.localPeer = localPeer
            self.speed = speed
            self.deadPort = deadPort
        }

        func connectAndAwait(expectedConnections: Int) async throws {
            try await poll { await self.dialler.currentReadBinding() == nil }
            await dialler.connectTo(host: "127.0.0.1", port: port, local: diallerPeer.local)
            try await poll {
                self.session.count { if case .connected = $0 { return true } else { return false } } >= expectedConnections
            }
        }

        /// Closes the dialler's end of the live connection without a `BYE`: a network loss.
        func dropDiallerConnection() async throws {
            let binding = await dialler.currentReadBinding()
            let live = try XCTUnwrap(binding, "the dialler's connection must be live")
            live.connection.close()
        }

        func poll(_ condition: @escaping @Sendable () async -> Bool) async throws {
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline {
                if await condition() { return }
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            throw ControlTransportError.notReady
        }
    }

    /// Leaves the pair connected by an **inbound** promotion that happened while this side's own
    /// ladder was running against a stale address.
    private func withInboundReconnect(_ body: (Sut) async throws -> Void) async throws {
        let (a, b) = try TestSessions.pairedPeers("cccccccccccccccc", "dddddddddddddddd")
        let clock = MonotonicCounter()
        let speed = LockedSpeed()
        let manager = ControlSessionManager(
            localPeerId: a.peerId,
            // A dial to a refused port sits in `.waiting` until this bound (see
            // `ControlConnection.connect`); it is this side's only outbound dial, so it is kept short
            // enough that a whole budget of attempts fits in the test.
            channel: a.channel(connectTimeoutMs: Self.deadDialTimeoutMs),
            trustedPeers: a.trustedPeers,
            monotonicNowUs: { clock.next() },
            nowEpochSeconds: { TestTlsSupport.nowEpochSeconds },
            reconnectDelayMs: { ms in await Self.scaledDelay(ms, speed: speed) }
        )
        let dialler = b.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()
        do {
            let port = try await manager.startListening(local: a.local)
            _ = try await dialler.startListening(local: b.local)
            XCTAssertTrue(session.apply(.startDiscovery) && session.apply(.peerSelected))
            let sut = Sut(
                manager: manager, session: session, port: port, dialler: dialler,
                diallerPeer: b, localPeer: a, speed: speed, deadPort: try await TestSessions.deadPort()
            )

            try await sut.connectAndAwait(expectedConnections: 1)
            try await session.awaitStatus(.connected)

            // The link drops; as `SessionCoordinator` does, this side starts its ladder — here
            // against an address nothing answers, like a remembered port from an earlier session.
            try await sut.dropDiallerConnection()
            try await session.awaitStatus(.reconnecting)
            await manager.beginReconnect(local: a.local, host: "127.0.0.1", port: sut.deadPort)
            try await sut.poll { await manager.reconnectCount >= 1 }

            // The peer dials back in: an inbound promotion completes the reconnect.
            try await sut.connectAndAwait(expectedConnections: 2)
            try await session.awaitStatus(.connected)

            try await body(sut)
        } catch {
            await dialler.shutdown()
            await manager.shutdown()
            throw error
        }
        await dialler.shutdown()
        await manager.shutdown()
    }

    /// Waits `ms` of ladder time in real 10 ms steps whose worth is read each step, so a wait
    /// already in progress speeds up the moment the speed changes.
    private static func scaledDelay(_ ms: Int64, speed: LockedSpeed) async {
        var remaining = ms
        while remaining > 0 {
            try? await Task.sleep(nanoseconds: stepNs)
            if Task.isCancelled { return }
            remaining -= stepMs * speed.get()
        }
    }

    private static let stepMs: Int64 = 10
    private static let deadDialTimeoutMs: Int64 = 100
    private static let stepNs: UInt64 = 10_000_000
    /// One real 10 ms step stands for 10 s of ladder time: a whole budget in well under a second.
    private static let fast: Int64 = 1000
    /// Long enough for an orphaned ladder to finish its parked wait and spend 120 s at `fast`.
    private static let orphanBudgetWindowNs: UInt64 = 3_000_000_000
    /// 7.5 s on the first four rungs, then at most 9.6 s each: a 120 s budget holds at least 16.
    private static let minAttemptsInAFullBudget = 10
}

private final class LockedSpeed: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1

    func get() -> Int64 { lock.withLock { value } }
    func set(_ newValue: Int64) { lock.withLock { value = newValue } }
}

private final class MonotonicCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1_000_000

    func next() -> Int64 { lock.withLock { value += 1000; return value } }
}
