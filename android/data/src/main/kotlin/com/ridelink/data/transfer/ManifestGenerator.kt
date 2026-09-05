package com.ridelink.data.transfer

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds this phone's outgoing manifest entries from Phase 3's authoritative library state
 * (brief §5): only [com.ridelink.core.library.DecodeStatus.INDEXED] rows with a `contentHash`
 * already computed (ADR-005 — a row still awaiting background hashing is displayable locally but
 * not sync-eligible), deterministically ordered by `content_hash` so two generation passes over an
 * unchanged library produce byte-identical output, and built without ever loading an artwork blob
 * — only [com.ridelink.core.library.LibraryEntry.track]'s `artworkRef` presence, never its bytes.
 *
 * A one-shot suspend function over [LibraryRepository.allSyncEligible], never a collected `Flow` —
 * this must not depend on a UI collector being active (brief §5). Cooperative cancellation via
 * [ensureActive] is what makes it safe to run against a library of thousands of tracks without
 * blocking a coroutine that a caller has since decided to cancel.
 */
class ManifestGenerator(
    private val libraryRepository: LibraryRepository,
) {
    suspend fun generate(): List<ManifestEntry> {
        val entries = libraryRepository.allSyncEligible()
        val result = ArrayList<ManifestEntry>(entries.size)
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            result.add(entry.toManifestEntry())
        }
        return result
    }

    private fun LibraryEntry.toManifestEntry(): ManifestEntry {
        val hash =
            checkNotNull(track.contentHash) {
                "LibraryRepository.allSyncEligible() must only return rows with a non-null contentHash"
            }
        return ManifestEntry(
            contentHash = hash,
            quickId = track.quickId,
            workKey = workKeyOf(track.artist, track.title, track.durationMs),
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            codec = track.codec,
            bitrateKbps = track.bitrateKbps,
            sizeBytes = track.sizeBytes,
            filename = track.filename,
            hasArtwork = track.artworkRef != null,
        )
    }

    /**
     * ARCHITECTURE §8.1: `normalize(artist) ‖ normalize(title) ‖ round(duration_ms, 2s)` — a
     * non-authoritative UI grouping key, never identity. The exact normalization here need not
     * match the peer's byte-for-byte (each side only ever groups its *own* manifest for display),
     * so a simple case/whitespace fold is sufficient.
     */
    private fun workKeyOf(
        artist: String,
        title: String,
        durationMs: Long,
    ): String {
        val roundedSeconds = (durationMs / DURATION_BUCKET_MS) * (DURATION_BUCKET_MS / MILLIS_PER_SECOND)
        return "${normalize(artist)}|${normalize(title)}|$roundedSeconds"
    }

    private fun normalize(s: String): String = s.trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val DURATION_BUCKET_MS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
