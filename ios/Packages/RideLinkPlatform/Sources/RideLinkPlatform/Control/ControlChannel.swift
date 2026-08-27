import Foundation
import RideLinkCore

/// What one established control connection knows about the peer at the other end, once the
/// transport's own handshake has completed.
///
/// This is the seam between the framing layer and the security layer: `ControlSessionManager`
/// never imports a TLS type, and the pin check (`RideLinkCore.PeerTrust`) never imports a socket
/// type. Only the facts below cross. Mirrors Android's `ChannelSecurity`.
public protocol ChannelSecurity: Sendable {
    /// `identity_spki_sha256` computed from the peer's certificate. The **only** trustworthy
    /// identity input (ADR-012).
    var peerIdentitySpkiSha256: SpkiHash { get }

    /// The platform's verdict on the peer certificate's structure, self-signature and validity
    /// window — not on any PKI chain, which RideLink deliberately does not use
    /// (ARCHITECTURE §4.3).
    var peerCertificateStructurallyValid: Bool { get }

    /// Negotiated TLS version as reported by the stack, for diagnostics.
    var negotiatedProtocolDescription: String { get }

    /// The six-digit pairing code for **this** handshake (PROTOCOL §4.5.1, ADR-018).
    ///
    /// Never logged, never persisted, never sent — there is no log path for it at all
    /// (ARCHITECTURE §11). Returns nil if the exporter is unavailable, which is a hard failure:
    /// pairing must not proceed without a channel binding.
    func deriveSas6() -> String?
}

/// How control-plane bytes get from one phone to the other.
///
/// **Production has exactly one implementation, and it is TLS 1.3.** PROTOCOL §1 and NFR-06 do
/// not admit a plaintext production path, so there is no runtime switch here and no fallback: a
/// `ControlChannel` that is not secure exists only as a test fixture, lives only in the test
/// target, and therefore cannot be linked into the app at all.
public protocol ControlChannel: Sendable {
    /// Shown verbatim in the diagnostics UI. Must never claim security the channel does not have.
    var transportLabel: String { get }

    /// False only for the test-only plaintext fixture.
    var isSecure: Bool { get }

    /// Binds an OS-selected dynamic port (PROTOCOL §1) and starts accepting.
    func bind() async throws -> ControlListener

    /// Dials a peer and completes the transport handshake before returning.
    func connect(host: String, port: UInt16) async throws -> ControlConnection
}
