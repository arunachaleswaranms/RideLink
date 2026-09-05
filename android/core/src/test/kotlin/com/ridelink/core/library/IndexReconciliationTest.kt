package com.ridelink.core.library

import com.ridelink.core.model.QuickId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun quickId(byte: String): QuickId = QuickId("sha256:" + byte.repeat(32))

private fun location(name: String): LocalTrackLocation = LocalTrackLocation(name)

class IndexReconciliationTest {
    private val a = quickId("a1")
    private val b = quickId("b2")
    private val c = quickId("c3")
    private val locA = location("content://a")
    private val locB = location("content://b")
    private val locC = location("content://c")

    @Test
    fun `a location seen for the first time is new`() {
        val plan = IndexReconciliation.reconcile(previous = emptyMap(), discovered = mapOf(locA to a))
        assertEquals(setOf(locA), plan.newLocations)
        assertTrue(plan.unchangedLocations.isEmpty())
        assertTrue(plan.changedLocations.isEmpty())
        assertTrue(plan.missingLocations.isEmpty())
    }

    @Test
    fun `a location seen again with an unchanged quick id is unchanged, not reindexed`() {
        val plan = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = mapOf(locA to a))
        assertEquals(setOf(locA), plan.unchangedLocations)
        assertTrue(plan.newLocations.isEmpty())
        assertTrue(plan.changedLocations.isEmpty())
        assertTrue(plan.missingLocations.isEmpty())
    }

    @Test
    fun `a location seen again with a different quick id is changed, edited in place`() {
        // Same location, but its content was edited since the last scan — ADR-005's "a file edited in
        // place changes both hashes; quick_id detects it cheaply on rescan," at the location level.
        val plan = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = mapOf(locA to b))
        assertEquals(setOf(locA), plan.changedLocations)
        assertTrue(plan.newLocations.isEmpty())
        assertTrue(plan.unchangedLocations.isEmpty())
        assertTrue(plan.missingLocations.isEmpty())
    }

    @Test
    fun `a location no longer found is missing, not silently dropped`() {
        val plan = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = emptyMap())
        assertEquals(setOf(locA), plan.missingLocations)
    }

    @Test
    fun `a previously-missing location found again returns to unchanged`() {
        val afterRemoval = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = emptyMap())
        assertEquals(setOf(locA), afterRemoval.missingLocations)
        // The next scan runs against the same "previously indexed" map (locA is still known, just
        // marked MISSING) — rediscovering it with the same quick id must clear that, not require
        // re-adding it as new.
        val rediscovered = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = mapOf(locA to a))
        assertEquals(setOf(locA), rediscovered.unchangedLocations)
    }

    @Test
    fun `two different locations sharing a quick id are never collapsed`() {
        // ADR-005 Amendment A1 / the closure-audit CRITICAL finding: QuickId is a 128 KiB sample, not
        // authoritative identity. Two distinct locations that happen to hash to the same QuickId (a
        // false collision, or two genuinely byte-identical files at two paths) must both surface as
        // their own location — real FR-010 dedup happens later, only via ContentHash equality, never
        // here.
        val plan = IndexReconciliation.reconcile(previous = emptyMap(), discovered = mapOf(locA to a, locB to a))
        assertEquals(setOf(locA, locB), plan.newLocations)
    }

    @Test
    fun `renaming a file is no longer invisible — it surfaces as missing plus new`() {
        // ADR-005 Amendment A1: the previous design silently followed a QuickId to its new location,
        // which is exactly the unsafe cross-location comparison this amendment removes. A rename is
        // therefore not free — it costs one reindex — but it can never merge two different files.
        val plan = IndexReconciliation.reconcile(previous = mapOf(locA to a), discovered = mapOf(locB to a))
        assertEquals(setOf(locA), plan.missingLocations)
        assertEquals(setOf(locB), plan.newLocations)
    }

    @Test
    fun `a mixed scan partitions correctly`() {
        val plan =
            IndexReconciliation.reconcile(
                previous = mapOf(locA to a, locB to b),
                discovered = mapOf(locB to b, locC to c),
            )
        assertEquals(setOf(locC), plan.newLocations)
        assertEquals(setOf(locB), plan.unchangedLocations)
        assertEquals(setOf(locA), plan.missingLocations)
    }
}
