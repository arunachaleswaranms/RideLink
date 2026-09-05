import CryptoKit
import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `CacheStorage`'s two-phase commit over **real files** — brief §33's transfer-integrity harness
/// (A: exact success, B: corrupt byte, C: truncated, D: oversized/extra bytes), plus the safety
/// properties around it. Real files in a temp directory, not mocked — this is the exact code path a
/// multi-megabyte real transfer runs. Mirrors `com.ridelink.data.transfer.CacheStorageTest`.
final class CacheStorageTests: XCTestCase {
    private var root: URL!
    private var storage: CacheStorage!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("ridelink-cache-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        storage = CacheStorage(root: root)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func hashOf(_ bytes: Data) -> ContentHash {
        ContentHash("sha256:" + SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
    }

    private func writeChunks(_ hash: ContentHash, bytes: Data, chunkSize: Int = 65_536) throws {
        let handle = try storage.openPartForWrite(hash)
        var offset = 0
        while offset < bytes.count {
            let end = min(offset + chunkSize, bytes.count)
            try storage.appendChunk(handle, bytes: bytes.subdata(in: offset..<end))
            offset = end
        }
        try handle.close()
    }

    /// A: exact successful file — sender bytes -> multiple chunks -> receiver temp file -> final
    /// hash matches -> atomic cache commit.
    func testA_exactSuccessfulTransferPromotesToAVerifiedMediaFile() throws {
        let bytes = Data((0..<200_000).map { UInt8($0 % 253) })
        let hash = hashOf(bytes)
        try writeChunks(hash, bytes: bytes)

        let result = storage.promote(hash, expectedSizeBytes: Int64(bytes.count))

        XCTAssertEqual(result, .promoted)
        XCTAssertTrue(storage.hasMediaFile(hash))
        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(hash).path), "the .part file must not survive a successful promote")
        let attrs = try FileManager.default.attributesOfItem(atPath: storage.mediaFile(hash).path)
        XCTAssertEqual(attrs[.size] as? Int64, Int64(bytes.count))
        XCTAssertEqual(try Data(contentsOf: storage.mediaFile(hash)), bytes)
    }

    /// B: corrupt byte — one byte modified in transit/test stream -> hash mismatch -> no valid cache.
    func testB_aSingleCorruptedByteFailsHashVerificationAndPromotesNothing() throws {
        let original = Data((0..<1_000).map { UInt8($0 % 256) })
        let hash = hashOf(original) // the hash we *requested*
        var corrupted = original
        corrupted[500] = corrupted[500] &+ 1
        try writeChunks(hash, bytes: corrupted)

        let result = storage.promote(hash, expectedSizeBytes: Int64(corrupted.count))

        XCTAssertEqual(result, .hashMismatch)
        XCTAssertFalse(storage.hasMediaFile(hash), "no valid cache entry may exist after a hash mismatch")
        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(hash).path), "the corrupt .part must be deleted, not left behind")
    }

    /// C: truncated — receiver never got all the bytes -> failure -> no valid cache.
    func testC_aTruncatedStreamFailsTheExactSizeCheckAndPromotesNothing() throws {
        let full = Data((0..<10_000).map { UInt8($0 % 256) })
        let hash = hashOf(full)
        try writeChunks(hash, bytes: full.subdata(in: 0..<4_000)) // only 40% arrived

        let result = storage.promote(hash, expectedSizeBytes: Int64(full.count))

        XCTAssertEqual(result, .sizeMismatch)
        XCTAssertFalse(storage.hasMediaFile(hash))
        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(hash).path))
    }

    /// D: oversized/extra bytes — more arrived than declared -> failure.
    func testD_extraBytesPastTheDeclaredSizeFailTheExactSizeCheck() throws {
        let declared = Data((0..<1_000).map { UInt8($0 % 256) })
        let withExtra = declared + Data(repeating: 0x7F, count: 500)
        let hash = hashOf(declared) // the hash of the *declared* content, not what actually arrived
        try writeChunks(hash, bytes: withExtra)

        let result = storage.promote(hash, expectedSizeBytes: Int64(declared.count))

        XCTAssertEqual(result, .sizeMismatch)
        XCTAssertFalse(storage.hasMediaFile(hash))
    }

    /// E (disconnect mid-transfer): a cancelled/interrupted transfer's .part is safely disposable,
    /// never promoted.
    func testACancelledTransferLeavesADisposablePartFileNeverAPromotedOne() throws {
        let hash = ContentHash("sha256:" + String(repeating: "7", count: 64))
        try writeChunks(hash, bytes: Data(repeating: 0, count: 100))
        // the transfer is cancelled here — deletePart is what a cancellation handler calls
        storage.deletePart(hash)

        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(hash).path))
        XCTAssertFalse(storage.hasMediaFile(hash))
    }

    func testSweepIncompleteRemovesEveryPartFileOnStartup() throws {
        let h1 = ContentHash("sha256:" + String(repeating: "1", count: 64))
        let h2 = ContentHash("sha256:" + String(repeating: "2", count: 64))
        try storage.openPartForWrite(h1).close()
        try storage.openPartForWrite(h2).close()

        storage.sweepIncomplete()

        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(h1).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.partFile(h2).path))
    }

    func testReopeningAPartFileForWriteTruncatesAnyStalePartialContent() throws {
        let hash = ContentHash("sha256:" + String(repeating: "3", count: 64))
        let stale = try storage.openPartForWrite(hash)
        try storage.appendChunk(stale, bytes: Data(repeating: 0, count: 10_000))
        try stale.close()
        let fresh = try storage.openPartForWrite(hash)
        let freshBytes = Data((0..<10).map { UInt8($0) })
        try storage.appendChunk(fresh, bytes: freshBytes)
        try fresh.close()

        let attrs = try FileManager.default.attributesOfItem(atPath: storage.partFile(hash).path)
        XCTAssertEqual(attrs[.size] as? Int64, Int64(freshBytes.count), "a fresh open must not append to stale bytes")
    }

    func testTheCachePathIsDerivedOnlyFromTheContentHashNeverAFilename() {
        let hash = ContentHash("sha256:" + String(repeating: "a", count: 64))
        XCTAssertEqual(storage.mediaFile(hash).lastPathComponent, String(repeating: "a", count: 64))
        XCTAssertEqual(storage.partFile(hash).lastPathComponent, "\(String(repeating: "a", count: 64)).part")
        XCTAssertFalse(storage.mediaFile(hash).lastPathComponent.contains(".."))
    }
}
