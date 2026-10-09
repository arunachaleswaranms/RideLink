package com.ridelink.app.music

/**
 * The one place a transport action can be taken over by a synchronised session.
 *
 * **This exists so there is exactly one command path, not two** (this phase's brief §39). Both the
 * in-app controls and the ADR-022 `MediaSession` (via `MusicSessionPlayer`, a `ForwardingPlayer`)
 * already funnel every play/pause/seek/next/previous into [MusicCoordinator]; a lock-screen tap and
 * an in-app tap are the same call. Rather than adding a second, synchronisation-aware path beside
 * it, [MusicCoordinator] asks this gate first — so a lock-screen *pause* during a synchronised ride
 * becomes a leader-ordered `PAUSE` on both phones, and cannot silently mutate one.
 *
 * Declared here, next to the coordinator that consults it, rather than in `app.sync`: this is
 * [MusicCoordinator]'s requirement on whatever owns synchronisation, and the dependency points that
 * way. `com.ridelink.app.sync.SyncPlaybackCoordinator` implements it.
 *
 * Every method returns **true when the synchronised session has taken ownership** of the action, in
 * which case [MusicCoordinator] must not touch the player itself. Outside synchronised mode every
 * method returns false and Phase 3 behaviour is bit-for-bit unchanged — including when no peer has
 * ever connected, which is the whole of brief §40's local/synchronised boundary.
 */
interface SyncPlaybackGate {
    fun interceptPlay(): Boolean

    fun interceptPause(): Boolean

    fun interceptSeek(positionMs: Long): Boolean

    fun interceptNext(): Boolean

    fun interceptPrevious(): Boolean

    /**
     * A track finished on its own. Locally that means "advance the queue"; in a synchronised session
     * only the ADR-010 leader may decide what plays next, and it does so by issuing an authoritative
     * `NEXT` that both phones then schedule. A follower returns true and does nothing — waiting for
     * the leader's command is correct, not a stall.
     */
    fun interceptTrackEnded(): Boolean

    /**
     * Whether the **local** queue is locked because a synchronised session owns transport (Phase
     * 9A.5, PR #18 review). Unlike the intercepts above this takes nothing over and forwards nothing:
     * local Up Next has no synchronised equivalent to forward to, so while synchronised mode owns
     * playback a local select, remove, move, clear, add or play-now is simply **refused**. Otherwise
     * each would change only this phone — `Select` loads and plays a track, `Clear` and removing the
     * current entry stop or advance the player — around the leader-ordered path, and an addition would
     * be silently discarded by the next synchronised selection, which replaces the local queue.
     *
     * Answered from the same ownership as every method here (ADR-024 Amendment A14), read
     * synchronously at the moment [MusicCoordinator] admits the edit.
     */
    fun localQueueLocked(): Boolean

    /**
     * Admits one local edit or local transport press **under the exact local-ownership lifetime in
     * force now**, or returns `null` while synchronised transport owns playback (ADR-024 Amendment
     * A15, PR #18 review round 2). The admission travels with every player effect the edit causes and
     * is re-proved with [isLocalQueueEditStillValid] before each one: a proof taken when the edit was
     * admitted does not authorise a `Load`, `Play` or `Stop` that runs after a suspension.
     */
    fun admitLocalQueueEdit(): LocalQueueEditAdmission?

    /**
     * Whether [admission]'s own local lifetime is still the one in force. A lifetime ends for good the
     * moment synchronised mode takes transport; returning to local starts a **new** lifetime, so an
     * admission from before the synchronised interval never becomes valid again (no ABA).
     */
    fun isLocalQueueEditStillValid(admission: LocalQueueEditAdmission): Boolean
}

/**
 * Permission for one local edit's player effects, bound to the local transport-ownership lifetime
 * that admitted it (ADR-024 Amendment A15). [lifetime] is minted by the synchronisation owner, never
 * by [MusicCoordinator], and is never reused: every ownership change advances it. Compared, never
 * re-derived.
 */
class LocalQueueEditAdmission internal constructor(
    internal val lifetime: Long,
) {
    override fun toString() = "LocalQueueEditAdmission(lifetime=$lifetime)"

    internal companion object {
        /** No synchronisation owner was ever wired (no gate): a lifetime that never ends. */
        val UNGATED = LocalQueueEditAdmission(lifetime = -1)
    }
}
