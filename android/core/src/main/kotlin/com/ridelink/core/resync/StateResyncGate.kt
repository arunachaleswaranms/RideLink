package com.ridelink.core.resync

/**
 * The one new pure decision Phase 7's `STATE_REQUEST` needs (this phase's brief §11/§25):
 * **whether to send one**, given what is already outstanding. Everything else — whether an
 * incoming `STATE_SNAPSHOT` may be *applied* — is answered by the existing, already-audited
 * generation and role checks inside [com.ridelink.core.playback] reconciliation (`adoptSnapshot` /
 * `onPeerPlaybackState` on both platforms), reused rather than re-derived here (rule 19/20's
 * standing lesson: a second provenance check is a second place for the two to disagree).
 *
 * The gate is keyed on the **authentication generation**, not a bare boolean, for the same reason
 * every other per-session flag in this codebase is: a bare "request pending" flag survives a
 * reconnect and would refuse a fresh request a **new** generation legitimately needs, while a
 * stale generation can never collide with a live one because generations strictly increase
 * ([com.ridelink.network.control.ControlSessionManager]'s allocation). Comparing generations is
 * therefore both the dedup **and** the reconnect-reset, in one comparison.
 */
object StateResyncGate {
    /** What the caller should do about a trigger to request authoritative state. */
    enum class RequestDecision {
        /** No request is outstanding for the live generation — send one now. */
        SEND_REQUEST,

        /** A request already covers this generation — nothing to do. */
        ALREADY_PENDING,
    }

    /**
     * @param pendingGeneration the generation a `STATE_REQUEST` was last sent for, or `null` if
     *   none is outstanding (never sent, or already resolved by [onSnapshotObserved]).
     * @param liveGeneration the authentication generation live right now.
     */
    fun onTrigger(
        pendingGeneration: Long?,
        liveGeneration: Long,
    ): RequestDecision = if (pendingGeneration == liveGeneration) RequestDecision.ALREADY_PENDING else RequestDecision.SEND_REQUEST

    /**
     * A `STATE_SNAPSHOT` was observed for [snapshotGeneration]. Returns the new pending-generation
     * value: `null` (request satisfied) when it matches what was outstanding, otherwise
     * [pendingGeneration] unchanged — a snapshot from a foreign (stale or, by construction,
     * impossible-to-be-future) generation must not clear a request a live generation is still
     * waiting on.
     */
    fun onSnapshotObserved(
        pendingGeneration: Long?,
        snapshotGeneration: Long,
    ): Long? = if (pendingGeneration == snapshotGeneration) null else pendingGeneration
}
