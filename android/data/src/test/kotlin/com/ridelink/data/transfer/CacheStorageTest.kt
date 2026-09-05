package com.ridelink.data.transfer

import com.ridelink.core.model.ContentHash
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CacheStorage]'s two-phase commit over **real files** — brief §33's transfer-integrity harness
 * (A: exact success, B: corrupt byte, C: truncated, D: oversized/extra bytes), plus the safety
 * properties around it. Real `java.io.File`s in a temp directory, not mocked — this is the exact
 * code path a multi-megabyte real transfer runs.
 */
class CacheStorageTest {
    private lateinit var root: java.io.File
    private lateinit var storage: CacheStorage

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("ridelink-cache-test").toFile()
        storage = CacheStorage(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hashOf(bytes: ByteArray): ContentHash = ContentHash("sha256:" + sha256Hex(bytes))

    /** A: exact successful file — sender bytes -> multiple chunks -> receiver temp file -> final hash matches -> atomic cache commit. */
    @Test
    fun `A - exact successful transfer promotes to a verified media file`() =
        runTest {
            val bytes = ByteArray(200_000) { (it % 253).toByte() }
            val hash = hashOf(bytes)
            val stream = storage.openPartForWrite(hash)
            for (piece in bytes.toList().chunked(65_536)) storage.appendChunk(stream, piece.toByteArray())
            stream.close()

            val result = storage.promote(hash, bytes.size.toLong())

            assertEquals(PromoteResult.PROMOTED, result)
            assertTrue(storage.hasMediaFile(hash))
            assertFalse(storage.partFile(hash).exists(), "the .part file must not survive a successful promote")
            assertEquals(bytes.size.toLong(), storage.mediaFile(hash).length())
            assertTrue(bytes.contentEquals(storage.mediaFile(hash).readBytes()))
        }

    /** B: corrupt byte — one byte modified in transit/test stream -> hash mismatch -> no valid cache. */
    @Test
    fun `B - a single corrupted byte fails hash verification and promotes nothing`() =
        runTest {
            val original = ByteArray(1_000) { it.toByte() }
            val hash = hashOf(original) // the hash we *requested*
            val corrupted = original.copyOf().also { it[500] = (it[500] + 1).toByte() }
            val stream = storage.openPartForWrite(hash)
            storage.appendChunk(stream, corrupted)
            stream.close()

            val result = storage.promote(hash, corrupted.size.toLong())

            assertEquals(PromoteResult.HASH_MISMATCH, result)
            assertFalse(storage.hasMediaFile(hash), "no valid cache entry may exist after a hash mismatch")
            assertFalse(storage.partFile(hash).exists(), "the corrupt .part must be deleted, not left behind")
        }

    /** C: truncated — receiver never got all the bytes -> failure -> no valid cache. */
    @Test
    fun `C - a truncated stream fails the exact size check and promotes nothing`() =
        runTest {
            val full = ByteArray(10_000) { it.toByte() }
            val hash = hashOf(full)
            val stream = storage.openPartForWrite(hash)
            storage.appendChunk(stream, full.copyOfRange(0, 4_000)) // only 40% arrived
            stream.close()

            val result = storage.promote(hash, full.size.toLong())

            assertEquals(PromoteResult.SIZE_MISMATCH, result)
            assertFalse(storage.hasMediaFile(hash))
            assertFalse(storage.partFile(hash).exists())
        }

    /** D: oversized/extra bytes — more arrived than declared -> failure. */
    @Test
    fun `D - extra bytes past the declared size fail the exact size check`() =
        runTest {
            val declared = ByteArray(1_000) { it.toByte() }
            val withExtra = declared + ByteArray(500) { 0x7F }
            val hash = hashOf(declared) // the hash of the *declared* content, not what actually arrived
            val stream = storage.openPartForWrite(hash)
            storage.appendChunk(stream, withExtra)
            stream.close()

            val result = storage.promote(hash, declared.size.toLong())

            assertEquals(PromoteResult.SIZE_MISMATCH, result)
            assertFalse(storage.hasMediaFile(hash))
        }

    /** E (disconnect mid-transfer): a cancelled/interrupted transfer's .part is safely disposable, never promoted. */
    @Test
    fun `a cancelled transfer leaves a disposable part file, never a promoted one`() =
        runTest {
            val hash = ContentHash("sha256:" + "7".repeat(64))
            val stream = storage.openPartForWrite(hash)
            storage.appendChunk(stream, ByteArray(100))
            stream.close()
            // the transfer is cancelled here — deletePart is what a cancellation handler calls
            storage.deletePart(hash)

            assertFalse(storage.partFile(hash).exists())
            assertFalse(storage.hasMediaFile(hash))
        }

    @Test
    fun `sweepIncomplete removes every part file on startup`() =
        runTest {
            val h1 = ContentHash("sha256:" + "1".repeat(64))
            val h2 = ContentHash("sha256:" + "2".repeat(64))
            storage.openPartForWrite(h1).close()
            storage.openPartForWrite(h2).close()

            storage.sweepIncomplete()

            assertFalse(storage.partFile(h1).exists())
            assertFalse(storage.partFile(h2).exists())
        }

    @Test
    fun `re-opening a part file for write truncates any stale partial content`() =
        runTest {
            val hash = ContentHash("sha256:" + "3".repeat(64))
            storage.openPartForWrite(hash).use { it.write(ByteArray(10_000)) }
            val fresh = ByteArray(10) { it.toByte() }
            storage.openPartForWrite(hash).use { it.write(fresh) }

            assertEquals(fresh.size.toLong(), storage.partFile(hash).length(), "a fresh open must not append to stale bytes")
        }

    @Test
    fun `the cache path is derived only from the content hash, never a filename`() {
        val hash = ContentHash("sha256:" + "a".repeat(64))
        assertEquals("a".repeat(64), storage.mediaFile(hash).name)
        assertEquals("${"a".repeat(64)}.part", storage.partFile(hash).name)
        // No path separator, no "..", nothing beyond the 64 hex characters ContentHash itself validates.
        assertFalse(storage.mediaFile(hash).name.contains("/"))
        assertFalse(storage.mediaFile(hash).name.contains(".."))
    }
}
