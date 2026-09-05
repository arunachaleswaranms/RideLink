package com.ridelink.data.library

import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import com.ridelink.data.database.TrackEntity

/**
 * The one place [TrackEntity] (storage shape) and [LibraryEntry] (domain shape) convert between
 * each other — kept out of both [com.ridelink.data.database.TrackDao] (storage should not know the
 * domain model) and [LibraryIndexer] (indexing should not know column names).
 */
fun TrackEntity.toDomain(): LibraryEntry =
    LibraryEntry(
        localEntryId = LocalEntryId(localEntryId),
        track =
            Track(
                contentHash = contentHash?.let { ContentHash.parse(it) },
                quickId = QuickId(quickId),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                filename = filename,
                codec = codec,
                bitrateKbps = bitrateKbps,
                artworkRef = artworkRef,
                sizeBytes = sizeBytes,
            ),
        location = LocalTrackLocation(uri = locationUri),
        decodeStatus = DecodeStatus.valueOf(decodeStatus),
        indexedAtMonoUs = indexedAtMonoUs,
        lastSeenAtMonoUs = lastSeenAtMonoUs,
    )

fun LibraryEntry.toEntity(): TrackEntity =
    TrackEntity(
        localEntryId = localEntryId.value,
        quickId = track.quickId.value,
        contentHash = track.contentHash?.value,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        filename = track.filename,
        codec = track.codec,
        bitrateKbps = track.bitrateKbps,
        artworkRef = track.artworkRef,
        sizeBytes = track.sizeBytes,
        locationUri = location.uri,
        decodeStatus = decodeStatus.name,
        indexedAtMonoUs = indexedAtMonoUs,
        lastSeenAtMonoUs = lastSeenAtMonoUs,
    )
