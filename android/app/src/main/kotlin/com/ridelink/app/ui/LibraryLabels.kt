package com.ridelink.app.ui

import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibrarySort

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
