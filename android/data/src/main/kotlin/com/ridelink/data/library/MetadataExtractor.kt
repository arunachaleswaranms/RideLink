package com.ridelink.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * What [MediaMetadataRetriever] gave us, before [com.ridelink.core.library.MetadataNormalizer] ever
 * sees it — raw, possibly missing, possibly empty. `artworkBytes` is the embedded picture exactly
 * as extracted; bounding it is [ArtworkProcessor]'s job, kept separate so this class does one thing
 * (talk to the platform retriever) and nothing else.
 */
data class ExtractedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val bitrateKbps: Int,
    val codec: String?,
    val artworkBytes: ByteArray?,
)

/**
 * The one place `android.media.MediaMetadataRetriever` is called. [extract] never throws: a file
 * the retriever cannot open at all is exactly this phase's brief §9's "corrupt" case, and the
 * caller ([LibraryIndexer]) is the one that decides what that means for
 * [com.ridelink.core.library.DecodeStatus] — this function only reports success or failure.
 */
object MetadataExtractor {
    fun extract(
        context: Context,
        uri: Uri,
    ): Result<ExtractedMetadata> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            Result.success(
                ExtractedMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = durationMs,
                    bitrateKbps = bitrateBps?.let { (it / BPS_PER_KBPS).toInt() } ?: 0,
                    codec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    artworkBytes = retriever.embeddedPicture,
                ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // Deliberately broad: MediaMetadataRetriever's own documentation only promises
            // RuntimeException (in practice IllegalArgumentException from Java, or an opaque
            // RuntimeException surfaced from native code, depending on OEM and API level) for
            // unparseable input — narrowing this catch would leave some real devices' corrupt files
            // uncaught, which is exactly this phase's brief §9's "must not crash the indexer."
            Result.failure(e)
        } finally {
            retriever.release()
        }
    }

    private const val BPS_PER_KBPS = 1000L
}
