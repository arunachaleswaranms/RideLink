import Foundation
import RideLinkCore

/// Machine-readable outcomes for the two-phase commit — never a human string parsed to decide
/// behaviour. Mirrors `com.ridelink.data.transfer.PromoteResult`.
public enum PromoteResult: Sendable, Equatable {
    case promoted
    case sizeMismatch
    case hashMismatch
    case ioError
}

/// ADR-023 §6 / brief §12 — the two-phase `.part` -> verified-media file dance, and nothing else: no
/// database row, no manifest, no network. `TransferCacheRepository` is the layer that combines this
/// with `TransferCacheRecord`. Mirrors `com.ridelink.data.transfer.CacheStorage` exactly.
///
/// Every path here is derived from a `ContentHash`'s own 64-hex-character form, **never** from a
/// remote-supplied filename (brief §12) — `ContentHash`'s own validated format is what makes this
/// safe against `../`, an absolute path, or a Unicode confusable: there is no character in a valid
/// `ContentHash` that could ever mean "leave this directory."
///
/// Hash recomputation reuses `ContentHashing.computeContentHash` (the same streamed, bounded-memory
/// `CryptoKit` SHA-256 `LibraryIndexer`'s own lazy-hashing pass uses) rather than a second hand-rolled
/// digest loop — one hashing code path, not two, and it already hashes from a file URL exactly as
/// written to disk, never from bytes buffered in memory.
public struct CacheStorage: Sendable {
    private let incomingDir: URL
    private let mediaDir: URL

    /// - Parameter root: this cache's own directory — RideLink-owned storage under Application
    ///   Support (never a security-scoped external URL), the same convention `MusicCoordinator`
    ///   already uses for the Phase 3 music root.
    public init(root: URL) {
        incomingDir = root.appendingPathComponent("incoming", isDirectory: true)
        mediaDir = root.appendingPathComponent("media", isDirectory: true)
    }

    private func safeName(_ contentHash: ContentHash) -> String { contentHash.hex }

    public func partFile(_ contentHash: ContentHash) -> URL {
        incomingDir.appendingPathComponent("\(safeName(contentHash)).part")
    }

    public func mediaFile(_ contentHash: ContentHash) -> URL {
        mediaDir.appendingPathComponent(safeName(contentHash))
    }

    /// True once a promoted, verified file exists at rest — never true for a `.part` in progress.
    public func hasMediaFile(_ contentHash: ContentHash) -> Bool {
        var isDirectory: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: mediaFile(contentHash).path, isDirectory: &isDirectory)
        return exists && !isDirectory.boolValue
    }

    /// Opens (creating the parent directory, truncating any stale partial) the `.part` file for
    /// streaming writes.
    public func openPartForWrite(_ contentHash: ContentHash) throws -> FileHandle {
        try FileManager.default.createDirectory(at: incomingDir, withIntermediateDirectories: true)
        let url = partFile(contentHash)
        // `createFile` both creates a fresh empty file and truncates an existing one — the same
        // "no append to stale bytes" guarantee `FileOutputStream(file, append = false)` gives on
        // Android.
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
            throw CacheStorageError.cannotOpenPartFile
        }
        return try FileHandle(forWritingTo: url)
    }

    public func appendChunk(_ handle: FileHandle, bytes: Data) throws {
        try handle.write(contentsOf: bytes)
    }

    /// The two-phase commit (ADR-023 §6): verify exact size, recompute SHA-256 **from the bytes as
    /// written to disk** (never from a running in-memory hash of what was received — brief §12's
    /// "hashing the file as written… catches truncated writes and disk-full conditions, not just
    /// network corruption"), then atomically move into `mediaDir` only on an exact match. A mismatch
    /// deletes the `.part` and returns the specific failure reason.
    public func promote(_ contentHash: ContentHash, expectedSizeBytes: Int64) -> PromoteResult {
        let part = partFile(contentHash)
        guard let actualSize = fileSize(at: part) else { return .ioError }
        if actualSize != expectedSizeBytes {
            try? FileManager.default.removeItem(at: part)
            return .sizeMismatch
        }
        guard let actualHash = try? ContentHashing.computeContentHash(fileURL: part) else {
            try? FileManager.default.removeItem(at: part)
            return .ioError
        }
        if actualHash.value != contentHash.value {
            try? FileManager.default.removeItem(at: part)
            return .hashMismatch
        }
        do {
            try FileManager.default.createDirectory(at: mediaDir, withIntermediateDirectories: true)
            let target = mediaFile(contentHash)
            // Same filesystem (both under this cache's own root), so a move failure is a real I/O
            // problem, not a cross-device-link case to work around with a copy.
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: part, to: target)
        } catch {
            try? FileManager.default.removeItem(at: part)
            return .ioError
        }
        return .promoted
    }

    public func deletePart(_ contentHash: ContentHash) {
        try? FileManager.default.removeItem(at: partFile(contentHash))
    }

    @discardableResult
    public func deleteMedia(_ contentHash: ContentHash) -> Bool {
        (try? FileManager.default.removeItem(at: mediaFile(contentHash))) != nil
    }

    /// Brief §12: `.part` files are swept on startup — nothing partial survives a process restart as
    /// a candidate to resume.
    public func sweepIncomplete() {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: incomingDir.path) else { return }
        for name in names {
            try? FileManager.default.removeItem(at: incomingDir.appendingPathComponent(name))
        }
    }

    private func fileSize(at url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        return (attributes[.size] as? NSNumber)?.int64Value
    }
}

public enum CacheStorageError: Error, Equatable {
    case cannotOpenPartFile
}
