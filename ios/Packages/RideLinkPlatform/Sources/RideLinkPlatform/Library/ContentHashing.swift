import CryptoKit
import Foundation
import RideLinkCore

/// Mirrors `com.ridelink.data.library.ContentHashing`'s failure shape: a thrown error rather than a
/// silent placeholder, so the caller (`LibraryIndexer`) decides what an unreadable file means for
/// `DecodeStatus` instead of this type inventing a value for it.
public enum ContentHashingError: Error {
    case cannotOpen
    case unknownSize
}

/// ADR-005's two-tier hashing, implemented directly against a local file URL rather than copying it
/// first — the whole reason `QuickId` is affordable at scan time (~1 ms/file, ADR-005) is that only
/// up to `windowSizeBytes` × 2 bytes are ever read for it, regardless of file size, by seeking
/// rather than streaming the middle of large files. Mirrors
/// `com.ridelink.data.library.ContentHashing` exactly — same window size, same small-file threshold,
/// same big-endian size prefix, so the two platforms hash identical bytes to identical hex.
///
/// `CryptoKit`, matching every other hash/signature in this app
/// (`RideLinkCore.Security.IdentityCertificate`, `RideLinkPlatform.Security.DeviceIdentity`) — one
/// crypto surface, not two. `CryptoKit` is available on both this package's declared platforms
/// (iOS and macOS, the latter for `swift test`), so there is no availability branch to write.
public enum ContentHashing {
    private static let windowSizeBytes = 64 * 1024
    private static let smallFileThresholdBytes = 128 * 1024

    /// `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)`, or `SHA-256(size_bytes ‖ whole file)` for
    /// files under 128 KiB (ADR-005: "no double-counting the overlapping window").
    public static func computeQuickId(fileURL: URL) throws -> QuickId {
        let size = try fileSize(of: fileURL)
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { throw ContentHashingError.cannotOpen }
        defer { try? handle.close() }

        var hasher = SHA256()
        hasher.update(data: bigEndianBytes(of: UInt64(size)))
        if size <= smallFileThresholdBytes {
            hasher.update(data: try readWindow(handle, offset: 0, length: size))
        } else {
            hasher.update(data: try readWindow(handle, offset: 0, length: windowSizeBytes))
            hasher.update(data: try readWindow(handle, offset: size - windowSizeBytes, length: windowSizeBytes))
        }
        return QuickId("sha256:" + hasher.finalize().hexString)
    }

    /// `SHA-256(whole file)` — the authoritative, lazily-computed tier (ADR-005). Streamed in
    /// bounded chunks; never holds more than one buffer's worth of the file in memory regardless of
    /// its size.
    public static func computeContentHash(fileURL: URL) throws -> ContentHash {
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { throw ContentHashingError.cannotOpen }
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            guard let chunk = try handle.read(upToCount: windowSizeBytes), !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return ContentHash("sha256:" + hasher.finalize().hexString)
    }

    private static func readWindow(
        _ handle: FileHandle,
        offset: Int,
        length: Int
    ) throws -> Data {
        try handle.seek(toOffset: UInt64(offset))
        var collected = Data()
        while collected.count < length {
            guard let chunk = try handle.read(upToCount: length - collected.count), !chunk.isEmpty else { break }
            collected.append(chunk)
        }
        return collected
    }

    private static func fileSize(of url: URL) throws -> Int {
        guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize else {
            throw ContentHashingError.unknownSize
        }
        return size
    }

    private static func bigEndianBytes(of value: UInt64) -> Data {
        var big = value.bigEndian
        return Data(bytes: &big, count: MemoryLayout<UInt64>.size)
    }
}

private extension SHA256Digest {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
