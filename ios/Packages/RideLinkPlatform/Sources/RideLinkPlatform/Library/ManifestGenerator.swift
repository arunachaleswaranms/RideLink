import Foundation
import RideLinkCore

/// Thrown only when `LibraryRepository.allSyncEligible` itself returns a row without a
/// `contentHash` — a contract violation of that query, never an expected runtime outcome.
public enum ManifestGenerationError: Error, Equatable {
    case missingContentHash
}

/// Builds this phone's outgoing manifest entries from Phase 3's authoritative library state (brief
/// §5): only `.indexed` rows with a `contentHash` already computed (ADR-005 — a row still awaiting
/// background hashing is displayable locally but not sync-eligible), deterministically ordered by
/// `content_hash` so two generation passes over an unchanged library produce byte-identical output,
/// and built without ever loading an artwork blob — only `Track.artworkRef`'s presence, never its
/// bytes. Mirrors `com.ridelink.data.transfer.ManifestGenerator` exactly.
///
/// A one-shot throwing call over `LibraryRepository.allSyncEligible`, never a collected
/// `AsyncStream` — this must not depend on a UI collector being active (brief §5). Cooperative
/// cancellation via `Task.checkCancellation()` is what makes it safe to run against a library of
/// thousands of tracks without blocking a task that a caller has since decided to cancel.
public struct ManifestGenerator: Sendable {
    private let libraryRepository: LibraryRepository

    public init(libraryRepository: LibraryRepository) {
        self.libraryRepository = libraryRepository
    }

    public func generate() async throws -> [ManifestEntry] {
        let entries = try libraryRepository.allSyncEligible()
        var result: [ManifestEntry] = []
        result.reserveCapacity(entries.count)
        for entry in entries {
            try Task.checkCancellation()
            result.append(try Self.toManifestEntry(entry))
        }
        return result
    }

    private static func toManifestEntry(_ entry: LibraryEntry) throws -> ManifestEntry {
        guard let hash = entry.track.contentHash else {
            // `LibraryRepository.allSyncEligible()` must only return rows with a non-null
            // contentHash — see its own doc comment.
            throw ManifestGenerationError.missingContentHash
        }
        return ManifestEntry(
            contentHash: hash,
            quickId: entry.track.quickId,
            workKey: workKey(artist: entry.track.artist, title: entry.track.title, durationMs: entry.track.durationMs),
            title: entry.track.title,
            artist: entry.track.artist,
            album: entry.track.album,
            durationMs: entry.track.durationMs,
            codec: entry.track.codec,
            bitrateKbps: entry.track.bitrateKbps,
            sizeBytes: entry.track.sizeBytes,
            filename: entry.track.filename,
            hasArtwork: entry.track.artworkRef != nil
        )
    }

    /// ARCHITECTURE §8.1: `normalize(artist) ‖ normalize(title) ‖ round(duration_ms, 2s)` — a
    /// non-authoritative UI grouping key, never identity. The exact normalization here need not
    /// match the peer's byte-for-byte (each side only ever groups its *own* manifest for display),
    /// so a simple case/whitespace fold is sufficient.
    private static func workKey(artist: String, title: String, durationMs: Int64) -> String {
        let bucketMs: Int64 = durationBucketMs
        let roundedSeconds = (durationMs / bucketMs) * (bucketMs / millisPerSecond)
        return "\(normalize(artist))|\(normalize(title))|\(roundedSeconds)"
    }

    private static func normalize(_ s: String) -> String {
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return trimmed.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    }

    private static let durationBucketMs: Int64 = 2_000
    private static let millisPerSecond: Int64 = 1_000
}
