package com.ridelink.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Every query needed by `data.library`'s repository — nothing here does normalization, hashing or
 * decode-status reasoning, only storage.
 *
 * **`localEntryId` is `UNIQUE` at the schema level ([TrackEntity]) and is this row's real identity**
 * (ADR-005 Amendment A1) — [insertNew] therefore uses [OnConflictStrategy.ABORT] rather than the
 * old `REPLACE`: a `localEntryId` collision on insert would mean two different rows were assigned
 * the same fresh identity, which is a bug to surface loudly, never something to paper over by
 * silently discarding one of them. `locationUri` is also `UNIQUE` (two distinct on-disk locations
 * are always two distinct rows); `quickId` is **not** unique, because it is only a 128 KiB sample
 * and two genuinely different files can share one.
 *
 * Search does the *matching* in SQL (FTS4, not an in-memory scan — this phase's brief §13's "use
 * database-native FTS rather than loading the whole library into memory and filtering in UI") and
 * leaves *sorting* to the repository layer in Kotlin: a personal library is small enough that
 * sorting an already-matched result set is simpler than four near-duplicate `ORDER BY` queries, and
 * REQUIREMENTS' scale ("realistic personal library size") does not call for a parameterized-sort
 * query.
 */
@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE localEntryId = :localEntryId")
    suspend fun findByLocalEntryId(localEntryId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE locationUri = :locationUri")
    suspend fun findByLocationUri(locationUri: String): TrackEntity?

    @Query("SELECT locationUri, quickId FROM tracks")
    suspend fun allLocationsAndQuickIds(): List<LocationQuickIdRow>

    /** ADR-005's lazy background hashing pass reads this directly, rather than a possibly-stale UI
     *  snapshot — a row is sync/transfer-eligible only once it leaves this list. */
    @Query("SELECT * FROM tracks WHERE contentHash IS NULL")
    suspend fun findMissingContentHash(): List<TrackEntity>

    /** A genuinely new location, never indexed before. Never expected to conflict on [locationUri]
     *  or [com.ridelink.data.database.TrackEntity.localEntryId] — [OnConflictStrategy.ABORT] means a
     *  conflict surfaces as a thrown exception rather than silently replacing an unrelated row. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNew(entity: TrackEntity): Long

    /**
     * The same [com.ridelink.core.model.LocalEntryId] as before, at the same [locationUri], but its
     * [com.ridelink.core.library.IndexReconciliation.ReconciliationPlan.changedLocations] `quickId`
     * changed — an in-place edit. Refreshes everything a fresh index would produce, resetting
     * `contentHash` to unknown (the old hash no longer describes the current bytes) — but the row's
     * identity is preserved, never re-created.
     */
    @Query(
        "UPDATE tracks SET quickId = :quickId, contentHash = NULL, title = :title, artist = :artist, " +
            "album = :album, durationMs = :durationMs, filename = :filename, codec = :codec, " +
            "bitrateKbps = :bitrateKbps, artworkRef = :artworkRef, sizeBytes = :sizeBytes, " +
            "decodeStatus = :decodeStatus, lastSeenAtMonoUs = :lastSeenAtMonoUs " +
            "WHERE localEntryId = :localEntryId",
    )
    suspend fun updateReindexed(
        localEntryId: String,
        quickId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        filename: String,
        codec: String,
        bitrateKbps: Int,
        artworkRef: String?,
        sizeBytes: Long,
        decodeStatus: String,
        lastSeenAtMonoUs: Long,
    )

    @Query("UPDATE tracks SET contentHash = :contentHash WHERE localEntryId = :localEntryId")
    suspend fun updateContentHash(
        localEntryId: String,
        contentHash: String?,
    )

    /**
     * A still-present location on a fresh scan whose `quickId` is unchanged: only
     * [com.ridelink.core.library.LibraryEntry.lastSeenAtMonoUs] and, if it was previously
     * [com.ridelink.core.library.DecodeStatus.MISSING], its recovery back to
     * [com.ridelink.core.library.DecodeStatus.INDEXED] — both handled in one statement rather than a
     * read-then-write race. Nothing about identity, metadata or hashes changes.
     */
    @Query(
        "UPDATE tracks SET decodeStatus = 'INDEXED', lastSeenAtMonoUs = :lastSeenAtMonoUs " +
            "WHERE locationUri = :locationUri",
    )
    suspend fun touchSeen(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    )

    @Query(
        "UPDATE tracks SET decodeStatus = 'MISSING', lastSeenAtMonoUs = :lastSeenAtMonoUs " +
            "WHERE locationUri = :locationUri",
    )
    suspend fun markMissing(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    )

    @Query("DELETE FROM tracks WHERE localEntryId = :localEntryId")
    suspend fun deleteByLocalEntryId(localEntryId: String)

    @Query("SELECT * FROM tracks ORDER BY id")
    fun observeAll(): Flow<List<TrackEntity>>

    /**
     * @param ftsQuery an FTS4 MATCH expression, already built by the repository (e.g. a
     *   prefix-wildcard form of the user's search text) — this DAO does not know FTS syntax rules,
     *   only how to run one.
     */
    @Query(
        "SELECT tracks.* FROM tracks " +
            "JOIN tracks_fts ON tracks.id = tracks_fts.rowid " +
            "WHERE tracks_fts MATCH :ftsQuery",
    )
    fun observeSearch(ftsQuery: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** Test/reindex support: a clean slate without dropping and recreating the schema. */
    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

/** Projection for [TrackDao.allLocationsAndQuickIds] — just enough to drive
 *  [com.ridelink.core.library.IndexReconciliation], never a full [TrackEntity] read. */
data class LocationQuickIdRow(
    val locationUri: String,
    val quickId: String,
)
