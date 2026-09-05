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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TransferCacheRepository] — the DB-plus-file commit order, cache-hit dedupe, and bounded eviction. */
class TransferCacheRepositoryTest {
    private lateinit var root: java.io.File
    private lateinit var storage: CacheStorage
    private lateinit var dao: FakeTransferCacheDao

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("ridelink-cache-repo-test").toFile()
        storage = CacheStorage(root)
        dao = FakeTransferCacheDao()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun hashOf(bytes: ByteArray): ContentHash {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ContentHash("sha256:$hex")
    }

    /** Streams [bytes] through the real two-phase commit, asserting the promote itself really succeeds. */
    private suspend fun promoteAndCommit(
        repo: TransferCacheRepository,
        bytes: ByteArray,
        nowMonoUs: Long,
        locked: Set<ContentHash> = emptySet(),
    ): ContentHash {
        val hash = hashOf(bytes)
        val stream = storage.openPartForWrite(hash)
        storage.appendChunk(stream, bytes)
        stream.close()
        val result = storage.promote(hash, bytes.size.toLong())
        check(result == PromoteResult.PROMOTED) { "test setup failure: $result" }
        repo.commit(hash, bytes.size.toLong(), nowMonoUs, locked)
        return hash
    }

    /** F: cache hit -> no transfer. The repository is what a caller checks *before* ever issuing a TRANSFER_REQUEST. */
    @Test
    fun `F - a verified entry is reported cached without needing a transfer`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao)
            val bytes = ByteArray(1_000) { it.toByte() }
            assertFalse(repo.isVerifiedCached(hashOf(bytes)), "nothing cached yet")

            val hash = promoteAndCommit(repo, bytes, nowMonoUs = 1_000)

            assertTrue(repo.isVerifiedCached(hash))
        }

    /** H: same ContentHash from two catalogue entries -> one verified cache object sufficient. */
    @Test
    fun `H - two manifest entries sharing a content hash are satisfied by one cache commit`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao)
            val bytes = ByteArray(500) { it.toByte() }
            val hash = promoteAndCommit(repo, bytes, nowMonoUs = 1_000)

            // Two "different manifest entries" pointing at the same content_hash both resolve to
            // the one cache object — there is nothing to transfer a second time for either.
            assertTrue(repo.isVerifiedCached(hash))
            assertTrue(repo.isVerifiedCached(hash))
            val opened = repo.open(hash, nowMonoUs = 2_000)
            assertEquals(500L, opened?.length())
        }

    @Test
    fun `open touches last-access time`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao)
            val hash = promoteAndCommit(repo, ByteArray(10) { it.toByte() }, nowMonoUs = 1_000)

            repo.open(hash, nowMonoUs = 5_000)

            assertEquals(5_000L, dao.findVerified(hash.value)?.lastAccessAtMonoUs)
        }

    @Test
    fun `open forgets a row whose file has disappeared rather than serving a hash the bytes no longer back`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao)
            val hash = promoteAndCommit(repo, ByteArray(10) { it.toByte() }, nowMonoUs = 1_000)
            storage.deleteMedia(hash) // simulate the file vanishing out from under the DB row

            val opened = repo.open(hash, nowMonoUs = 2_000)

            assertNull(opened)
            assertFalse(repo.isVerifiedCached(hash), "the stale row must be forgotten, not merely ignored once")
        }

    @Test
    fun `eviction removes the least-recently-used unlocked entry once over the byte budget`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao, maxCacheBytes = 150)
            val old = promoteAndCommit(repo, ByteArray(100) { it.toByte() }, nowMonoUs = 1_000)
            val recent = promoteAndCommit(repo, ByteArray(100) { (it + 1).toByte() }, nowMonoUs = 2_000) // pushes total to 200 > 150

            assertFalse(repo.isVerifiedCached(old), "the older, unlocked entry is evicted first")
            assertTrue(repo.isVerifiedCached(recent), "the entry that just committed is never evicted for itself")
            assertFalse(storage.hasMediaFile(old), "eviction must remove the file, not just the row")
        }

    @Test
    fun `a locked entry is never evicted even when it is the least recently used`() =
        runTest {
            val repo = TransferCacheRepository(storage, dao, maxCacheBytes = 150)
            val inUse = promoteAndCommit(repo, ByteArray(100) { it.toByte() }, nowMonoUs = 1_000, locked = setOf())

            // The caller (a coordinator that actually knows what is currently being played or
            // transferred) must pass the full in-use set on every commit — `locked` is not a
            // persisted flag on the row, since only the caller's runtime state knows it.
            promoteAndCommit(repo, ByteArray(100) { (it + 1).toByte() }, nowMonoUs = 2_000, locked = setOf(inUse))

            assertTrue(repo.isVerifiedCached(inUse), "a locked entry survives even as the oldest one")
        }
}
