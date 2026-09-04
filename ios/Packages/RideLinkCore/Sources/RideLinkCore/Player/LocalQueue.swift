import Foundation

/// One local-only queue slot. `id` is a fresh identifier per insertion (a ULID, matching
/// `SessionId`'s convention) — **not** derived from `quickId` — so the same track can be queued
/// more than once (brief §14's "duplicate track added" case) as two distinct, independently
/// removable/movable entries.
///
/// Deliberately has no `addedBy: PeerId` or `status: QueueItemStatus` the way the existing wire
/// `QueueItem` does — those are Phase 5 shared-queue-replication concepts. This is the local queue
/// that exists before there is anyone to share it with.
public struct LocalQueueItem: Sendable, Equatable {
    public let id: String
    public let quickId: QuickId
    public let insertedAtMonoUs: Int64

    public init(id: String, quickId: QuickId, insertedAtMonoUs: Int64) {
        self.id = id
        self.quickId = quickId
        self.insertedAtMonoUs = insertedAtMonoUs
    }
}

/// `currentId` rather than a raw index: tracking *which item* is current by identity, not by
/// position, is what makes `LocalQueue.reduce` correct across a `.remove` or `.move` without a
/// separate index-adjustment pass — the index is derived, never stored.
public struct LocalQueueState: Sendable, Equatable {
    public let items: [LocalQueueItem]
    public let currentId: String?

    public init(items: [LocalQueueItem] = [], currentId: String? = nil) {
        self.items = items
        self.currentId = currentId
    }

    public var currentIndex: Int? {
        guard let id = currentId else { return nil }
        return items.firstIndex { $0.id == id }
    }

    public var currentItem: LocalQueueItem? {
        guard let id = currentId else { return nil }
        return items.first { $0.id == id }
    }
}

/// Exactly the actions this phase's brief §14 lists — add/remove/move/clear/next/previous/select.
public enum LocalQueueAction: Sendable, Equatable {
    case add(LocalQueueItem)
    case remove(id: String)
    case move(id: String, toIndex: Int)
    case clear
    case next
    case previous
    case select(id: String)
}

/// What the queue owner must do in response — a diff, not a restatement (same convention as
/// `RideLinkCore.AudioPolicy`'s `IntercomAction`).
public enum LocalQueueEffect: Sendable, Equatable {
    case loadAndPlay(QuickId)
    case stopPlayback
}

public struct LocalQueueOutcome: Sendable, Equatable {
    public let state: LocalQueueState
    public let effects: [LocalQueueEffect]
}

/// The local queue, as a pure `(state, action) -> (state, effects)` reducer — same shape as
/// `IntercomTransmission` and `AudioSessionLifecycle` on the Android side, so the same table-driven
/// testing approach applies (this phase's brief §14/§23).
///
/// **What this type does not model, deliberately:** a track file disappearing out from under the
/// queue is not a queue action. `PlayerState.error == .fileMissing` and `PlayerState.ended` are both
/// player-observed facts; the app-level coordinator that owns both a `Player` and a `LocalQueue`
/// reacts to either by issuing `.next` itself. Modelling "file missing" as a queue-internal concept
/// would duplicate `DecodeStatus.missing` one layer up for no benefit.
///
/// **No repeat/loop mode in V1** — `.next` past the last item stops rather than wrapping, and
/// `.previous` at the first item is a no-op rather than wrapping backward or restarting the current
/// track. Both are the simplest coherent behaviour the brief's edge-case list actually requires;
/// repeat mode is not a REQUIREMENTS §16/FR item and can be added later without changing this shape.
public enum LocalQueue {
    public static func reduce(_ state: LocalQueueState, _ action: LocalQueueAction) -> LocalQueueOutcome {
        switch action {
        case let .add(item):
            return LocalQueueOutcome(state: LocalQueueState(items: state.items + [item], currentId: state.currentId), effects: [])
        case let .remove(id):
            return remove(state, id)
        case let .move(id, toIndex):
            return LocalQueueOutcome(state: move(state, id, toIndex), effects: [])
        case .clear:
            return clear(state)
        case .next:
            return step(state, delta: 1)
        case .previous:
            return step(state, delta: -1)
        case let .select(id):
            return select(state, id)
        }
    }

