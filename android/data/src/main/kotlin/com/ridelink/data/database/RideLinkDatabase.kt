package com.ridelink.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ridelink.data.library.ImportSourceKeys

/**
 * Schema version 3 (Phase 9A.5): [TrackEntity] gains its provenance (`sourceKind`, `sourceKey`) so a
 * scan can reconcile only the rows it owns (STATUS §4 problem 115). [MIGRATION_2_3] is a real
 * migration that keeps every existing row and derives each one's provenance from its location.
 *
 * Schema version 2 (Phase 4). Version 1 (Phase 3) is exported to `data/schemas/` and
 * [MIGRATION_1_2] is the real migration path a version-1 install upgrades through — not a
 * destructive fallback, per this phase's brief §16/§38: existing Phase 3 rows (a user's imported
 * library) must survive a Phase 4 update untouched.
 *
 * [TransferCacheEntity] (ADR-023 §6) is deliberately a new table, never a column added to
 * [TrackEntity] — brief §16/§18's "keep local Phase-3 imported content and Phase-4 peer cache
 * distinct at the storage/domain level."
 */
@Database(
    entities = [TrackEntity::class, TrackFtsEntity::class, TransferCacheEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class RideLinkDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun transferCacheDao(): TransferCacheDao

    companion object {
        const val DATABASE_NAME = "ridelink.db"
        private const val SCHEMA_VERSION_2 = 2
        private const val SCHEMA_VERSION_3 = 3

        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transfer_cache` (" +
                            "`contentHash` TEXT NOT NULL PRIMARY KEY, " +
                            "`cacheFileName` TEXT NOT NULL, " +
                            "`sizeBytes` INTEGER NOT NULL, " +
                            "`verified` INTEGER NOT NULL, " +
                            "`verifiedAtMonoUs` INTEGER NOT NULL, " +
                            "`lastAccessAtMonoUs` INTEGER NOT NULL)",
                    )
                }
            }

        /**
         * Adds provenance to every existing library row, keeping the row, its `localEntryId` and its
         * metadata exactly as they were (a user's library must survive an update untouched).
         *
         * The two `ADD COLUMN` defaults are the fail-safe kind ([TrackEntity]'s own defaults, which
         * Room validates against these exact clauses). Each row is then classified from its stored
         * location by [ImportSourceKeys.forExistingLocation]: a SAF child URI names its tree, so the
         * tree a row was imported from is recovered from SAF's own URI structure rather than guessed
         * from a path. A row that cannot be classified stays an individual file, which never marks
         * anything but itself missing.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(SCHEMA_VERSION_2, SCHEMA_VERSION_3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceKind` TEXT NOT NULL DEFAULT 'FILE'")
                    db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceKey` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_sourceKey` ON `tracks` (`sourceKey`)")
                    val rows = mutableListOf<Pair<Long, String>>()
                    db.query("SELECT `id`, `locationUri` FROM `tracks`").use { cursor ->
                        while (cursor.moveToNext()) rows += cursor.getLong(0) to cursor.getString(1)
                    }
                    for ((id, locationUri) in rows) {
                        val source = ImportSourceKeys.forExistingLocation(locationUri)
                        db.execSQL(
                            "UPDATE `tracks` SET `sourceKind` = ?, `sourceKey` = ? WHERE `id` = ?",
                            arrayOf<Any>(source.kind.name, source.key, id),
                        )
                    }
                }
            }
    }
}
