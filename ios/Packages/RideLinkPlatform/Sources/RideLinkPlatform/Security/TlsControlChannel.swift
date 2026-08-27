import Foundation
import Network
import RideLinkCore
import Security

/// The channel-security facts for one completed TLS 1.3 handshake.
///
/// Note what is *not* here: nothing returns the peer's certificate, its subject, or anything else
/// a caller could accidentally start trusting. Trust is `peerIdentitySpkiSha256` and nothing else
/// (ADR-012).
struct TlsChannelSecurity: ChannelSecurity, @unchecked Sendable {
    let peerIdentitySpkiSha256: SpkiHash
    let peerCertificateStructurallyValid: Bool
    let negotiatedProtocolDescription: String
    private let metadata: sec_protocol_metadata_t

    init(
        peerIdentitySpkiSha256: SpkiHash,
        peerCertificateStructurallyValid: Bool,
        negotiatedProtocolDescription: String,
        metadata: sec_protocol_metadata_t
    ) {
        self.peerIdentitySpkiSha256 = peerIdentitySpkiSha256
        self.peerCertificateStructurallyValid = peerCertificateStructurallyValid
        self.negotiatedProtocolDescription = negotiatedProtocolDescription
        self.metadata = metadata
    }

    /// PROTOCOL §4.5.1 / ADR-018.
    ///
    /// Uses the **context-less** exporter call. That is not a shortcut: Apple's public API has no
    /// way to pass a present-but-zero-length context — `sec_protocol_metadata_create_secret_with_context`
    /// returns nil for `context_len: 0` — and under TLS 1.3 the two are the same input anyway,
    /// because RFC 8446 §7.5 always hashes a context value. Measured equal against Conscrypt and
    /// OpenSSL in `docs/test-results/phase1b-security-spike-20260827.md`.
    ///
    /// The exporter output never leaves this method except as six decimal digits, and neither the
    /// digits nor the 32 bytes have any log path (ARCHITECTURE §11).
    func deriveSas6() -> String? {
        let label = Array(Sas.exporterLabel.utf8)
        let exported: dispatch_data_t? = label.withUnsafeBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return nil }
            return sec_protocol_metadata_create_secret(
                metadata,
                label.count,
                UnsafeRawPointer(base).assumingMemoryBound(to: CChar.self),
                Sas.exporterLengthBytes
            )
        }
        guard let exported else { return nil }
        var bytes = [UInt8]()
        (exported as DispatchData).enumerateBytes { buffer, _, _ in bytes.append(contentsOf: buffer) }
        guard bytes.count >= 4 else { return nil }
        return Sas.deriveSas6(bytes)
    }
}

/// **The production control channel.** TCP + TLS 1.3, mutually authenticated with the device's own
/// self-signed identity certificate (ADR-007, ADR-017).
///
/// Both ends require a peer certificate
/// (`sec_protocol_options_set_peer_authentication_required`). A one-sided handshake would leave
/// the connecting side's identity unbound, which would make the SPKI pin on that side meaningless
/// — the peer would be pinning a key nobody proved possession of.
///
/// PKI validation is deliberately absent: there is no CA and no hostname to check, so the verify
/// block accepts the transport and trust is applied one layer up by `RideLinkCore.PeerTrust`
/// against the pinned `identity_spki_sha256`. That split is what ARCHITECTURE §4.3 describes, and
/// it is why accepting here is not a hole: nothing is trusted until the pin says so, and a verify
/// block cannot express "ask the user" anyway.
public struct TlsControlChannel: ControlChannel, @unchecked Sendable {
    public let transportLabel = "TLS 1.3 / MUTUAL / SPKI-PINNED"
    public let isSecure = true

    private let identity: DeviceIdentity
    private let connectTimeoutMs: Int64
    private let handshakeTimeoutMs: Int64 = 5000
    private let verifyQueue = DispatchQueue(label: "com.ridelink.platform.tls.verify")

    public init(identity: DeviceIdentity, connectTimeoutMs: Int64 = 5000) {
        self.identity = identity
        self.connectTimeoutMs = connectTimeoutMs
    }