    /// Removing an item that is not current only ever shifts positions, never identity or playback.
    /// Removing the *current* item hands playback to whatever now occupies its old position — the
    /// item that used to be immediately after it — or stops if nothing did (brief §14's "current
    /// item removed" case).
    private static func remove(_ state: LocalQueueState, _ id: String) -> LocalQueueOutcome {
        guard let removedIndex = state.items.firstIndex(where: { $0.id == id }) else {
            return LocalQueueOutcome(state: state, effects: [])
        }
        var newItems = state.items
        newItems.remove(at: removedIndex)
        guard state.currentId == id else {
            return LocalQueueOutcome(state: LocalQueueState(items: newItems, currentId: state.currentId), effects: [])
        }
        if removedIndex < newItems.count {
            let successor = newItems[removedIndex]
            return LocalQueueOutcome(
                state: LocalQueueState(items: newItems, currentId: successor.id),
                effects: [.loadAndPlay(successor.quickId)]
            )
        } else {
            return LocalQueueOutcome(state: LocalQueueState(items: newItems, currentId: nil), effects: [.stopPlayback])
        }
    }

    private static func move(_ state: LocalQueueState, _ id: String, _ toIndex: Int) -> LocalQueueState {
        guard let fromIndex = state.items.firstIndex(where: { $0.id == id }), !state.items.isEmpty else { return state }
        let clampedTarget = max(0, min(toIndex, state.items.count - 1))
        var mutable = state.items
        let item = mutable.remove(at: fromIndex)
        mutable.insert(item, at: clampedTarget)
        return LocalQueueState(items: mutable, currentId: state.currentId)
    }

    /// Brief §14's "queue cleared during playback" case: playback stops only if something was
    /// actually playing — clearing an already-empty/idle queue emits no effect.
    private static func clear(_ state: LocalQueueState) -> LocalQueueOutcome {
        let hadCurrent = state.currentId != nil
        return LocalQueueOutcome(state: LocalQueueState(), effects: hadCurrent ? [.stopPlayback] : [])
    }

    /// Shared by `.next` (`delta = 1`) and `.previous` (`delta = -1`).
    private static func step(_ state: LocalQueueState, delta: Int) -> LocalQueueOutcome {
        guard let currentIndex = state.currentIndex else {
            // Nothing selected yet: Next starts the queue at its first item; Previous has nothing to
            // go back to.
            guard delta > 0, let first = state.items.first else {
                return LocalQueueOutcome(state: state, effects: [])
            }
            return LocalQueueOutcome(state: LocalQueueState(items: state.items, currentId: first.id), effects: [.loadAndPlay(first.quickId)])
        }
        let targetIndex = currentIndex + delta
        if targetIndex >= 0, targetIndex < state.items.count {
            let target = state.items[targetIndex]
            return LocalQueueOutcome(state: LocalQueueState(items: state.items, currentId: target.id), effects: [.loadAndPlay(target.quickId)])
        }
        if delta > 0 {
            // Next past the last item: brief §14's "last item ends" case. Deliberately no wraparound.
            return LocalQueueOutcome(state: LocalQueueState(items: state.items, currentId: nil), effects: [.stopPlayback])
        }
        // Previous at the first item: brief §14's "previous at first item" case. Stays put.
        return LocalQueueOutcome(state: state, effects: [])
    }

    private static func select(_ state: LocalQueueState, _ id: String) -> LocalQueueOutcome {
        guard let target = state.items.first(where: { $0.id == id }) else {
            return LocalQueueOutcome(state: state, effects: [])
        }
        return LocalQueueOutcome(state: LocalQueueState(items: state.items, currentId: id), effects: [.loadAndPlay(target.quickId)])
    }
}
