package com.ridelink.data.library

import android.content.Context
import com.ridelink.core.model.QuickId
import java.io.File

/**
 * Where bounded artwork actually lives — a cache file, never the database row itself (this phase's
 * brief §18: "do not store giant images directly in database rows... prefer cached artwork file").
 * [Track.artworkRef] is the relative path this returns, resolved back to a real [File] by [fileFor]
 * whenever the UI needs to load one.
 *
 * `context.cacheDir`, not files dir: artwork is a derived, regenerable cache — losing it under
 * storage pressure (the OS may clear `cacheDir` at any time) means the next reindex regenerates it,
 * never a user-visible data loss.
 */
class ArtworkCache(
    private val context: Context,
) {
    /** @return the ref to store on [com.ridelink.core.model.Track.artworkRef], or null if [bytes]
     *   is null (no artwork) or could not be written. */
    fun store(
        quickId: QuickId,
        bytes: ByteArray?,
    ): String? {
        if (bytes == null) return null
        artworkDir().mkdirs()
        val ref = refFor(quickId)
        val file = File(context.cacheDir, ref)
        return runCatching {
            file.writeBytes(bytes)
            ref
        }.getOrNull()
    }

    fun fileFor(ref: String): File = File(context.cacheDir, ref)

    private fun artworkDir(): File = File(context.cacheDir, ARTWORK_SUBDIR)

    private fun refFor(quickId: QuickId): String {
        // The file's name is derived from the content-addressed quickId, never from the track's own
        // filename — CLAUDE.md's privacy rule against unnecessary paths/names in stored artefacts.
        val safeName = quickId.value.substringAfter("sha256:")
        return "$ARTWORK_SUBDIR/$safeName.jpg"
    }

    private companion object {
        const val ARTWORK_SUBDIR = "artwork"
    }
}
