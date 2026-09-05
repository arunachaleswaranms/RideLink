import CryptoKit
import Foundation
import GRDB
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `LocalContentResolver` — cache-first-then-library-fallback resolution, the ADR-023 §7 staleness
/// check, and the cases that must fail closed. Real GRDB, real files, real `LibraryIndexer` import
/// pipeline (`LibraryIndexerTests`' convention). Mirrors
/// `com.ridelink.data.transfer.LocalContentResolver`'s test coverage — no direct Android test file
/// exists for it, so this follows the same real-pipeline style the rest of this stage's tests use.
final class LocalContentResolverTests: XCTestCase {
    private var dbQueue: DatabaseQueue!
    private var repository: LibraryRepository!
    private var indexer: LibraryIndexer!
    private var musicDirectory: URL!
    private var cachesDirectory: URL!
    private var cacheRoot: URL!
    private var cacheStorage: CacheStorage!
    private var cacheRepository: TransferCacheRepository!
    private var resolver: LocalContentResolver!
    private let clock = TestClock()

    override func setUpWithError() throws {
        dbQueue = try DatabaseQueue()
        try LibraryDatabase.makeMigrator().migrate(dbQueue)
        repository = LibraryRepository(dbQueue: dbQueue)
        let tempRoot = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        musicDirectory = tempRoot.appendingPathComponent("music")
        cachesDirectory = tempRoot.appendingPathComponent("caches")
        cacheRoot = tempRoot.appendingPathComponent("transfer_cache")
        try FileManager.default.createDirectory(at: musicDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cachesDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
        indexer = LibraryIndexer(
            repository: repository,
            artworkCache: ArtworkCache(cachesDirectory: cachesDirectory),
            musicDirectory: musicDirectory,
            monotonicNowUs: { [clock] in clock.next() }
        )
        cacheStorage = CacheStorage(root: cacheRoot)
        cacheRepository = TransferCacheRepository(storage: cacheStorage, dbQueue: dbQueue)
        resolver = LocalContentResolver(libraryRepository: repository, libraryIndexer: indexer, cacheRepository: cacheRepository)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: musicDirectory.deletingLastPathComponent())
    }

    private func firstEntries() async -> [LibraryEntry] {
        var iterator = repository.observe(query: LibraryQuery()).makeAsyncIterator()
        return await iterator.next() ?? []
    }

    func testAContentHashNotInTheLibraryOrCacheIsNotFound() throws {
        let missing = ContentHash("sha256:" + String(repeating: "9", count: 64))
        let resolution = try resolver.resolve(contentHash: missing, nowMonoUs: 1_000)
        XCTAssertEqual(resolution, .notFound)
    }

    func testALibraryTrackResolvesToItsAppOwnedFileWithItsCurrentSize() async throws {
        try await indexer.importFiles([TestMedia.url("normal.m4a")])
        try await indexer.completeContentHashing()
        let entries = await firstEntries()
        let entry = try XCTUnwrap(entries.first)
        let hash = try XCTUnwrap(entry.track.contentHash)

        let resolution = try resolver.resolve(contentHash: hash, nowMonoUs: 1_000)

        guard case .found(let fileURL, let sizeBytes) = resolution else {
            return XCTFail("expected .found, got \(resolution)")
        }
        XCTAssertEqual(fileURL, indexer.resolvedUrl(for: entry))
        XCTAssertEqual(sizeBytes, entry.track.sizeBytes)
    }

    func testAFileEditedAfterIndexingFailsWithFileChangedRatherThanServingStaleBytes() async throws {
        try await indexer.importFiles([TestMedia.url("normal.m4a")])
        try await indexer.completeContentHashing()
        let entries = await firstEntries()
        let entry = try XCTUnwrap(entries.first)
        let hash = try XCTUnwrap(entry.track.contentHash)
        // Simulate the on-disk file changing size after indexing (ADR-023 §7's actual risk case).
        let handle = try FileHandle(forWritingTo: indexer.resolvedUrl(for: entry))
        try handle.seekToEnd()
        try handle.write(contentsOf: Data(repeating: 0xFF, count: 4_096))
        try handle.close()

        let resolution = try resolver.resolve(contentHash: hash, nowMonoUs: 1_000)

        XCTAssertEqual(resolution, .fileChanged)
    }

    func testALibraryFileRemovedFromDiskFailsWithIoErrorRatherThanCrashing() async throws {
        try await indexer.importFiles([TestMedia.url("normal.m4a")])
        try await indexer.completeContentHashing()
        let entries = await firstEntries()
        let entry = try XCTUnwrap(entries.first)
        let hash = try XCTUnwrap(entry.track.contentHash)
        try FileManager.default.removeItem(at: indexer.resolvedUrl(for: entry))

        let resolution = try resolver.resolve(contentHash: hash, nowMonoUs: 1_000)

        XCTAssertEqual(resolution, .ioError)
    }

    func testAVerifiedCacheEntryIsPreferredOverTheLibraryEvenWhenBothExist() async throws {
        try await indexer.importFiles([TestMedia.url("normal.m4a")])
        try await indexer.completeContentHashing()
        let entries = await firstEntries()
        let entry = try XCTUnwrap(entries.first)
        let hash = try XCTUnwrap(entry.track.contentHash)

        // Promote a (deliberately different-content, same-hash-claimed-for-the-test) cache entry —
        // what matters here is only that the cache path, once verified+committed, wins the lookup.
        let bytes = try Data(contentsOf: indexer.resolvedUrl(for: entry))
        let partHandle = try cacheStorage.openPartForWrite(hash)
        try cacheStorage.appendChunk(partHandle, bytes: bytes)
        try partHandle.close()
        let promoteResult = cacheStorage.promote(hash, expectedSizeBytes: Int64(bytes.count))
        XCTAssertEqual(promoteResult, .promoted)
        try cacheRepository.commit(hash, sizeBytes: Int64(bytes.count), nowMonoUs: 1_000)

        let resolution = try resolver.resolve(contentHash: hash, nowMonoUs: 2_000)

        guard case .found(let fileURL, _) = resolution else {
            return XCTFail("expected .found, got \(resolution)")
        }
        XCTAssertEqual(fileURL, cacheStorage.mediaFile(hash), "the cache path must win over the library fallback")
    }
}

/// A tiny `Sendable` monotonic counter — mirrors `LibraryIndexerTests`' own `TestClock`.
private final class TestClock: @unchecked Sendable {
    private var value: Int64 = 0
    func next() -> Int64 {
        value += 1
        return value
    }
}
