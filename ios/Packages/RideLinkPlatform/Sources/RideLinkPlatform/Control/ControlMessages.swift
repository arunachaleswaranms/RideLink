import Foundation
import RideLinkCore
import Security

/// ADR-015: 16 CSPRNG bytes as 32 lowercase hex characters, generated once per app process per
/// discovery session, stable across every connection opened or accepted in that session. A
/// distinct value from the device's durable `peer_id` and from the mDNS discovery handle —
/// reusing one random value for two jobs is the exact mistake ADR-015 warns against.
///
/// (Phase 1a's `ProvisionalIdentity` — a per-process random `peer_id` and a zero-filled sentinel
/// `identity_spki_sha256` — is gone. Both are real now: the `peer_id` is persisted by
/// `LocalPeerIdStore`, and the SPKI hash comes from the Keychain identity key.)
public enum ConnTiebreakGenerator {
    public static func generate() -> ConnTiebreak {
        var bytes = [UInt8](repeating: 0, count: 16)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return ConnTiebreak(bytes.map { String(format: "%02x", $0) }.joined())
    }
}

/// Per-connection monotonic `seq` counter, starting at 1 per session (PROTOCOL §2).
public final class SeqCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var next: Int64 = 1

    public init() {}

    public func nextSeq() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        let value = next
        next += 1
        return value
    }
}

public func newMsgId() -> String { UUID().uuidString }

/// Builds the fixed envelope + type-specific payload for every Phase 1a session message.
public enum ControlMessages {
    public static func hello(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        displayName: String,
        platform: String,
        osVersion: String,
        appVersion: String,
        sessionIdProposal: SessionId,
        connTiebreak: ConnTiebreak,
        identitySpkiSha256: SpkiHash
    ) -> Envelope {
        envelope(
            localPeerId: localPeerId,
            type: "HELLO",
            sessionId: sessionId,
            seq: seq,
            sentAtMonoUs: sentAtMonoUs,
            payload: [
                "peer_id": .string(localPeerId.value),
                "display_name": .string(displayName),
                "platform": .string(platform),
                "os_version": .string(osVersion),
                "app_version": .string(appVersion),
                "protocol_versions": .array([.number(Double(ProtocolVersion.current))]),
                "session_id_proposal": .string(sessionIdProposal.value),
                // Advisory only (PROTOCOL §4.1): cross-checked against the TLS certificate, and a
                // mismatch is ERROR/identity_mismatch. Trust never derives from it.
                "identity_spki_sha256": .string(identitySpkiSha256.value),
                "conn_tiebreak": .string(connTiebreak.value),
            ]
        )
    }

    public static func helloAck(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        acceptedSessionId: SessionId,
        connTiebreak: ConnTiebreak,
        leaderPeerId: PeerId,
        identitySpkiSha256: SpkiHash
    ) -> Envelope {
        envelope(
            localPeerId: localPeerId,
            type: "HELLO_ACK",
            sessionId: sessionId,
            seq: seq,
            sentAtMonoUs: sentAtMonoUs,
            payload: [
                "peer_id": .string(localPeerId.value),
                "accepted_session_id": .string(acceptedSessionId.value),
                "protocol_version": .number(Double(ProtocolVersion.current)),
                "identity_spki_sha256": .string(identitySpkiSha256.value),
                "conn_tiebreak": .string(connTiebreak.value),
                "leader_peer_id": .string(leaderPeerId.value),
            ]
        )
    }

