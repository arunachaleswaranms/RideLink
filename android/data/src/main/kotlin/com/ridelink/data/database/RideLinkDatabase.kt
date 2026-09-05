package com.ridelink.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
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
    version = 2,
    exportSchema = true,
)
abstract class RideLinkDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun transferCacheDao(): TransferCacheDao

    companion object {
        const val DATABASE_NAME = "ridelink.db"

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
    }
}
