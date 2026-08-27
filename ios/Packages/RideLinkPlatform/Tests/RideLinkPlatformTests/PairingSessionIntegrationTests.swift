import Security
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// The Phase 1b security invariant, end to end over real TLS:
///
/// > For an **unknown** peer there is no execution path that reaches `CONNECTED` before SAS
/// > confirmation on both sides and trust persistence.
///
/// Two real `ControlSessionManager`s stand in for the two phones — real P-256 identities, a real
/// TLS 1.3 handshake, real mutual authentication, a real exporter-derived six-digit code — and each
/// one's `ControlEvent` stream drives a real `SessionGate` and a real `SessionFsm`, which is
/// exactly the wiring `SessionCoordinator` has.
///
/// The bug these exist to keep dead: `ControlEvent.connected` used to be emitted the moment
/// duplicate resolution picked a survivor, and `SessionCoordinator` read it as implicit pairing
/// success. An unknown peer therefore reached `CONNECTED` *before* the six digits were even
/// displayed. See `docs/STATUS.md` §4.
///
/// The Android mirror is `PairingSessionIntegrationTest`; the two assert the same properties.
final class PairingSessionIntegrationTests: XCTestCase {
    private func clock() -> @Sendable () -> Int64 {
        let counter = PairingClock(1_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    // MARK: - unknown peer

    func testAnUnknownPeerHoldsTheSessionInPairingWithOneCodeAndNoConnected() async throws {
        try await twoPhones("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb") { a, b, _ in
            let promptA = try await a.awaitPairingPrompt()
            let promptB = try await b.awaitPairingPrompt()

            // Both screens show the same six digits — the whole point of the exporter binding.
            XCTAssertEqual(promptA.sas6, promptB.sas6, "a man-in-the-middle is what two different codes look like")
            XCTAssertEqual(promptA.sas6.count, 6)
            XCTAssertTrue(promptA.sas6.allSatisfy(\.isNumber))

            // PROTOCOL §4.2: one prompt per device even though two connections were dialled.
            XCTAssertEqual(a.count { if case .pairingRequired = $0 { return true }; return false }, 1)
            XCTAssertEqual(b.count { if case .pairingRequired = $0 { return true }; return false }, 1)

            assertNoConnected(a, b)
            XCTAssertEqual(a.status, .pairing)
            XCTAssertEqual(b.status, .pairing)
            assertNoTrust(a, b)
        }
    }

    func testStartRideIsRefusedWhileTheSessionIsStillPairing() async throws {
        try await twoPhones("0000000000000001", "0000000000000002") { a, _, _ in
            try await a.awaitPairingPrompt()
            XCTAssertFalse(a.apply(.startRide), "a ride cannot start over an unauthenticated peer")
            XCTAssertEqual(a.status, .pairing)
        }
    }

    func testThisUserConfirmingAlonePairsNothing() async throws {
        try await twoPhones("1111111111111111", "2222222222222222") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()

            await a.manager.confirmPairing(accepted: true)
            try await Task.sleep(nanoseconds: FsmSession.settleNs)

            XCTAssertEqual(a.status, .pairing, "one screen's yes is not a pairing")
            XCTAssertEqual(b.status, .pairing)
            assertNoConnected(a, b)
            assertNoTrust(a, b)
            let prompt = await a.manager.pairingPrompt
            XCTAssertNotNil(prompt, "the code stays up until the other user answers")
        }
    }

    func testThePeerConfirmingAlonePairsNothing() async throws {
        try await twoPhones("3333333333333333", "4444444444444444") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()

            await b.manager.confirmPairing(accepted: true)
            try await Task.sleep(nanoseconds: FsmSession.settleNs)

            XCTAssertEqual(a.status, .pairing, "the remote user cannot pair on this user's behalf")
            XCTAssertEqual(b.status, .pairing)
            assertNoConnected(a, b)
            assertNoTrust(a, b)
        }
    }

