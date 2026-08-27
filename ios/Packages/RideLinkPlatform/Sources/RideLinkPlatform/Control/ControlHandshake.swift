import Foundation
import RideLinkCore

extension JSONValue {
    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// `Int64(exactly:)`, not the non-failable `Int64(_:)` — the latter **traps** (fatal error,
    /// not a thrown `Error`) on a `Double` that is NaN, infinite, or outside `Int64`'s range.
    /// PROTOCOL fields like `t1_mono_us` are attacker/peer-controlled JSON numbers, so an
    /// extreme or non-finite value here must fail safely, not crash the read loop (this
    /// session's brief §7).
    var int64Value: Int64? {
        if case .number(let value) = self { return Int64(exactly: value) }
        return nil
    }

    var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }
}

/// Smaller `peer_id` leads (ADR-010), independent of who initiated the connection (ADR-015 A2).
func computeLeaderId(_ a: PeerId, _ b: PeerId) -> PeerId { a.value < b.value ? a : b }

/// Placeholder session-id generation. `SessionId` (PROTOCOL §2) is documented as a ULID for
/// sortability; nothing in Phase 1a parses it as one, so a random UUID string is a deliberate
/// "smallest coherent increment" stand-in (CLAUDE.md) rather than a protocol deviation.
func freshSessionId() -> SessionId { SessionId(UUID().uuidString) }

public enum HandshakeOutcome: Sendable {
    case success(
        remotePeerId: PeerId,
        remoteConnTiebreak: ConnTiebreak,
        sessionId: SessionId,
        leaderPeerId: PeerId,
        /// Computed from the peer's TLS certificate — the only trustworthy identity input (ADR-012).
        peerIdentitySpkiSha256: SpkiHash,
        /// Whether this peer is already trusted, needs pairing, or must be refused. Carried rather
        /// than acted on here, because PROTOCOL §4.5 says pairing runs **only on the surviving
        /// connection** — so the decision is made once, at handshake time, and applied after
        /// duplicate-connection resolution.
        pinDecision: PinDecision
    )
    case rejected(errorCode: String)
    case connectionClosed
}

public struct LocalHandshakeIdentity: Sendable {
    public let displayName: String
    public let platform: String
    public let osVersion: String
    public let appVersion: String
    public let connTiebreak: ConnTiebreak
    /// This device's own `identity_spki_sha256` (ADR-012). Advisory on the wire; the certificate
    /// is authoritative.
    public let identitySpkiSha256: SpkiHash

    public init(
        displayName: String,
        platform: String,
        osVersion: String,
        appVersion: String,
        connTiebreak: ConnTiebreak,
        identitySpkiSha256: SpkiHash
    ) {
        self.displayName = displayName
        self.platform = platform
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.connTiebreak = connTiebreak
        self.identitySpkiSha256 = identitySpkiSha256
    }

    func with(connTiebreak: ConnTiebreak) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(
            displayName: displayName,
            platform: platform,
            osVersion: osVersion,
            appVersion: appVersion,
            connTiebreak: connTiebreak,
            identitySpkiSha256: identitySpkiSha256
        )
    }
}

/// PROTOCOL §4.1 HELLO/HELLO_ACK, plus the SPKI pin check that decides whether this peer is
/// trusted, unknown, or refused. The mirror of Android's `ControlHandshake`.
///
/// **Every field read here comes off the wire**, so nothing in this file may trap on a malformed
/// value: a peer that sends `"peer_id": 7` or omits `conn_tiebreak` must get a clean
/// `malformed_frame` and a closed connection, not a `precondition` failure that takes the process
/// with it. `PeerId.parse`/`ConnTiebreak.parse`/`SpkiHash.parse` exist for exactly this, and the
/// trapping initialisers are reserved for values this device produced itself.
///
/// **Ordering.** PROTOCOL §4.1's diagram draws the pin check before HELLO, while its normative
/// table defines the pin as "the stored pin **for that `peer_id`**" — which HELLO is what carries.
/// Both are honoured: the certificate's own structural validity is checked before HELLO is sent
/// (so an expired or unverifiable certificate never gets a session's worth of device metadata out
/// of us), and the pin comparison happens once `peer_id` is known.
public enum ControlHandshake {
    public static func performAsInitiator(
        socket: ControlConnection,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: @Sendable () -> Int64,
        local: LocalHandshakeIdentity,
        trustedPeers: any TrustedPeerStore
    ) async throws -> HandshakeOutcome {
        guard let security = socket.security else { return .rejected(errorCode: errorCodeInternal) }
        guard security.peerCertificateStructurallyValid else {
            return .rejected(errorCode: errorCodeCertificateInvalid)
        }

        let proposal = freshSessionId()
        try await socket.writeFrame(
            ControlMessages.hello(
                localPeerId: localPeerId,
                sessionId: proposal,
                seq: seqCounter.nextSeq(),
                sentAtMonoUs: monotonicNowUs(),
                displayName: local.displayName,
                platform: local.platform,
                osVersion: local.osVersion,
                appVersion: local.appVersion,
                sessionIdProposal: proposal,
                connTiebreak: local.connTiebreak,
                identitySpkiSha256: local.identitySpkiSha256
            )
        )

        let frame = await socket.readFrame()
        guard case .frame(let envelope, _) = frame, envelope.type == "HELLO_ACK" else { return mapFailure(frame) }
        let payload = envelope.payload

        guard
            let remotePeerId = payload["peer_id"]?.stringValue.flatMap(PeerId.parse),
            let remoteConnTiebreak = payload["conn_tiebreak"]?.stringValue.flatMap(ConnTiebreak.parse),
            let acceptedSessionIdString = payload["accepted_session_id"]?.stringValue,
            let claimedLeader = payload["leader_peer_id"]?.stringValue.flatMap(PeerId.parse)
        else {
            return .rejected(errorCode: errorCodeMalformedFrame)
        }

        let computedLeader = computeLeaderId(localPeerId, remotePeerId)
        guard computedLeader.value == claimedLeader.value else { return .rejected(errorCode: errorCodeLeaderMismatch) }

        return finish(
            payload: payload,
            security: security,
            remotePeerId: remotePeerId,
            remoteConnTiebreak: remoteConnTiebreak,
            sessionId: SessionId(acceptedSessionIdString),
            leaderPeerId: computedLeader,
            trustedPeers: trustedPeers
        )
    }

