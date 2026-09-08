package com.ridelink.core.playback

/**
 * The replicated shared queue's state (PROTOCOL §9). [items] is always held sorted ascending by
 * [SharedQueueItem.order], so `current_index` on the wire and the index the two phones compute are
 * the same number.
 *
 * Tracked by **id**, not index, for the same reason
 * [com.ridelink.core.player.LocalQueueState] is: a remove or a move must not silently re-point
 * "current" at a different track, and an index would.
 *
 * This is **not** a replacement for [com.ridelink.core.player.LocalQueue] (this phase's brief §26).
 * That remains the local player's queue and is what a solo, peerless ride uses; this is the
 * peer-replicated one, materialised into the local queue only while a synchronised session is live.
 */
data class SharedQueueState(
    val items: List<SharedQueueItem> = emptyList(),
    val currentItemId: String? = null,
    val revision: Long = 0,
) {
    val currentIndex: Int? get() = currentItemId?.let { id -> items.indexOfFirst { it.queueItemId == id }.takeIf { it >= 0 } }

    val currentItem: SharedQueueItem? get() = currentItemId?.let { id -> items.firstOrNull { it.queueItemId == id } }
}

/** A leader-applied mutation. Followers never apply these directly — they adopt the snapshot (ADR-024 §5). */
sealed class SharedQueueMutation {
    data class Add(
        val items: List<QueueAddItem>,
    ) : SharedQueueMutation()

    data class Remove(
        val queueItemIds: List<String>,
    ) : SharedQueueMutation()

    data class Move(
        val queueItemId: String,
        val toIndex: Int,
    ) : SharedQueueMutation()
}

/** Why a mutation was refused. Surfaced locally; only [QUEUE_FULL] has a PROTOCOL §4.6 code. */
enum class SharedQueueRejection {
    /** PROTOCOL §9's cap, corrected to [PlaybackBounds.MAX_QUEUE_ITEMS] by ADR-024 §6. Answered with `ERROR/capability_missing`. */
    QUEUE_FULL,
}

/**
 * @property changed false when the mutation was a no-op — an add whose every `queue_item_id` was
 *   already present (PROTOCOL §9's idempotency-under-retry promise), a remove of nothing, a move
 *   that moved nothing. A no-op **must not** advance [SharedQueueState.revision]: a revision that
 *   moved without the queue moving would desynchronise the two peers for no reason.
 */
data class SharedQueueOutcome(
    val state: SharedQueueState,
    val changed: Boolean,
    val rejection: SharedQueueRejection? = null,
)

/** Where [SharedQueue.step] can end up. */
data class SharedQueueStep(
    val state: SharedQueueState,
    /** The item now selected, or `null` when the step ran off the end and playback should stop. */
    val selected: SharedQueueItem?,
    /** True only when the step actually moved (a `Previous` at the first item does not). */
    val moved: Boolean,
)

/**
 * PROTOCOL §9's queue algebra as a pure reducer, mirrored on both platforms and pinned by
 * `protocol/vectors/queue/`.
 *
 * The leader is the only caller of [apply]: it serialises every mutation (its own user's and the
 * follower's intents), assigns the resulting [SharedQueueState.revision], and broadcasts a
 * `QUEUE_SNAPSHOT`. A follower only ever calls [applySnapshot]. That is §9's "the snapshot always
 * wins — there is no merge algorithm to get subtly wrong" taken literally, and it is why there is
 * no CRDT here (ADR-024 §5; brief §28 forbids inventing one).
 */
object SharedQueue {
    /**
     * Applies one mutation on the **leader**, bumping [SharedQueueState.revision] by exactly one
     * when anything actually changed.
     */
    fun apply(
        state: SharedQueueState,
        mutation: SharedQueueMutation,
    ): SharedQueueOutcome =
        when (mutation) {
            is SharedQueueMutation.Add -> add(state, mutation.items)
            is SharedQueueMutation.Remove -> remove(state, mutation.queueItemIds)
            is SharedQueueMutation.Move -> move(state, mutation.queueItemId, mutation.toIndex)
        }

