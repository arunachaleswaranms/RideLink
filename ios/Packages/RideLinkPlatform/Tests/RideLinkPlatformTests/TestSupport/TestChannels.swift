import Foundation
import Network
import RideLinkCore
@testable import RideLinkPlatform

/// A plaintext `ControlChannel`, **for unit tests only**.
///
/// This file lives in the test target. That is the whole isolation mechanism, and it is stronger
/// than the `#if DEBUG` gate it replaces: a type in the test target is not compiled into the
/// library at all, so no app build — debug or release — contains these bytes. NFR-06 / PROTOCOL §1
/// say production control traffic is TLS 1.3; there is no runtime switch here to get wrong.
///
/// It exists because the framing suite wants a real socket without a TLS handshake's keys and
/// timing in the way. Anything that concerns *security* — pinning, the exporter, the SAS — is
/// tested against the real `TlsControlChannel` instead.
///
/// Its connections carry no `ChannelSecurity`, which makes the difference visible in the type
/// system: `ControlHandshake` refuses a connection with no security outright, so a test that wants
/// a handshake must use the TLS channel.
struct PlaintextControlChannelFixture: ControlChannel {
    let transportLabel = "PLAINTEXT TEST FIXTURE / NOT SECURE"
    let isSecure = false

    func bind() async throws -> ControlListener {
        try await ControlListener.bind(parameters: Self.parameters())
    }

    func connect(host: String, port: UInt16) async throws -> ControlConnection {
        try await ControlConnection.connect(host: host, port: port, parameters: Self.parameters())
    }

    private static func parameters() -> NWParameters {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        return parameters
    }
}

/// Test doubles for the one thing a laptop cannot supply: a Keychain-resident identity key.
///
/// Everything else is real. The certificate is encoded by the production `IdentityCertificate` and
/// signed by a real P-256 key through `SecKeyCreateSignature`; the TLS 1.3 handshake, the mutual
/// authentication and the exporter computation are Apple's shipping `Network.framework` — the same
/// frameworks and the same functions the iPhone runs.
///
/// What is substituted is *where the private key lives*: `.ephemeral` instead of `.keychain`,
/// because an unsigned `swift test` binary has no keychain entitlement. What that leaves unproven
/// — persistence across restart and upgrade, and `SecIdentityCreate` over a Keychain-resident key
/// — is recorded in `docs/test-results/phase1b-security-spike-20260827.md` §5 and closed by the
/// real-device gate.
enum TestTlsSupport {
    /// 27 August 2026, 12:00 UTC — a fixed instant, so no test depends on when it is run.
    static let nowEpochSeconds: Int64 = 1_787_832_000
    static let oneHourSeconds: Int64 = 3600

    /// 1 Jan 2000: ten years' validity from here closed long before the fixed "now".
    static let expiredIssueEpochSeconds: Int64 = 946_684_800

    static func freshIdentity(now: UtcTime = UtcTime(nowEpochSeconds)) throws -> DeviceIdentity {
        try DeviceIdentityStore(storage: .ephemeral).loadOrCreate(now: now)
    }

    static func channel(_ identity: DeviceIdentity, connectTimeoutMs: Int64 = 5000) -> TlsControlChannel {
        TlsControlChannel(identity: identity, connectTimeoutMs: connectTimeoutMs)
    }
}

/// One side of a test session: an identity, a `peer_id`, a trust store and the pieces built from
/// them.
///
/// **Peers are pre-paired by default.** A test about reconnect should not also be a test about
/// pairing: with an empty trust store every handshake would correctly stop for a six-digit code and
/// never reach `.connected`. `TestSessions.pairedPeers` seeds each side's store with the other's
/// real `identity_spki_sha256`, which is the "known peer, silent connect" path of PROTOCOL §4.1 —
/// so these tests assert transport behaviour on a trusted link, exactly as a ride would have it.
struct TestPeer {
    let peerId: PeerId
    let identity: DeviceIdentity
    let trustedPeers: InMemoryTrustedPeerStore
    let displayName: String

