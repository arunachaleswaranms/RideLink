package com.ridelink.network.transfer

import java.io.InputStream

/**
 * A [ChunkSource] over an already-open [InputStream], yielding frames of exactly [chunkSizeBytes]
 * until the stream ends (the last frame is whatever remains).
 *
 * **Closure-audit Amendment A4 Finding T — why this fills the frame rather than doing one `read`.**
 * The `TRANSFER_OFFER` the provider has already sent declares both `chunk_size` and
 * `chunk_count = ceil(size_bytes / chunk_size)` (PROTOCOL §8.2), and the requester enforces both:
 * every frame's `chunk_index` must be the exact next expected value, and a frame past
 * `chunk_count` is a `PROTOCOL_ERROR`. [InputStream.read] is only ever obliged to return *some*
 * bytes, not the buffer's worth — and on Android `ContentResolver.openInputStream` over a
 * `content://` document routinely returns short reads. One `read` per frame therefore emitted more,
 * smaller frames than the count already promised on the wire, and the requester's (correct) index
 * check rejected the whole transfer. Filling each frame is what keeps the provider's framing
 * consistent with its own offer.
 *
 * `InputStream.readNBytes` says this in one call but is API 33+; `minSdk` is 31 (ADR-011), so the
 * fill loop is written out. Mirrors `RideLinkPlatform`'s `FileChunkSource`.
 */
class InputStreamChunkSource(
    private val stream: InputStream,
    private val chunkSizeBytes: Int,
) : ChunkSource {
    override suspend fun nextChunk(): ByteArray? {
        val buffer = ByteArray(chunkSizeBytes)
        var offset = 0
        while (offset < buffer.size) {
            val n = stream.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return if (offset <= 0) null else buffer.copyOf(offset)
    }
}