    public func bind() async throws -> ControlListener {
        try await ControlListener.bind(parameters: makeParameters()) { connection in
            // An accepted connection has not finished its TLS handshake yet, and the peer
            // certificate and exporter do not exist until it has.
            try await connection.awaitReady(timeoutMs: handshakeTimeoutMs)
            try attachSecurity(to: connection)
        }
    }

    public func connect(host: String, port: UInt16) async throws -> ControlConnection {
        let connection = try await ControlConnection.connect(
            host: host,
            port: port,
            parameters: makeParameters(),
            connectTimeoutMs: connectTimeoutMs
        )
        do {
            try attachSecurity(to: connection)
        } catch {
            connection.close()
            throw error
        }
        return connection
    }

    /// Reads the peer certificate and the negotiated parameters off a connection that has already
    /// reached `.ready`, and hands the result to the framing layer as an opaque `ChannelSecurity`.
    ///
    /// Done here rather than in the verify block because a listener's `NWParameters` — and
    /// therefore its verify block — are shared across every inbound connection, while these facts
    /// are per-connection. `sec_protocol_metadata` from the connection itself is the per-connection
    /// view.
    public func attachSecurity(to connection: ControlConnection) throws {
        guard let metadata = connection.securityProtocolMetadata else {
            throw ControlTransportError.connectFailed("no TLS metadata on a connection that should be TLS")
        }
        let version = sec_protocol_metadata_get_negotiated_tls_protocol_version(metadata)
        guard version == .TLSv13 else {
            throw ControlTransportError.connectFailed(
                "negotiated \(Self.describe(version)), but PROTOCOL §1 requires TLS 1.3")
        }
        guard let certificate = Self.peerLeafCertificate(from: metadata) else {
            throw ControlTransportError.connectFailed("peer presented no certificate")
        }
        connection.attachSecurity(
            TlsChannelSecurity(
                peerIdentitySpkiSha256: try PeerCertificateInspector.identitySpkiSha256(of: certificate),
                peerCertificateStructurallyValid: PeerCertificateInspector.isStructurallyValid(certificate),
                negotiatedProtocolDescription: Self.describe(version),
                metadata: metadata
            )
        )
    }

    private func makeParameters() -> NWParameters {
        let tlsOptions = NWProtocolTLS.Options()
        let sec = tlsOptions.securityProtocolOptions
        let localIdentity = sec_identity_create(identity.secIdentity)!

        sec_protocol_options_set_local_identity(sec, localIdentity)
        // Not a floor and a ceiling for negotiation room — this pins TLS 1.3 exactly. NFR-06 and
        // PROTOCOL §1 do not admit a 1.2 fallback, and making it unnegotiable is stronger than
        // detecting it afterwards.
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_peer_authentication_required(sec, true)
        sec_protocol_options_set_challenge_block(sec, { _, complete in
            complete(localIdentity) // the client certificate half of mutual authentication
        }, verifyQueue)
        sec_protocol_options_set_verify_block(sec, { _, _, complete in
            complete(true) // see the type-level comment: trust is the SPKI pin, one layer up
        }, verifyQueue)

        let parameters = NWParameters(tls: tlsOptions)
        if let tcpOptions = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
        }
        parameters.allowLocalEndpointReuse = true
        return parameters
    }

    private static func peerLeafCertificate(from metadata: sec_protocol_metadata_t) -> SecCertificate? {
        var leaf: SecCertificate?
        sec_protocol_metadata_access_peer_certificate_chain(metadata) { secCertificate in
            guard leaf == nil else { return } // the leaf is the peer's own certificate
            leaf = sec_certificate_copy_ref(secCertificate).takeRetainedValue()
        }
        return leaf
    }

    private static func describe(_ version: tls_protocol_version_t) -> String {
        switch version {
        case .TLSv13: return "TLSv1.3"
        case .TLSv12: return "TLSv1.2"
        case .TLSv11: return "TLSv1.1"
        case .TLSv10: return "TLSv1.0"
        default: return "unknown(\(version.rawValue))"
        }
    }
}
