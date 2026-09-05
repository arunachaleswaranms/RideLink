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
 *
 * **Identity (ADR-005 Amendment A1):** every test below keys on `localEntryId`/`locationUri`, never
 * on `quickId` — `quickId` is deliberately non-unique at the schema level (see [TrackEntity]'s doc),
 * so a test that upserted/looked up by it would no longer even compile against the real schema.
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
        localEntryId: String,
        locationUri: String = "content://$localEntryId",
        quickId: String = "sha256:" + "a1".repeat(32),
        title: String = "Title",
        artist: String = "Artist",
        album: String = "Album",
        filename: String = "file.m4a",
        contentHash: String? = null,
        indexedAt: Long = 0,
        lastSeen: Long = 0,
    ) = TrackEntity(
        localEntryId = localEntryId,
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
        locationUri = locationUri,
        decodeStatus = "INDEXED",
        indexedAtMonoUs = indexedAt,
        lastSeenAtMonoUs = lastSeen,
    )

    @Test
    fun insertNewAndFindByLocalEntryId() =
        runBlocking {
            dao.insertNew(track("id-a1", title = "Hello"))
            val found = dao.findByLocalEntryId("id-a1")
            assertEquals("Hello", found?.title)
        }

    @Test
    fun findByLocalEntryIdReturnsNullWhenAbsent() =
        runBlocking {
            assertNull(dao.findByLocalEntryId("ghost"))
        }

    @Test
    fun findByLocationUriReturnsNullWhenAbsent() =
        runBlocking {
            assertNull(dao.findByLocationUri("content://ghost"))
        }

    @Test
    fun twoDifferentLocationsSharingAQuickIdBothPersistAsSeparateRows() =
        runBlocking {
            // ADR-005 Amendment A1: quickId has no uniqueness constraint at all — two different rows
            // (different localEntryId, different locationUri) may legitimately share one.
            val sharedQuickId = "sha256:" + "b2".repeat(32)
            dao.insertNew(track("id-a", locationUri = "content://a", quickId = sharedQuickId, title = "First"))
            dao.insertNew(track("id-b", locationUri = "content://b", quickId = sharedQuickId, title = "Second"))
            assertEquals(2, dao.count())
            assertEquals("First", dao.findByLocalEntryId("id-a")?.title)
            assertEquals("Second", dao.findByLocalEntryId("id-b")?.title)
        }

    @Test
    fun updateReindexedChangesFieldsButPreservesLocalEntryIdAndLocation() =
        runBlocking {
            dao.insertNew(track("id-c", locationUri = "content://c", quickId = "sha256:" + "c1".repeat(32), title = "First Scan"))
            dao.updateReindexed(
                localEntryId = "id-c",
                quickId = "sha256:" + "c2".repeat(32),
                title = "Second Scan",
                artist = "Artist",
                album = "Album",
                durationMs = 2000,
                filename = "file.m4a",
                codec = "aac",
                bitrateKbps = 128,
                artworkRef = null,
                sizeBytes = 8192,
                decodeStatus = "INDEXED",
                lastSeenAtMonoUs = 42,
            )
            assertEquals(1, dao.count())
            val updated = dao.findByLocalEntryId("id-c")
            assertEquals("Second Scan", updated?.title)
            assertEquals("sha256:" + "c2".repeat(32), updated?.quickId)
            assertNull(updated?.contentHash, "an in-place edit must reset the authoritative hash to unknown")
            assertEquals("content://c", updated?.locationUri, "the location must not move for an in-place edit")
        }

    @Test
    fun updateContentHashSetsHashWithoutTouchingIdentityOrMetadata() =
        runBlocking {
            dao.insertNew(track("id-d", title = "Keep Me"))
            dao.updateContentHash("id-d", "sha256:" + "d1".repeat(32))
            val updated = dao.findByLocalEntryId("id-d")
            assertEquals("sha256:" + "d1".repeat(32), updated?.contentHash)
            assertEquals("Keep Me", updated?.title)
        }

    @Test
    fun deleteByLocalEntryIdRemovesExactlyThatRow() =
        runBlocking {
            dao.insertNew(track("id-a", locationUri = "content://a"))
            dao.insertNew(track("id-b", locationUri = "content://b"))
            dao.deleteByLocalEntryId("id-a")
            assertEquals(listOf("content://b"), dao.allLocationsAndQuickIds().map { it.locationUri })
            assertNull(dao.findByLocalEntryId("id-a"))
            assertEquals(1, dao.count())
        }

    @Test
    fun deleteAllClearsTheTableForAFullReindex() =
        runBlocking {
            dao.insertNew(track("id-a", locationUri = "content://a"))
            dao.insertNew(track("id-b", locationUri = "content://b"))
            dao.deleteAll()
            assertEquals(0, dao.count())
        }

    @Test
    fun touchSeenUpdatesStatusAndLastSeenWithoutTouchingOtherFields() =
        runBlocking {
            dao.insertNew(track("id-e", locationUri = "content://e", title = "Still Here", indexedAt = 100, lastSeen = 100))
            dao.touchSeen("content://e", lastSeenAtMonoUs = 999)
            val updated = dao.findByLocalEntryId("id-e")
            assertEquals("INDEXED", updated?.decodeStatus)
            assertEquals(999, updated?.lastSeenAtMonoUs)
            assertEquals("Still Here", updated?.title)
            assertEquals(100, updated?.indexedAtMonoUs)
        }

    @Test
    fun markMissingSetsStatusWithoutTouchingOtherFields() =
        runBlocking {
            dao.insertNew(track("id-f", locationUri = "content://f", title = "Still Here", indexedAt = 100, lastSeen = 100))
            dao.markMissing("content://f", lastSeenAtMonoUs = 999)
            val updated = dao.findByLocalEntryId("id-f")
            assertEquals("MISSING", updated?.decodeStatus)
            assertEquals(999, updated?.lastSeenAtMonoUs)
            assertEquals("Still Here", updated?.title)
        }

    @Test
    fun findMissingContentHashReturnsOnlyRowsWithoutOne() =
        runBlocking {
            dao.insertNew(track("id-g", locationUri = "content://g", contentHash = null))
            dao.insertNew(track("id-h", locationUri = "content://h", contentHash = "sha256:" + "aa".repeat(32)))
            val missing = dao.findMissingContentHash()
            assertEquals(listOf("id-g"), missing.map { it.localEntryId })
        }

    @Test
    fun ftsSearchMatchesByTitleArtistAlbumAndFilename() =
        runBlocking {
            dao.insertNew(
                track(
                    "id-d1",
                    locationUri = "content://d1",
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    album = "A Night at the Opera",
                    filename = "bohemian.m4a",
                ),
            )
            dao.insertNew(
                track(
                    "id-d2",
                    locationUri = "content://d2",
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
            dao.insertNew(track("id-e1", locationUri = "content://e1", title = "Purple Rain", artist = "Prince"))
            assertEquals(1, dao.observeSearch("purple*").first().size)
            assertEquals(1, dao.observeSearch("PURPLE*").first().size)
        }

    @Test
    fun ftsIndexStaysInSyncAfterAnInPlaceEdit() =
        runBlocking {
            dao.insertNew(track("id-f1", locationUri = "content://f1", title = "Original Title"))
            assertEquals(1, dao.observeSearch("Original*").first().size)
            dao.updateReindexed(
                localEntryId = "id-f1",
                quickId = "sha256:" + "f1".repeat(32),
                title = "Renamed Title",
                artist = "Artist",
                album = "Album",
                durationMs = 1000,
                filename = "file.m4a",
                codec = "aac",
                bitrateKbps = 128,
                artworkRef = null,
                sizeBytes = 4096,
                decodeStatus = "INDEXED",
                lastSeenAtMonoUs = 1,
            )
            assertEquals(0, dao.observeSearch("Original*").first().size)
            assertEquals(1, dao.observeSearch("Renamed*").first().size)
        }

    @Test
    fun ftsIndexStaysInSyncAfterADelete() =
        runBlocking {
            dao.insertNew(track("id-f2", locationUri = "content://f2", title = "Deletable"))
            assertEquals(1, dao.observeSearch("Deletable*").first().size)
            dao.deleteByLocalEntryId("id-f2")
            assertEquals(0, dao.observeSearch("Deletable*").first().size)
        }

    @Test
    fun allLocationsAndQuickIdsReflectsExactlyWhatWasInserted() =
        runBlocking {
            val ids = (0 until 5).map { "id-$it" to ("content://$it" to "sha256:" + "0$it".repeat(32)) }
            ids.forEach { (id, rest) -> dao.insertNew(track(id, locationUri = rest.first, quickId = rest.second)) }
            val rows = dao.allLocationsAndQuickIds()
            assertEquals(ids.map { it.second.first }.toSet(), rows.map { it.locationUri }.toSet())
            assertEquals(ids.map { it.second.second }.toSet(), rows.map { it.quickId }.toSet())
        }

    @Test
    fun observeAllReflectsInsertsReactively() =
        runBlocking {
            assertTrue(dao.observeAll().first().isEmpty())
            dao.insertNew(track("id-a"))
            assertEquals(1, dao.observeAll().first().size)
        }
}
