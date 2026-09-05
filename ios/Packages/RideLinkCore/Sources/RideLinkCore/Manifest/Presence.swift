import Foundation

/// ARCHITECTURE §8.2's static presence classification: does *this* phone have a `content_hash`
/// locally, does the connected peer's manifest advertise it, or both. The transfer-in-progress
/// states (`.queued`/`.transferring`/`.failed`) come from `TransferReducer` instead — a
/// set-membership question and a state-machine question are different pure functions, not one
/// type trying to be both.
///
/// Classification and delta below are keyed on `ContentHash` equality **only**. `QuickId` never
/// participates (ADR-005 Amendment A1) — two entries sharing a `quick_id` but differing in
/// `content_hash` are always classified or diffed independently.
///
/// The Kotlin mirror is `com.ridelink.core.manifest.Presence`.
public enum Presence {
    /// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/manifest/`
    /// names them that way and both platforms read the same file.
    public enum Classification: String, Sendable, Equatable, CaseIterable {
        case localOnly = "LOCAL_ONLY"
        case peerOnly = "PEER_ONLY"
        case both = "BOTH"
    }

    public static func classify(
        local localHashes: Set<ContentHash>,
        peer peerHashes: Set<ContentHash>
    ) -> [ContentHash: Classification] {
        var result: [ContentHash: Classification] = [:]
        for hash in localHashes.union(peerHashes) {
            switch (localHashes.contains(hash), peerHashes.contains(hash)) {
            case (true, true): result[hash] = .both
            case (true, false): result[hash] = .localOnly
            default: result[hash] = .peerOnly
            }
        }
        return result
    }
}

/// PROTOCOL §8.1's delta computation: given an old and a new full manifest snapshot, the `added`
/// entries and `removed` content_hash values a sender would place in a `kind: "delta"` sequence.
/// A metadata-only change on the same `content_hash` is neither an add nor a remove.
public enum ManifestDelta {
    public struct Delta: Sendable, Equatable {
        public let added: [ManifestEntry]
        public let removed: [ContentHash]
    }

    public static func compute(old: [ManifestEntry], new: [ManifestEntry]) -> Delta {
        var oldByHash: [ContentHash: ManifestEntry] = [:]
        for entry in old where entry.contentHash != nil {
            oldByHash[entry.contentHash!] = entry
        }
        var newByHash: [ContentHash: ManifestEntry] = [:]
        for entry in new where entry.contentHash != nil {
            newByHash[entry.contentHash!] = entry
        }
        let added = new.filter { entry in
            guard let hash = entry.contentHash else { return false }
            return oldByHash[hash] == nil
        }
        // Sorted (by the wire string, not insertion order) — matches
        // tools/generate_manifest_vectors.py's `sorted(...)`, since removal order carries no
        // meaning of its own and a stable, comparable order keeps the vector deterministic.
        let removed = oldByHash.keys.filter { newByHash[$0] == nil }.sorted { $0.value < $1.value }
        return Delta(added: added, removed: removed)
    }
}
