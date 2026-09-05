package com.ridelink.core.library

import com.ridelink.core.model.QuickId

/**
 * Decides what a scan pass means for the library, from location/[QuickId] pairs alone — no I/O, no
 * file handles, no clock (CLAUDE.md rule 9).
 *
 * **ADR-005 Amendment A1 (this phase's closure-audit hardening pass).** This used to reconcile on
 * bare `Set<QuickId>`, which meant two *different* locations sharing a [QuickId] collapsed into one
 * library row — safe only because [QuickId] was (wrongly) assumed to prove identity. It does not:
 * `QuickId` samples only the size plus the first/last 64 KiB, so two files over 128 KiB that differ
 * solely in the middle produce the *same* `QuickId` while being genuinely different content. That is
 * not a SHA-256 collision, just a consequence of sampling, and it is deterministic and constructible,
 * not merely theoretical.
 *
 * The identity this reconciles on now is **[LocalTrackLocation]** — one row per on-disk location,
 * which can never falsely collide, because two distinct files are, definitionally, at two distinct
 * locations. [QuickId] is demoted to exactly the roles ADR-005 actually grants it: comparing the
 * freshly-discovered `QuickId` for an **already-known** location against its previously-stored one is
 * a safe, cheap way to detect "this specific file changed in place" (the consequence ADR-005 already
 * documents: "A file edited in place changes both hashes; `quick_id` detects it cheaply on rescan"),
 * because both values are known to describe the same location — there is no cross-location
 * comparison anywhere in this file.
 *
 * **A rename is no longer invisible.** The previous design treated a same-`QuickId` reappearance at a
 * new location as the same row, moved. That relied on exactly the unsafe cross-location `QuickId`
 * comparison this amendment removes, so it is not preserved: a renamed file now surfaces as one
 * [ReconciliationPlan.missingLocations] entry plus one [ReconciliationPlan.newLocations] entry —
 * Phase 3 does not attempt to reconcile the two by content, deliberately (see [TrackEntity][com.ridelink.data.database.TrackEntity]'s
 * doc: real cross-row duplicate collapsing is Phase 4/5 transfer scope, not a Phase 3 concern).
 */
object IndexReconciliation {
    fun reconcile(
        previous: Map<LocalTrackLocation, QuickId>,
        discovered: Map<LocalTrackLocation, QuickId>,
    ): ReconciliationPlan {
        val stillPresent = discovered.keys.intersect(previous.keys)
        val unchanged = stillPresent.filterTo(mutableSetOf()) { discovered.getValue(it) == previous.getValue(it) }
        return ReconciliationPlan(
            newLocations = discovered.keys - previous.keys,
            unchangedLocations = unchanged,
            changedLocations = stillPresent - unchanged,
            missingLocations = previous.keys - discovered.keys,
        )
    }
}

/**
 * @property newLocations Never indexed before; the full pipeline (hash/metadata/artwork) must run
 *   and a fresh [com.ridelink.core.model.LocalEntryId] is generated.
 * @property unchangedLocations Indexed before, seen again, and [QuickId] unchanged since last scan —
 *   only [LibraryEntry.lastSeenAtMonoUs] (and, if it had gone missing, [LibraryEntry.decodeStatus])
 *   need updating. The existing [com.ridelink.core.model.LocalEntryId] is untouched.
 * @property changedLocations Indexed before, seen again, but [QuickId] differs from last time — the
 *   file at this same location was edited in place. The full pipeline reruns for this location, but
 *   its existing [com.ridelink.core.model.LocalEntryId] is preserved (this is still the same row) and
 *   its [com.ridelink.core.model.ContentHash] is invalidated back to unknown, since the old hash no
 *   longer describes the current bytes.
 * @property missingLocations Indexed before, not seen this scan; the platform layer marks the
 *   corresponding [LibraryEntry.decodeStatus] as [DecodeStatus.MISSING] rather than deleting the
 *   row — a removable drive or an unmounted SAF tree can bring a "missing" file back on the next
 *   scan (brief §10's "file removed" case is a state, not a deletion).
 */
data class ReconciliationPlan(
    val newLocations: Set<LocalTrackLocation>,
    val unchangedLocations: Set<LocalTrackLocation>,
    val changedLocations: Set<LocalTrackLocation>,
    val missingLocations: Set<LocalTrackLocation>,
)
