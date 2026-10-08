package com.ridelink.data.library

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.data.database.TrackEntity
import com.ridelink.data.transfer.FakeTrackDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 9A.5 §6: measure before adding machinery. Sorting stays in Kotlin; this pins that it is
 * total, deterministic and dispatched off the collector's thread, and records what it costs.
 */
class LibraryRepositorySortTest {
    private fun entity(
        i: Int,
        title: String = "Track ${i % 700}",
        artist: String = "Artist ${i % 90}",
        album: String = "Album ${i % 300}",
    ) = TrackEntity(
        localEntryId = "00000000-0000-0000-0000-%012d".format(i),
        quickId = "sha256:" + "%064x".format(i),
        contentHash = null,
        title = title,
        artist = artist,
        album = album,
        durationMs = 1000,
        filename = "f$i.mp3",
        codec = "mp3",
        bitrateKbps = 320,
        artworkRef = null,
        sizeBytes = 1,
        locationUri = "content://x/$i",
        decodeStatus = "INDEXED",
        indexedAtMonoUs = (i % 50).toLong(),
        lastSeenAtMonoUs = 0,
    )

    private fun library(size: Int): List<LibraryEntry> = (0 until size).map { entity(it).toDomain() }

    @Test
    fun `equal sort keys are ordered by local entry id, whatever order the rows arrive in`() {
        val rows = (0 until 40).map { entity(it, title = "Same", artist = "Same", album = "Same").copy(indexedAtMonoUs = 7).toDomain() }

        LibrarySort.entries.forEach { sort ->
            val forward = LibraryRepository.sorted(rows, sort)
            val reversed = LibraryRepository.sorted(rows.reversed(), sort)
            val shuffled = LibraryRepository.sorted(rows.shuffled(java.util.Random(sort.ordinal.toLong())), sort)
            assertEquals(forward, reversed, "$sort")
            assertEquals(forward, shuffled, "$sort")
            assertEquals(rows.sortedBy { it.localEntryId.value }, forward, "$sort tie-break")
        }
    }

    @Test
    fun `each sort orders by its key, case-insensitively, recent first for recently added`() {
        val rows =
            listOf(
                entity(1, title = "beta", artist = "Zed", album = "a"),
                entity(2, title = "Alpha", artist = "yak", album = "C"),
                entity(3, title = "gamma", artist = "Xi", album = "b"),
            ).map { it.toDomain() }

        assertEquals(listOf("Alpha", "beta", "gamma"), LibraryRepository.sorted(rows, LibrarySort.TITLE).map { it.track.title })
        assertEquals(listOf("Xi", "yak", "Zed"), LibraryRepository.sorted(rows, LibrarySort.ARTIST).map { it.track.artist })
        assertEquals(listOf("a", "b", "C"), LibraryRepository.sorted(rows, LibrarySort.ALBUM).map { it.track.album })
        assertEquals(listOf(3L, 2L, 1L), LibraryRepository.sorted(rows, LibrarySort.RECENTLY_ADDED).map { it.indexedAtMonoUs })
    }

    /**
     * Records the cost rather than asserting a tight bound — a laptop JVM figure is not a phone
     * figure, and a timing assertion in CI is how this repository learned to distrust them (STATUS
     * problem 29). The bound only catches an accidental quadratic.
     */
    @Test
    fun `sorting five thousand rows is cheap enough to keep in Kotlin`() {
        val rows = library(5_000)
        repeat(3) { LibrarySort.entries.forEach { sort -> LibraryRepository.sorted(rows, sort) } } // warm-up
        val timings =
            LibrarySort.entries.associateWith { sort ->
                val start = System.nanoTime()
                LibraryRepository.sorted(rows, sort)
                (System.nanoTime() - start) / 1_000_000.0
            }
        println("LibraryRepository.sorted, 5,000 rows, JVM ms: $timings")
        timings.forEach { (sort, ms) -> assertTrue(ms < 2_000.0, "$sort took $ms ms") }
    }

    @Test
    fun `mapping and sorting run on the sort dispatcher, not the collector`() =
        runBlocking {
            val dao = FakeTrackDao()
            (0 until 10).forEach { dao.seed(entity(it)) }
            val counting = CountingDispatcher(Dispatchers.Default)
            val repository = LibraryRepository(dao, sortDispatcher = counting)

            val entries = repository.observe(LibraryQuery()).first()

            assertEquals(10, entries.size)
            assertTrue(counting.dispatches.get() > 0, "observe() never left the collector's dispatcher")
        }

    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }
}
