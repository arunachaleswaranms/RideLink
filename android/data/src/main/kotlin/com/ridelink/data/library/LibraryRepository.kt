package com.ridelink.data.library

import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
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
 */
class LibraryRepository(
    private val dao: TrackDao,
) {
    fun observe(query: LibraryQuery): Flow<List<LibraryEntry>> {
        val rows = if (query.searchText.isBlank()) dao.observeAll() else dao.observeSearch(ftsQueryFor(query.searchText))
        return rows.map { entities -> sorted(entities.map { it.toDomain() }, query.sort) }
    }

    suspend fun allQuickIds(): Set<QuickId> = dao.allQuickIds().mapNotNull { QuickId.parse(it) }.toSet()

    suspend fun findByQuickId(quickId: QuickId): LibraryEntry? = dao.findByQuickId(quickId.value)?.toDomain()

    suspend fun upsert(entry: LibraryEntry) {
        dao.upsert(entry.toEntity())
    }

    /** A location this scan saw again — [com.ridelink.core.library.IndexReconciliation]'s
     *  "stillPresentQuickIds". Brings a [DecodeStatus.MISSING] row back to [DecodeStatus.INDEXED]
     *  and updates the location in case a rename moved it, without recomputing anything else. */
    suspend fun touchSeen(
        quickId: QuickId,
        locationUri: String,
        atMonoUs: Long,
    ) {
        dao.touchSeenAndUpdateLocation(quickId.value, locationUri, atMonoUs)
    }

    /** A previously-indexed location this scan did not find —
     *  [com.ridelink.core.library.IndexReconciliation]'s "missingQuickIds". */
    suspend fun markMissing(
        quickId: QuickId,
        atMonoUs: Long,
    ) {
        dao.updateDecodeStatus(quickId.value, DecodeStatus.MISSING.name, atMonoUs)
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
