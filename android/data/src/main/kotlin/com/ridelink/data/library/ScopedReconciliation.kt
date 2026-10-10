package com.ridelink.data.library

import com.ridelink.core.library.IndexReconciliation
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.library.ReconciliationPlan
import com.ridelink.core.model.QuickId

/** What reconciliation needs to know about one existing row — its last `quickId` and its owner. */
data class KnownLocation(
    val quickId: QuickId,
    val source: ImportSource,
)

/**
 * [IndexReconciliation] restricted to one [ImportSource] (STATUS §4 problem 115).
 *
 * The bug was the *previous* set, not the diff: `missing = every row - this scan` treats "not in the
 * folder I just walked" as "deleted from the phone". Here the previous set is only:
 *
 * 1. rows this [scope] owns — the only rows this scan has the authority to call missing; plus
 * 2. rows this scan **found**, whatever owns them — so a location already known under another
 *    source is reconciled as unchanged/changed rather than inserted a second time (`locationUri`
 *    is `UNIQUE`; that crash is STATUS problem 111's follow-up).
 *
 * A row in (2) whose owner differs is [ScopedPlan.adopted]: the scan that most recently walked a
 * location owns its future reconciliation. That only happens when two scopes really do name the
 * same location (a legacy row, or a plain `file://` tree in tests); two different trees over the
 * same files produce different document URIs and stay independent rows.
 *
 * Nothing outside [scope] can ever appear in [ReconciliationPlan.missingLocations] — the property
 * the regression cases pin.
 */
object ScopedReconciliation {
    fun reconcile(
        scope: ImportSource,
        known: Map<LocalTrackLocation, KnownLocation>,
        discovered: Map<LocalTrackLocation, QuickId>,
    ): ScopedPlan {
        val relevant = known.filter { (location, row) -> row.source == scope || location in discovered }
        val plan = IndexReconciliation.reconcile(relevant.mapValues { it.value.quickId }, discovered)
        val adopted = relevant.filter { (location, row) -> location in discovered && row.source != scope }.keys
        return ScopedPlan(plan, adopted)
    }
}

/**
 * @property plan the ordinary new/unchanged/changed/missing split, already scoped.
 * @property adopted locations this scan found that another source previously owned; their
 *   provenance moves to the scan's scope when they are touched or re-indexed.
 */
data class ScopedPlan(
    val plan: ReconciliationPlan,
    val adopted: Set<LocalTrackLocation>,
)
