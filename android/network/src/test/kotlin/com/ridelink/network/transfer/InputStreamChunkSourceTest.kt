package com.ridelink.network.transfer

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A4 Finding T — the provider's framing must match the `chunk_size`/`chunk_count`
 * its own `TRANSFER_OFFER` already declared (PROTOCOL §8.2), whatever the underlying stream's read
 * granularity happens to be.
 */
class InputStreamChunkSourceTest {
    /**
     * Returns at most [limit] bytes per `read`, however much was asked for — exactly what
     * `ContentResolver.openInputStream` over a `content://` document does in practice, and what
     * [InputStream.read]'s contract permits any stream to do at any time.
     */
    private class ShortReadingStream(
        bytes: ByteArray,
        private val limit: Int,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var readCallCount = 0
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            readCallCount += 1
            return delegate.read(b, off, minOf(len, limit))
        }
    }

    private suspend fun drain(source: ChunkSource): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        while (true) frames.add(source.nextChunk() ?: break)
        return frames
    }

    @Test
    fun `a short-reading stream still yields exactly the frames the declared chunk_count promises`() =
        runTest {
            // 10 frames' worth at a 1 KiB chunk size, from a stream that never returns more than
            // 100 bytes per read. Before Amendment A4 this produced ~103 frames instead of 10 —
            // every one of them a valid RLB1 frame, and every one past index 9 rejected by the
            // requester's Finding K bound as a PROTOCOL_ERROR.
            val chunkSize = 1024
            val payload = ByteArray(chunkSize * 10) { (it % 251).toByte() }
            val stream = ShortReadingStream(payload, limit = 100)

            val frames = drain(InputStreamChunkSource(stream, chunkSize))

            assertEquals(10, frames.size)
            assertTrue(frames.all { it.size == chunkSize })
            assertTrue(payload.contentEquals(frames.reduce { a, b -> a + b }))
            assertTrue(stream.readCallCount > frames.size, "the short reads really happened, they were just absorbed")
        }

    @Test
    fun `the final frame is the remainder, not padded to chunk size`() =
        runTest {
            val chunkSize = 1024
            val payload = ByteArray(chunkSize * 2 + 7) { 1 }

            val frames = drain(InputStreamChunkSource(ShortReadingStream(payload, limit = 33), chunkSize))

            assertEquals(3, frames.size)
            assertEquals(chunkSize, frames[0].size)
            assertEquals(chunkSize, frames[1].size)
            assertEquals(7, frames[2].size)
        }

    @Test
    fun `an exact multiple of the chunk size does not yield a trailing empty frame`() =
        runTest {
            val chunkSize = 64
            val frames = drain(InputStreamChunkSource(ByteArrayInputStream(ByteArray(chunkSize * 3)), chunkSize))

            assertEquals(3, frames.size)
        }

    @Test
    fun `an empty stream yields no frames at all`() =
        runTest {
            assertNull(InputStreamChunkSource(ByteArrayInputStream(ByteArray(0)), 64).nextChunk())
        }
}
