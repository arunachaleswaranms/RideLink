import Foundation

/// Matches CLAUDE.md's logging redaction rule: identifiers show only their first 6 characters.
private let redactedPrefixLen = 6

/// Durable peer identifier: 16 lowercase hex characters, assigned at pairing.
///
/// `description` is redacted by construction (CLAUDE.md logging rule: peer_id -> first 6 chars),
/// so an accidental `"\(peerId)"` string interpolation cannot leak the full value. Code that
/// genuinely needs the full value (protocol encoding, storage) must read `value` explicitly.
public struct PeerId: Hashable, Sendable, CustomStringConvertible {
    public let value: String

    public init(_ value: String) {
        precondition(PeerId.isValid(value), "PeerId must be 16 lowercase hex characters")
        self.value = value
    }

    public var description: String { "peer:\(value.prefix(redactedPrefixLen))…" }

    private static func isValid(_ s: String) -> Bool {
        s.count == 16 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

/// Session identifier: a ULID string, regenerated per fresh CONNECTING, preserved across RECONNECTING.
public struct SessionId: Hashable, Sendable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

/// `identity_spki_sha256` — the only pinned identity in the system (ADR-012).
/// Format: `"sha256:"` + 64 lowercase hex characters.
public struct SpkiHash: Hashable, Sendable, CustomStringConvertible {
    public let value: String

    public init(_ value: String) {
        precondition(SpkiHash.isValid(value), "SpkiHash must be sha256: followed by 64 lowercase hex characters")
        self.value = value
    }

    public var hex: String { String(value.dropFirst("sha256:".count)) }

    public var description: String { "spki:\(hex.prefix(redactedPrefixLen))…" }

    private static func isValid(_ s: String) -> Bool {
        guard s.hasPrefix("sha256:") else { return false }
        let hex = s.dropFirst("sha256:".count)
        return hex.count == 64 && hex.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

/// Ephemeral per-process, per-discovery-session random value used only for PROTOCOL §4.2 /
/// ADR-015 duplicate-connection resolution. Never persisted, never derived from `PeerId` or
/// `SpkiHash` (see ADR-015 "Why conn_tiebreak and not peer_id").
public struct ConnTiebreak: Hashable, Sendable, CustomStringConvertible {
    public let value: String

    public init(_ value: String) {
        precondition(ConnTiebreak.isValid(value), "ConnTiebreak must be 32 lowercase hex characters")
        self.value = value
    }

    public var description: String { "tiebreak:\(value.prefix(redactedPrefixLen))…" }

    private static func isValid(_ s: String) -> Bool {
        s.count == 32 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

/// SHA-256 of the whole file — the authoritative track identity (ADR-005).
public struct ContentHash: Hashable, Sendable {
    public let value: String
    public init(_ value: String) { self.value = value }
}
