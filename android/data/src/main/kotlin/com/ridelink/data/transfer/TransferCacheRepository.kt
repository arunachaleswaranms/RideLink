package com.ridelink.data.transfer

import com.ridelink.core.model.ContentHash
import com.ridelink.data.database.TransferCacheDao
import com.ridelink.data.database.TransferCacheEntity

/**
 * The verified transfer cache (ADR-023 §6): [CacheStorage]'s bytes plus
 * [TransferCacheDao]'s metadata, combined behind the one commit order that keeps them consistent
 * even across a crash — the file is renamed into place **first**, and only then does the database
 * row get written. A crash between the two leaves an unreferenced file and no row, which this
 * repository treats as "not cached" (fails closed) rather than a false-positive hit; it is safe to
 * sweep such orphans later and merely costs a redundant re-transfer, never a wrong result.
 *
 * A conservative, capacity-bounded LRU-by-last-access eviction is the full extent of Phase 4's
 * cache policy (brief §16) — never evicting an entry the caller marks [locked] (currently
 * transferring, or referenced by the player).
 */
class TransferCacheRepository(
    private val storage: CacheStorage,
    private val dao: TransferCacheDao,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    /** True only once bytes have arrived, been whole-file verified, **and** committed (ADR-023 §6). */
    suspend fun isVerifiedCached(contentHash: ContentHash): Boolean = dao.findVerified(contentHash.value) != null

    suspend fun verifiedEntry(contentHash: ContentHash): TransferCacheEntity? = dao.findVerified(contentHash.value)

    /** @return the readable, verified media file, touching its last-access time, or null if not cached. */
    @Suppress("ReturnCount")
    suspend fun open(
        contentHash: ContentHash,
        nowMonoUs: Long,
    ): java.io.File? {
        dao.findVerified(contentHash.value) ?: return null
        val file = storage.mediaFile(contentHash)
        if (!file.isFile) {
            // The row claims verified but the file is gone (e.g. manually cleared storage) —
            // fail closed and forget the stale row rather than serving a hash the bytes no longer back.
            dao.delete(contentHash.value)
            return null
        }
        dao.touchAccess(contentHash.value, nowMonoUs)
        return file
    }

    /**
     * Commits a just-[CacheStorage.promote]d file as the verified cache entry, then evicts the
     * least-recently-used unlocked entries until the total is back under [maxCacheBytes] — never
     * evicting [contentHash] itself or anything in [locked].
     *
     * [locked] is **not** a persisted flag on any row — this repository has no way to know what a
     * caller currently has open. The coordinator calling [commit] must pass the *complete* current
     * in-use set (whatever is mid-transfer or referenced by the player) on every call; an entry
     * omitted here because an earlier call happened to include it is not protected.
     */
    suspend fun commit(
        contentHash: ContentHash,
        sizeBytes: Long,
        nowMonoUs: Long,
        locked: Set<ContentHash> = emptySet(),
    ) {
        dao.upsertVerified(
            TransferCacheEntity(
                contentHash = contentHash.value,
                cacheFileName = storage.mediaFile(contentHash).name,
                sizeBytes = sizeBytes,
                verified = true,
                verifiedAtMonoUs = nowMonoUs,
                lastAccessAtMonoUs = nowMonoUs,
            ),
        )
        evictIfNeeded(locked = locked + contentHash)
    }

    /** Never deletes a file the user's own library still references — see [CacheStorage]'s module-boundary note. */
    private suspend fun evictIfNeeded(locked: Set<ContentHash>) {
        var total = dao.totalBytes()
        if (total <= maxCacheBytes) return
        val lockedValues = locked.map { it.value }
        for (candidate in dao.evictionCandidates(lockedValues)) {
            if (total <= maxCacheBytes) break
            storage.deleteMedia(ContentHash(candidate.contentHash))
            dao.delete(candidate.contentHash)
            total -= candidate.sizeBytes
        }
    }

    private companion object {
        /** A conservative default (brief §16/§30) — full policy configurability is out of Phase 4 scope. */
        const val DEFAULT_MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GiB
    }
}
