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

    public func allQuickIds() throws -> Set<QuickId> {
        try dbQueue.read { db in
            try String.fetchAll(db, sql: "SELECT quickId FROM track")
        }
        .compactMap(QuickId.parse)
        .reduce(into: Set<QuickId>()) { $0.insert($1) }
    }

    public func findByQuickId(_ quickId: QuickId) throws -> LibraryEntry? {
        try dbQueue.read { db in
            try TrackRecord.filter(Column("quickId") == quickId.value).fetchOne(db)
        }
        .flatMap(LibraryMapping.toDomain)
    }

    public func upsert(_ entry: LibraryEntry) throws {
        try dbQueue.write { db in
            let existingId = try Int64.fetchOne(
                db, sql: "SELECT id FROM track WHERE quickId = ?", arguments: [entry.track.quickId.value]
            )
            var record = LibraryMapping.toRecord(entry, id: existingId)
            try record.save(db)
        }
    }

    /// A location this scan saw again — `IndexReconciliation.stillPresentQuickIds`. Brings a
    /// `.missing` row back to `.indexed` and updates the location in case a rename moved it, without
    /// recomputing anything else.
    public func touchSeen(quickId: QuickId, locationUri: String, atMonoUs: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: """
                UPDATE track SET locationUri = ?, decodeStatus = ?, lastSeenAtMonoUs = ?
                WHERE quickId = ?
                """,
                arguments: [locationUri, LibraryMapping.storedValue(for: .indexed), atMonoUs, quickId.value]
            )
        }
    }

    /// A previously-indexed location this scan did not find — `IndexReconciliation.missingQuickIds`.
    public func markMissing(quickId: QuickId, atMonoUs: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE track SET decodeStatus = ?, lastSeenAtMonoUs = ? WHERE quickId = ?",
                arguments: [LibraryMapping.storedValue(for: .missing), atMonoUs, quickId.value]
            )
        }
    }

    public func deleteAll() throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM track") }
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