    public static func performAsAcceptor(
        socket: ControlConnection,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: @Sendable () -> Int64,
        local: LocalHandshakeIdentity,
        trustedPeers: any TrustedPeerStore
    ) async throws -> HandshakeOutcome {
        guard let security = socket.security else { return .rejected(errorCode: errorCodeInternal) }
        guard security.peerCertificateStructurallyValid else {
            return .rejected(errorCode: errorCodeCertificateInvalid)
        }

        let frame = await socket.readFrame()
        guard case .frame(let envelope, _) = frame, envelope.type == "HELLO" else { return mapFailure(frame) }
        let payload = envelope.payload

        guard
            let remotePeerId = payload["peer_id"]?.stringValue.flatMap(PeerId.parse),
            let remoteConnTiebreak = payload["conn_tiebreak"]?.stringValue.flatMap(ConnTiebreak.parse),
            let initiatorProposalString = payload["session_id_proposal"]?.stringValue
        else {
            return .rejected(errorCode: errorCodeMalformedFrame)
        }

        let initiatorProposal = SessionId(initiatorProposalString)
        let leader = computeLeaderId(localPeerId, remotePeerId)
        let acceptedSessionId = leader.value == localPeerId.value ? freshSessionId() : initiatorProposal

        try await socket.writeFrame(
            ControlMessages.helloAck(
                localPeerId: localPeerId,
                sessionId: acceptedSessionId,
                seq: seqCounter.nextSeq(),
                sentAtMonoUs: monotonicNowUs(),
                acceptedSessionId: acceptedSessionId,
                connTiebreak: local.connTiebreak,
                leaderPeerId: leader,
                identitySpkiSha256: local.identitySpkiSha256
            )
        )

        return finish(
            payload: payload,
            security: security,
            remotePeerId: remotePeerId,
            remoteConnTiebreak: remoteConnTiebreak,
            sessionId: acceptedSessionId,
            leaderPeerId: leader,
            trustedPeers: trustedPeers
        )
    }

    private static func finish(
        payload: [String: JSONValue],
        security: any ChannelSecurity,
        remotePeerId: PeerId,
        remoteConnTiebreak: ConnTiebreak,
        sessionId: SessionId,
        leaderPeerId: PeerId,
        trustedPeers: any TrustedPeerStore
    ) -> HandshakeOutcome {
        // Absent is tolerated (PROTOCOL §2 rule 1: unknown/missing fields are not fatal) and simply
        // means "nothing to cross-check". Present-but-malformed is not: a peer that sends a
        // wrongly-shaped identity field is either broken or probing, and either way the session is
        // not worth having.
        let advertisedField = payload["identity_spki_sha256"]?.stringValue
        let advertised = advertisedField.flatMap(SpkiHash.parse)
        if advertisedField != nil, advertised == nil { return .rejected(errorCode: errorCodeMalformedFrame) }

        let decision = PeerTrust.decide(
            storedPin: trustedPeers.byPeerId(remotePeerId)?.identitySpkiSha256,
            presentedSpki: security.peerIdentitySpkiSha256,
            helloAdvertisedSpki: advertised,
            certificateStructurallyValid: security.peerCertificateStructurallyValid
        )
        if case .refused(let code) = decision { return .rejected(errorCode: code) }

        return .success(
            remotePeerId: remotePeerId,
            remoteConnTiebreak: remoteConnTiebreak,
            sessionId: sessionId,
            leaderPeerId: leaderPeerId,
            peerIdentitySpkiSha256: security.peerIdentitySpkiSha256,
            pinDecision: decision
        )
    }

    private static func mapFailure(_ frame: FrameReadResult) -> HandshakeOutcome {
        switch frame {
        case .connectionClosed: .connectionClosed
        case .frameTooLarge: .rejected(errorCode: errorCodeFrameTooLarge)
        case .malformed(let code): .rejected(errorCode: code)
        case .frame: .rejected(errorCode: "unexpected_message_type")
        }
    }
}
