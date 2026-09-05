package com.ridelink.data.library

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.data.database.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one seam between `core.library` (pure domain) and [TrackDao] (Room). Nothing outside this
 * package touches [TrackDao] directly, matching the module-boundary discipline ADR-014 already
 * applies to `network`/`audio`/`data` not depending on each other.
 *
 * Sorting happens here, in Kotlin, over an already-FTS-matched result set — this phase's brief §13
 * requires the *matching* to be database-native (done), not the sort, and a personal library's
 * result set is small enough that sorting it in memory is simpler than four near-duplicate
 * `ORDER BY` queries in [TrackDao].
 *
 * **Identity note (ADR-005 Amendment A1):** every method below is keyed by
 * [com.ridelink.core.model.LocalEntryId] or by [LocalTrackLocation] — never by
 * [com.ridelink.core.model.QuickId], which is not guaranteed unique across rows and must never be
 * used to look up or mutate "the" row for a given value.
 */
class LibraryRepository(
    private val dao: TrackDao,
) {
    fun observe(query: LibraryQuery): Flow<List<LibraryEntry>> {
        val rows = if (query.searchText.isBlank()) dao.observeAll() else dao.observeSearch(ftsQueryFor(query.searchText))
        return rows.map { entities -> sorted(entities.map { it.toDomain() }, query.sort) }
    }

    /** Every currently-known location and the `quickId` last recorded for it — exactly what
     *  [com.ridelink.core.library.IndexReconciliation] needs to decide new/unchanged/changed/missing,
     *  and nothing else (never a full row read for a whole-library scan). */
    suspend fun allLocationsAndQuickIds(): Map<LocalTrackLocation, QuickId> =
        dao.allLocationsAndQuickIds().associate { LocalTrackLocation(it.locationUri) to QuickId(it.quickId) }

    suspend fun findByLocalEntryId(localEntryId: LocalEntryId): LibraryEntry? = dao.findByLocalEntryId(localEntryId.value)?.toDomain()

    suspend fun findByLocationUri(locationUri: String): LibraryEntry? = dao.findByLocationUri(locationUri)?.toDomain()

    /** Phase 4's transfer-serving lookup — see [com.ridelink.data.database.TrackDao.findByContentHash]. */
    suspend fun findByContentHash(contentHash: ContentHash): LibraryEntry? = dao.findByContentHash(contentHash.value)?.toDomain()

    /** Every row still missing its authoritative [ContentHash] — ADR-005's lazy background hashing
     *  pass reads this directly rather than a possibly-stale UI snapshot. */
    suspend fun entriesMissingContentHash(): List<LibraryEntry> = dao.findMissingContentHash().map { it.toDomain() }

    /** Phase 4's manifest generator input: every indexed, content-hashed row, deterministically ordered. */
    suspend fun allSyncEligible(): List<LibraryEntry> = dao.findAllSyncEligible().map { it.toDomain() }

    /** A location never indexed before — [entry] carries a freshly-generated
     *  [com.ridelink.core.model.LocalEntryId] the caller must have already assigned. */
    suspend fun insertNew(entry: LibraryEntry) {
        dao.insertNew(entry.toEntity())
    }

    /** The same [com.ridelink.core.library.IndexReconciliation.ReconciliationPlan.changedLocations]
     *  row, re-indexed after an in-place edit: [entry] must carry the *existing* row's
     *  [com.ridelink.core.model.LocalEntryId] and [LocalTrackLocation] unchanged — only its
     *  metadata/`quickId`/`contentHash`/decode status are refreshed. */
    suspend fun updateReindexed(entry: LibraryEntry) {
        dao.updateReindexed(
            localEntryId = entry.localEntryId.value,
            quickId = entry.track.quickId.value,
            title = entry.track.title,
            artist = entry.track.artist,
            album = entry.track.album,
            durationMs = entry.track.durationMs,
            filename = entry.track.filename,
            codec = entry.track.codec,
            bitrateKbps = entry.track.bitrateKbps,
            artworkRef = entry.track.artworkRef,
            sizeBytes = entry.track.sizeBytes,
            decodeStatus = entry.decodeStatus.name,
            lastSeenAtMonoUs = entry.lastSeenAtMonoUs,
        )
    }

    /** ADR-005's lazy background pass: fills in the authoritative
     *  [com.ridelink.core.model.ContentHash] for a row that already exists, identified by its
     *  [com.ridelink.core.model.LocalEntryId] — never by `quickId`, and never by re-deriving which
     *  row "should" get it from content alone. */
    suspend fun updateContentHash(
        localEntryId: LocalEntryId,
        contentHash: ContentHash?,
    ) {
        dao.updateContentHash(localEntryId.value, contentHash?.value)
    }

    /** A location this scan saw again with an unchanged `quickId` —
     *  [com.ridelink.core.library.IndexReconciliation]'s `unchangedLocations`. Brings a
     *  [com.ridelink.core.library.DecodeStatus.MISSING] row back to
     *  [com.ridelink.core.library.DecodeStatus.INDEXED] without touching identity or metadata. */
    suspend fun touchSeen(
        location: LocalTrackLocation,
        atMonoUs: Long,
    ) {
        dao.touchSeen(location.uri, atMonoUs)
    }

    /** A previously-indexed location this scan did not find —
     *  [com.ridelink.core.library.IndexReconciliation]'s `missingLocations`. */
    suspend fun markMissing(
        location: LocalTrackLocation,
        atMonoUs: Long,
    ) {
        dao.markMissing(location.uri, atMonoUs)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun count(): Int = dao.count()

    private fun sorted(
        entries: List<LibraryEntry>,
        sort: LibrarySort,
    ): List<LibraryEntry> =
        when (sort) {
            LibrarySort.TITLE -> entries.sortedBy { it.track.title.lowercase() }
            LibrarySort.ARTIST -> entries.sortedBy { it.track.artist.lowercase() }
            LibrarySort.ALBUM -> entries.sortedBy { it.track.album.lowercase() }
            LibrarySort.RECENTLY_ADDED -> entries.sortedByDescending { it.indexedAtMonoUs }
        }

    /**
     * Turns free-text search into a safe FTS4 MATCH expression (this phase's brief §13: "no SQL
     * injection / unsafe raw interpolation"). Room's `:ftsQuery` bind parameter already rules out
     * classic SQL injection; what is sanitized here is FTS4's *own* mini query language — every
     * token is quoted (neutralizing `AND`/`OR`/`NOT` keyword interpretation and any embedded `"`,
     * `*`, `-`, or parenthesis a user might type) with any embedded quote doubled per FTS3/4's own
     * escaping rule, then suffixed with an unquoted `*` for prefix matching — `"word"*` is valid FTS
     * syntax for "prefix-match the last term of this phrase."
     */
    private fun ftsQueryFor(text: String): String =
        text
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> "\"${token.replace("\"", "\"\"")}\"*" }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