    /// PROTOCOL §4.5. Sent by the **initiator** of the surviving connection only, so exactly one
    /// pairing exchange runs per first meeting (§4.2).
    ///
    /// Carries no code and no key material: the six digits are derived independently on each side
    /// from the TLS exporter and compared by the two humans. Putting the SAS on the wire would
    /// destroy the entire point of the check — a man-in-the-middle would simply forward it.
    public static func pairRequest(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        displayName: String,
        platform: String,
        identitySpkiSha256: SpkiHash
    ) -> Envelope {
        envelope(localPeerId: localPeerId, type: "PAIR_REQUEST", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "display_name": .string(displayName),
            "platform": .string(platform),
            "identity_spki_sha256": .string(identitySpkiSha256.value),
        ])
    }

    /// PROTOCOL §4.5: `{ sas6_accepted: true }` — a **boolean**, never the digits. The sender is
    /// asserting "my user looked at my screen and said the two codes match", which is a claim
    /// about a human, not a value that could be forwarded.
    public static func pairConfirm(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        accepted: Bool
    ) -> Envelope {
        envelope(localPeerId: localPeerId, type: "PAIR_CONFIRM", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "sas6_accepted": .bool(accepted),
        ])
    }

    /// PROTOCOL §4.5, the acceptor's verdict. On `accepted`, both sides persist the trusted-peer record.
    public static func pairResult(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        accepted: Bool,
        identitySpkiSha256: SpkiHash
    ) -> Envelope {
        envelope(localPeerId: localPeerId, type: "PAIR_RESULT", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "accepted": .bool(accepted),
            "peer_id": .string(localPeerId.value),
            "identity_spki_sha256": .string(identitySpkiSha256.value),
        ])
    }

    public static func ping(localPeerId: PeerId, sessionId: SessionId, seq: Int64, sentAtMonoUs: Int64, t1MonoUs: Int64) -> Envelope {
        envelope(localPeerId: localPeerId, type: "PING", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "t1_mono_us": .number(Double(t1MonoUs)),
        ])
    }

    public static func pong(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        t1MonoUs: Int64,
        t2MonoUs: Int64,
        t3MonoUs: Int64
    ) -> Envelope {
        envelope(localPeerId: localPeerId, type: "PONG", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "t1_mono_us": .number(Double(t1MonoUs)),
            "t2_mono_us": .number(Double(t2MonoUs)),
            "t3_mono_us": .number(Double(t3MonoUs)),
        ])
    }

    public static func ack(localPeerId: PeerId, sessionId: SessionId, seq: Int64, sentAtMonoUs: Int64, ackedMsgId: String) -> Envelope {
        envelope(localPeerId: localPeerId, type: "ACK", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "acked_msg_id": .string(ackedMsgId),
        ])
    }

    public static func error(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        code: String,
        message: String,
        fatal: Bool
    ) -> Envelope {
        envelope(localPeerId: localPeerId, type: "ERROR", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "code": .string(code),
            "message": .string(message),
            "fatal": .bool(fatal),
        ])
    }

    public static func bye(localPeerId: PeerId, sessionId: SessionId, seq: Int64, sentAtMonoUs: Int64, reason: String) -> Envelope {
        envelope(localPeerId: localPeerId, type: "BYE", sessionId: sessionId, seq: seq, sentAtMonoUs: sentAtMonoUs, payload: [
            "reason": .string(reason),
        ])
    }

    private static func envelope(
        localPeerId: PeerId,
        type: String,
        sessionId: SessionId,
        seq: Int64,
        sentAtMonoUs: Int64,
        payload: JSONObject
    ) -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: type,
            sessionId: sessionId.value,
            senderId: localPeerId.value,
            msgId: newMsgId(),
            seq: seq,
            sentAtMonoUs: sentAtMonoUs,
            requiresAck: false,
            payload: payload
        )
    }
}

/// `duplicate_connection` BYE reason (PROTOCOL §4.2 / ADR-015).
public let byeReasonDuplicateConnection = "duplicate_connection"
public let byeReasonUserEnded = "user_ended"
public let byeReasonShutdown = "shutdown"
public let errorCodeSessionAlreadyActive = "session_already_active"
public let errorCodeLeaderMismatch = "leader_mismatch"
public let errorCodeVersionMismatch = "version_mismatch"
public let errorCodeFrameTooLarge = "frame_too_large"
public let errorCodeMalformedFrame = "malformed_frame"
public let errorCodePinMismatch = "pin_mismatch"
public let errorCodeIdentityMismatch = "identity_mismatch"
public let errorCodeCertificateInvalid = "certificate_invalid"
public let errorCodeUntrustedPeer = "untrusted_peer"
public let errorCodePairingRejected = "pairing_rejected"
public let errorCodePairingRateLimited = "pairing_rate_limited"
public let errorCodeInternal = "internal"
