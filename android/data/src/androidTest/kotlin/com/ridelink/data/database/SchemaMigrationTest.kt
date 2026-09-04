package com.ridelink.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Establishes the schema-versioning discipline this phase's brief §12 requires: version 1 is
 * exported to `data/schemas/` ([RideLinkDatabase]'s KDoc) and this test opens a database from that
 * exact exported schema file, not from the live entity classes — the same check a version-1-to-2
 * migration test will extend once a second version exists. There is deliberately no migration
 * assertion beyond "the exported schema opens": with only one schema version, there is nothing yet
 * to migrate *from*, and a test that pretended otherwise would be testing nothing real.
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

    private companion object {
        const val TEST_DB_NAME = "schema-migration-test.db"
    }
}
