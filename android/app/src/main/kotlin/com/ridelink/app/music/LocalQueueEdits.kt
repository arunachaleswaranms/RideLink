package com.ridelink.app.music

import com.ridelink.core.player.LocalQueue
import com.ridelink.core.player.LocalQueueAction
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.LocalQueueOutcome
import com.ridelink.core.player.LocalQueueState

/**
 * Admission for a user's edit of the **local** queue (Phase 9A.5, PR #18 review). Mirrors
 * `RideLinkPlatform.LocalQueueEdits`.
 *
 * Up Next made select, remove, move and clear reachable, and none of them passed through
 * [SyncPlaybackGate]: during a synchronised ride `Select` loaded and played a track on this phone
 * only, `Clear` or removing the current entry stopped or advanced this phone only, and `Move` reordered
 * a queue the synchronised path does not own. So while synchronised transport owns playback every such
 * edit is refused **here**, at the coordinator boundary — never only by a disabled control.
 *
 * [reduce] admits once ([admit]) and, when admitted, reduces every action in the same synchronous
 * step, so no suspension separates the admission from the queue mutation it authorises, and an edit
 * made of several actions (add-then-select) is admitted as one. `null` means refused: no state change
 * and no effect at all.
 *
 * **Round 2 (ADR-024 Amendment A15): the admission is returned with the outcome.** The queue mutation
 * is synchronous, but its player effects are not — they run in launched coroutines — so the
 * [LocalQueueEditAdmission] travels with them and is re-proved before every player call
 * ([LocalPlaybackEffects]). A Boolean "is the queue locked?" taken here would authorise nothing after
 * the first suspension.
 */
internal object LocalQueueEdits {
    /** The admission for a fresh local edit or press, or `null` while synchronised mode owns transport. */
    fun admit(gate: SyncPlaybackGate?): LocalQueueEditAdmission? =
        if (gate == null) LocalQueueEditAdmission.UNGATED else gate.admitLocalQueueEdit()

    /** Whether [admission] — never a freshly minted one — still holds. */
    fun stillValid(
        gate: SyncPlaybackGate?,
        admission: LocalQueueEditAdmission,
    ): Boolean = if (gate == null) admission === LocalQueueEditAdmission.UNGATED else gate.isLocalQueueEditStillValid(admission)

    fun reduce(
        state: LocalQueueState,
        actions: List<LocalQueueAction>,
        gate: SyncPlaybackGate?,
    ): AdmittedEdit? {
        val admission = admit(gate) ?: return null
        var current = state
        val effects = actions.flatMap { action -> LocalQueue.reduce(current, action).also { current = it.state }.effects }
        return AdmittedEdit(LocalQueueOutcome(current, effects), admission)
    }
}

/** An admitted edit: its queue outcome and the admission its player effects must carry. */
internal data class AdmittedEdit(
    val outcome: LocalQueueOutcome,
    val admission: LocalQueueEditAdmission,
) {
    val state: LocalQueueState get() = outcome.state
    val effects: List<LocalQueueEffect> get() = outcome.effects
}
