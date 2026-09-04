package com.ridelink.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Room, real SQLite, on the real `RideLink_API36` emulator — this phase's brief §24's
 * "upsert/dedup", "delete/reindex" and "FTS search" requirements, none of which a plain JVM unit
 * test can exercise (`SQLiteDatabase` is a stub outside an Android runtime). `core.library`'s pure
 * logic (reconciliation, normalization) already has fast JVM coverage; this is the one layer that
 * genuinely needs the device/emulator, same reasoning `AndroidVoiceAudioSession`'s tests split
 * pure-reducer-on-JVM from platform-call-on-device.
 */
@RunWith(AndroidJUnit4::class)
class TrackDaoTest {
    private lateinit var db: RideLinkDatabase
    private lateinit var dao: TrackDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RideLinkDatabase::class.java)
                .build()
        dao = db.trackDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(
        quickId: String,
        title: String = "Title",
        artist: String = "Artist",
        album: String = "Album",
        filename: String = "file.m4a",
        contentHash: String? = null,
        indexedAt: Long = 0,
        lastSeen: Long = 0,
    ) = TrackEntity(
        quickId = quickId,
        contentHash = contentHash,
        title = title,
        artist = artist,
        album = album,
        durationMs = 1000,
        filename = filename,
        codec = "aac",
        bitrateKbps = 128,
        artworkRef = null,
        sizeBytes = 4096,
        locationUri = "content://$quickId",
        decodeStatus = "INDEXED",
        indexedAtMonoUs = indexedAt,
        lastSeenAtMonoUs = lastSeen,
    )

    @Test
    fun insertAndFindByQuickId() =
        runBlocking {
            val quickId = "sha256:" + "a1".repeat(32)
            dao.upsert(track(quickId, title = "Hello"))
            val found = dao.findByQuickId(quickId)
            assertEquals("Hello", found?.title)
        }

    @Test
    fun findByQuickIdReturnsNullWhenAbsent() =
        runBlocking {
            assertNull(dao.findByQuickId("sha256:" + "ff".repeat(32)))
        }

    @Test
    fun upsertOnAnExistingQuickIdReplacesTheRowRatherThanDuplicatingIt() =
        runBlocking {
            val quickId = "sha256:" + "b2".repeat(32)
            dao.upsert(track(quickId, title = "First Scan"))
            dao.upsert(track(quickId, title = "Second Scan"))
            assertEquals(1, dao.count())
            assertEquals("Second Scan", dao.findByQuickId(quickId)?.title)
        }

    @Test
    fun deleteByQuickIdRemovesExactlyThatRow() =
        runBlocking {
            val a = "sha256:" + "aa".repeat(32)
            val b = "sha256:" + "bb".repeat(32)
            dao.upsert(track(a))
            dao.upsert(track(b))
            dao.deleteByQuickId(a)
            assertEquals(listOf(b), dao.allQuickIds())
        }

    @Test
    fun deleteAllClearsTheTableForAFullReindex() =
        runBlocking {
            dao.upsert(track("sha256:" + "aa".repeat(32)))
            dao.upsert(track("sha256:" + "bb".repeat(32)))
            dao.deleteAll()
            assertEquals(0, dao.count())
        }

    @Test
    fun updateDecodeStatusChangesStatusAndLastSeenWithoutTouchingOtherFields() =
        runBlocking {
            val quickId = "sha256:" + "cc".repeat(32)
            dao.upsert(track(quickId, title = "Still Here", indexedAt = 100, lastSeen = 100))
            dao.updateDecodeStatus(quickId, "MISSING", lastSeenAtMonoUs = 999)
            val updated = dao.findByQuickId(quickId)
            assertEquals("MISSING", updated?.decodeStatus)
            assertEquals(999, updated?.lastSeenAtMonoUs)
            assertEquals("Still Here", updated?.title)
            assertEquals(100, updated?.indexedAtMonoUs)
        }

    @Test
    fun ftsSearchMatchesByTitleArtistAlbumAndFilename() =
        runBlocking {
            dao.upsert(
                track(
                    "sha256:" + "d1".repeat(32),
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    album = "A Night at the Opera",
                    filename = "bohemian.m4a",
                ),
            )
            dao.upsert(
                track(
                    "sha256:" + "d2".repeat(32),
                    title = "Yesterday",
                    artist = "The Beatles",
                    album = "Help!",
                    filename = "yesterday.m4a",
                ),
            )

            assertEquals(1, dao.observeSearch("Bohemian*").first().size)
            assertEquals(1, dao.observeSearch("Queen*").first().size)
            assertEquals(1, dao.observeSearch("Opera*").first().size)
            assertEquals(1, dao.observeSearch("yesterday*").first().size)
            assertEquals(0, dao.observeSearch("Nonexistent*").first().size)
        }

    @Test
    fun ftsSearchIsCaseInsensitive() =
        runBlocking {
            dao.upsert(track("sha256:" + "e1".repeat(32), title = "Purple Rain", artist = "Prince"))
            assertEquals(1, dao.observeSearch("purple*").first().size)
            assertEquals(1, dao.observeSearch("PURPLE*").first().size)
        }

    @Test
    fun ftsIndexStaysInSyncAfterAnUpsertReplace() =
        runBlocking {
            val quickId = "sha256:" + "f1".repeat(32)
            dao.upsert(track(quickId, title = "Original Title"))
            assertEquals(1, dao.observeSearch("Original*").first().size)
            dao.upsert(track(quickId, title = "Renamed Title"))
            assertEquals(0, dao.observeSearch("Original*").first().size)
            assertEquals(1, dao.observeSearch("Renamed*").first().size)
        }

    @Test
    fun ftsIndexStaysInSyncAfterADelete() =
        runBlocking {
            val quickId = "sha256:" + "f2".repeat(32)
            dao.upsert(track(quickId, title = "Deletable"))
            assertEquals(1, dao.observeSearch("Deletable*").first().size)
            dao.deleteByQuickId(quickId)
            assertEquals(0, dao.observeSearch("Deletable*").first().size)
        }

    @Test
    fun allQuickIdsReflectsExactlyWhatWasUpserted() =
        runBlocking {
            val ids = (0 until 5).map { "sha256:" + "0$it".repeat(32) }
            ids.forEach { dao.upsert(track(it)) }
            assertEquals(ids.toSet(), dao.allQuickIds().toSet())
        }

    @Test
    fun observeAllReflectsInsertsReactively() =
        runBlocking {
            assertTrue(dao.observeAll().first().isEmpty())
            dao.upsert(track("sha256:" + "aa".repeat(32)))
            assertEquals(1, dao.observeAll().first().size)
        }
}
