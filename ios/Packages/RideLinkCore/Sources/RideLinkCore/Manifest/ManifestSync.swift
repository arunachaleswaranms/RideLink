import Foundation

/// PROTOCOL §8.1 `MANIFEST_BEGIN.kind`.
public enum ManifestKind: String, Sendable, Equatable, CaseIterable {
    case full
    case delta

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> ManifestKind? {
        allCases.first { $0.wire == value }
    }
}

/// PROTOCOL §8.1's receiver-side error codes — the only two ways an open synchronisation fails.
public enum ManifestSyncError: Sendable, Equatable {
    case sequenceError
    case digestMismatch
    case incomplete
}

/// One event applied to `ManifestSyncState`, one per PROTOCOL §8.1 wire message plus two transport
/// events.
public enum ManifestSyncEvent: Sendable, Equatable {
    case begin(
        manifestId: ManifestId,
        kind: ManifestKind,
        manifestRevision: Int64,
        baseRevision: Int64?,
        totalEntries: Int,
        totalRemoved: Int
    )
    case page(
        manifestId: ManifestId,
        manifestRevision: Int64,
        pageIndex: Int,
        entries: [ManifestEntry],
        removed: [ContentHash]
    )
    case end(
        manifestId: ManifestId,
        manifestRevision: Int64,
        pageCount: Int,
        totalEntries: Int,
        totalRemoved: Int,
        digest: String
    )
    case abort(manifestId: ManifestId, reason: String)
    /// PROTOCOL §8.1 rule 6: 10 s between consecutive frames of an open synchronisation.
    case timeout
    /// PROTOCOL §8.1 rule 7: staging is in-memory/session-scoped, never persisted.
    case controlLinkLost
}

/// The result of applying one `ManifestSyncEvent`.
public enum ManifestSyncStepResult: Sendable, Equatable {
    /// Staging advanced, or the event was a harmless no-op (e.g. an abort for an id that is not open).
    case `continue`
    case aborted(reason: ManifestSyncError)
    case committed(revision: Int64, entries: [ManifestEntry], removed: [ContentHash])
}

/// One open, in-progress manifest synchronisation's staging area.
struct OpenManifestSync: Sendable, Equatable {
    var manifestId: ManifestId
    var kind: ManifestKind
    var revision: Int64
    var expectedPage: Int = 0
    var stagedEntries: [ManifestEntry] = []
    var stagedRemoved: [ContentHash] = []
    var totalEntries: Int
    var totalRemoved: Int
}

/// PROTOCOL §8.1 "Receiver rules" — the manifest-sync state: idle -> staging -> validating ->
/// committed. A plain `Sendable` value type (ARCHITECTURE §9.2), not a mutable class: `ManifestSync
/// .apply` is the pure reducer over it, exactly the convention `VoiceNegotiation`/`AudioStatePublisher`
/// already use in this package, rather than the Kotlin mirror's stateful `ManifestSyncStateMachine`
/// class — a class is not itself a platform type, but a value type keeps this state exhausted by
/// the same equality-based vector testing every other reducer here uses.
///
/// One instance per manifest-sync **direction** per session — PROTOCOL §8.1 rule 8: at most one
/// synchronisation is open per direction, and a concurrent `MANIFEST_BEGIN` implicitly aborts
/// whichever one was open.
public struct ManifestSyncState: Sendable, Equatable {
    public internal(set) var liveRevision: Int64
    var open: OpenManifestSync?

    public init(liveRevision: Int64) {
        self.liveRevision = liveRevision
        self.open = nil
    }

    public var isSyncOpen: Bool { open != nil }
}

