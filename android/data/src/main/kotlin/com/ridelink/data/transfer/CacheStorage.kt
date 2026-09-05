package com.ridelink.data.transfer

import com.ridelink.core.model.ContentHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Machine-readable outcomes for the two-phase commit — never a human string parsed to decide behaviour. */
enum class PromoteResult { PROMOTED, SIZE_MISMATCH, HASH_MISMATCH, IO_ERROR }

/**
 * ADR-023 §6 / brief §12 — the two-phase `.part` -> verified-media file dance, and nothing else:
 * no database row, no manifest, no network. [TransferCacheRepository] is the layer that combines
 * this with [com.ridelink.data.database.TransferCacheDao].
 *
 * Every path here is derived from a [ContentHash]'s own 64-hex-character form, **never** from a
 * remote-supplied filename (brief §12) — `ContentHash`'s own `require`d format is what makes this
 * safe against `../`, an absolute path, or a Unicode confusable: there is no character in a valid
 * `ContentHash` that could ever mean "leave this directory."
 */
class CacheStorage(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val incomingDir = File(root, "incoming")
    private val mediaDir = File(root, "media")

    private fun safeName(contentHash: ContentHash): String = contentHash.hex

    fun partFile(contentHash: ContentHash): File = File(incomingDir, "${safeName(contentHash)}.part")

    fun mediaFile(contentHash: ContentHash): File = File(mediaDir, safeName(contentHash))

    /** True once a promoted, verified file exists at rest — never true for a `.part` in progress. */
    fun hasMediaFile(contentHash: ContentHash): Boolean = mediaFile(contentHash).isFile

    /** Opens (creating parent directories, truncating any stale partial) the `.part` file for streaming writes. */
    suspend fun openPartForWrite(contentHash: ContentHash): FileOutputStream =
        withContext(ioDispatcher) {
            incomingDir.mkdirs()
            FileOutputStream(partFile(contentHash), false)
        }

    suspend fun appendChunk(
        stream: FileOutputStream,
        bytes: ByteArray,
    ) {
        withContext(ioDispatcher) { stream.write(bytes) }
    }

    /**
     * The two-phase commit (ADR-023 §6): verify exact size, recompute SHA-256 **from the bytes as
     * written to disk** (never from a running in-memory hash of what was received — brief §12's
     * "hashing the file as written… catches truncated writes and disk-full conditions, not just
     * network corruption"), then atomically `rename()` into [mediaDir] only on an exact match.
     * A mismatch deletes the `.part` and returns the specific failure reason.
     */
    suspend fun promote(
        contentHash: ContentHash,
        expectedSizeBytes: Long,
    ): PromoteResult =
        withContext(ioDispatcher) {
            val part = partFile(contentHash)
            if (!part.isFile) return@withContext PromoteResult.IO_ERROR
            val actualSize = part.length()
            if (actualSize != expectedSizeBytes) {
                part.delete()
                return@withContext PromoteResult.SIZE_MISMATCH
            }
            val actualHash = sha256Of(part)
            if (actualHash != contentHash.hex) {
                part.delete()
                return@withContext PromoteResult.HASH_MISMATCH
            }
            mediaDir.mkdirs()
            val target = mediaFile(contentHash)
            if (!part.renameTo(target)) {
                // Same filesystem (both under `root`), so a rename failure is a real I/O problem,
                // not a cross-device-link case to work around with a copy.
                part.delete()
                return@withContext PromoteResult.IO_ERROR
            }
            PromoteResult.PROMOTED
        }

    suspend fun deletePart(contentHash: ContentHash) {
        withContext(ioDispatcher) { partFile(contentHash).delete() }
    }

    suspend fun deleteMedia(contentHash: ContentHash): Boolean = withContext(ioDispatcher) { mediaFile(contentHash).delete() }

    /** Brief §12: `.part` files are swept on startup — nothing partial survives a process restart as a candidate to resume. */
    suspend fun sweepIncomplete() {
        withContext(ioDispatcher) {
            incomingDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 65_536
    }
}
