import Foundation
import RideLinkCore
import Security
import XCTest
@testable import RideLinkPlatform

/// The Phase 1b secure control channel, end to end, over **real loopback TCP with a real TLS 1.3
/// handshake** (ADR-007, ADR-012, ADR-017, ADR-018).
///
/// The mirror of Android's `TlsControlChannelTest`, and the test that would catch the failure the
/// six-digit SAS exists to prevent: two phones deriving *different* codes from one handshake is,
/// to the two users, indistinguishable from a man-in-the-middle — and the correct response to
/// seeing it would be to refuse to pair. So it has to fail on a laptop, never on a bike.
///
/// It also covers the two things the Phase 1b spike flagged as the highest-risk items on this
/// platform (ARCHITECTURE §12): that a hand-encoded DER certificate survives Apple's own parser and
/// `SecIdentityCreate`, and that a keying-material exporter is reachable from public API.
final class TlsControlChannelTests: XCTestCase {
    // Static, and every concurrent call site goes through `Self.`: Swift 6 region isolation treats
    // an `async let` that reaches for `self` (a non-Sendable XCTestCase) as a data-race risk, and
    // it is right to — these helpers hold no state, so there is nothing to share.
    private static let alicePeerId = PeerId("aaaaaaaaaaaaaaaa")
    private static let bobPeerId = PeerId("bbbbbbbbbbbbbbbb")

