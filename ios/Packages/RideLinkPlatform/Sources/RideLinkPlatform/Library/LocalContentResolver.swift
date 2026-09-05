import Foundation
import RideLinkCore

/// What a transfer-request handler does next — never a human string parsed to decide behaviour.
/// Mirrors `com.ridelink.data.transfer.ContentResolution`.
///
/// **A deliberate divergence from Android's shape, for a genuine platform reason:** Android's
/// `.Found` carries an `open: () -> InputStream` closure because a `content://` URI needs an
/// explicit `ContentResolver.openInputStream` call to become readable bytes. iOS has no
/// `ContentResolver` equivalent — both provenances this type resolves (the verified transfer cache
/// and the Phase 3 library) always resolve to a plain file already inside this app's own container,
/// so returning the resolved `URL` directly is sufficient; the caller opens it with a plain
/// `FileHandle` when it actually starts serving chunks, which is a later stage's concern.
public enum ContentResolution: Sendable, Equatable {
    case found(fileURL: URL, sizeBytes: Int64)
    case notFound
    /// ADR-023 §7 — the on-disk bytes no longer match what was indexed. Fail the request; never
    /// serve them anyway.
    case fileChanged
    case ioError
}

/// Resolves a peer's `TRANSFER_REQUEST.content_hash` to actual readable bytes (brief §9), checking
/// both provenances — the verified transfer cache first (a plain file, cheapest to open), then the
/// Phase 3 local library (this app's own container copy, ADR-009), and falling back only if the
/// cache misses. Mirrors `com.ridelink.data.transfer.LocalContentResolver`.
///
/// **Consistency model (ADR-023 §7):** a full re-hash on every serve is not required — Phase 3's
/// lazy hashing job assigns `content_hash` once per stable file, so the only real risk is a file
/// edited or replaced after indexing. The cheap check this type performs is comparing the file's
/// *current* size against `Track.sizeBytes` as last indexed; any mismatch means the file changed
/// since then, and the request fails with `.fileChanged` rather than serving bytes under a stale
/// assumption.
///
/// The library-side file is resolved via `LibraryIndexer.resolvedUrl(for:)` — the same app-container
/// destination path the indexer itself copied bytes into — **never** `LibraryEntry.location.uri`,
/// which stores the original picker source URL (often a security-scoped external reference this app
/// may no longer have access to; see `LibraryIndexer`'s own doc comment). Reusing that one path
/// computation keeps "where a track's bytes actually live" defined in exactly one place.
public struct LocalContentResolver: Sendable {
    private let libraryRepository: LibraryRepository
    private let libraryIndexer: LibraryIndexer
    private let cacheRepository: TransferCacheRepository

    public init(
        libraryRepository: LibraryRepository,
        libraryIndexer: LibraryIndexer,
        cacheRepository: TransferCacheRepository
    ) {
        self.libraryRepository = libraryRepository
        self.libraryIndexer = libraryIndexer
        self.cacheRepository = cacheRepository
    }

    public func resolve(contentHash: ContentHash, nowMonoUs: Int64) throws -> ContentResolution {
        if let cachedFile = try cacheRepository.open(contentHash, nowMonoUs: nowMonoUs) {
            guard let size = fileSize(at: cachedFile) else { return .ioError }
            return .found(fileURL: cachedFile, sizeBytes: size)
        }

        guard let entry = try libraryRepository.findByContentHash(contentHash) else { return .notFound }
        let fileURL = libraryIndexer.resolvedUrl(for: entry)
        guard let currentSize = fileSize(at: fileURL) else { return .ioError }
        if currentSize != entry.track.sizeBytes { return .fileChanged }
        return .found(fileURL: fileURL, sizeBytes: currentSize)
    }

    private func fileSize(at url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        return (attributes[.size] as? NSNumber)?.int64Value
    }
}
