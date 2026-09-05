import Foundation
import GRDB
import RideLinkCore

/// The one seam between `RideLinkCore.Library` (pure domain) and `TrackRecord`/GRDB. Nothing outside
/// this file touches the database queue directly, matching the module-boundary discipline ADR-014
/// already applies on Android. Mirrors `com.ridelink.data.library.LibraryRepository`.
///
/// Sorting happens here, in Swift, over an already-FTS-matched result set — this phase's brief §13
/// requires the *matching* to be database-native (done, via FTS5), not the sort, and a personal
/// library's result set is small enough that sorting it in memory is simpler than four near-duplicate
/// `ORDER BY` queries.
///
/// **Identity note (ADR-005 Amendment A1):** every method below is keyed by `LocalEntryId` or by
/// `LocalTrackLocation`'s `uri` — never by `QuickId`, which is not guaranteed unique across rows and
/// must never be used to look up or mutate "the" row for a given value.
public final class LibraryRepository: Sendable {
    private let dbQueue: DatabaseQueue

    public init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    /// A fresh array of matching entries every time the underlying table changes — GRDB's
    /// `ValueObservation`, the same "reactive query" role `TrackDao.observeAll`/`observeSearch`'s
    /// `Flow` play on Android, bridged to `AsyncStream` so callers do not need a GRDB import of
    /// their own (this package's public surface stays narrow — RideLinkCore types in, plain Swift
    /// types out).
    public func observe(query: LibraryQuery) -> AsyncStream<[LibraryEntry]> {
        let observation = ValueObservation.tracking { db in
            try Self.fetch(db, query: query)
        }
        let (stream, continuation) = AsyncStream<[LibraryEntry]>.makeStream()
        let task = Task {
            do {
                for try await entries in observation.values(in: dbQueue) {
                    continuation.yield(entries)
                }
            } catch {
                // A cancelled observation (the caller's Task went away) surfaces here as a thrown
                // error too; either way there is nothing left to yield.
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    /// Every currently-known location and the `quickId` last recorded for it — exactly what
    /// `RideLinkCore.Library.IndexReconciliation` needs to decide new/unchanged/changed/missing, and
    /// nothing else (never a full row read for a whole-library scan).
    public func allLocationsAndQuickIds() throws -> [LocalTrackLocation: QuickId] {
        try dbQueue.read { db in
            try Row.fetchAll(db, sql: "SELECT locationUri, quickId FROM \(TrackRecord.databaseTableName)")
        }
        .reduce(into: [LocalTrackLocation: QuickId]()) { result, row in
            guard let quickId = QuickId.parse(row["quickId"]) else { return }
            result[LocalTrackLocation(uri: row["locationUri"])] = quickId
        }
    }

    public func findByLocalEntryId(_ localEntryId: LocalEntryId) throws -> LibraryEntry? {
        try dbQueue.read { db in
            try TrackRecord.filter(Column("localEntryId") == localEntryId.value).fetchOne(db)
        }
        .flatMap(LibraryMapping.toDomain)
    }

    public func findByLocationUri(_ locationUri: String) throws -> LibraryEntry? {
        try dbQueue.read { db in
            try TrackRecord.filter(Column("locationUri") == locationUri).fetchOne(db)
        }
        .flatMap(LibraryMapping.toDomain)
    }

    /// Every row still missing its authoritative `ContentHash` — ADR-005's lazy background hashing
    /// pass reads this directly rather than a possibly-stale UI snapshot.
    public func entriesMissingContentHash() throws -> [LibraryEntry] {
        try dbQueue.read { db in
            try TrackRecord.filter(Column("contentHash") == nil).fetchAll(db)
        }
        .compactMap(LibraryMapping.toDomain)
    }

    /// Phase 4's transfer-serving lookup (brief §9): `content_hash` is **not** unique at the schema
    /// level (ADR-005 — two rows may legitimately share one, being byte-identical files), so this
    /// returns the first match by `id`. Any row sharing a `content_hash` is, by definition, an
    /// equally valid source for those bytes; this repository does not need to try a second one if
    /// the first fails to open (the caller reports a plain I/O failure rather than searching
    /// further). Mirrors `com.ridelink.data.database.TrackDao.findByContentHash`.
    public func findByContentHash(_ contentHash: ContentHash) throws -> LibraryEntry? {
        try dbQueue.read { db in
            try TrackRecord
                .filter(Column("contentHash") == contentHash.value)
                .filter(Column("decodeStatus") == LibraryMapping.storedValue(for: .indexed))
                .order(Column("id"))
                .fetchOne(db)
        }
        .flatMap(LibraryMapping.toDomain)
    }

    /// Phase 4's manifest generator input (brief §5): every row that is both usable (`.indexed`,
    /// never `.missing`/`.unsupported`/`.corrupt`) and sync-eligible (`contentHash` present —
    /// ADR-005). A one-shot throwing read, deliberately not `observe`'s `AsyncStream` — manifest
    /// generation must not depend on a UI collector currently being active. Ordered by `contentHash`
    /// so two generation passes over an unchanged library produce byte-identical manifest ordering.
    /// Mirrors `com.ridelink.data.database.TrackDao.findAllSyncEligible`.
    public func allSyncEligible() throws -> [LibraryEntry] {
        try dbQueue.read { db in
            try TrackRecord
                .filter(Column("contentHash") != nil)
                .filter(Column("decodeStatus") == LibraryMapping.storedValue(for: .indexed))
                .order(Column("contentHash"))
                .fetchAll(db)
        }
        .compactMap(LibraryMapping.toDomain)
    }

    /// A location never indexed before — `entry` carries a freshly-generated `LocalEntryId` the
    /// caller must have already assigned. A `localEntryId` or `locationUri` collision on insert would
    /// mean two different rows were assigned the same fresh identity, or that the caller failed to
    /// find an existing row that was already there — either way a bug to surface loudly (GRDB's plain
    /// `insert`, not an upsert, so a `UNIQUE` violation throws rather than silently replacing an
    /// unrelated row, matching `TrackDao.insertNew`'s `OnConflictStrategy.ABORT`).
    public func insertNew(_ entry: LibraryEntry) throws {
        try dbQueue.write { db in
            var record = LibraryMapping.toRecord(entry)
            try record.insert(db)
        }
    }

    /// The same `IndexReconciliation.ReconciliationPlan.changedLocations` row, re-indexed after an
    /// in-place edit: `entry` must carry the *existing* row's `LocalEntryId` and `LocalTrackLocation`
    /// unchanged — only its metadata/`quickId`/`contentHash`/decode status are refreshed.
    /// `indexedAtMonoUs` is deliberately absent from the `UPDATE` column list, exactly like
    /// `TrackDao.updateReindexed`'s SQL: the row's original index time is preserved, never overwritten
    /// by whatever timestamp a re-index happened to build its `LibraryEntry` with.
    public func updateReindexed(_ entry: LibraryEntry) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: """
                UPDATE \(TrackRecord.databaseTableName) SET
                    quickId = ?, contentHash = NULL, title = ?, artist = ?, album = ?, durationMs = ?,
                    filename = ?, codec = ?, bitrateKbps = ?, artworkRef = ?, sizeBytes = ?,
                    decodeStatus = ?, lastSeenAtMonoUs = ?
                WHERE localEntryId = ?
                """,
                arguments: [
                    entry.track.quickId.value,
                    entry.track.title,
                    entry.track.artist,
                    entry.track.album,
                    entry.track.durationMs,
                    entry.track.filename,
                    entry.track.codec,
                    entry.track.bitrateKbps,
                    entry.track.artworkRef,
                    entry.track.sizeBytes,
                    LibraryMapping.storedValue(for: entry.decodeStatus),
                    entry.lastSeenAtMonoUs,
                    entry.localEntryId.value,
                ]
            )
        }
    }

