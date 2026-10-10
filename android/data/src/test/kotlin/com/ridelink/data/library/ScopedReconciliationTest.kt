package com.ridelink.data.library

import com.ridelink.core.library.IndexReconciliation
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.QuickId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 115: a scan may only call **its own** rows missing.
 *
 * The cases are the brief's A–D at the level where the rule lives. The same cases run end to end —
 * real Room, real files, a real tree walk — in `LibraryIndexerTest` on the emulator.
 */
class ScopedReconciliationTest {
    private val treeA = ImportSource.Tree("content://docs/tree/primary%3AMusic%2FA")
    private val treeB = ImportSource.Tree("content://docs/tree/primary%3AMusic%2FB")

    private fun quickId(seed: Char) = QuickId("sha256:" + seed.toString().repeat(64))

    private fun loc(name: String) = LocalTrackLocation("content://docs/$name")

    private val a1 = loc("tree/A/document/a1")
    private val a2 = loc("tree/A/document/a2")
    private val b1 = loc("tree/B/document/b1")
    private val b2 = loc("tree/B/document/b2")
    private val c = loc("document/c")

    private val known =
        mapOf(
            a1 to KnownLocation(quickId('1'), treeA),
            a2 to KnownLocation(quickId('2'), treeA),
            b1 to KnownLocation(quickId('3'), treeB),
            b2 to KnownLocation(quickId('4'), treeB),
            c to KnownLocation(quickId('5'), ImportSource.File(c.uri)),
        )

    @Test
    fun `A - rescanning tree A with A2 gone marks only A2 missing`() {
        val scoped = ScopedReconciliation.reconcile(treeA, known, mapOf(a1 to quickId('1')))

        assertEquals(setOf(a2), scoped.plan.missingLocations)
        assertEquals(setOf(a1), scoped.plan.unchangedLocations)
        assertTrue(scoped.plan.newLocations.isEmpty())
        assertTrue(scoped.adopted.isEmpty())
    }

    @Test
    fun `the unscoped rule this replaces marks every other source missing`() {
        // The defect, reproduced against the shape LibraryIndexer used before problem 115's fix.
        val unscoped = IndexReconciliation.reconcile(known.mapValues { it.value.quickId }, mapOf(a1 to quickId('1')))

        assertEquals(setOf(a2, b1, b2, c), unscoped.missingLocations)
    }

    @Test
    fun `B - an individually imported file survives an unrelated tree rescan`() {
        val scoped = ScopedReconciliation.reconcile(treeB, known, mapOf(b1 to quickId('3'), b2 to quickId('4')))

        assertTrue(c !in scoped.plan.missingLocations)
        assertTrue(scoped.plan.missingLocations.isEmpty())
    }

    @Test
    fun `C - two trees holding byte-identical files at different locations stay independent`() {
        val sameBytes = quickId('9')
        val knownBoth =
            mapOf(
                a1 to KnownLocation(sameBytes, treeA),
                b1 to KnownLocation(sameBytes, treeB),
            )

        val rescanA = ScopedReconciliation.reconcile(treeA, knownBoth, emptyMap())

        assertEquals(setOf(a1), rescanA.plan.missingLocations, "only tree A's copy is gone")
        assertTrue(b1 !in rescanA.plan.missingLocations)
    }

    @Test
    fun `D - re-importing the same tree finds nothing new`() {
        val scoped =
            ScopedReconciliation.reconcile(treeA, known, mapOf(a1 to quickId('1'), a2 to quickId('2')))

        assertTrue(scoped.plan.newLocations.isEmpty())
        assertEquals(setOf(a1, a2), scoped.plan.unchangedLocations)
    }

    @Test
    fun `a location this scan found under another owner is reconciled, never inserted twice`() {
        val scoped = ScopedReconciliation.reconcile(treeA, known, mapOf(a1 to quickId('1'), c to quickId('5')))

        assertEquals(setOf(a1, c), scoped.plan.unchangedLocations)
        assertTrue(scoped.plan.newLocations.isEmpty(), "c is UNIQUE(locationUri); inserting it again would crash")
        assertEquals(setOf(c), scoped.adopted)
        assertEquals(setOf(a2), scoped.plan.missingLocations)
    }

    @Test
    fun `MediaStore reconciles only MediaStore rows`() {
        val media = LocalTrackLocation("content://media/external/audio/media/7")
        val withMedia = known + (media to KnownLocation(quickId('7'), ImportSource.MediaStore))

        val scoped = ScopedReconciliation.reconcile(ImportSource.MediaStore, withMedia, emptyMap())

        assertEquals(setOf(media), scoped.plan.missingLocations)
    }

    @Test
    fun `an unknown stored kind fails safe as an individual file`() {
        assertEquals(ImportSource.File("x"), ImportSource.of("SOMETHING_NEW", "x"))
        assertEquals(ImportSource.MediaStore, ImportSource.of("MEDIA_STORE", "anything"))
        assertEquals(treeA, ImportSource.of("TREE", treeA.key))
    }
}
