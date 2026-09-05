import CryptoKit
import Foundation
import GRDB
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `TransferCacheRepository` — the DB-plus-file commit order, cache-hit dedupe, and bounded
/// eviction. Real files plus a real in-memory GRDB database, matching `LibraryIndexerTests`'
/// convention rather than a hand-written fake DAO. Mirrors
/// `com.ridelink.data.transfer.TransferCacheRepositoryTest`.
final class TransferCacheRepositoryTests: XCTestCase {
    private var root: URL!
    private var storage: CacheStorage!
    private var dbQueue: DatabaseQueue!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("ridelink-cache-repo-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        storage = CacheStorage(root: root)
        dbQueue = try DatabaseQueue()
        try LibraryDatabase.makeMigrator().migrate(dbQueue)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func hashOf(_ bytes: Data) -> ContentHash {
        ContentHash("sha256:" + SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
    }

    /// Streams `bytes` through the real two-phase commit, asserting the promote itself really
    /// succeeds.
    @discardableResult
    private func promoteAndCommit(
        _ repo: TransferCacheRepository,
        bytes: Data,
        nowMonoUs: Int64,
        locked: Set<ContentHash> = []
    ) throws -> ContentHash {
        let hash = hashOf(bytes)
        let handle = try storage.openPartForWrite(hash)
        try storage.appendChunk(handle, bytes: bytes)
        try handle.close()
        let result = storage.promote(hash, expectedSizeBytes: Int64(bytes.count))
        guard result == .promoted else { throw TestSetupError.promoteFailed(result) }
        try repo.commit(hash, sizeBytes: Int64(bytes.count), nowMonoUs: nowMonoUs, locked: locked)
        return hash
    }

    private enum TestSetupError: Error { case promoteFailed(PromoteResult) }

    /// F: cache hit -> no transfer. The repository is what a caller checks *before* ever issuing a
    /// TRANSFER_REQUEST.
    func testF_aVerifiedEntryIsReportedCachedWithoutNeedingATransfer() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue)
        let bytes = Data((0..<1_000).map { UInt8($0 % 256) })
        XCTAssertFalse(try repo.isVerifiedCached(hashOf(bytes)), "nothing cached yet")

        let hash = try promoteAndCommit(repo, bytes: bytes, nowMonoUs: 1_000)

        XCTAssertTrue(try repo.isVerifiedCached(hash))
    }

    /// H: same ContentHash from two catalogue entries -> one verified cache object sufficient.
    func testH_twoManifestEntriesSharingAContentHashAreSatisfiedByOneCacheCommit() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue)
        let bytes = Data((0..<500).map { UInt8($0 % 256) })
        let hash = try promoteAndCommit(repo, bytes: bytes, nowMonoUs: 1_000)

        XCTAssertTrue(try repo.isVerifiedCached(hash))
        XCTAssertTrue(try repo.isVerifiedCached(hash))
        let opened = try repo.open(hash, nowMonoUs: 2_000)
        let attrs = try FileManager.default.attributesOfItem(atPath: XCTUnwrap(opened).path)
        XCTAssertEqual(attrs[.size] as? Int64, 500)
    }

    func testOpenTouchesLastAccessTime() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue)
        let hash = try promoteAndCommit(repo, bytes: Data((0..<10).map { UInt8($0) }), nowMonoUs: 1_000)

        _ = try repo.open(hash, nowMonoUs: 5_000)

        XCTAssertEqual(try repo.verifiedEntry(hash)?.lastAccessAtMonoUs, 5_000)
    }

    func testOpenForgetsARowWhoseFileHasDisappearedRatherThanServingAHashTheBytesNoLongerBack() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue)
        let hash = try promoteAndCommit(repo, bytes: Data((0..<10).map { UInt8($0) }), nowMonoUs: 1_000)
        storage.deleteMedia(hash) // simulate the file vanishing out from under the DB row

        let opened = try repo.open(hash, nowMonoUs: 2_000)

        XCTAssertNil(opened)
        XCTAssertFalse(try repo.isVerifiedCached(hash), "the stale row must be forgotten, not merely ignored once")
    }

    func testEvictionRemovesTheLeastRecentlyUsedUnlockedEntryOnceOverTheByteBudget() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue, maxCacheBytes: 150)
        let old = try promoteAndCommit(repo, bytes: Data((0..<100).map { UInt8($0) }), nowMonoUs: 1_000)
        let recent = try promoteAndCommit(repo, bytes: Data((0..<100).map { UInt8($0 + 1) }), nowMonoUs: 2_000) // pushes total to 200 > 150

        XCTAssertFalse(try repo.isVerifiedCached(old), "the older, unlocked entry is evicted first")
        XCTAssertTrue(try repo.isVerifiedCached(recent), "the entry that just committed is never evicted for itself")
        XCTAssertFalse(storage.hasMediaFile(old), "eviction must remove the file, not just the row")
    }

    func testALockedEntryIsNeverEvictedEvenWhenItIsTheLeastRecentlyUsed() throws {
        let repo = TransferCacheRepository(storage: storage, dbQueue: dbQueue, maxCacheBytes: 150)
        let inUse = try promoteAndCommit(repo, bytes: Data((0..<100).map { UInt8($0) }), nowMonoUs: 1_000, locked: [])

        // The caller (a coordinator that actually knows what is currently being played or
        // transferred) must pass the full in-use set on every commit — `locked` is not a persisted
        // flag on the row, since only the caller's runtime state knows it.
        try promoteAndCommit(repo, bytes: Data((0..<100).map { UInt8($0 + 1) }), nowMonoUs: 2_000, locked: [inUse])

        XCTAssertTrue(try repo.isVerifiedCached(inUse), "a locked entry survives even as the oldest one")
    }
}
