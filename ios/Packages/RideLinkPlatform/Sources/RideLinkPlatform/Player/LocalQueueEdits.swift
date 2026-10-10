import Foundation
import RideLinkCore

/// Admission for a user's edit of the **local** queue (Phase 9A.5, PR #18 review). Mirrors Android's
/// `com.ridelink.app.music.LocalQueueEdits`.
///
/// Up Next made select, remove, move and clear reachable, and none of them passed through
/// `SyncPlaybackGate`: during a synchronised ride `.select` loaded and played a track on this phone
/// only, `.clear` or removing the current entry stopped or advanced this phone only, and `.move`
/// reordered a queue the synchronised path does not own. So while synchronised transport owns
/// playback every such edit is refused **here**, at `MusicCoordinator`'s boundary — never only by a
/// disabled control. It lives in this package, beside the gate, because the app target has no unit
/// test bundle and the real `SyncPlaybackGateAdapter` has to be exercised against it.
///
/// `reduce` admits once (`admit`) and, when admitted, reduces every action in the same synchronous
/// step, so no suspension separates the admission from the queue mutation it authorises, and an edit
/// made of several actions (add-then-select) is admitted as one. `nil` means refused: no state change
/// and no effect at all.
///
/// **Round 2 (ADR-024 Amendment A15): the admission is returned with the outcome.** The queue mutation
/// is synchronous, but its player effects are not — they run in `Task`s, behind audio-session
/// activation and a library lookup — so the `LocalQueueEditAdmission` travels with them and is
/// re-proved before every player call (`LocalPlaybackEffects`). A Boolean "is the queue locked?" taken
/// here would authorise nothing after the first suspension, and `@MainActor` does not change that:
/// every `await` releases the main actor to whatever activates synchronised mode next.
public enum LocalQueueEdits {
    /// The admission for a fresh local edit or press, or `nil` while synchronised mode owns transport.
    @MainActor
    public static func admit(gate: (any SyncPlaybackGate)?) -> LocalQueueEditAdmission? {
        guard let gate else { return .ungated }
        return gate.admitLocalQueueEdit()
    }

    /// Whether `admission` — never a freshly minted one — still holds.
    @MainActor
    public static func stillValid(_ admission: LocalQueueEditAdmission, gate: (any SyncPlaybackGate)?) -> Bool {
        guard let gate else { return admission == .ungated }
        return gate.isLocalQueueEditStillValid(admission)
    }

    @MainActor
    public static func reduce(
        _ state: LocalQueueState, _ actions: [LocalQueueAction], gate: (any SyncPlaybackGate)?
    ) -> AdmittedLocalQueueEdit? {
        guard let admission = admit(gate: gate) else { return nil }
        var current = state
        var effects: [LocalQueueEffect] = []
        for action in actions {
            let outcome = LocalQueue.reduce(current, action)
            current = outcome.state
            effects += outcome.effects
        }
        return AdmittedLocalQueueEdit(outcome: LocalQueueOutcome(state: current, effects: effects), admission: admission)
    }
}

/// An admitted edit: its queue outcome and the admission its player effects must carry.
public struct AdmittedLocalQueueEdit: Sendable {
    public let outcome: LocalQueueOutcome
    public let admission: LocalQueueEditAdmission

    public init(outcome: LocalQueueOutcome, admission: LocalQueueEditAdmission) {
        self.outcome = outcome
        self.admission = admission
    }

    public var state: LocalQueueState { outcome.state }
    public var effects: [LocalQueueEffect] { outcome.effects }
}

/// Which local-queue controls a screen offers — the presentation half of `LocalQueueEdits`, so the
/// SwiftUI views and their tests read one table. While locked nothing that edits the local queue is
/// offered at all (absent rather than disabled, so VoiceOver cannot reach it either); the queue is
/// still shown. Rendering only: the authority is `LocalQueueEdits.reduce`.
public struct LocalQueueAffordances: Equatable, Sendable {
    public let rowsPlay: Bool
    public let canRemove: Bool
    public let canReorder: Bool
    public let canClear: Bool
    public let canAdd: Bool

    public static func forLocked(_ locked: Bool) -> LocalQueueAffordances {
        LocalQueueAffordances(rowsPlay: !locked, canRemove: !locked, canReorder: !locked, canClear: !locked, canAdd: !locked)
    }
}