    /**
     * A follower adopting the leader's authoritative snapshot, wholesale. No merge, no gap
     * detection, no revision arithmetic — whatever the leader says the queue is, it is.
     */
    fun applySnapshot(
        revision: Long,
        items: List<SharedQueueItem>,
        currentIndex: Int?,
    ): SharedQueueState {
        val sorted = items.sortedBy { it.order }
        val currentItemId = currentIndex?.let { index -> sorted.getOrNull(index)?.queueItemId }
        return SharedQueueState(items = sorted, currentItemId = currentItemId, revision = revision)
    }

    /**
     * PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the replicated queue rather than either
     * device's local one (this phase's brief §25). Both peers hold byte-identical
     * [SharedQueueState] at a given revision, so both resolve the same [SharedQueueStep.selected].
     *
     * `delta = 1` is `NEXT`, `-1` is `PREVIOUS`. Deliberately the same semantics as
     * [com.ridelink.core.player.LocalQueue]: `NEXT` past the last item stops rather than wrapping,
     * `PREVIOUS` at the first item stays put. No repeat/loop mode in V1.
     */
    fun step(
        state: SharedQueueState,
        delta: Int,
    ): SharedQueueStep {
        val index = state.currentIndex
        if (index == null) {
            val first = state.items.firstOrNull()
            return if (delta > 0 && first != null) {
                SharedQueueStep(state.copy(currentItemId = first.queueItemId), first, moved = true)
            } else {
                SharedQueueStep(state, null, moved = false)
            }
        }
        val target = state.items.getOrNull(index + delta)
        return when {
            target != null -> SharedQueueStep(state.copy(currentItemId = target.queueItemId), target, moved = true)
            delta > 0 -> SharedQueueStep(state.copy(currentItemId = null), null, moved = true)
            else -> SharedQueueStep(state, state.currentItem, moved = false)
        }
    }

    /** Selects an existing item by id — what an authoritative `PLAY` naming a `queue_item_id` does. */
    fun select(
        state: SharedQueueState,
        queueItemId: String,
    ): SharedQueueState = if (state.items.any { it.queueItemId == queueItemId }) state.copy(currentItemId = queueItemId) else state

    @Suppress("ReturnCount") // one early-out per PROTOCOL §9 add rule (idempotent, capped, applied)
    private fun add(
        state: SharedQueueState,
        additions: List<QueueAddItem>,
    ): SharedQueueOutcome {
        // PROTOCOL §9: `queue_item_id` is minted by the issuer, so a retried QUEUE_ADD carries the
        // same ids. Re-adding one is a no-op, never a second entry and never an error — that *is*
        // the idempotency the ULID buys. Two genuinely separate adds of the same track carry two
        // different ids and correctly become two entries (brief §27).
        val fresh = additions.filter { candidate -> state.items.none { it.queueItemId == candidate.queueItemId } }
        if (fresh.isEmpty()) return SharedQueueOutcome(state, changed = false)
        if (state.items.size + fresh.size > PlaybackBounds.MAX_QUEUE_ITEMS) {
            return SharedQueueOutcome(state, changed = false, rejection = SharedQueueRejection.QUEUE_FULL)
        }
        var working = state
        for (item in fresh) {
            working = insert(working, item)
        }
        return SharedQueueOutcome(working.copy(revision = state.revision + 1), changed = true)
    }