    private static func localIdentity(_ identity: DeviceIdentity, _ name: String) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(
            displayName: name,
            platform: "ios",
            osVersion: "test",
            appVersion: "0.1.0",
            connTiebreak: ConnTiebreakGenerator.generate(),
            identitySpkiSha256: identity.identitySpkiSha256
        )
    }

    /// Runs one full connect + mutual TLS handshake and hands both ends to `body`.
    private func connected(
        server serverIdentity: DeviceIdentity,
        client clientIdentity: DeviceIdentity,
        _ body: (_ server: ControlConnection, _ client: ControlConnection) async throws -> Void
    ) async throws {
        let listener = try await TestTlsSupport.channel(serverIdentity).bind()
        defer { listener.close() }
        async let accepted = listener.accept()
        let client = try await TestTlsSupport.channel(clientIdentity).connect(host: "127.0.0.1", port: listener.localPort)
        let server = try await accepted
        defer { server.close(); client.close() }
        try await body(server, client)
    }

    // MARK: - The identity chain

    func testAHandWrittenDerCertificateIsAcceptedByApplesOwnParserAndBecomesATlsIdentity() throws {
        // ARCHITECTURE §12's highest-risk Phase 1b item: `SecKey` has no certificate-building API,
        // so RideLink encodes its own DER. If Apple's parser ever stops accepting it, everything
        // above this line stops working, and it must fail here rather than as an unexplained
        // handshake failure on the peer.
        let identity = try TestTlsSupport.freshIdentity()
        XCTAssertNotNil(SecCertificateCreateWithData(nil, identity.certificateDER as CFData))
        XCTAssertFalse(identity.certificateDER.isEmpty)

        let certificate = try XCTUnwrap(SecCertificateCreateWithData(nil, identity.certificateDER as CFData))
        // ADR-017 §2: a constant subject that carries no device information.
        XCTAssertEqual(IdentityCertificate.subjectCommonName, SecCertificateCopySubjectSummary(certificate) as String?)
        // Self-signature verifies and the certificate is inside its validity window.
        XCTAssertTrue(PeerCertificateInspector.isStructurallyValid(certificate))
        // And the SPKI derived from the parsed certificate matches the one derived at creation.
        XCTAssertEqual(identity.identitySpkiSha256, try PeerCertificateInspector.identitySpkiSha256(of: certificate))
    }

    func testReIssuingAroundTheSameKeyKeepsTheIdentityUnchanged() throws {
        // ADR-012's central behaviour, and the reason RideLink encodes its own certificate rather
        // than relying on a platform-issued one (ADR-017 §3): a new serial, a new validity window
        // and a new self-signature around an unchanged key must be a non-event.
        let store = DeviceIdentityStore(storage: .ephemeral)
        let key = try XCTUnwrap(SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: false],
        ] as CFDictionary, nil))

        let first = try store.issueIdentity(privateKey: key, now: UtcTime(TestTlsSupport.nowEpochSeconds))
        let second = try store.issueIdentity(
            privateKey: key, now: UtcTime(TestTlsSupport.nowEpochSeconds + TestTlsSupport.oneHourSeconds))

        XCTAssertEqual(first.identitySpkiSha256, second.identitySpkiSha256, "re-issuing must not change the pin")
        XCTAssertNotEqual(first.certificateDER, second.certificateDER, "but it must be a different certificate")
    }

    func testAnIdentityIsRejectedIfItIsNotP256() throws {
        // ADR-017 §1 admits exactly one key shape, so algorithm confusion cannot become a class of
        // bug. An RSA certificate must never produce something that looks like an identity.
        let rsaKey = try XCTUnwrap(SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: false],
        ] as CFDictionary, nil))
        XCTAssertThrowsError(try DeviceIdentityStore(storage: .ephemeral)
            .issueIdentity(privateKey: rsaKey, now: UtcTime(TestTlsSupport.nowEpochSeconds)))
    }

    // MARK: - The handshake

    func testARealTls13HandshakeCompletesAndBothEndsSeeEachOthersSpki() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        try await connected(server: alice, client: bob) { server, client in
            let serverSecurity = try XCTUnwrap(server.security, "a TLS connection must carry ChannelSecurity")
            let clientSecurity = try XCTUnwrap(client.security)

            XCTAssertEqual("TLSv1.3", serverSecurity.negotiatedProtocolDescription)
            XCTAssertEqual("TLSv1.3", clientSecurity.negotiatedProtocolDescription)

            // Mutual authentication: each side derived the *other's* pinned identity.
            XCTAssertEqual(bob.identitySpkiSha256, serverSecurity.peerIdentitySpkiSha256)
            XCTAssertEqual(alice.identitySpkiSha256, clientSecurity.peerIdentitySpkiSha256)
            XCTAssertNotEqual(alice.identitySpkiSha256, bob.identitySpkiSha256)

            XCTAssertTrue(serverSecurity.peerCertificateStructurallyValid)
            XCTAssertTrue(clientSecurity.peerCertificateStructurallyValid)
        }
    }

    func testBothEndsDeriveTheSameSixDigitSasFromOneHandshake() async throws {
        try await connected(server: try TestTlsSupport.freshIdentity(), client: try TestTlsSupport.freshIdentity()) { server, client in
            let serverSas = try XCTUnwrap(server.security?.deriveSas6(), "server exporter unavailable")
            let clientSas = try XCTUnwrap(client.security?.deriveSas6(), "client exporter unavailable")

            XCTAssertEqual(serverSas, clientSas, "the two screens must show the same six digits")
            XCTAssertEqual(6, serverSas.count, "PROTOCOL §4.5.1: exactly six characters, always")
            XCTAssertTrue(serverSas.allSatisfy(\.isNumber))
            // Stable across repeated reads of the same handshake — the code a user is looking at
            // must not change while they read it out.
            XCTAssertEqual(serverSas, server.security?.deriveSas6())
        }
    }

    func testADifferentHandshakeProducesADifferentSas() async throws {
        var codes: [String] = []
        for _ in 0 ..< 2 {
            try await connected(server: try TestTlsSupport.freshIdentity(), client: try TestTlsSupport.freshIdentity()) { server, _ in
                codes.append(try XCTUnwrap(server.security?.deriveSas6()))
            }
        }
        // Two independent handshakes agreeing would mean the code is not bound to the session,
        // which is the entire property the SAS relies on. (A genuine 1-in-10^6 collision is
        // possible; a repeated value here in practice means the binding is broken.)
        XCTAssertNotEqual(codes[0], codes[1], "the SAS must be bound to a specific TLS session")
    }

    // MARK: - The pin decision

    func testAnUnknownPeerRequiresPairingAndAKnownPeerConnectsSilently() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()

        try await connected(server: alice, client: bob) { server, client in
            let empty = InMemoryTrustedPeerStore()
            let outcome = try await Self.handshake(server: server, client: client, alice: alice, bob: bob,
                                                   serverStore: empty, clientStore: empty)
            XCTAssertEqual("pairing_required", Self.decisionName(outcome), "an unknown peer must not connect silently")
        }

        try await connected(server: alice, client: bob) { server, client in
            let serverStore = InMemoryTrustedPeerStore([TestSessions.record(peerId: Self.bobPeerId, identity: bob)])
            let clientStore = InMemoryTrustedPeerStore([TestSessions.record(peerId: Self.alicePeerId, identity: alice)])
            let outcome = try await Self.handshake(server: server, client: client, alice: alice, bob: bob,
                                                   serverStore: serverStore, clientStore: clientStore)
            XCTAssertEqual("trusted", Self.decisionName(outcome), "a pinned peer must connect with no prompt")
        }
    }

    func testAChangedIdentityKeyIsRefusedWithPinMismatch() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let impostor = try TestTlsSupport.freshIdentity()

        try await connected(server: alice, client: impostor) { server, client in
            // The server has a pin for bobPeerId, but the peer presenting that peer_id now has a
            // different key. ADR-012: an unknown peer wearing a familiar name.
            let serverStore = InMemoryTrustedPeerStore([TestSessions.record(peerId: Self.bobPeerId, identity: bob)])
            async let clientOutcome = ControlHandshake.performAsInitiator(
                socket: client, localPeerId: Self.bobPeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: Self.localIdentity(impostor, "Impostor"), trustedPeers: InMemoryTrustedPeerStore())
            let serverOutcome = try await ControlHandshake.performAsAcceptor(
                socket: server, localPeerId: Self.alicePeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: Self.localIdentity(alice, "Alice"), trustedPeers: serverStore)

            XCTAssertEqual("pin_mismatch", Self.decisionName(serverOutcome),
                           "a changed key must be refused, not silently re-pinned")
            client.close()
            _ = try? await clientOutcome
            XCTAssertEqual(bob.identitySpkiSha256, serverStore.byPeerId(Self.bobPeerId)?.identitySpkiSha256,
                           "the stored pin must be untouched by a failed handshake")
        }
    }

    func testAHelloWhoseAdvisoryIdentityContradictsTheCertificateIsRefused() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        try await connected(server: alice, client: bob) { server, client in
            // The initiator advertises somebody else's SPKI while presenting its own certificate.
            // PROTOCOL §4.1: trust never derives from a field a peer can choose.
            let lying = LocalHandshakeIdentity(
                displayName: "Liar", platform: "ios", osVersion: "test", appVersion: "0.1.0",
                connTiebreak: ConnTiebreakGenerator.generate(), identitySpkiSha256: alice.identitySpkiSha256)
            async let clientOutcome = ControlHandshake.performAsInitiator(
                socket: client, localPeerId: Self.bobPeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: lying, trustedPeers: InMemoryTrustedPeerStore())
            let serverOutcome = try await ControlHandshake.performAsAcceptor(
                socket: server, localPeerId: Self.alicePeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: Self.localIdentity(alice, "Alice"), trustedPeers: InMemoryTrustedPeerStore())
            XCTAssertEqual("identity_mismatch", Self.decisionName(serverOutcome))
            client.close()
            _ = try? await clientOutcome
        }
    }

    func testAnExpiredCertificateIsRefusedAsCertificateInvalidNotAsAnAttack() async throws {
        // ADR-012 requires a distinct code here so a phone with a wrong clock is not reported to
        // its owner as a security incident.
        let alice = try TestTlsSupport.freshIdentity()
        let ancient = try TestTlsSupport.freshIdentity(now: UtcTime(TestTlsSupport.expiredIssueEpochSeconds))
        try await connected(server: alice, client: ancient) { server, client in
            // The initiator's own peer (Alice) is fine, so it sends HELLO and waits for a reply
            // that never comes — the acceptor rejected the expired certificate before answering.
            // In production `ControlSessionManager.refuse` sends ERROR and closes, which is what
            // unblocks the peer; here the test closes the connection itself.
            async let clientOutcome = ControlHandshake.performAsInitiator(
                socket: client, localPeerId: Self.bobPeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: Self.localIdentity(ancient, "Old"), trustedPeers: InMemoryTrustedPeerStore())
            let serverOutcome = try await ControlHandshake.performAsAcceptor(
                socket: server, localPeerId: Self.alicePeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
                local: Self.localIdentity(alice, "Alice"), trustedPeers: InMemoryTrustedPeerStore())
            XCTAssertEqual("certificate_invalid", Self.decisionName(serverOutcome))
            client.close()
            _ = try? await clientOutcome
        }
    }

    // MARK: - Helpers

    private static func handshake(
        server: ControlConnection,
        client: ControlConnection,
        alice: DeviceIdentity,
        bob: DeviceIdentity,
        serverStore: InMemoryTrustedPeerStore,
        clientStore: InMemoryTrustedPeerStore
    ) async throws -> HandshakeOutcome {
        async let serverOutcome = ControlHandshake.performAsAcceptor(
            socket: server, localPeerId: Self.alicePeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
            local: Self.localIdentity(alice, "Alice"), trustedPeers: serverStore)
        let clientOutcome = try await ControlHandshake.performAsInitiator(
            socket: client, localPeerId: Self.bobPeerId, seqCounter: SeqCounter(), monotonicNowUs: { 0 },
            local: Self.localIdentity(bob, "Bob"), trustedPeers: clientStore)
        let resolved = try await serverOutcome
        // Both sides must reach the same verdict about each other, or one would pair while the
        // other connected silently.
        XCTAssertEqual(decisionName(resolved), decisionName(clientOutcome))
        return resolved
    }

    private static func decisionName(_ outcome: HandshakeOutcome) -> String {
        switch outcome {
        case .success(_, _, _, _, _, let decision):
            switch decision {
            case .trusted: return "trusted"
            case .pairingRequired: return "pairing_required"
            case .refused(let code): return code
            }
        case .rejected(let code): return code
        case .connectionClosed: return "connection_closed"
        }
    }
}
