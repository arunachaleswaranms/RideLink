package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.QuickId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PROTOCOL §8.1's `MANIFEST_PAGE` entry shape — deliberately minimal (ARCHITECTURE §8.2:
 * "entries are deliberately minimal"). [filename] is a basename only, never a path
 * (REQUIREMENTS §11) — enforcement of that lives in whoever builds this from a library row, not
 * here.
 *
 * [contentHash] is `null` exactly when this phone's background hashing job (ADR-005) has not
 * finished this file yet — such an entry is displayable but not sync-eligible or transferable
 * (ARCHITECTURE §8.1). [workKey] is ARCHITECTURE §8.1's non-authoritative grouping key; it is
 * carried on the wire because PROTOCOL §8.1's example includes it, but nothing here or in
 * [ManifestDigest] ever reads it for identity.
 */
data class ManifestEntry(
    val contentHash: ContentHash?,
    val quickId: QuickId,
    val workKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val codec: String,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val filename: String,
    val hasArtwork: Boolean,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put(FIELD_CONTENT_HASH, contentHash?.value)
            put(FIELD_QUICK_ID, quickId.value)
            put(FIELD_WORK_KEY, workKey)
            put(FIELD_TITLE, title)
            put(FIELD_ARTIST, artist)
            put(FIELD_ALBUM, album)
            put(FIELD_DURATION_MS, durationMs)
            put(FIELD_CODEC, codec)
            put(FIELD_BITRATE_KBPS, bitrateKbps)
            put(FIELD_SIZE_BYTES, sizeBytes)
            put(FIELD_FILENAME, filename)
            put(FIELD_HAS_ARTWORK, hasArtwork)
        }

    /** Encoded byte length as it would appear inside a page — see [ManifestPaging]'s page budget. */
    fun encodedByteLength(): Int = toJsonObject().toString().toByteArray(Charsets.UTF_8).size

    companion object {
        const val FIELD_CONTENT_HASH = "content_hash"
        const val FIELD_QUICK_ID = "quick_id"
        const val FIELD_WORK_KEY = "work_key"
        const val FIELD_TITLE = "title"
        const val FIELD_ARTIST = "artist"
        const val FIELD_ALBUM = "album"
        const val FIELD_DURATION_MS = "duration_ms"
        const val FIELD_CODEC = "codec"
        const val FIELD_BITRATE_KBPS = "bitrate_kbps"
        const val FIELD_SIZE_BYTES = "size_bytes"
        const val FIELD_FILENAME = "filename"
        const val FIELD_HAS_ARTWORK = "has_artwork"

        /** In spec order — the field list both the encoder and any "no extra fields" test read. */
        val FIELDS =
            listOf(
                FIELD_CONTENT_HASH,
                FIELD_QUICK_ID,
                FIELD_WORK_KEY,
                FIELD_TITLE,
                FIELD_ARTIST,
                FIELD_ALBUM,
                FIELD_DURATION_MS,
                FIELD_CODEC,
                FIELD_BITRATE_KBPS,
                FIELD_SIZE_BYTES,
                FIELD_FILENAME,
                FIELD_HAS_ARTWORK,
            )
    }
}