    @Suppress("ReturnCount") // one early-out per placement case: end, no successor, exhausted gap, gap
    private fun insert(
        state: SharedQueueState,
        addition: QueueAddItem,
    ): SharedQueueState {
        val wantsNext = addition.position == PlaybackBounds.QUEUE_POSITION_NEXT
        val currentIndex = state.currentIndex
        if (!wantsNext || currentIndex == null) {
            val order = (state.items.lastOrNull()?.order ?: 0L) + PlaybackBounds.QUEUE_ORDER_STEP
            val item = SharedQueueItem(addition.queueItemId, addition.trackHash, addition.addedBy, order)
            return state.copy(items = state.items + item)
        }
        val current = state.items[currentIndex]
        val following = state.items.getOrNull(currentIndex + 1)
        if (following == null) {
            val order = current.order + PlaybackBounds.QUEUE_ORDER_STEP
            val item = SharedQueueItem(addition.queueItemId, addition.trackHash, addition.addedBy, order)
            return state.copy(items = state.items + item)
        }
        // PROTOCOL §9: sparse orders exist so an insert "rarely needs to renumber". When the gap is
        // exhausted it must, and renumbering the whole list to fresh multiples of the step is the
        // simplest correct answer — a queue capped at 1 000 items makes it cheap.
        if (following.order - current.order < 2) {
            val renumbered = state.items.mapIndexed { index, item -> item.copy(order = (index + 1) * PlaybackBounds.QUEUE_ORDER_STEP) }
            val renumberedCurrent = renumbered[currentIndex]
            val item =
                SharedQueueItem(
                    addition.queueItemId,
                    addition.trackHash,
                    addition.addedBy,
                    renumberedCurrent.order + PlaybackBounds.QUEUE_ORDER_STEP / 2,
                )
            return state.copy(items = (renumbered + item).sortedBy { it.order })
        }
        val order = current.order + (following.order - current.order) / 2
        val item = SharedQueueItem(addition.queueItemId, addition.trackHash, addition.addedBy, order)
        return state.copy(items = (state.items + item).sortedBy { it.order })
    }

    /**
     * Removing the current item hands "current" to whatever now occupies its old position — the
     * item that used to follow it — or clears it if nothing did. Identical to
     * [com.ridelink.core.player.LocalQueue]'s rule, deliberately: the two queues must not disagree
     * about what removing the playing track means.
     *
     * Removing an id that is not present is silently ignored, which is what makes a duplicated
     * `QUEUE_REMOVE` (the same item removed twice) a no-op rather than an error.
     */
    private fun remove(
        state: SharedQueueState,
        ids: List<String>,
    ): SharedQueueOutcome {
        val doomed = ids.toSet()
        val removedIndex = state.items.indexOfFirst { it.queueItemId in doomed && it.queueItemId == state.currentItemId }
        val remaining = state.items.filterNot { it.queueItemId in doomed }
        if (remaining.size == state.items.size) return SharedQueueOutcome(state, changed = false)
        val currentItemId =
            if (removedIndex >= 0) remaining.getOrNull(removedIndex)?.queueItemId else state.currentItemId
        return SharedQueueOutcome(
            state.copy(items = remaining, currentItemId = currentItemId, revision = state.revision + 1),
            changed = true,
        )
    }

    /**
     * Moves one item to [toIndex], renumbering the whole list to fresh sparse orders. Renumbering
     * unconditionally (rather than trying to find a gap) is what keeps a move deterministic across
     * the two implementations — there is exactly one resulting order sequence for a given list.
     *
     * Moving an item that is not present, or to the index it already occupies, is a no-op and does
     * not advance the revision — which is what makes "move an item another peer just removed"
     * resolve deterministically rather than throwing.
     */
    @Suppress("ReturnCount") // one early-out per no-op case, each of which must not advance the revision
    private fun move(
        state: SharedQueueState,
        queueItemId: String,
        toIndex: Int,
    ): SharedQueueOutcome {
        val fromIndex = state.items.indexOfFirst { it.queueItemId == queueItemId }
        if (fromIndex < 0) return SharedQueueOutcome(state, changed = false)
        val target = toIndex.coerceIn(0, state.items.lastIndex)
        if (target == fromIndex) return SharedQueueOutcome(state, changed = false)
        val mutable = state.items.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(target, item)
        val renumbered = mutable.mapIndexed { index, entry -> entry.copy(order = (index + 1) * PlaybackBounds.QUEUE_ORDER_STEP) }
        return SharedQueueOutcome(state.copy(items = renumbered, revision = state.revision + 1), changed = true)
    }
}
