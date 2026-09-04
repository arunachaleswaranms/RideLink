package com.ridelink.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema version 1. Exported to `data/schemas/` (the `ksp { arg("room.schemaLocation", ...) }`
 * line in `data/build.gradle.kts`) from the very first version, not only starting at version 2 —
 * this phase's brief §12's "establish explicit schema versioning now" applies to the baseline
 * itself, not just to future changes. A real migration test therefore has a version-1 fixture to
 * migrate *from* the day a version 2 is ever needed.
 *
 * Only [TrackEntity] and its FTS4 shadow [TrackFtsEntity] exist yet. `trustedpeers` deliberately
 * stays on its own `FileTrustedPeerStore` (Phase 1b) rather than moving into this database — Phase
 * 3 owns the schema for the music catalogue, not a reason to migrate unrelated Phase 1b storage.
 */
@Database(
    entities = [TrackEntity::class, TrackFtsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RideLinkDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val DATABASE_NAME = "ridelink.db"
    }
}
