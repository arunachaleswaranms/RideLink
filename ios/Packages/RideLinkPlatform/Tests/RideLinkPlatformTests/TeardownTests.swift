import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// This session's brief §9/§10: on shutdown, every task the session owns must actually stop —
/// reconnect, the active socket, pending PING waiters, candidate sockets held by the duplicate-
/// connection arbiter — and a fresh session started afterward on the same (reused)
/// `ControlSessionManager` instance must work cleanly, with nothing left over from the torn-down
/// one. Mirrors Android's `TeardownTest`.
final class TeardownTests: XCTestCase {
    private func localIdentity(_ name: String) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(displayName: name, platform: "ios", osVersion: "test", appVersion: "test", connTiebreak: ConnTiebreakGenerator.generate())
    }

    private func clock() -> @Sendable () -> Int64 {
        let counter = LockedCounter(1_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    func testShutdownStopsTheReconnectLadderNoFurtherAttemptsAfterward() async throws {
        let peer = ControlSessionManager(localPeerId: PeerId("1010101010101010"), monotonicNowUs: clock(), connectTimeoutMs: 200)
        let deadListener = try await ControlListener.bind()
        let deadPort = deadListener.localPort
        deadListener.close()

        await peer.beginReconnect(local: localIdentity("A"), host: "127.0.0.1", port: deadPort)
        try await Task.sleep(nanoseconds: 200_000_000) // let the ladder get going
        await peer.shutdown()

        let countAtShutdown = await peer.reconnectCount
        try await Task.sleep(nanoseconds: 2_000_000_000)
        let countAfterWaiting = await peer.reconnectCount
        XCTAssertEqual(countAtShutdown, countAfterWaiting, "no reconnect attempt may run after shutdown")
    }

    func testShutdownClosesTheActiveSocketThePeerObservesConnectionClosed() async throws {
        let sut = ControlSessionManager(localPeerId: PeerId("2020202020202020"), monotonicNowUs: clock())
        let port = try await sut.startListening(local: localIdentity("SUT"))

        let fakePeerId = PeerId("3030303030303030")
        let fake = try await ControlConnection.connect(host: "127.0.0.1", port: port)
        let outcome = try await ControlHandshake.performAsInitiator(
            socket: fake, localPeerId: fakePeerId, seqCounter: SeqCounter(), monotonicNowUs: clock(), local: localIdentity("fake")
        )
        guard case .success = outcome else { XCTFail("handshake must succeed: \(outcome)"); return }

        try await waitUntil(timeoutSeconds: 5) { await sut.diagnostics.controlState == .connected }

        await sut.shutdown()

        // The SUT's own keepalive/clock-sync loop may still be sending its own PING/PONG traffic
        // on this socket in the instant before shutdown's cancellation takes effect; skip over
        // those and look for the actual teardown signal — BYE, or the socket simply closing.
        var closedCleanly = false
        let deadline = Date().addingTimeInterval(5)
        while !closedCleanly, Date() < deadline {
            switch await fake.readFrame() {
            case .connectionClosed:
                closedCleanly = true
            case .frame(let envelope, _):
                if envelope.type == "BYE" { closedCleanly = true }
            default:
                break
            }
        }
        XCTAssertTrue(closedCleanly)
        fake.close()
    }

    func testANewSessionOnTheSameReusedManagerStartsCleanlyAfterShutdown() async throws {
        let sut = ControlSessionManager(localPeerId: PeerId("4040404040404040"), monotonicNowUs: clock())
        let peerB1 = ControlSessionManager(localPeerId: PeerId("5050505050505050"), monotonicNowUs: clock())

        let portB1 = try await peerB1.startListening(local: localIdentity("B1"))
        let portSut1 = try await sut.startListening(local: localIdentity("SUT"))
        await sut.connectTo(host: "127.0.0.1", port: portB1, local: localIdentity("SUT"))
        await peerB1.connectTo(host: "127.0.0.1", port: portSut1, local: localIdentity("B1"))
        try await waitUntil(timeoutSeconds: 5) { await sut.diagnostics.controlState == .connected }

        await sut.shutdown()
        await peerB1.shutdown()
        let stateAfterShutdown = await sut.diagnostics.controlState
        XCTAssertEqual(stateAfterShutdown, .ended)

        // Reuse the SAME sut instance for a fresh session, matching how SessionCoordinator holds
        // one ControlSessionManager for the app's lifetime.
        let peerB2 = ControlSessionManager(localPeerId: PeerId("6060606060606060"), monotonicNowUs: clock())
        let portB2 = try await peerB2.startListening(local: localIdentity("B2"))
        let portSut2 = try await sut.startListening(local: localIdentity("SUT"))
        await sut.connectTo(host: "127.0.0.1", port: portB2, local: localIdentity("SUT"))
        await peerB2.connectTo(host: "127.0.0.1", port: portSut2, local: localIdentity("B2"))

        try await waitUntil(timeoutSeconds: 5) { await sut.diagnostics.controlState == .connected }
        let finalState = await sut.diagnostics.controlState
        XCTAssertEqual(finalState, .connected)

        await sut.shutdown()
        await peerB2.shutdown()
    }

    private func waitUntil(timeoutSeconds: Double, _ predicate: () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await predicate() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw ControlTransportError.notReady
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ initial: Int64) { value = initial }

    func incrementAndGet(by delta: Int64) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += delta
        return value
    }
}
