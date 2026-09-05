import Foundation
import RideLinkCore
import Security

/// ADR-023 §2/§3 — single-use, 30 s TTL, generation-scoped bulk-transfer authorization tokens.
///
/// A token is minted per `TransferMessage.offer`, delivered only inside the already-authenticated
/// control channel, and consumed exactly once by the bulk connection it authorises. It is tagged
/// with the `ControlSessionManager` generation active at mint time; `validateAndConsume` fails a
/// token whose generation is not the *current* one, which is what makes a reconnect's
/// re-authentication invalidate every outstanding token without an explicit sweep (ADR-023 §3).
///
/// An `actor` here, unlike Android's `BulkTokenTable` (a plain class backed by a
/// `ConcurrentHashMap`): CLAUDE.md's Swift rule is actors for shared mutable state, and this table
/// is exactly that — touched by whichever transfer is currently being served or fetched.
public actor BulkTokenTable {
    private struct Entry {
        let token: String
        let generation: Int64
        let mintedAtMonoUs: Int64
        var consumed = false
    }

    private let monotonicNowUs: @Sendable () -> Int64
    private var entries: [TransferId: Entry] = [:]

    public init(monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.monotonicNowUs = monotonicNowUs
    }

    /// 32 CSPRNG bytes, hex-encoded (64 lowercase hex characters) — PROTOCOL §8.2.
    ///
    /// Closure-audit Finding M: a `transferId` is minted fresh by the requester (ADR-023 §2), so a
    /// second `issue` for one already carrying a still-live, unconsumed entry means a peer resent
    /// (or replayed) a `TRANSFER_REQUEST` reusing an id it already used. Silently overwriting that
    /// entry would invalidate a still-outstanding token with no signal to whoever was about to
    /// consume it. `tryIssue` is the safe entry point; `issue` is kept for callers that have already
    /// decided a collision cannot happen and would rather fail loudly than check.
    public func issue(transferId: TransferId, generation: Int64) -> String {
        var bytes = [UInt8](repeating: 0, count: Self.tokenBytes)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        let token = bytes.map { String(format: "%02x", $0) }.joined()
        entries[transferId] = Entry(token: token, generation: generation, mintedAtMonoUs: monotonicNowUs())
        return token
    }

    /// See `issue`'s Finding M discussion. Returns `nil` — rather than silently overwriting — if
    /// `transferId` already has a live (unconsumed, not-yet-expired) entry; the caller must not
    /// construct/send an offer in that case.
    public func tryIssue(transferId: TransferId, generation: Int64) -> String? {
        if let existing = entries[transferId], !existing.consumed, monotonicNowUs() - existing.mintedAtMonoUs <= Self.ttlUs {
            return nil
        }
        return issue(transferId: transferId, generation: generation)
    }

    /// Single-use: a second call for the same `transferId` fails even with the right token, because
    /// `entries` is updated to the consumed form before any bytes are streamed. `currentGeneration`
    /// must be read fresh at validation time, not cached — a stale caller comparing against an old
    /// generation would defeat the whole guard.
    public func validateAndConsume(transferId: TransferId, presentedToken: String, currentGeneration: Int64) -> Bool {
        guard var entry = entries[transferId] else { return false }
        if entry.consumed { return false }
        if entry.generation != currentGeneration {
            entries.removeValue(forKey: transferId)
            return false
        }
        if monotonicNowUs() - entry.mintedAtMonoUs > Self.ttlUs {
            entries.removeValue(forKey: transferId)
            return false
        }
        guard constantTimeEquals(entry.token, presentedToken) else { return false }
        entry.consumed = true
        entries[transferId] = entry
        return true
    }

    /// Called on every reconnect/re-authentication — the new generation makes old entries dead
    /// weight.
    public func sweepBelow(_ currentGeneration: Int64) {
        entries = entries.filter { $0.value.generation >= currentGeneration }
    }

    public func clear() {
        entries.removeAll()
    }

    private static let tokenBytes = 32
    private static let ttlUs: Int64 = 30_000_000
}

/// Closure-audit Finding L: a hex-encoded, single-use bulk-transfer token is a security-sensitive
/// authorization secret (ADR-023 §2), so its comparison should not leak timing information about
/// how many leading characters matched, even though the practical severity is low — this check
/// runs over an already TLS/SPKI-authenticated local link, not across the open Internet. Fixed-time
/// in the number of characters compared: every character pair is compared, and the result is
/// accumulated with bitwise OR rather than short-circuiting on the first mismatch.
private func constantTimeEquals(_ a: String, _ b: String) -> Bool {
    let aBytes = Array(a.utf8)
    let bBytes = Array(b.utf8)
    guard aBytes.count == bBytes.count else { return false }
    var diff: UInt8 = 0
    for i in 0..<aBytes.count {
        diff |= aBytes[i] ^ bBytes[i]
    }
    return diff == 0
}
