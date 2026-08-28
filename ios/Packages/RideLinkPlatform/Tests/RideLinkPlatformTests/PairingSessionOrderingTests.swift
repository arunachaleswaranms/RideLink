import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// Reproduces `PairingSessionIntegrationTests`' trust-gate properties through the **production
/// delivery path** — `OrderedFsmSession`, wired exactly like `SessionCoordinator.startDiscovery`
/// (`ControlSessionManager.emit` -> `channel.send` -> one long-lived `Task` draining
/// `channel.stream`) — rather than through `FsmSession`'s synchronous, order-preserving-by-
/// construction callback. `FsmSession`'s own doc comment says as much: it cannot stand in for
/// this, because the property under test is exactly the one it does not exercise.
///
/// These are real TLS 1.3 handshakes between two real `ControlSessionManager`s, same as the tests
/// they mirror — nothing about the crypto changes here, only how the resulting events are
/// delivered to the FSM.
final class PairingSessionOrderingTests: XCTestCase {
    private func clock() -> @Sendable () -> Int64 {
        let counter = OrderingClock(2_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    // MARK: - unknown peer: PairingRequired -> PairingSucceeded -> Connected

    func testUnknownPeerOrderedDeliveryReachesConnectedOnlyAfterPairingSucceeded() async throws {
        try await twoPhones("aa11aa11aa11aa11", "bb22bb22bb22bb22") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)

            try await a.awaitStatus(.connected)
            try await b.awaitStatus(.connected)

            for session in [a, b] {
                // Item 1 of the acceptance list: PairingRequired -> PairingSucceeded -> Connected,
                // and never Connected without PairingSucceeded first, even through the async
                // channel + single-consumer-Task delivery path.
                XCTAssertEqual(
                    session.gateEventNames, ["PairingRequired", "PairingSucceeded", "Connected"],
                    "ordered delivery must not reorder the trust-gate events")
                XCTAssertEqual(
                    session.visitedStatuses, [.idle, .discovering, .pairing, .connecting, .connected],
                    "PAIRING -> CONNECTING -> CONNECTED, never PAIRING -> CONNECTED directly")
            }
        }
    }

    // MARK: - trusted peer: PeerTrusted -> Connected, no SAS prompt

    func testTrustedPeerOrderedDeliveryReachesConnectedViaPeerTrustedWithNoPrompt() async throws {
        let (a, b) = try TestSessions.pairedPeers("cc33cc33cc33cc33", "dd44dd44dd44dd44")
        try await withPhones(a, b) { sessionA, sessionB, _ in
            try await sessionA.awaitStatus(.connected)
            try await sessionB.awaitStatus(.connected)

            for session in [sessionA, sessionB] {
                XCTAssertEqual(
                    session.gateEventNames, ["PeerTrusted", "Connected"],
                    "a known peer's ordered delivery must still carry PeerTrusted before Connected")
                let prompt = await session.manager.pairingPrompt
                XCTAssertNil(prompt, "a known peer is never asked for a code")
            }
        }
    }

    // MARK: - stale event after teardown cannot mutate state

    func testDetachingThenSendingDirectlyCannotAdvanceTheFsm() async throws {
        try await twoPhones("ee55ee55ee55ee55", "ff66ff66ff66ff66") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitStatus(.connected)

            let statusBefore = a.status
            let eventsBefore = a.events.count
            a.detach()
            // Mirrors a `ControlSessionManager` callback still firing after `SessionCoordinator`
            // has moved on: with the consumer cancelled and the channel finished, this must be
            // silently dropped, exactly as `OrderedEventChannelTests
            // .testSendAfterFinishIsADroppedNoOp` proves for the channel alone.
            a.sendDirectly(.connected(remotePeerId: PeerId("0000000000000099"), sessionId: SessionId("stale"), isLocalLeader: true))
            try await Task.sleep(nanoseconds: 200_000_000)

            XCTAssertEqual(a.status, statusBefore, "a stale post-teardown event must not move the FSM")
            XCTAssertEqual(a.events.count, eventsBefore, "a stale post-teardown event must not even be recorded")
        }
    }

    // MARK: - helpers (mirrors `PairingSessionIntegrationTests`' own helpers)

    private func twoPhones(
        _ aPeerId: String,
        _ bPeerId: String,
        _ body: (OrderedFsmSession, OrderedFsmSession, () -> [Int]) async throws -> Void
    ) async throws {
        let a = try TestSessions.unpairedPeer(aPeerId, name: "A")
        let b = try TestSessions.unpairedPeer(bPeerId, name: "B")
        try await withPhones(a, b, body)
    }

    private func withPhones(
        _ a: TestPeer,
        _ b: TestPeer,
        _ body: (OrderedFsmSession, OrderedFsmSession, () -> [Int]) async throws -> Void
    ) async throws {
        let channelA = CountingControlChannel(a.channel())
        let channelB = CountingControlChannel(b.channel())
        let sessionA = OrderedFsmSession(peer: a, manager: a.manager(monotonicNowUs: clock(), channelOverride: channelA))
        let sessionB = OrderedFsmSession(peer: b, manager: b.manager(monotonicNowUs: clock(), channelOverride: channelB))
        await sessionA.attach()
        await sessionB.attach()

        let portA = try await sessionA.manager.startListening(local: a.local)
        let portB = try await sessionB.manager.startListening(local: b.local)

        for session in [sessionA, sessionB] {
            session.apply(.startDiscovery)
            session.apply(.peerSelected)
        }

        await sessionA.manager.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await sessionB.manager.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        try await body(sessionA, sessionB) { [channelA, channelB] in [channelA.dials, channelB.dials] }

        sessionA.detach()
        sessionB.detach()
        await sessionA.manager.shutdown()
        await sessionB.manager.shutdown()
    }
}

private final class OrderingClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ start: Int64) { value = start }

    func incrementAndGet(by delta: Int64) -> Int64 {
        lock.withLock {
            value += delta
            return value
        }
    }
}
