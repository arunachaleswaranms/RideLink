package com.ridelink.core.library

import com.ridelink.core.model.Track

/**
 * Where this track's bytes actually live on this phone. Platform-opaque by design (CLAUDE.md
 * rule 9): a `content://` URI string, a security-scoped bookmark, or an app-owned relative path
 * are all just strings at this layer — only `data.library` / `RideLinkPlatform.Library` knows how
 * to open one. Never a display path (REQUIREMENTS §11's basename-only rule applies to `filename`
 * on [Track], not to this).
 */
data class LocalTrackLocation(
    val uri: String,
)

/**
 * The deterministic outcome of trying to make sense of a file, never a crash (this phase's brief
 * §9/§20). A file the platform decoder rejects is [UNSUPPORTED] or [CORRUPT] — distinguished so the
 * UI can say "not a supported format" versus "this file looks damaged" — and a file the last scan
 * saw but this one didn't is [MISSING], not deleted from the catalogue outright (brief §10/§11:
 * "file removed" and "file renamed" are both scan outcomes, not exceptions).
 */
enum class DecodeStatus {
    /** Metadata and, eventually, [Track.contentHash] were extracted successfully. */
    INDEXED,

    /** The platform decoder does not support this container/codec at all. */
    UNSUPPORTED,

    /** The extension/container looked supported but the file could not be parsed. */
    CORRUPT,

    /** Indexed at some point; the most recent scan could not find it at [LibraryEntry.location]. */
    MISSING,
}

/**
 * One row of the local library: a [Track] (the wire-shape identity/metadata record, reused as-is
 * since REQUIREMENTS §16 already defines exactly the fields Phase 3 needs), where it lives on this
 * phone, and this phone's own bookkeeping about it. Never shared, never sent — Phase 3 is local-only
 * (this phase's brief §2/§28).
 */
data class LibraryEntry(
    val track: Track,
    val location: LocalTrackLocation,
    val decodeStatus: DecodeStatus,
    /** When this phone first indexed this content, monotonic (CLAUDE.md rule 5). */
    val indexedAtMonoUs: Long,
    /** When the most recent scan last saw it at [location]. Advances [DecodeStatus.MISSING] back
     *  to [DecodeStatus.INDEXED] the moment a rescan finds it again. */
    val lastSeenAtMonoUs: Long,
)

/** Ascending only for V1 — the simplest coherent increment; a descending toggle is a UI concern
 *  that can be added without touching this type. */
enum class LibrarySort {
    TITLE,
    ARTIST,
    ALBUM,
    RECENTLY_ADDED,
}

/**
 * What the library screen asks for. Empty [searchText] means "no filter" — the whole library in
 * [sort] order — which is the defined empty-query behaviour this phase's brief §13 requires rather
 * than leaving unspecified.
 */
data class LibraryQuery(
    val searchText: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
)
