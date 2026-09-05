package com.ridelink.core.player

import com.ridelink.core.model.LocalEntryId

/**
 * One local-only queue slot. [id] is a fresh identifier per insertion (a ULID, matching
 * [com.ridelink.core.model.SessionId]'s convention) — **not** derived from [localEntryId] — so the
 * same track can be queued more than once (brief §14's "duplicate track added" case) as two
 * distinct, independently removable/movable entries.
 *
 * [localEntryId], not [com.ridelink.core.model.QuickId] (ADR-005 Amendment A1): `QuickId` is not
 * guaranteed unique across library rows, so it cannot safely name *which* row this queue slot plays.
 *
 * Deliberately has no `addedBy: PeerId` or `status: QueueItemStatus` the way the existing wire
 * [com.ridelink.core.model.QueueItem] does — those are Phase 5 shared-queue-replication concepts.
 * This is the local queue that exists before there is anyone to share it with.
 */
data class LocalQueueItem(
    val id: String,
    val localEntryId: LocalEntryId,
    val insertedAtMonoUs: Long,
)

/**
 * [currentId] rather than a raw index: tracking *which item* is current by identity, not by
 * position, is what makes [LocalQueue.reduce] correct across a [LocalQueueAction.Remove] or
 * [LocalQueueAction.Move] without a separate index-adjustment pass — the index is derived, never
 * stored.
 */
data class LocalQueueState(
    val items: List<LocalQueueItem> = emptyList(),
    val currentId: String? = null,
) {
    val currentIndex: Int? get() = currentId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    val currentItem: LocalQueueItem? get() = currentId?.let { id -> items.firstOrNull { it.id == id } }
}

/** Exactly the actions this phase's brief §14 lists — add/remove/move/clear/next/previous/select. */
sealed class LocalQueueAction {
    data class Add(
        val item: LocalQueueItem,
    ) : LocalQueueAction()

    data class Remove(
        val id: String,
    ) : LocalQueueAction()

    data class Move(
        val id: String,
        val toIndex: Int,
    ) : LocalQueueAction()

    object Clear : LocalQueueAction()

    object Next : LocalQueueAction()

    object Previous : LocalQueueAction()

    data class Select(
        val id: String,
    ) : LocalQueueAction()
}

/** What the queue owner must do in response — a diff, not a restatement (same convention as
 *  [com.ridelink.core.audiopolicy.IntercomAction]). */
sealed class LocalQueueEffect {
    data class LoadAndPlay(
        val localEntryId: LocalEntryId,
    ) : LocalQueueEffect()

    object StopPlayback : LocalQueueEffect()
}

data class LocalQueueOutcome(
    val state: LocalQueueState,
    val effects: List<LocalQueueEffect>,
)

/**
 * The local queue, as a pure `(state, action) -> (state, effects)` reducer — same shape as
 * [com.ridelink.core.audiopolicy.IntercomTransmission] and
 * [com.ridelink.core.audiopolicy.AudioSessionLifecycle], so the same table-driven testing approach
 * applies (this phase's brief §14/§23).
 *
 * **What this type does not model, deliberately:** a track file disappearing out from under the
 * queue is not a queue action. [PlayerState.error] `== `[MusicFailure.FILE_MISSING] and
 * [PlayerState.ended] are both player-observed facts; the app-level coordinator that owns both a
 * [Player] and a [LocalQueue] reacts to either by issuing [Next] itself. Modelling "file missing"
 * as a queue-internal concept would duplicate [com.ridelink.core.library.DecodeStatus.MISSING] one
 * layer up for no benefit.
 *
 * **No repeat/loop mode in V1** — [Next] past the last item stops rather than wrapping, and
 * [Previous] at the first item is a no-op rather than wrapping backward or restarting the current
 * track. Both are the simplest coherent behaviour the brief's edge-case list actually requires;
 * repeat mode is not a REQUIREMENTS §16/FR item and can be added later without changing this shape.
 */
