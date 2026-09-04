package com.ridelink.core.library

import com.ridelink.core.model.QuickId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun quickId(byte: String): QuickId = QuickId("sha256:" + byte.repeat(32))

class IndexReconciliationTest {
    private val a = quickId("a1")
    private val b = quickId("b2")
    private val c = quickId("c3")

    @Test
    fun `a file seen for the first time is new`() {
        val plan = IndexReconciliation.reconcile(previousQuickIds = emptySet(), discoveredQuickIds = setOf(a))
        assertEquals(setOf(a), plan.newQuickIds)
        assertTrue(plan.stillPresentQuickIds.isEmpty())
        assertTrue(plan.missingQuickIds.isEmpty())
    }

    @Test
    fun `a file seen again is still present, not reindexed`() {
        val plan = IndexReconciliation.reconcile(previousQuickIds = setOf(a), discoveredQuickIds = setOf(a))
        assertEquals(setOf(a), plan.stillPresentQuickIds)
        assertTrue(plan.newQuickIds.isEmpty())
        assertTrue(plan.missingQuickIds.isEmpty())
    }

    @Test
    fun `a file no longer found is missing, not silently dropped`() {
        val plan = IndexReconciliation.reconcile(previousQuickIds = setOf(a), discoveredQuickIds = emptySet())
        assertEquals(setOf(a), plan.missingQuickIds)
    }

    @Test
    fun `a previously-missing file found again returns to still-present`() {
        val afterRemoval = IndexReconciliation.reconcile(previousQuickIds = setOf(a), discoveredQuickIds = emptySet())
        assertEquals(setOf(a), afterRemoval.missingQuickIds)
        // The next scan runs against the same "previously indexed" set (a is still a known id, just
        // marked MISSING) — rediscovering it must clear that, not require re-adding it as new.
        val rediscovered = IndexReconciliation.reconcile(previousQuickIds = setOf(a), discoveredQuickIds = setOf(a))
        assertEquals(setOf(a), rediscovered.stillPresentQuickIds)
    }

    @Test
    fun `two files with identical bytes collapse to one quick id`() {
        // "duplicate bytes, different filename" (FR-010): the platform layer would discover the same
        // QuickId twice (once per physical file), but a Set naturally collapses that into one id — the
        // whole point of keying identity on content rather than location.
        val discovered = setOf(a) // as if two distinct files both hashed to `a`
        val plan = IndexReconciliation.reconcile(previousQuickIds = emptySet(), discoveredQuickIds = discovered)
        assertEquals(1, plan.newQuickIds.size)
    }

    @Test
    fun `renaming a file is invisible to reconciliation`() {
        // Content unchanged -> QuickId unchanged -> still-present, regardless of what the platform
        // layer's location string for it says this scan versus last scan.
        val plan = IndexReconciliation.reconcile(previousQuickIds = setOf(a, b), discoveredQuickIds = setOf(a, b))
        assertEquals(setOf(a, b), plan.stillPresentQuickIds)
    }

    @Test
    fun `a mixed scan partitions correctly`() {
        val plan = IndexReconciliation.reconcile(previousQuickIds = setOf(a, b), discoveredQuickIds = setOf(b, c))
        assertEquals(setOf(c), plan.newQuickIds)
        assertEquals(setOf(b), plan.stillPresentQuickIds)
        assertEquals(setOf(a), plan.missingQuickIds)
    }
}
