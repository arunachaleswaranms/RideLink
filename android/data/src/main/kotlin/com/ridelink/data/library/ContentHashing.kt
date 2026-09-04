package com.ridelink.data.library

import android.content.ContentResolver
import android.net.Uri
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.QuickId
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ADR-005's two-tier hashing, implemented directly against a `content://` [Uri]'s file descriptor
 * rather than copying the file first — the whole reason [QuickId] is affordable at scan time
 * (~1 ms/file, ADR-005) is that only up to [WINDOW_SIZE_BYTES]×2 bytes are ever read for it,
 * regardless of file size, by seeking rather than streaming the middle of large files.
 *
 * The only I/O this file does is opening the descriptor and reading bounded windows from it —
 * never loading a whole large file into memory, per this phase's brief §20's resource bounds.
 */
object ContentHashing {
    private const val WINDOW_SIZE_BYTES = 64 * 1024
    private const val SMALL_FILE_THRESHOLD_BYTES = 128 * 1024

    /**
     * `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)`, or `SHA-256(size_bytes ‖ whole file)` for
     * files under 128 KiB (ADR-005: "no double-counting the overlapping window").
     */
    fun computeQuickId(
        resolver: ContentResolver,
        uri: Uri,
    ): QuickId {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size < 0) throw IOException("$uri reported an unknown size")
            digest.update(longToBigEndianBytes(size))
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                if (size <= SMALL_FILE_THRESHOLD_BYTES) {
                    digest.update(readWindow(channel, offset = 0, length = size.toInt()))
                } else {
                    digest.update(readWindow(channel, offset = 0, length = WINDOW_SIZE_BYTES))
                    digest.update(readWindow(channel, offset = size - WINDOW_SIZE_BYTES, length = WINDOW_SIZE_BYTES))
                }
            }
        } ?: throw IOException("cannot open a file descriptor for $uri")
        return QuickId("sha256:" + digest.digest().toHexLower())
    }

    /**
     * `SHA-256(whole file)` — the authoritative, lazily-computed tier (ADR-005). Streamed in
     * bounded chunks; never holds more than one buffer's worth of the file in memory regardless of
     * its size.
     */
    fun computeContentHash(
        resolver: ContentResolver,
        uri: Uri,
    ): ContentHash {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(WINDOW_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("cannot open an input stream for $uri")
        return ContentHash("sha256:" + digest.digest().toHexLower())
    }

    private fun readWindow(
        channel: java.nio.channels.FileChannel,
        offset: Long,
        length: Int,
    ): ByteArray {
        channel.position(offset)
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun longToBigEndianBytes(value: Long): ByteArray =
        ByteArray(Long.SIZE_BYTES) { index -> (value ushr (Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - index))).toByte() }

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
}
