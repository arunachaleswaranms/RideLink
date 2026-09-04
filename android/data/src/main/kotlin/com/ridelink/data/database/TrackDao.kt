package com.ridelink.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Every query needed by `data.library`'s repository — nothing here does normalization, hashing or
 * decode-status reasoning, only storage. `quickId` is `UNIQUE` at the schema level ([TrackEntity]),
 * so [upsert]'s `REPLACE` conflict strategy *is* the dedup mechanism: inserting a row whose
 * `quickId` already exists deletes the old row and inserts the new one — a genuine SQLite
 * `DELETE`+`INSERT` under the hood, which is exactly what fires [TrackFtsEntity]'s Room-generated
 * sync triggers correctly (an `UPDATE` alone would not).
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
    @Query("SELECT * FROM tracks WHERE quickId = :quickId")
    suspend fun findByQuickId(quickId: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackEntity): Long

    @Query("DELETE FROM tracks WHERE quickId = :quickId")
    suspend fun deleteByQuickId(quickId: String)

    @Query("SELECT quickId FROM tracks")
    suspend fun allQuickIds(): List<String>

    @Query("UPDATE tracks SET decodeStatus = :status, lastSeenAtMonoUs = :lastSeenAtMonoUs WHERE quickId = :quickId")
    suspend fun updateDecodeStatus(
        quickId: String,
        status: String,
        lastSeenAtMonoUs: Long,
    )

    /**
     * A still-present row on a fresh scan: its location may have moved (a rename,
     * [com.ridelink.core.library.IndexReconciliation]'s "invisible by design" case) and, if it was
     * previously [com.ridelink.core.library.DecodeStatus.MISSING], this is the rescan that brings it
     * back — both handled in one statement rather than a read-then-write race.
     */
    @Query(
        "UPDATE tracks SET locationUri = :locationUri, decodeStatus = 'INDEXED', lastSeenAtMonoUs = :lastSeenAtMonoUs " +
            "WHERE quickId = :quickId",
    )
    suspend fun touchSeenAndUpdateLocation(
        quickId: String,
        locationUri: String,
        lastSeenAtMonoUs: Long,
    )

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