    func testBothUsersConfirmingPairsOnceClearsTheCodeAndOnlyThenReachesConnected() async throws {
        try await twoPhones("5555555555555555", "6666666666666666") { a, b, dials in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)

            try await a.awaitStatus(.connected)
            try await b.awaitStatus(.connected)

            for session in [a, b] {
                // The order is the invariant. PairingRequired, then the pin written, then — and
                // only then — a connection the FSM may treat as authenticated.
                XCTAssertEqual(
                    session.gateEventNames, ["PairingRequired", "PairingSucceeded", "Connected"],
                    "trust-gate event order")
                XCTAssertEqual(
                    session.visitedStatuses, [.idle, .discovering, .pairing, .connecting, .connected],
                    "PAIRING and CONNECTING are distinct states and neither is skipped")
                let prompt = await session.manager.pairingPrompt
                XCTAssertNil(prompt, "the six digits are dropped the moment pairing settles")
                let reconnects = await session.manager.reconnectCount
                XCTAssertEqual(reconnects, 0)
            }

            // Exactly one trusted-peer record per side, pinning the other's real SPKI.
            XCTAssertEqual(a.trustStore.byPeerId(b.peer.peerId)?.identitySpkiSha256, b.peer.identity.identitySpkiSha256)
            XCTAssertEqual(b.trustStore.byPeerId(a.peer.peerId)?.identitySpkiSha256, a.peer.identity.identitySpkiSha256)
            XCTAssertEqual(a.trustStore.all().count, 1, "the losing candidate must not have written a pin of its own")
            XCTAssertEqual(b.trustStore.all().count, 1)

            // PROTOCOL §4.5.1: the code was bound to one exporter, so pairing succeeding must
            // continue on that same connection rather than dial a fresh one.
            XCTAssertEqual(dials(), [1, 1], "pairing must not open a second TLS connection")
        }
    }

    // MARK: - refusal

    func testThisUserRejectingNeverConnectsAndWritesNoPin() async throws {
        try await twoPhones("7777777777777777", "8888888888888888") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()

            await a.manager.confirmPairing(accepted: false)
            try await a.awaitEvent { if case .pairingFailed = $0 { return true }; return false }
            try await b.awaitEvent { if case .pairingFailed = $0 { return true }; return false }

            try await assertPairingRefused(a, b)
        }
    }

    func testThePeerRejectingNeverConnectsAndWritesNoPin() async throws {
        try await twoPhones("9999999999999999", "aaaaaaaabbbbbbbb") { a, b, _ in
            try await a.awaitPairingPrompt()
            try await b.awaitPairingPrompt()

            await b.manager.confirmPairing(accepted: false)
            try await a.awaitEvent { if case .pairingFailed = $0 { return true }; return false }
            try await b.awaitEvent { if case .pairingFailed = $0 { return true }; return false }

            try await assertPairingRefused(a, b)
        }
    }

    // MARK: - known peer

    func testAPeerWhosePinMatchesConnectsSilentlyAndFast() async throws {
        let (a, b) = try TestSessions.pairedPeers("cccccccccccccccc", "dddddddddddddddd")
        try await withPhones(a, b) { sessionA, sessionB, _ in
            try await sessionA.awaitStatus(.connected)
            try await sessionB.awaitStatus(.connected)

            for session in [sessionA, sessionB] {
                XCTAssertEqual(
                    session.gateEventNames, ["PeerTrusted", "Connected"],
                    "a known peer passes the trust gate on the pin alone")
                let prompt = await session.manager.pairingPrompt
                XCTAssertNil(prompt, "a known peer is never asked for a code")
                XCTAssertEqual(session.count { if case .pairingRequired = $0 { return true }; return false }, 0)
            }
        }
    }

    func testACertificateReIssuedAroundTheSameKeyStaysTrustedAndAsksForNoCode() async throws {
        // B's certificate is regenerated around B's existing key: new serial, new validity window,
        // new self-signature, same SPKI (ADR-012 / PROTOCOL §4.5.3).
        let store = DeviceIdentityStore(storage: .ephemeral)
        let bKey = try XCTUnwrap(SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: false],
        ] as CFDictionary, nil))
        let firstB = try store.issueIdentity(privateKey: bKey, now: UtcTime(TestTlsSupport.nowEpochSeconds))
        let reissuedB = try store.issueIdentity(
            privateKey: bKey, now: UtcTime(TestTlsSupport.nowEpochSeconds + TestTlsSupport.oneHourSeconds))
        XCTAssertEqual(firstB.identitySpkiSha256, reissuedB.identitySpkiSha256, "re-issuing must not change the pin")
        XCTAssertNotEqual(firstB.certificateDER, reissuedB.certificateDER, "but it must be a different certificate")

        let aId = PeerId("eeeeeeeeeeeeeeee"), bId = PeerId("ffffffffffffffff")
        let aIdentity = try TestTlsSupport.freshIdentity()
        // A pinned B's *first* certificate; B now presents the second one.
        let a = TestPeer(
            peerId: aId, identity: aIdentity,
            trustedPeers: InMemoryTrustedPeerStore([TestSessions.record(peerId: bId, identity: firstB)]),
            displayName: "A")
        let b = TestPeer(
            peerId: bId, identity: reissuedB,
            trustedPeers: InMemoryTrustedPeerStore([TestSessions.record(peerId: aId, identity: aIdentity)]),
            displayName: "B")

        try await withPhones(a, b) { sessionA, sessionB, _ in
            try await sessionA.awaitStatus(.connected)
            try await sessionB.awaitStatus(.connected)
            XCTAssertEqual(
                sessionA.count { if case .pairingRequired = $0 { return true }; return false }, 0,
                "the pin is the key, not the certificate")
        }
    }

    func testForgettingATrustedPeerMakesTheNextConnectionAskForACodeAgain() async throws {
        let (a, b) = try TestSessions.pairedPeers("0123456789abcdef", "fedcba9876543210")
        a.trustedPeers.forget(b.peerId)
        b.trustedPeers.forget(a.peerId)
        try await withPhones(a, b) { sessionA, sessionB, _ in
            try await sessionA.awaitPairingPrompt()
            try await sessionB.awaitPairingPrompt()
            assertNoConnected(sessionA, sessionB)
            XCTAssertEqual(sessionA.status, .pairing)
        }
    }

    // MARK: - pin mismatch

    func testAPeerIdWearingADifferentKeyIsRefusedAndNeverConnects() async throws {
        let (a, b) = try TestSessions.pairedPeers("1234123412341234", "5678567856785678")
        // Same peer_id, different identity keypair — an unknown peer wearing a familiar name.
        let impostor = TestPeer(
            peerId: b.peerId, identity: try TestTlsSupport.freshIdentity(),
            trustedPeers: InMemoryTrustedPeerStore(), displayName: "B")

        try await withPhones(a, impostor) { sessionA, _, _ in
            try await sessionA.awaitEvent { if case .handshakeRefused = $0 { return true }; return false }

            var refusalCode: String?
            for event in sessionA.events {
                if case .handshakeRefused(let code) = event { refusalCode = code; break }
            }
            XCTAssertEqual(refusalCode, errorCodePinMismatch)
            let prompt = await sessionA.manager.pairingPrompt
            XCTAssertNil(prompt, "a pin mismatch is never resolved by re-pairing")
            XCTAssertEqual(sessionA.count { if case .pairingRequired = $0 { return true }; return false }, 0)
            XCTAssertFalse(sessionA.hasReached(.connected))
            XCTAssertEqual(
                sessionA.trustStore.byPeerId(b.peerId)?.identitySpkiSha256, b.identity.identitySpkiSha256,
                "the stored pin must be untouched")
        }
    }

    // MARK: - helpers

    private func assertNoConnected(_ sessions: FsmSession...) {
        for session in sessions {
            XCTAssertEqual(
                session.count { if case .connected = $0 { return true }; return false }, 0,
                "Connected before the trust gate passed: \(session.gateEventNames)")
            XCTAssertFalse(session.hasReached(.connected), "CONNECTED reached before pairing")
            XCTAssertFalse(session.hasReached(.connecting))
        }
    }

    private func assertNoTrust(_ sessions: FsmSession...) {
        for session in sessions {
            XCTAssertTrue(session.trustStore.all().isEmpty, "a pin was written without both confirmations")
        }
    }

    private func assertPairingRefused(_ a: FsmSession, _ b: FsmSession) async throws {
        for session in [a, b] {
            var code: String?
            for event in session.events {
                if case .pairingFailed(let failure) = event { code = failure; break }
            }
            XCTAssertEqual(code, errorCodePairingRejected)
            let prompt = await session.manager.pairingPrompt
            XCTAssertNil(prompt, "the six digits are dropped on failure too")
            XCTAssertFalse(session.hasReached(.connected))
            // PROTOCOL §4.5: back where PAIRING came from, and never onward.
            XCTAssertEqual(session.status, .discovering)
            let reconnects = await session.manager.reconnectCount
            XCTAssertEqual(reconnects, 0, "a refused pairing must not become a reconnect")
        }
        assertNoTrust(a, b)
    }

    private func twoPhones(
        _ aPeerId: String,
        _ bPeerId: String,
        _ body: (FsmSession, FsmSession, () -> [Int]) async throws -> Void
    ) async throws {
        let a = try TestSessions.unpairedPeer(aPeerId, name: "A")
        let b = try TestSessions.unpairedPeer(bPeerId, name: "B")
        try await withPhones(a, b, body)
    }

    /// Stands both phones up, walks each one's FSM as far as `.pairing` exactly as
    /// `SessionCoordinator.startDiscovery`/`maybeConnect` do, then has both dial each other at once.
    private func withPhones(
        _ a: TestPeer,
        _ b: TestPeer,
        _ body: (FsmSession, FsmSession, () -> [Int]) async throws -> Void
    ) async throws {
        let channelA = CountingControlChannel(a.channel())
        let channelB = CountingControlChannel(b.channel())
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: clock(), channelOverride: channelA))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: clock(), channelOverride: channelB))
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

        await sessionA.manager.shutdown()
        await sessionB.manager.shutdown()
    }
}

private final class PairingClock: @unchecked Sendable {
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
