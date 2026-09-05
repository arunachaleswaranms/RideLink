package com.ridelink.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Every query [com.ridelink.data.transfer.TransferCacheRepository] needs — storage only, no
 * hashing or path decisions here (ADR-023 §6/§12).
 */
@Dao
interface TransferCacheDao {
    @Query("SELECT * FROM transfer_cache WHERE contentHash = :contentHash AND verified = 1")
    suspend fun findVerified(contentHash: String): TransferCacheEntity?

    /**
     * A verified entry replaces any prior row for the same [com.ridelink.core.model.ContentHash]
     * outright — [OnConflictStrategy.REPLACE] is correct here (unlike [TrackDao]'s `ABORT`)
     * because `contentHash` equality already **is** byte identity (ADR-005); there is no
     * "two different rows collapsed" risk the way there was for `quickId`.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVerified(entity: TransferCacheEntity)

    @Query("UPDATE transfer_cache SET lastAccessAtMonoUs = :atMonoUs WHERE contentHash = :contentHash")
    suspend fun touchAccess(
        contentHash: String,
        atMonoUs: Long,
    )

    @Query("DELETE FROM transfer_cache WHERE contentHash = :contentHash")
    suspend fun delete(contentHash: String)

    /** Bounded eviction candidates: least-recently-used first, excluding anything the caller has locked. */
    @Query("SELECT * FROM transfer_cache WHERE contentHash NOT IN (:locked) ORDER BY lastAccessAtMonoUs ASC")
    suspend fun evictionCandidates(locked: List<String>): List<TransferCacheEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM transfer_cache")
    suspend fun totalBytes(): Long

    @Query("SELECT * FROM transfer_cache ORDER BY lastAccessAtMonoUs DESC")
    suspend fun all(): List<TransferCacheEntity>

    /** Test support: a clean slate without dropping and recreating the schema. */
    @Query("DELETE FROM transfer_cache")
    suspend fun deleteAll()
}
