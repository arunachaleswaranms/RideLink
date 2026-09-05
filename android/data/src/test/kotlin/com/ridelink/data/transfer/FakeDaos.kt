package com.ridelink.data.transfer

import com.ridelink.data.database.LocationQuickIdRow
import com.ridelink.data.database.TrackDao
import com.ridelink.data.database.TrackEntity
import com.ridelink.data.database.TransferCacheDao
import com.ridelink.data.database.TransferCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * A pure in-memory [TrackDao] — no Room, no Android framework, so [ManifestGenerator] and
 * [com.ridelink.data.library.LibraryRepository] can be unit-tested on the plain JVM. Room/SQLite
 * specifics (FTS4 matching, migrations, real constraint enforcement) remain `androidTest`'s job
 * (`TrackDaoTest`, `SchemaMigrationTest`) — this fake exists for the logic layered *above* storage,
 * not to re-prove storage itself.
 */
class FakeTrackDao : TrackDao {
    private val rows = MutableStateFlow<List<TrackEntity>>(emptyList())
    private var nextId = 1L

    fun seed(entity: TrackEntity): TrackEntity {
        val withId = if (entity.id == 0L) entity.copy(id = nextId++) else entity
        rows.update { it + withId }
        return withId
    }

    private fun MutableStateFlow<List<TrackEntity>>.update(transform: (List<TrackEntity>) -> List<TrackEntity>) {
        value = transform(value)
    }

    override suspend fun findByLocalEntryId(localEntryId: String): TrackEntity? = rows.value.find { it.localEntryId == localEntryId }

    override suspend fun findByLocationUri(locationUri: String): TrackEntity? = rows.value.find { it.locationUri == locationUri }

    override suspend fun findByContentHash(contentHash: String): TrackEntity? =
        rows.value.filter { it.contentHash == contentHash && it.decodeStatus == "INDEXED" }.minByOrNull { it.id }

    override suspend fun allLocationsAndQuickIds(): List<LocationQuickIdRow> =
        rows.value.map { LocationQuickIdRow(it.locationUri, it.quickId) }

    override suspend fun findMissingContentHash(): List<TrackEntity> = rows.value.filter { it.contentHash == null }

    override suspend fun findAllSyncEligible(): List<TrackEntity> =
        rows.value.filter { it.contentHash != null && it.decodeStatus == "INDEXED" }.sortedBy { it.contentHash }

    override suspend fun insertNew(entity: TrackEntity): Long {
        val withId = seed(entity)
        return withId.id
    }

    @Suppress("LongParameterList")
    override suspend fun updateReindexed(
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
    ) {
        rows.update { list ->
            list.map {
                if (it.localEntryId != localEntryId) {
                    it
                } else {
                    it.copy(
                        quickId = quickId,
                        contentHash = null,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        filename = filename,
                        codec = codec,
                        bitrateKbps = bitrateKbps,
                        artworkRef = artworkRef,
                        sizeBytes = sizeBytes,
                        decodeStatus = decodeStatus,
                        lastSeenAtMonoUs = lastSeenAtMonoUs,
                    )
                }
            }
        }
    }

    override suspend fun updateContentHash(
        localEntryId: String,
        contentHash: String?,
    ) {
        rows.update { list -> list.map { if (it.localEntryId == localEntryId) it.copy(contentHash = contentHash) else it } }
    }

    override suspend fun touchSeen(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    ) {
        rows.update { list ->
            list.map { if (it.locationUri == locationUri) it.copy(decodeStatus = "INDEXED", lastSeenAtMonoUs = lastSeenAtMonoUs) else it }
        }
    }

    override suspend fun markMissing(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    ) {
        rows.update { list ->
            list.map { if (it.locationUri == locationUri) it.copy(decodeStatus = "MISSING", lastSeenAtMonoUs = lastSeenAtMonoUs) else it }
        }
    }

    override suspend fun deleteByLocalEntryId(localEntryId: String) {
        rows.update { list -> list.filterNot { it.localEntryId == localEntryId } }
    }

    override fun observeAll(): Flow<List<TrackEntity>> = rows.asStateFlow()

    override fun observeSearch(ftsQuery: String): Flow<List<TrackEntity>> = rows.asStateFlow().map { it }

    override suspend fun count(): Int = rows.value.size

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** A pure in-memory [TransferCacheDao] — see [FakeTrackDao]'s rationale. */
class FakeTransferCacheDao : TransferCacheDao {
    private val rows = mutableMapOf<String, TransferCacheEntity>()

    override suspend fun findVerified(contentHash: String): TransferCacheEntity? = rows[contentHash]?.takeIf { it.verified }

    override suspend fun upsertVerified(entity: TransferCacheEntity) {
        rows[entity.contentHash] = entity
    }

    override suspend fun touchAccess(
        contentHash: String,
        atMonoUs: Long,
    ) {
        rows[contentHash]?.let { rows[contentHash] = it.copy(lastAccessAtMonoUs = atMonoUs) }
    }

    override suspend fun delete(contentHash: String) {
        rows.remove(contentHash)
    }

    override suspend fun evictionCandidates(locked: List<String>): List<TransferCacheEntity> =
        rows.values.filter { it.contentHash !in locked }.sortedBy { it.lastAccessAtMonoUs }

    override suspend fun totalBytes(): Long = rows.values.sumOf { it.sizeBytes }

    override suspend fun all(): List<TransferCacheEntity> = rows.values.sortedByDescending { it.lastAccessAtMonoUs }

    override suspend fun deleteAll() {
        rows.clear()
    }
}