/// Pure, mirrored per PROTOCOL §8.1 — pinned by `protocol/vectors/manifest-paging-errors/`
/// (ADR-013's rule 5: nothing partial is ever promoted). The Kotlin mirror is
/// `com.ridelink.core.manifest.ManifestSyncStateMachine`.
public enum ManifestSync {
    public static func apply(
        _ event: ManifestSyncEvent,
        to state: ManifestSyncState
    ) -> (state: ManifestSyncState, result: ManifestSyncStepResult) {
        switch event {
        case .begin(let manifestId, let kind, let manifestRevision, let baseRevision, let totalEntries, let totalRemoved):
            return applyBegin(
                manifestId: manifestId,
                kind: kind,
                manifestRevision: manifestRevision,
                baseRevision: baseRevision,
                totalEntries: totalEntries,
                totalRemoved: totalRemoved,
                state: state
            )
        case .page(let manifestId, let manifestRevision, let pageIndex, let entries, let removed):
            return applyPage(
                manifestId: manifestId,
                manifestRevision: manifestRevision,
                pageIndex: pageIndex,
                entries: entries,
                removed: removed,
                state: state
            )
        case .end(let manifestId, let manifestRevision, let pageCount, let totalEntries, let totalRemoved, let digest):
            return applyEnd(
                manifestId: manifestId,
                manifestRevision: manifestRevision,
                pageCount: pageCount,
                totalEntries: totalEntries,
                totalRemoved: totalRemoved,
                digest: digest,
                state: state
            )
        case .abort(let manifestId, _):
            var next = state
            if next.open?.manifestId == manifestId { next.open = nil }
            // An abort for an id that isn't the open one is simply ignored (stale).
            return (next, .continue)
        case .timeout:
            guard state.open != nil else { return (state, .continue) }
            return abortAndReturn(.incomplete, state: state)
        case .controlLinkLost:
            var next = state
            next.open = nil
            return (next, .continue)
        }
    }

    private static func applyBegin(
        manifestId: ManifestId,
        kind: ManifestKind,
        manifestRevision: Int64,
        baseRevision: Int64?,
        totalEntries: Int,
        totalRemoved: Int,
        state: ManifestSyncState
    ) -> (ManifestSyncState, ManifestSyncStepResult) {
        var next = state
        // Rule 8: a new Begin implicitly aborts any open sync — not itself an error for the new one.
        next.open = nil
        if kind == .delta, baseRevision != state.liveRevision {
            return (next, .aborted(reason: .sequenceError))
        }
        next.open = OpenManifestSync(
            manifestId: manifestId,
            kind: kind,
            revision: manifestRevision,
            totalEntries: totalEntries,
            totalRemoved: totalRemoved
        )
        return (next, .continue)
    }

    private static func applyPage(
        manifestId: ManifestId,
        manifestRevision: Int64,
        pageIndex: Int,
        entries: [ManifestEntry],
        removed: [ContentHash],
        state: ManifestSyncState
    ) -> (ManifestSyncState, ManifestSyncStepResult) {
        guard var sync = state.open else { return abortAndReturn(.sequenceError, state: state) }
        if manifestId != sync.manifestId || manifestRevision != sync.revision {
            return abortAndReturn(.sequenceError, state: state)
        }
        if pageIndex != sync.expectedPage {
            return abortAndReturn(.sequenceError, state: state)
        }
        if sync.kind == .full, !removed.isEmpty {
            return abortAndReturn(.sequenceError, state: state)
        }
        sync.stagedEntries.append(contentsOf: entries)
        sync.stagedRemoved.append(contentsOf: removed)
        sync.expectedPage += 1
        var next = state
        next.open = sync
        return (next, .continue)
    }

    private static func applyEnd(
        manifestId: ManifestId,
        manifestRevision: Int64,
        pageCount: Int,
        totalEntries: Int,
        totalRemoved: Int,
        digest: String,
        state: ManifestSyncState
    ) -> (ManifestSyncState, ManifestSyncStepResult) {
        guard let sync = state.open else { return abortAndReturn(.sequenceError, state: state) }
        if manifestId != sync.manifestId || manifestRevision != sync.revision {
            return abortAndReturn(.sequenceError, state: state)
        }
        if pageCount != sync.expectedPage {
            return abortAndReturn(.sequenceError, state: state)
        }
        if totalEntries != sync.totalEntries || totalRemoved != sync.totalRemoved ||
            sync.stagedEntries.count != sync.totalEntries || sync.stagedRemoved.count != sync.totalRemoved {
            return abortAndReturn(.sequenceError, state: state)
        }
        let expectedDigest = ManifestPaging.digest(entries: sync.stagedEntries, removed: sync.stagedRemoved)
        if digest != expectedDigest {
            return abortAndReturn(.digestMismatch, state: state)
        }
        var next = state
        next.liveRevision = sync.revision
        next.open = nil
        return (next, .committed(revision: sync.revision, entries: sync.stagedEntries, removed: sync.stagedRemoved))
    }

    private static func abortAndReturn(
        _ reason: ManifestSyncError,
        state: ManifestSyncState
    ) -> (ManifestSyncState, ManifestSyncStepResult) {
        var next = state
        next.open = nil
        return (next, .aborted(reason: reason))
    }
}
