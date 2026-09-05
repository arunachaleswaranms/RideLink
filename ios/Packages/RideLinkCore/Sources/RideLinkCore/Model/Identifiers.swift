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

    /// Non-trapping constructor for a value that arrives **off the wire**. `init(_:)`'s
    /// `precondition` is right for our own values, where a malformed one is a bug — but a peer
    /// chooses what it sends, and a `precondition` on wire input is a remotely triggerable crash.
    public static func parse(_ value: String) -> PeerId? { isValid(value) ? PeerId(value) : nil }

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

    /// Non-trapping constructor for values that arrive **off the wire**
    /// (`HELLO.identity_spki_sha256`, a persisted trusted-peer record). `init(_:)`'s
    /// `precondition` is right for our own values, where a malformed one is a bug — but a peer
    /// chooses what it sends, and a `precondition` on wire input is a remotely triggerable crash.
    /// Returns nil instead; the caller answers `ERROR/malformed_frame`.
    public static func parse(_ value: String) -> SpkiHash? {
        isValid(value) ? SpkiHash(value) : nil
    }

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

    /// Non-trapping constructor for a value that arrives off the wire — see `PeerId.parse`.
    public static func parse(_ value: String) -> ConnTiebreak? {
        isValid(value) ? ConnTiebreak(value) : nil
    }

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
/// Format: `"sha256:"` + 64 lowercase hex characters (PROTOCOL §8.1's manifest entry shape).
///
/// Not redacted: unlike `SpkiHash`/`ConnTiebreak`, a content hash names no peer and identifies no
/// security material — CLAUDE.md's logging redaction table does not list it, and a music file's
/// hash reveals nothing about the person carrying it.
public struct ContentHash: Hashable, Sendable {
    public let value: String

    public init(_ value: String) {
        precondition(ContentHash.isValid(value), "ContentHash must be sha256: followed by 64 lowercase hex characters")
        self.value = value
    }

    public var hex: String { String(value.dropFirst("sha256:".count)) }

    /// Non-trapping constructor for a value read back from storage or computed from possibly
    /// unexpected input. Phase 3's indexer runs over arbitrary local files ("even local files are
    /// untrusted input"), so a malformed persisted row must not crash a read the way `init(_:)`'s
    /// `precondition` would.
    public static func parse(_ value: String) -> ContentHash? {
        isValid(value) ? ContentHash(value) : nil
    }

    private static func isValid(_ s: String) -> Bool {
        guard s.hasPrefix("sha256:") else { return false }
        let hex = s.dropFirst("sha256:".count)
        return hex.count == 64 && hex.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

/// `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)` (ADR-005) — the cheap tier computed at scan
/// time, before the authoritative `ContentHash` is known. Same wire format as `ContentHash`
/// (PROTOCOL §8.1: `"quick_id": "sha256:77bd…"`) and used as the stable local index key: it
/// survives a rename, unlike a path, and is available immediately, unlike the full hash.
public struct QuickId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) {
        precondition(QuickId.isValid(value), "QuickId must be sha256: followed by 64 lowercase hex characters")
        self.value = value
    }

    public static func parse(_ value: String) -> QuickId? {
        isValid(value) ? QuickId(value) : nil
    }

    private static func isValid(_ s: String) -> Bool {
        guard s.hasPrefix("sha256:") else { return false }
        let hex = s.dropFirst("sha256:".count)
        return hex.count == 64 && hex.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

/// This phone's own local identity for one library row (ADR-005 Amendment A1). Generated once, the
/// moment a location is first indexed, and never recomputed or derived from the file's bytes —
/// unlike `QuickId` (a lossy 128 KiB sample) or `ContentHash` (authoritative but lazy), a
/// `LocalEntryId` has no relationship to content at all, so two distinct files can never collide
/// onto the same one. It is what a player/queue/artwork cache must key on to unambiguously mean
/// *this specific row*, now that `QuickId` is no longer guaranteed unique across rows.
///
/// Never sent over the wire and never persisted past a reindex-from-scratch — this is local
/// bookkeeping only, the same way `LocalTrackLocation` is.
public struct LocalEntryId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) {
        precondition(LocalEntryId.isValid(value), "LocalEntryId must be a lowercase UUID")
        self.value = value
    }

    /// Non-trapping constructor for a value read back from storage — see `ContentHash.parse`'s
    /// identical reasoning: a malformed persisted row must not crash a read.
    public static func parse(_ value: String) -> LocalEntryId? {
        isValid(value) ? LocalEntryId(value) : nil
    }

    private static func isValid(_ s: String) -> Bool {
        let groups = s.split(separator: "-", omittingEmptySubsequences: false)
        let expectedLengths = [8, 4, 4, 4, 12]
        guard groups.count == expectedLengths.count else { return false }
        return zip(groups, expectedLengths).allSatisfy { group, length in
            group.count == length && group.allSatisfy { $0.isHexDigit && !$0.isUppercase }
        }
    }
}
