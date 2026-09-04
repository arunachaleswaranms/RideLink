import Foundation

/// @property newQuickIds Not indexed before; the full pipeline (hash/metadata/artwork) must run.
/// @property stillPresentQuickIds Indexed before and seen again; only `LibraryEntry.lastSeenAtMonoUs`
///   and, if it changed, `LocalTrackLocation` need updating.
/// @property missingQuickIds Indexed before, not seen this scan; the platform layer marks the
///   corresponding `LibraryEntry.decodeStatus` as `.missing` rather than deleting the row — a
///   removable drive or an unmounted security-scoped bookmark can bring a "missing" file back on the
///   next scan (brief §10's "file removed" case is a state, not a deletion).
public struct ReconciliationPlan: Sendable, Equatable {
    public let newQuickIds: Set<QuickId>
    public let stillPresentQuickIds: Set<QuickId>
    public let missingQuickIds: Set<QuickId>
}

/// Decides what a scan pass means for the library, from `QuickId` sets alone — no I/O, no file
/// handles, no clock (CLAUDE.md rule 9). `QuickId` rather than `ContentHash` is the right key here:
/// it is always known immediately at scan time (ADR-005), unlike the full hash, which is computed
/// lazily.
///
/// **Two files with byte-identical content collapse to one `QuickId`** — this is the intended
/// duplicate-detection behaviour (FR-010, this phase's brief §7): "same content, different
/// filename" is not two library entries, it is one entry whose location may point at either copy.
/// If a rescan sees both, `discoveredQuickIds` contains the id exactly once regardless (it's a
/// `Set`), so `reconcile` treats it as a single still-present track — the platform layer decides
/// which of the two on-disk locations a duplicate's single row points at, by upserting with
/// whichever location the most recent scan produced.
///
/// A **rename** is invisible here by design: the content is unchanged, so its `QuickId` is
/// unchanged, so it lands in `stillPresentQuickIds` — no reindex, no hash recomputation (brief §19:
/// "avoid hashing the same unchanged file repeatedly"). The platform layer still updates the stored
/// location/filename for a still-present id on every scan, because the *location* may have moved
/// even though the *identity* has not.
public enum IndexReconciliation {
    public static func reconcile(previousQuickIds: Set<QuickId>, discoveredQuickIds: Set<QuickId>) -> ReconciliationPlan {
        ReconciliationPlan(
            newQuickIds: discoveredQuickIds.subtracting(previousQuickIds),
            stillPresentQuickIds: discoveredQuickIds.intersection(previousQuickIds),
            missingQuickIds: previousQuickIds.subtracting(discoveredQuickIds)
        )
    }
}
