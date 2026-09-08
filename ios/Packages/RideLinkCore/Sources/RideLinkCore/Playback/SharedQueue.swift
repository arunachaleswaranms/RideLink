import Foundation

/// The replicated shared queue's state (PROTOCOL §9). `items` is always held sorted ascending by
/// `SharedQueueItem.order`, so `current_index` on the wire and the index the two phones compute are
/// the same number.
///
/// Tracked by **id**, not index, for the same reason `LocalQueueState` is: a remove or a move must
/// not silently re-point "current" at a different track, and an index would.
///
/// This is **not** a replacement for `LocalQueue` (this phase's brief §26). That remains the local
/// player's queue and is what a solo, peerless ride uses; this is the peer-replicated one,
/// materialised into the local queue only while a synchronised session is live.
public struct SharedQueueState: Sendable, Equatable {
    public let items: [SharedQueueItem]
    public let currentItemId: String?
    public let revision: Int64

    public init(items: [SharedQueueItem] = [], currentItemId: String? = nil, revision: Int64 = 0) {
        self.items = items
        self.currentItemId = currentItemId
        self.revision = revision
    }

    public var currentIndex: Int? {
        guard let currentItemId else { return nil }
        return items.firstIndex { $0.queueItemId == currentItemId }
    }

    public var currentItem: SharedQueueItem? {
        guard let currentItemId else { return nil }
        return items.first { $0.queueItemId == currentItemId }
    }

    func with(items: [SharedQueueItem]? = nil, currentItemId: String?? = nil, revision: Int64? = nil) -> SharedQueueState {
        SharedQueueState(
            items: items ?? self.items,
            currentItemId: currentItemId ?? self.currentItemId,
            revision: revision ?? self.revision
        )
    }
}

/// A leader-applied mutation. Followers never apply these directly — they adopt the snapshot (ADR-024 §5).
public enum SharedQueueMutation: Sendable, Equatable {
    case add(items: [QueueAddItem])
    case remove(queueItemIds: [String])
    case move(queueItemId: String, toIndex: Int)
}

/// Why a mutation was refused. Surfaced locally; only `queueFull` has a PROTOCOL §4.6 code.
public enum SharedQueueRejection: String, Sendable, Equatable {
    /// PROTOCOL §9's cap, corrected to `PlaybackBounds.maxQueueItems` by ADR-024 §6. Answered with
    /// `ERROR/capability_missing`.
    case queueFull = "QUEUE_FULL"
}

/// - `changed`: false when the mutation was a no-op — an add whose every `queue_item_id` was already
///   present (PROTOCOL §9's idempotency-under-retry promise), a remove of nothing, a move that moved
///   nothing. A no-op **must not** advance `SharedQueueState.revision`: a revision that moved without
///   the queue moving would desynchronise the two peers for no reason.
public struct SharedQueueOutcome: Sendable, Equatable {
    public let state: SharedQueueState
    public let changed: Bool
    public let rejection: SharedQueueRejection?

    public init(state: SharedQueueState, changed: Bool, rejection: SharedQueueRejection? = nil) {
        self.state = state
        self.changed = changed
        self.rejection = rejection
    }
}

/// Where `SharedQueue.step` can end up.
public struct SharedQueueStep: Sendable, Equatable {
    public let state: SharedQueueState
    /// The item now selected, or `nil` when the step ran off the end and playback should stop.
    public let selected: SharedQueueItem?
    /// True only when the step actually moved (a `previous` at the first item does not).
    public let moved: Bool
}

/// PROTOCOL §9's queue algebra as a pure reducer, mirroring Android `core.playback.SharedQueue`;
/// both run `protocol/vectors/queue/`.
///
/// The leader is the only caller of `apply`: it serialises every mutation (its own user's and the
/// follower's intents), assigns the resulting `SharedQueueState.revision`, and broadcasts a
/// `QUEUE_SNAPSHOT`. A follower only ever calls `applySnapshot`. That is §9's "the snapshot always
/// wins — there is no merge algorithm to get subtly wrong" taken literally, and it is why there is
/// no CRDT here (ADR-024 §5; brief §28 forbids inventing one).
public enum SharedQueue {
    /// Applies one mutation on the **leader**, bumping `SharedQueueState.revision` by exactly one
    /// when anything actually changed.
    public static func apply(state: SharedQueueState, mutation: SharedQueueMutation) -> SharedQueueOutcome {
        switch mutation {
        case .add(let items): return add(state, items)
        case .remove(let ids): return remove(state, ids)
        case .move(let queueItemId, let toIndex): return move(state, queueItemId, toIndex)
        }
    }

