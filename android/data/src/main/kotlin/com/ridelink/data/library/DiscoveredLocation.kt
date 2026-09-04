package com.ridelink.data.library

/** A file a scan found, before hashing or metadata extraction — deliberately not a domain type
 *  ([com.ridelink.core.library.LibraryEntry] requires a [com.ridelink.core.model.Track], which
 *  needs a hash this stage does not have yet). */
data class DiscoveredLocation(
    val uri: String,
    val filename: String,
    val sizeBytes: Long,
)