    /// ADR-005's lazy background pass: fills in the authoritative `ContentHash` for a row that
    /// already exists, identified by its `LocalEntryId` — never by `quickId`, and never by
    /// re-deriving which row "should" get it from content alone.
    public func updateContentHash(localEntryId: LocalEntryId, contentHash: ContentHash?) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE \(TrackRecord.databaseTableName) SET contentHash = ? WHERE localEntryId = ?",
                arguments: [contentHash?.value, localEntryId.value]
            )
        }
    }

    /// A location this scan saw again with an unchanged `quickId` —
    /// `IndexReconciliation.ReconciliationPlan.unchangedLocations`. Brings a `.missing` row back to
    /// `.indexed` without touching identity or metadata.
    public func touchSeen(locationUri: String, atMonoUs: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE \(TrackRecord.databaseTableName) SET decodeStatus = ?, lastSeenAtMonoUs = ? WHERE locationUri = ?",
                arguments: [LibraryMapping.storedValue(for: .indexed), atMonoUs, locationUri]
            )
        }
    }

    /// A previously-indexed location this scan did not find —
    /// `IndexReconciliation.ReconciliationPlan.missingLocations`.
    public func markMissing(locationUri: String, atMonoUs: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE \(TrackRecord.databaseTableName) SET decodeStatus = ?, lastSeenAtMonoUs = ? WHERE locationUri = ?",
                arguments: [LibraryMapping.storedValue(for: .missing), atMonoUs, locationUri]
            )
        }
    }

    public func deleteByLocalEntryId(_ localEntryId: LocalEntryId) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM \(TrackRecord.databaseTableName) WHERE localEntryId = ?", arguments: [localEntryId.value])
        }
    }

    public func deleteAll() throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM \(TrackRecord.databaseTableName)") }
    }

    public func count() throws -> Int {
        try dbQueue.read { db in try TrackRecord.fetchCount(db) }
    }

    private static func fetch(_ db: Database, query: LibraryQuery) throws -> [LibraryEntry] {
        let records: [TrackRecord]
        let trimmed = query.searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            records = try TrackRecord.fetchAll(db)
        } else if let pattern = FTS5Pattern(matchingAllPrefixesIn: trimmed) {
            records = try TrackRecord.fetchAll(
                db,
                sql: """
                SELECT track.* FROM track
                JOIN track_fts ON track_fts.rowid = track.id
                WHERE track_fts MATCH ?
                """,
                arguments: [pattern]
            )
        } else {
            // A pattern GRDB itself judged unbuildable (this phase's plan's own note: FTS5Pattern's
            // initializers never throw, but they do return nil for pathological input like a bare
            // "*") — the defined empty-query-shaped behaviour is "no results", not a crash.
            records = []
        }
        return sorted(records.compactMap(LibraryMapping.toDomain), by: query.sort)
    }

    private static func sorted(_ entries: [LibraryEntry], by sort: LibrarySort) -> [LibraryEntry] {
        switch sort {
        case .title: entries.sorted { $0.track.title.lowercased() < $1.track.title.lowercased() }
        case .artist: entries.sorted { $0.track.artist.lowercased() < $1.track.artist.lowercased() }
        case .album: entries.sorted { $0.track.album.lowercased() < $1.track.album.lowercased() }
        case .recentlyAdded: entries.sorted { $0.indexedAtMonoUs > $1.indexedAtMonoUs }
        }
    }
}