    /// A follower adopting the leader's authoritative snapshot, wholesale. No merge, no gap
    /// detection, no revision arithmetic — whatever the leader says the queue is, it is.
    public static func applySnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?) -> SharedQueueState {
        let sorted = items.sorted { $0.order < $1.order }
        var currentItemId: String?
        if let currentIndex, currentIndex >= 0, currentIndex < sorted.count {
            currentItemId = sorted[currentIndex].queueItemId
        }
        return SharedQueueState(items: sorted, currentItemId: currentItemId, revision: revision)
    }

    /// PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the replicated queue rather than either
    /// device's local one (this phase's brief §25). Both peers hold identical `SharedQueueState` at a
    /// given revision, so both resolve the same `selected`.
    ///
    /// `delta = 1` is `NEXT`, `-1` is `PREVIOUS`. Deliberately the same semantics as `LocalQueue`:
    /// `NEXT` past the last item stops rather than wrapping, `PREVIOUS` at the first item stays put.
    /// No repeat/loop mode in V1.
    public static func step(state: SharedQueueState, delta: Int) -> SharedQueueStep {
        guard let index = state.currentIndex else {
            if delta > 0, let first = state.items.first {
                return SharedQueueStep(state: state.with(currentItemId: first.queueItemId), selected: first, moved: true)
            }
            return SharedQueueStep(state: state, selected: nil, moved: false)
        }
        let targetIndex = index + delta
        if targetIndex >= 0, targetIndex < state.items.count {
            let target = state.items[targetIndex]
            return SharedQueueStep(state: state.with(currentItemId: target.queueItemId), selected: target, moved: true)
        }
        if delta > 0 {
            return SharedQueueStep(state: state.with(currentItemId: .some(nil)), selected: nil, moved: true)
        }
        return SharedQueueStep(state: state, selected: state.currentItem, moved: false)
    }

    /// Selects an existing item by id — what an authoritative `PLAY` naming a `queue_item_id` does.
    public static func select(state: SharedQueueState, queueItemId: String) -> SharedQueueState {
        state.items.contains { $0.queueItemId == queueItemId } ? state.with(currentItemId: queueItemId) : state
    }

    private static func add(_ state: SharedQueueState, _ additions: [QueueAddItem]) -> SharedQueueOutcome {
        // PROTOCOL §9: `queue_item_id` is minted by the issuer, so a retried QUEUE_ADD carries the
        // same ids. Re-adding one is a no-op, never a second entry and never an error — that *is*
        // the idempotency the ULID buys. Two genuinely separate adds of the same track carry two
        // different ids and correctly become two entries (brief §27).
        let fresh = additions.filter { candidate in !state.items.contains { $0.queueItemId == candidate.queueItemId } }
        if fresh.isEmpty { return SharedQueueOutcome(state: state, changed: false) }
        if state.items.count + fresh.count > PlaybackBounds.maxQueueItems {
            return SharedQueueOutcome(state: state, changed: false, rejection: .queueFull)
        }
        var working = state
        for item in fresh { working = insert(working, item) }
        return SharedQueueOutcome(state: working.with(revision: state.revision + 1), changed: true)
    }

    private static func insert(_ state: SharedQueueState, _ addition: QueueAddItem) -> SharedQueueState {
        let wantsNext = addition.position == PlaybackBounds.queuePositionNext
        guard wantsNext, let currentIndex = state.currentIndex else {
            let order = (state.items.last?.order ?? 0) + PlaybackBounds.queueOrderStep
            let item = SharedQueueItem(
                queueItemId: addition.queueItemId, trackHash: addition.trackHash, addedBy: addition.addedBy, order: order
            )
            return state.with(items: state.items + [item])
        }
        let current = state.items[currentIndex]
        guard currentIndex + 1 < state.items.count else {
            let order = current.order + PlaybackBounds.queueOrderStep
            let item = SharedQueueItem(
                queueItemId: addition.queueItemId, trackHash: addition.trackHash, addedBy: addition.addedBy, order: order
            )
            return state.with(items: state.items + [item])
        }
        let following = state.items[currentIndex + 1]
        // PROTOCOL §9: sparse orders exist so an insert "rarely needs to renumber". When the gap is
        // exhausted it must, and renumbering the whole list to fresh multiples of the step is the
        // simplest correct answer — a queue capped at 1 000 items makes it cheap.
        if following.order - current.order < 2 {
            let renumbered = state.items.enumerated().map { index, item in
                SharedQueueItem(
                    queueItemId: item.queueItemId,
                    trackHash: item.trackHash,
                    addedBy: item.addedBy,
                    order: Int64(index + 1) * PlaybackBounds.queueOrderStep
                )
            }
            let order = renumbered[currentIndex].order + PlaybackBounds.queueOrderStep / 2
            let item = SharedQueueItem(
                queueItemId: addition.queueItemId, trackHash: addition.trackHash, addedBy: addition.addedBy, order: order
            )
            return state.with(items: (renumbered + [item]).sorted { $0.order < $1.order })
        }
        let order = current.order + (following.order - current.order) / 2
        let item = SharedQueueItem(
            queueItemId: addition.queueItemId, trackHash: addition.trackHash, addedBy: addition.addedBy, order: order
        )
        return state.with(items: (state.items + [item]).sorted { $0.order < $1.order })
    }

    /// Removing the current item hands "current" to whatever now occupies its old position — the item
    /// that used to follow it — or clears it if nothing did. Identical to `LocalQueue`'s rule,
    /// deliberately: the two queues must not disagree about what removing the playing track means.
    ///
    /// Removing an id that is not present is silently ignored, which is what makes a duplicated
    /// `QUEUE_REMOVE` (the same item removed twice) a no-op rather than an error.
    private static func remove(_ state: SharedQueueState, _ ids: [String]) -> SharedQueueOutcome {
        let doomed = Set(ids)
        let removedIndex = state.items.firstIndex { doomed.contains($0.queueItemId) && $0.queueItemId == state.currentItemId }
        let remaining = state.items.filter { !doomed.contains($0.queueItemId) }
        if remaining.count == state.items.count { return SharedQueueOutcome(state: state, changed: false) }
        var currentItemId = state.currentItemId
        if let removedIndex {
            currentItemId = removedIndex < remaining.count ? remaining[removedIndex].queueItemId : nil
        }
        return SharedQueueOutcome(
            state: state.with(items: remaining, currentItemId: .some(currentItemId), revision: state.revision + 1),
            changed: true
        )
    }

    /// Moves one item to `toIndex`, renumbering the whole list to fresh sparse orders. Renumbering
    /// unconditionally (rather than trying to find a gap) is what keeps a move deterministic across
    /// the two implementations — there is exactly one resulting order sequence for a given list.
    ///
    /// Moving an item that is not present, or to the index it already occupies, is a no-op and does
    /// not advance the revision — which is what makes "move an item another peer just removed"
    /// resolve deterministically rather than throwing.
    private static func move(_ state: SharedQueueState, _ queueItemId: String, _ toIndex: Int) -> SharedQueueOutcome {
        guard let fromIndex = state.items.firstIndex(where: { $0.queueItemId == queueItemId }) else {
            return SharedQueueOutcome(state: state, changed: false)
        }
        let target = min(max(toIndex, 0), state.items.count - 1)
        if target == fromIndex { return SharedQueueOutcome(state: state, changed: false) }
        var mutable = state.items
        let item = mutable.remove(at: fromIndex)
        mutable.insert(item, at: target)
        let renumbered = mutable.enumerated().map { index, entry in
            SharedQueueItem(
                queueItemId: entry.queueItemId,
                trackHash: entry.trackHash,
                addedBy: entry.addedBy,
                order: Int64(index + 1) * PlaybackBounds.queueOrderStep
            )
        }
        return SharedQueueOutcome(state: state.with(items: renumbered, revision: state.revision + 1), changed: true)
    }
}