object LocalQueue {
    fun reduce(
        state: LocalQueueState,
        action: LocalQueueAction,
    ): LocalQueueOutcome =
        when (action) {
            is LocalQueueAction.Add -> LocalQueueOutcome(state.copy(items = state.items + action.item), emptyList())
            is LocalQueueAction.Remove -> remove(state, action.id)
            is LocalQueueAction.Move -> LocalQueueOutcome(move(state, action.id, action.toIndex), emptyList())
            LocalQueueAction.Clear -> clear(state)
            LocalQueueAction.Next -> step(state, delta = 1)
            LocalQueueAction.Previous -> step(state, delta = -1)
            is LocalQueueAction.Select -> select(state, action.id)
        }

    /**
     * Removing an item that is not current only ever shifts positions, never identity or playback.
     * Removing the *current* item hands playback to whatever now occupies its old position — the
     * item that used to be immediately after it — or stops if nothing did (brief §14's "current item
     * removed" case).
     */
    private fun remove(
        state: LocalQueueState,
        id: String,
    ): LocalQueueOutcome {
        val removedIndex = state.items.indexOfFirst { it.id == id }
        val newItems = state.items.filterNot { it.id == id }
        val successor = newItems.getOrNull(removedIndex)
        return when {
            removedIndex < 0 -> LocalQueueOutcome(state, emptyList())
            state.currentId != id -> LocalQueueOutcome(state.copy(items = newItems), emptyList())
            successor != null ->
                LocalQueueOutcome(
                    state.copy(items = newItems, currentId = successor.id),
                    listOf(LocalQueueEffect.LoadAndPlay(successor.localEntryId)),
                )
            else -> LocalQueueOutcome(state.copy(items = newItems, currentId = null), listOf(LocalQueueEffect.StopPlayback))
        }
    }

    private fun move(
        state: LocalQueueState,
        id: String,
        toIndex: Int,
    ): LocalQueueState {
        val fromIndex = state.items.indexOfFirst { it.id == id }
        if (fromIndex < 0 || state.items.isEmpty()) return state
        val clampedTarget = toIndex.coerceIn(0, state.items.lastIndex)
        val mutable = state.items.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(clampedTarget, item)
        return state.copy(items = mutable)
    }

    /** Brief §14's "queue cleared during playback" case: playback stops only if something was
     *  actually playing — clearing an already-empty/idle queue emits no effect. */
    private fun clear(state: LocalQueueState): LocalQueueOutcome {
        val hadCurrent = state.currentId != null
        return LocalQueueOutcome(
            LocalQueueState(),
            if (hadCurrent) listOf(LocalQueueEffect.StopPlayback) else emptyList(),
        )
    }

    /** Shared by [LocalQueueAction.Next] (`delta = 1`) and [LocalQueueAction.Previous] (`delta = -1`). */
    private fun step(
        state: LocalQueueState,
        delta: Int,
    ): LocalQueueOutcome {
        val currentIndex = state.currentIndex
        if (currentIndex == null) {
            // Nothing selected yet: Next starts the queue at its first item; Previous has nothing to
            // go back to.
            val first = state.items.firstOrNull()
            return if (delta > 0 && first != null) {
                LocalQueueOutcome(
                    state.copy(currentId = first.id),
                    listOf(LocalQueueEffect.LoadAndPlay(first.localEntryId)),
                )
            } else {
                LocalQueueOutcome(state, emptyList())
            }
        }
        val target = state.items.getOrNull(currentIndex + delta)
        return when {
            target != null ->
                LocalQueueOutcome(state.copy(currentId = target.id), listOf(LocalQueueEffect.LoadAndPlay(target.localEntryId)))
            // Next past the last item: brief §14's "last item ends" case. Deliberately no wraparound.
            delta > 0 -> LocalQueueOutcome(state.copy(currentId = null), listOf(LocalQueueEffect.StopPlayback))
            // Previous at the first item: brief §14's "previous at first item" case. Stays put.
            else -> LocalQueueOutcome(state, emptyList())
        }
    }

    private fun select(
        state: LocalQueueState,
        id: String,
    ): LocalQueueOutcome {
        val target = state.items.firstOrNull { it.id == id } ?: return LocalQueueOutcome(state, emptyList())
        return LocalQueueOutcome(state.copy(currentId = id), listOf(LocalQueueEffect.LoadAndPlay(target.localEntryId)))
    }
}