    var local: LocalHandshakeIdentity {
        LocalHandshakeIdentity(
            displayName: displayName,
            platform: "ios",
            osVersion: "test",
            appVersion: "test",
            connTiebreak: ConnTiebreakGenerator.generate(),
            identitySpkiSha256: identity.identitySpkiSha256
        )
    }

    func channel(connectTimeoutMs: Int64 = 5000) -> TlsControlChannel {
        TestTlsSupport.channel(identity, connectTimeoutMs: connectTimeoutMs)
    }

    /// - Parameter connectTimeoutMs: bounds each dial. It reaches the **channel**, which is what
    ///   opens the socket. That matters on Apple platforms specifically: `NWConnection` reports a
    ///   refused connection as `.waiting` rather than `.failed` and will retry on its own
    ///   indefinitely, so nothing except this timeout bounds a reconnect attempt against a dead
    ///   port. (Android gets an immediate `ECONNREFUSED` on loopback instead, which is why its
    ///   equivalent tests need no such knob.)
    /// - Parameter channelOverride: defaults to this peer's own real TLS channel. A test that needs
    ///   to observe the transport itself — how many connections a flow actually opens, say — passes
    ///   a wrapper.
    func manager(
        monotonicNowUs: @escaping @Sendable () -> Int64,
        connectTimeoutMs: Int64 = 5000,
        channelOverride: (any ControlChannel)? = nil
    ) -> ControlSessionManager {
        ControlSessionManager(
            localPeerId: peerId,
            channel: channelOverride ?? channel(connectTimeoutMs: connectTimeoutMs),
            trustedPeers: trustedPeers,
            monotonicNowUs: monotonicNowUs,
            nowEpochSeconds: { TestTlsSupport.nowEpochSeconds }
        )
    }

    /// Adds `other`'s real identity to this peer's trust store, so a handshake connects silently.
    func trust(_ other: TestPeer) throws {
        try trustedPeers.remember(TestSessions.record(peerId: other.peerId, identity: other.identity))
    }
}

enum TestSessions {
    static func pairedPeers(
        _ aPeerId: String,
        _ bPeerId: String,
        aName: String = "A",
        bName: String = "B"
    ) throws -> (TestPeer, TestPeer) {
        let aId = PeerId(aPeerId), bId = PeerId(bPeerId)
        let a = try TestTlsSupport.freshIdentity(), b = try TestTlsSupport.freshIdentity()
        return (
            TestPeer(peerId: aId, identity: a, trustedPeers: InMemoryTrustedPeerStore([record(peerId: bId, identity: b)]), displayName: aName),
            TestPeer(peerId: bId, identity: b, trustedPeers: InMemoryTrustedPeerStore([record(peerId: aId, identity: a)]), displayName: bName)
        )
    }

    /// A peer with an empty trust store — for tests that are *about* first-meeting pairing.
    static func unpairedPeer(_ peerId: String, name: String = "peer") throws -> TestPeer {
        TestPeer(
            peerId: PeerId(peerId),
            identity: try TestTlsSupport.freshIdentity(),
            trustedPeers: InMemoryTrustedPeerStore(),
            displayName: name
        )
    }

    static func record(peerId: PeerId, identity: DeviceIdentity) -> TrustedPeer {
        TrustedPeer(
            peerId: peerId,
            identitySpkiSha256: identity.identitySpkiSha256,
            displayName: "peer",
            pairedAtEpochSeconds: TestTlsSupport.nowEpochSeconds,
            lastSeenAtEpochSeconds: TestTlsSupport.nowEpochSeconds
        )
    }

    /// A port that is bound and immediately released, so a connect to it is refused. Uses the
    /// plaintext fixture deliberately: nothing ever completes a handshake against it, and a TLS
    /// listener would only add setup cost to a socket whose whole purpose is to not answer.
    static func deadPort() async throws -> UInt16 {
        let listener = try await PlaintextControlChannelFixture().bind()
        let port = listener.localPort
        listener.close()
        return port
    }
}
