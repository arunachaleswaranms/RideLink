package com.ridelink.app.music

import com.ridelink.core.player.LocalQueue
import com.ridelink.core.player.LocalQueueAction
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
 * [reduce] asks [SyncPlaybackGate.localQueueLocked] once and, when admitted, reduces every action in
 * the same synchronous step, so no suspension separates the ownership answer from the mutation it
 * authorises, and an edit made of several actions (add-then-select) is admitted as one. `null` means
 * refused: no state change and no effect at all.
 */
internal object LocalQueueEdits {
    fun reduce(
        state: LocalQueueState,
        actions: List<LocalQueueAction>,
        gate: SyncPlaybackGate?,
    ): LocalQueueOutcome? {
        if (gate?.localQueueLocked() == true) return null
        var current = state
        val effects = actions.flatMap { action -> LocalQueue.reduce(current, action).also { current = it.state }.effects }
        return LocalQueueOutcome(current, effects)
    }
}
