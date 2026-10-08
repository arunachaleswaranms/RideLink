package com.ridelink.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.data.library.ImportSource
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Establishes the schema-versioning discipline this phase's brief §12 requires: every version is
 * exported to `data/schemas/` ([RideLinkDatabase]'s KDoc) and these tests open databases from those
 * exact exported schema files, not from the live entity classes.
 *
 * Phase 9A.5 (STATUS §4 problem 115, case E): version 3 adds provenance. An existing install's
 * library must survive the update row for row, and each row must come out owned by the source it
 * was really imported from — otherwise the first rescan after the update would reconcile it against
 * the wrong scope.
 */
class SchemaMigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            RideLinkDatabase::class.java,
        )

    @Test
    fun theExportedVersion1SchemaOpensAndHasTheExpectedTable() {
        val db = helper.createDatabase(TEST_DB_NAME, 1)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='tracks'")
        assertTrue(cursor.moveToFirst(), "the exported version-1 schema must create a 'tracks' table")
        cursor.close()
        db.close()
    }

    @Test
    fun version2LibraryMigratesToVersion3WithEveryRowAndItsProvenance() {
        helper.createDatabase(TEST_DB_NAME, 2).use { db ->
            insertV2(db, 1, "id-tree-a", TREE_A_CHILD)
            insertV2(db, 2, "id-tree-b", TREE_B_CHILD)
            insertV2(db, 3, "id-file", PICKED_FILE)
            insertV2(db, 4, "id-media", MEDIA_ROW)
            insertV2(db, 5, "id-plain", "file:///data/user/0/x/plain.m4a")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, RideLinkDatabase.MIGRATION_2_3)

        val rows = mutableMapOf<String, Triple<String, String, String>>()
        migrated.query("SELECT localEntryId, title, sourceKind, sourceKey FROM tracks").use { cursor ->
            while (cursor.moveToNext()) rows[cursor.getString(0)] = Triple(cursor.getString(1), cursor.getString(2), cursor.getString(3))
        }
        migrated.close()

        assertEquals(5, rows.size, "no row may be lost")
        rows.values.forEach { (title, _, _) -> assertEquals("Kept", title, "metadata must survive untouched") }
        assertEquals(Triple("Kept", "TREE", TREE_A), rows["id-tree-a"])
        assertEquals(Triple("Kept", "TREE", TREE_B), rows["id-tree-b"])
        assertEquals(Triple("Kept", "FILE", PICKED_FILE), rows["id-file"])
        assertEquals(Triple("Kept", "MEDIA_STORE", ImportSource.MEDIA_STORE_KEY), rows["id-media"])
        assertEquals("FILE", rows["id-plain"]?.second, "an unclassifiable location fails safe as an individual file")
    }

    /** The full upgrade path a Phase 3 install takes, through the real Room builder the app uses. */
    @Test
    fun version1InstallReachesVersion3ThroughTheProductionMigrations() {
        helper.createDatabase(TEST_DB_NAME, 1).use { db -> insertV2(db, 1, "id-v1", TREE_A_CHILD) }

        val room =
            Room
                .databaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    RideLinkDatabase::class.java,
                    TEST_DB_NAME,
                ).addMigrations(RideLinkDatabase.MIGRATION_1_2, RideLinkDatabase.MIGRATION_2_3)
                .build()
        helper.closeWhenFinished(room)

        runBlocking {
            val provenance = LibraryRepository(room.trackDao()).allLocationsWithProvenance()
            assertEquals(ImportSource.Tree(TREE_A), provenance.values.single().source)
        }
    }

    private fun insertV2(
        db: SupportSQLiteDatabase,
        id: Long,
        localEntryId: String,
        locationUri: String,
    ) {
        db.insert(
            "tracks",
            SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("localEntryId", localEntryId)
                put("quickId", "sha256:" + "a".repeat(64))
                putNull("contentHash")
                put("title", "Kept")
                put("artist", "Artist")
                put("album", "Album")
                put("durationMs", 1000L)
                put("filename", "song.m4a")
                put("codec", "aac")
                put("bitrateKbps", 128)
                putNull("artworkRef")
                put("sizeBytes", 10L)
                put("locationUri", locationUri)
                put("decodeStatus", "INDEXED")
                put("indexedAtMonoUs", 1L)
                put("lastSeenAtMonoUs", 1L)
            },
        )
    }

    private companion object {
        const val TEST_DB_NAME = "schema-migration-test.db"
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val TREE_A = "content://$AUTHORITY/tree/primary%3AMusic%2FA"
        const val TREE_B = "content://$AUTHORITY/tree/primary%3AMusic%2FB"
        const val TREE_A_CHILD = "$TREE_A/document/primary%3AMusic%2FA%2Fsong.m4a"
        const val TREE_B_CHILD = "$TREE_B/document/primary%3AMusic%2FB%2Fsong.m4a"
        const val PICKED_FILE = "content://com.android.providers.media.documents/document/audio%3A42"
        const val MEDIA_ROW = "content://media/external/audio/media/42"
    }
}
