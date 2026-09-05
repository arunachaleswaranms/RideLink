import Foundation
import GRDB
import RideLinkCore

/// The verified transfer cache (ADR-023 §6): `CacheStorage`'s bytes plus `TransferCacheRecord`'s
/// metadata, combined behind the one commit order that keeps them consistent even across a crash —
/// the file is moved into place **first**, and only then does the database row get written. A crash
/// between the two leaves an unreferenced file and no row, which this repository treats as "not
/// cached" (fails closed) rather than a false-positive hit; it is safe to sweep such orphans later
/// and merely costs a redundant re-transfer, never a wrong result. Mirrors
/// `com.ridelink.data.transfer.TransferCacheRepository` exactly, including its API contract.
///
/// A conservative, capacity-bounded LRU-by-last-access eviction is the full extent of Phase 4's cache
/// policy (brief §16) — never evicting an entry the caller marks `locked` (currently transferring, or
/// referenced by the player).
public final class TransferCacheRepository: Sendable {
    private let storage: CacheStorage
    private let dbQueue: DatabaseQueue
    private let maxCacheBytes: Int64

    /// A conservative default (brief §16/§30) — full policy configurability is out of Phase 4 scope.
    public static let defaultMaxCacheBytes: Int64 = 2 * 1024 * 1024 * 1024 // 2 GiB

    public init(storage: CacheStorage, dbQueue: DatabaseQueue, maxCacheBytes: Int64 = TransferCacheRepository.defaultMaxCacheBytes) {
        self.storage = storage
        self.dbQueue = dbQueue
        self.maxCacheBytes = maxCacheBytes
    }

    /// True only once bytes have arrived, been whole-file verified, **and** committed (ADR-023 §6).
    public func isVerifiedCached(_ contentHash: ContentHash) throws -> Bool {
        try findVerified(contentHash) != nil
    }

    public func verifiedEntry(_ contentHash: ContentHash) throws -> TransferCacheRecord? {
        try findVerified(contentHash)
    }

    /// @return the readable, verified media file's URL, touching its last-access time, or nil if not
    ///   cached.
    public func open(_ contentHash: ContentHash, nowMonoUs: Int64) throws -> URL? {
        guard try findVerified(contentHash) != nil else { return nil }
        let file = storage.mediaFile(contentHash)
        guard FileManager.default.fileExists(atPath: file.path) else {
            // The row claims verified but the file is gone (e.g. manually cleared storage) — fail
            // closed and forget the stale row rather than serving a hash the bytes no longer back.
            try delete(contentHash)
            return nil
        }
        try touchAccess(contentHash, atMonoUs: nowMonoUs)
        return file
    }

    /// Commits a just-`CacheStorage.promote`d file as the verified cache entry, then evicts the
    /// least-recently-used unlocked entries until the total is back under `maxCacheBytes` — never
    /// evicting `contentHash` itself or anything in `locked`.
    ///
    /// `locked` is **not** a persisted flag on any row — this repository has no way to know what a
    /// caller currently has open. The coordinator calling `commit` must pass the *complete* current
    /// in-use set (whatever is mid-transfer or referenced by the player) on every call; an entry
    /// omitted here because an earlier call happened to include it is not protected.
    public func commit(
        _ contentHash: ContentHash,
        sizeBytes: Int64,
        nowMonoUs: Int64,
        locked: Set<ContentHash> = []
    ) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: """
                INSERT OR REPLACE INTO \(TransferCacheRecord.databaseTableName)
                    (contentHash, cacheFileName, sizeBytes, verified, verifiedAtMonoUs, lastAccessAtMonoUs)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                arguments: [
                    contentHash.value,
                    storage.mediaFile(contentHash).lastPathComponent,
                    sizeBytes,
                    true,
                    nowMonoUs,
                    nowMonoUs,
                ]
            )
        }
        try evictIfNeeded(locked: locked.union([contentHash]))
    }

    /// Never deletes a file the user's own library still references — see `CacheStorage`'s
    /// module-boundary note.
    private func evictIfNeeded(locked: Set<ContentHash>) throws {
        var total = try totalBytes()
        if total <= maxCacheBytes { return }
        let lockedValues = Set(locked.map(\.value))
        for candidate in try evictionCandidates(excluding: lockedValues) {
            if total <= maxCacheBytes { break }
            guard let candidateHash = ContentHash.parse(candidate.contentHash) else { continue }
            storage.deleteMedia(candidateHash)
            try delete(candidateHash)
            total -= candidate.sizeBytes
        }
    }

    private func findVerified(_ contentHash: ContentHash) throws -> TransferCacheRecord? {
        try dbQueue.read { db in
            try TransferCacheRecord
                .filter(Column("contentHash") == contentHash.value)
                .filter(Column("verified") == true)
                .fetchOne(db)
        }
    }

    private func touchAccess(_ contentHash: ContentHash, atMonoUs: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE \(TransferCacheRecord.databaseTableName) SET lastAccessAtMonoUs = ? WHERE contentHash = ?",
                arguments: [atMonoUs, contentHash.value]
            )
        }
    }

    private func delete(_ contentHash: ContentHash) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "DELETE FROM \(TransferCacheRecord.databaseTableName) WHERE contentHash = ?",
                arguments: [contentHash.value]
            )
        }
    }

    /// Bounded eviction candidates: least-recently-used first, excluding anything the caller has
    /// locked.
    private func evictionCandidates(excluding locked: Set<String>) throws -> [TransferCacheRecord] {
        try dbQueue.read { db in
            try TransferCacheRecord
                .filter(!locked.contains(Column("contentHash")))
                .order(Column("lastAccessAtMonoUs"))
                .fetchAll(db)
        }
    }

    private func totalBytes() throws -> Int64 {
        try dbQueue.read { db in
            try Int64.fetchOne(db, sql: "SELECT COALESCE(SUM(sizeBytes), 0) FROM \(TransferCacheRecord.databaseTableName)") ?? 0
        }
    }
}
