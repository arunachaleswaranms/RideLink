package com.ridelink.data.transfer

import android.content.ContentResolver
import android.net.Uri
import com.ridelink.core.model.ContentHash
import com.ridelink.data.library.LibraryRepository
import java.io.InputStream

/** What a transfer-request handler does next — never a human string parsed to decide behaviour. */
sealed class ContentResolution {
    data class Found(
        val open: () -> InputStream,
        val sizeBytes: Long,
    ) : ContentResolution()

    object NotFound : ContentResolution()

    /** ADR-023 §7 — the on-disk bytes no longer match what was indexed. Fail the request; never serve them anyway. */
    object FileChanged : ContentResolution()

    object IoError : ContentResolution()
}

/**
 * Resolves a peer's `TRANSFER_REQUEST.content_hash` to actual readable bytes (brief §9), checking
 * both provenances — the verified transfer cache first (a plain file, cheapest to open), then the
 * Phase 3 local library (a `content://` location that needs a fresh staleness check).
 *
 * **Consistency model (ADR-023 §7):** a full re-hash on every serve is not required — Phase 3's
 * lazy hashing job assigns `content_hash` once per stable file, so the only real risk is a file
 * edited or replaced after indexing. The cheap check this class performs is comparing the file's
 * *current* size against [com.ridelink.core.model.Entities.Track.sizeBytes] as last indexed; any
 * mismatch means the file changed since then, and the request fails with [ContentResolution.FileChanged]
 * rather than serving bytes under a stale assumption.
 */
class LocalContentResolver(
    private val contentResolver: ContentResolver,
    private val libraryRepository: LibraryRepository,
    private val cacheRepository: TransferCacheRepository,
) {
    @Suppress("ReturnCount")
    suspend fun resolve(
        contentHash: ContentHash,
        nowMonoUs: Long,
    ): ContentResolution {
        cacheRepository.open(contentHash, nowMonoUs)?.let { file ->
            return ContentResolution.Found({ file.inputStream() }, file.length())
        }
        val entry = libraryRepository.findByContentHash(contentHash) ?: return ContentResolution.NotFound
        val uri =
            runCatching { Uri.parse(entry.location.uri) }.getOrElse {
                return ContentResolution.IoError
            }
        val currentSize =
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: return ContentResolution.IoError
        if (currentSize != entry.track.sizeBytes) return ContentResolution.FileChanged
        return ContentResolution.Found(
            open = {
                contentResolver.openInputStream(uri) ?: error("cannot open $uri for reading")
            },
            sizeBytes = currentSize,
        )
    }
}
