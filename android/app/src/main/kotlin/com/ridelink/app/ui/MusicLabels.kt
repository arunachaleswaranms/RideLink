package com.ridelink.app.ui

import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.player.MusicFailure

internal fun libraryCountLabel(
    shown: Int,
    total: Int,
    searchText: String,
): String = if (searchText.isBlank()) tracks(total) else "${count(shown)} of ${tracks(total)}"

internal fun sortLabel(sort: LibrarySort): String =
    when (sort) {
        LibrarySort.TITLE -> "Title"
        LibrarySort.ARTIST -> "Artist"
        LibrarySort.ALBUM -> "Album"
        LibrarySort.RECENTLY_ADDED -> "Recently added"
    }

internal fun decodeStatusLabel(status: DecodeStatus): String =
    when (status) {
        DecodeStatus.INDEXED -> ""
        DecodeStatus.UNSUPPORTED -> "Unsupported format"
        DecodeStatus.CORRUPT -> "File looks damaged"
        DecodeStatus.MISSING -> "File not found"
    }

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / MILLIS_PER_SECOND
    return "%d:%02d".format(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

internal fun playerFailureLabel(failure: MusicFailure): String =
    when (failure) {
        MusicFailure.DECODE_FAILED -> "This file could not be played. Try another track."
        MusicFailure.FILE_MISSING -> "This file is no longer on the phone. Import it again."
        MusicFailure.UNSUPPORTED_FORMAT -> "This audio format is not supported."
        MusicFailure.STORAGE_IO -> "The file could not be read."
        MusicFailure.CANCELLED -> "Playback cancelled."
        MusicFailure.FOREGROUND_SERVICE_START_FAILED -> "Bring RideLink to the front and try again."
    }

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
