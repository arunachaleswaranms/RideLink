import Foundation
import RideLinkCore
import Security

/// **Non-security-bearing.** Phase 1a has no TLS, no device keypair and no trusted-peer store
/// (CLAUDE.md rule 28 / this session's brief §9). `insecureSentinelSpkiHash` is a fixed sentinel,
/// never a real key hash — it exists only so `HELLO.identity_spki_sha256` satisfies the wire
/// shape (`SpkiHash`'s format) while this transport is active. It must never be treated as a
/// trust anchor, logged as if it were real, or compared for pin matching: there is no pinning in
/// Phase 1a.
public enum ProvisionalIdentity {
    /// Generated once per process start. Not persisted, not a durable Phase 1b `peer_id`.
    public static let peerId: PeerId = PeerId(randomHex(byteCount: 8))

    /// `sha256:` + 64 zero hex characters — structurally valid, semantically meaningless.
    public static let insecureSentinelSpkiHash = SpkiHash("sha256:" + String(repeating: "0", count: 64))

    static func randomHex(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

/// ADR-015: 16 CSPRNG bytes as 32 lowercase hex characters, generated once per app process per
/// discovery session, stable across every connection opened or accepted in that session. A
/// distinct value from `ProvisionalIdentity.peerId` and from the mDNS discovery handle — reusing
/// one random value for two jobs is the exact mistake ADR-015 warns against.
public enum ConnTiebreakGenerator {
    public static func generate() -> ConnTiebreak {
        ConnTiebreak(ProvisionalIdentity.randomHex(byteCount: 16))
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
        connTiebreak: ConnTiebreak
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
                "identity_spki_sha256": .string(ProvisionalIdentity.insecureSentinelSpkiHash.value),
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
        leaderPeerId: PeerId
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
                "identity_spki_sha256": .string(ProvisionalIdentity.insecureSentinelSpkiHash.value),
                "conn_tiebreak": .string(connTiebreak.value),
                "leader_peer_id": .string(leaderPeerId.value),
            ]
        )
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
