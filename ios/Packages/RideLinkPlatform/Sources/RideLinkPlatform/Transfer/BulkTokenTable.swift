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
    public func issue(transferId: TransferId, generation: Int64) -> String {
        var bytes = [UInt8](repeating: 0, count: Self.tokenBytes)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        let token = bytes.map { String(format: "%02x", $0) }.joined()
        entries[transferId] = Entry(token: token, generation: generation, mintedAtMonoUs: monotonicNowUs())
        return token
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
        if entry.token != presentedToken { return false }
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
