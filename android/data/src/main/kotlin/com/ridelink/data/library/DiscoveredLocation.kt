package com.ridelink.data.library

/** A file a scan found, before hashing or metadata extraction — deliberately not a domain type
 *  ([com.ridelink.core.library.LibraryEntry] requires a [com.ridelink.core.model.Track], which
 *  needs a hash this stage does not have yet).
 *
 *  [relativeFolders] are the folder names between the scanned root and this file, outermost first —
 *  display names a tree walk already has in hand, used only by [RecordingFolders] on this phone.
 *  Empty for a MediaStore row or an explicit pick. */
data class DiscoveredLocation(
    val uri: String,
    val filename: String,
    val sizeBytes: Long,
    val relativeFolders: List<String> = emptyList(),
)
