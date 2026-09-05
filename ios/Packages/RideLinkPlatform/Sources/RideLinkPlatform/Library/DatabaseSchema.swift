import Foundation
import GRDB
import RideLinkCore

/// The storage shape of one `LibraryEntry` — mirrors `com.ridelink.data.database.TrackEntity`
/// field-for-field. `id` is GRDB's own `rowid`, matching Android's synthetic autoincrement primary
/// key (Room's FTS4 external-content sync needs a rowid-based key, not a text natural key; GRDB's
/// FTS5 external-content tables have the identical requirement, so the same shape is used here even
/// though the underlying reason — SQLite rowid triggers — is shared rather than copied).
///
/// **`localEntryId` is `UNIQUE` at the schema level and `locationUri` is `UNIQUE` too; `quickId` is
/// deliberately *not* unique** (ADR-005 Amendment A1, this phase's closure-audit CRITICAL finding).
/// `quickId` samples only size plus first/last 64 KiB, so two genuinely different files over 128 KiB
/// can share one — a `UNIQUE` constraint on it, and this schema's previous `quickId`-keyed
/// find-and-replace `upsert`, would silently and irreversibly collapse two different files' rows into
/// one the moment that happened. `locationUri` cannot suffer that: two distinct on-disk locations are,
/// definitionally, two distinct rows.
///
/// **Phase 3 deliberately does not collapse two rows that turn out to share a `content_hash` either.**
/// FR-010 ("preventing unnecessary transfer of identical files with different names") is about
/// *transfer*, which does not exist until Phase 4/5 — collapsing local library rows today would mean
/// deleting one out from under a local queue/player reference for a requirement Phase 3 has no
/// consumer for yet. Two byte-identical files therefore show as two independent rows in Phase 3's
/// catalogue; real, authoritative duplicate detection for transfer happens in Phase 4/5, keyed on
/// `ContentHash` equality once both sides have it — never on `quickId`.
///
/// `contentHash` is nullable — ADR-005: computed lazily, absent until the background hashing pass
/// reaches this row. A row without one is displayable but not sync/transfer-eligible (Phase 4/5, not
/// Phase 3's concern, but the column exists now because Phase 3 owns the schema).
public struct TrackRecord: Codable, FetchableRecord, MutablePersistableRecord, Sendable, Equatable {
    public var id: Int64?
    public var localEntryId: String
    public var quickId: String
    public var contentHash: String?
    public var title: String
    public var artist: String
    public var album: String
    public var durationMs: Int64
    public var filename: String
    public var codec: String
    public var bitrateKbps: Int
    public var artworkRef: String?
    public var sizeBytes: Int64
    public var locationUri: String
    public var decodeStatus: String
    public var indexedAtMonoUs: Int64
    public var lastSeenAtMonoUs: Int64

    public static let databaseTableName = "track"

    public mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }

    public init(
        id: Int64? = nil,
        localEntryId: String,
        quickId: String,
        contentHash: String?,
        title: String,
        artist: String,
        album: String,
        durationMs: Int64,
        filename: String,
        codec: String,
        bitrateKbps: Int,
        artworkRef: String?,
        sizeBytes: Int64,
        locationUri: String,
        decodeStatus: String,
        indexedAtMonoUs: Int64,
        lastSeenAtMonoUs: Int64
    ) {
        self.id = id
        self.localEntryId = localEntryId
        self.quickId = quickId
        self.contentHash = contentHash
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.filename = filename
        self.codec = codec
        self.bitrateKbps = bitrateKbps
        self.artworkRef = artworkRef
        self.sizeBytes = sizeBytes
        self.locationUri = locationUri
        self.decodeStatus = decodeStatus
        self.indexedAtMonoUs = indexedAtMonoUs
        self.lastSeenAtMonoUs = lastSeenAtMonoUs
    }
}

/// The one music-library database, schema version 1 (mirrors
/// `com.ridelink.data.database.RideLinkDatabase`'s `@Database(version = 1, exportSchema = true)`
/// discipline — a real `DatabaseMigrator` registration from day one, even with only one version,
/// rather than inventing a fake migration just to prove the mechanism exists).
public enum LibraryDatabase {
    public static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1_createTrack") { db in
            try db.create(table: TrackRecord.databaseTableName) { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("localEntryId", .text).notNull().unique()
                t.column("quickId", .text).notNull()
                t.column("contentHash", .text)
                t.column("title", .text).notNull()
                t.column("artist", .text).notNull()
                t.column("album", .text).notNull()
                t.column("durationMs", .integer).notNull()
                t.column("filename", .text).notNull()
                t.column("codec", .text).notNull()
                t.column("bitrateKbps", .integer).notNull()
                t.column("artworkRef", .text)
                t.column("sizeBytes", .integer).notNull()
                t.column("locationUri", .text).notNull().unique()
                t.column("decodeStatus", .text).notNull()
                t.column("indexedAtMonoUs", .integer).notNull()
                t.column("lastSeenAtMonoUs", .integer).notNull()
            }
            // Non-unique — mirrors `com.ridelink.data.database.TrackEntity`'s own non-unique indices
            // on the same two columns, useful for `entriesMissingContentHash`/change-detection lookups
            // without granting either column the uniqueness `localEntryId`/`locationUri` alone carry.
            try db.create(index: "track_on_quickId", on: TrackRecord.databaseTableName, columns: ["quickId"])
            try db.create(index: "track_on_contentHash", on: TrackRecord.databaseTableName, columns: ["contentHash"])
            // A real FTS5 external-content table (this phase's plan's deliberate per-platform
            // difference from Android's FTS4 — Room ships no `@Fts5` annotation at all, GRDB gets
            // FTS5 unconditionally per Package.swift's `SQLITE_ENABLE_FTS5` define), synchronized by
            // GRDB's own generated triggers rather than hand-written ones — the exact pattern GRDB's
            // own Full-Text Search documentation gives for keeping an external-content index in
            // step with its regular table.
            try db.create(virtualTable: "track_fts", using: FTS5()) { t in
                t.synchronize(withTable: TrackRecord.databaseTableName)
                t.column("title")
                t.column("artist")
                t.column("album")
                t.column("filename")
            }
        }
        return migrator
    }
}
