import Foundation

/// The one new pure decision Phase 7's `STATE_REQUEST` needs (this phase's brief §11/§25):
/// **whether to send one**, given what is already outstanding. Everything else — whether an
/// incoming `STATE_SNAPSHOT` may be *applied* — is answered by the existing, already-audited
/// generation and role checks inside `RideLinkPlatform.SyncPlaybackCoordinator` reconciliation
/// (`adoptSnapshot` / `onPeerPlaybackState` on both platforms), reused rather than re-derived here
/// (rule 19/20's standing lesson: a second provenance check is a second place for the two to
/// disagree).
///
/// The gate is keyed on the **authentication generation**, not a bare boolean, for the same reason
/// every other per-session flag in this codebase is: a bare "request pending" flag survives a
/// reconnect and would refuse a fresh request a **new** generation legitimately needs, while a
/// stale generation can never collide with a live one because generations strictly increase
/// (`ControlSessionManager`'s allocation). Comparing generations is therefore both the dedup
/// **and** the reconnect-reset, in one comparison.
///
/// Mirrors Android's `com.ridelink.core.resync.StateResyncGate` exactly.
public enum StateResyncGate {
    /// What the caller should do about a trigger to request authoritative state.
    public enum RequestDecision: Sendable, Equatable {
        /// No request is outstanding for the live generation — send one now.
        case sendRequest
        /// A request already covers this generation — nothing to do.
        case alreadyPending
    }

    /// - Parameters:
    ///   - pendingGeneration: the generation a `STATE_REQUEST` was last sent for, or `nil` if none
    ///     is outstanding (never sent, or already resolved by `onSnapshotObserved`).
    ///   - liveGeneration: the authentication generation live right now.
    public static func onTrigger(pendingGeneration: Int64?, liveGeneration: Int64) -> RequestDecision {
        pendingGeneration == liveGeneration ? .alreadyPending : .sendRequest
    }

    /// A `STATE_SNAPSHOT` was observed for `snapshotGeneration`. Returns the new pending-generation
    /// value: `nil` (request satisfied) when it matches what was outstanding, otherwise
    /// `pendingGeneration` unchanged — a snapshot from a foreign (stale or, by construction,
    /// impossible-to-be-future) generation must not clear a request a live generation is still
    /// waiting on.
    public static func onSnapshotObserved(pendingGeneration: Int64?, snapshotGeneration: Int64) -> Int64? {
        pendingGeneration == snapshotGeneration ? nil : pendingGeneration
    }
}
