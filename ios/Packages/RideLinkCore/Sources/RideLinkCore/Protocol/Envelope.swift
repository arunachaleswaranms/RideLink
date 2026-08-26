import Foundation

/// The fixed control-frame envelope (PROTOCOL §2). Field names mirror the wire exactly via
/// `CodingKeys` so the Swift property names can stay idiomatic camelCase.
public struct Envelope: Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let senderId: String
    public let msgId: String
    public let seq: Int64
    public let sentAtMonoUs: Int64
    public let requiresAck: Bool
    public let payload: JSONObject

    public init(
        v: Int,
        type: String,
        sessionId: String,
        senderId: String,
        msgId: String,
        seq: Int64,
        sentAtMonoUs: Int64,
        requiresAck: Bool = false,
        payload: JSONObject
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.senderId = senderId
        self.msgId = msgId
        self.seq = seq
        self.sentAtMonoUs = sentAtMonoUs
        self.requiresAck = requiresAck
        self.payload = payload
    }
}

extension Envelope: Codable {
    enum CodingKeys: String, CodingKey {
        case v
        case type
        case sessionId = "session_id"
        case senderId = "sender_id"
        case msgId = "msg_id"
        case seq
        case sentAtMonoUs = "sent_at_mono_us"
        case requiresAck = "requires_ack"
        case payload
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        v = try container.decode(Int.self, forKey: .v)
        type = try container.decode(String.self, forKey: .type)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        senderId = try container.decode(String.self, forKey: .senderId)
        msgId = try container.decode(String.self, forKey: .msgId)
        seq = try container.decode(Int64.self, forKey: .seq)
        sentAtMonoUs = try container.decode(Int64.self, forKey: .sentAtMonoUs)
        requiresAck = try container.decodeIfPresent(Bool.self, forKey: .requiresAck) ?? false
        payload = try container.decode(JSONObject.self, forKey: .payload)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(v, forKey: .v)
        try container.encode(type, forKey: .type)
        try container.encode(sessionId, forKey: .sessionId)
        try container.encode(senderId, forKey: .senderId)
        try container.encode(msgId, forKey: .msgId)
        try container.encode(seq, forKey: .seq)
        try container.encode(sentAtMonoUs, forKey: .sentAtMonoUs)
        try container.encode(requiresAck, forKey: .requiresAck)
        try container.encode(payload, forKey: .payload)
    }
}
