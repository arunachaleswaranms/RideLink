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
    case success(remotePeerId: PeerId, remoteConnTiebreak: ConnTiebreak, sessionId: SessionId, leaderPeerId: PeerId)
    case rejected(errorCode: String)
    case connectionClosed
}

public struct LocalHandshakeIdentity: Sendable {
    public let displayName: String
    public let platform: String
    public let osVersion: String
    public let appVersion: String
    public let connTiebreak: ConnTiebreak

    public init(displayName: String, platform: String, osVersion: String, appVersion: String, connTiebreak: ConnTiebreak) {
        self.displayName = displayName
        self.platform = platform
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.connTiebreak = connTiebreak
    }

    func with(connTiebreak: ConnTiebreak) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(displayName: displayName, platform: platform, osVersion: osVersion, appVersion: appVersion, connTiebreak: connTiebreak)
    }
}

/// PROTOCOL §4.1 HELLO/HELLO_ACK exchange over `PlainControlTransportPhase1a`. No TLS, no SPKI
/// pin check, no pairing (Phase 1b) — see `ProvisionalIdentity`.
public enum ControlHandshake {
    public static func performAsInitiator(
        socket: ControlConnection,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: @Sendable () -> Int64,
        local: LocalHandshakeIdentity
    ) async throws -> HandshakeOutcome {
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
                connTiebreak: local.connTiebreak
            )
        )

        let frame = await socket.readFrame()
        guard case .frame(let envelope, _) = frame, envelope.type == "HELLO_ACK" else { return mapFailure(frame) }
        let payload = envelope.payload

        guard
            let remotePeerIdString = payload["peer_id"]?.stringValue,
            let remoteConnTiebreakString = payload["conn_tiebreak"]?.stringValue,
            let acceptedSessionIdString = payload["accepted_session_id"]?.stringValue,
            let claimedLeaderString = payload["leader_peer_id"]?.stringValue
        else {
            return .rejected(errorCode: errorCodeMalformedFrame)
        }

        let remotePeerId = PeerId(remotePeerIdString)
        let claimedLeader = PeerId(claimedLeaderString)
        let computedLeader = computeLeaderId(localPeerId, remotePeerId)
        guard computedLeader.value == claimedLeader.value else { return .rejected(errorCode: errorCodeLeaderMismatch) }

        return .success(
            remotePeerId: remotePeerId,
            remoteConnTiebreak: ConnTiebreak(remoteConnTiebreakString),
            sessionId: SessionId(acceptedSessionIdString),
            leaderPeerId: computedLeader
        )
    }

    public static func performAsAcceptor(
        socket: ControlConnection,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: @Sendable () -> Int64,
        local: LocalHandshakeIdentity
    ) async throws -> HandshakeOutcome {
        let frame = await socket.readFrame()
        guard case .frame(let envelope, _) = frame, envelope.type == "HELLO" else { return mapFailure(frame) }
        let payload = envelope.payload

        guard
            let remotePeerIdString = payload["peer_id"]?.stringValue,
            let remoteConnTiebreakString = payload["conn_tiebreak"]?.stringValue,
            let initiatorProposalString = payload["session_id_proposal"]?.stringValue
        else {
            return .rejected(errorCode: errorCodeMalformedFrame)
        }

        let remotePeerId = PeerId(remotePeerIdString)
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
                leaderPeerId: leader
            )
        )

        return .success(
            remotePeerId: remotePeerId,
            remoteConnTiebreak: ConnTiebreak(remoteConnTiebreakString),
            sessionId: acceptedSessionId,
            leaderPeerId: leader
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
