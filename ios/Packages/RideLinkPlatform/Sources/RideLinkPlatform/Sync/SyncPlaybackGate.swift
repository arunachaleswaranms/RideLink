import Foundation
import RideLinkCore

/// The one place a transport action can be taken over by a synchronised session.
///
/// **This exists so there is exactly one command path, not two** (this phase's brief §39). Both the
/// in-app controls and `MPRemoteCommandCenter` (via `NowPlayingController`) already funnel every
/// play/pause/seek/next/previous into `MusicCoordinator`; a lock-screen tap and an in-app tap are
/// the same call. Rather than adding a second, synchronisation-aware path beside it,
/// `MusicCoordinator` asks this gate first — so a lock-screen *pause* during a synchronised ride
/// becomes a leader-ordered `PAUSE` on both phones, and cannot silently mutate one.
///
/// It is `MusicCoordinator`'s requirement on whatever owns synchronisation, and the dependency still
/// points that way. It lives here rather than in the app target beside `MusicCoordinator` only
/// because the app target has no unit-test bundle, and `SyncPlaybackGateAdapter` — the one
/// implementation — has to be exercised for real (ADR-024 Amendment A14), the same reason
/// `RideSegmentLifecycle` lives in this package.
///
/// Every method returns **true when the synchronised session has taken ownership** of the action, in
/// which case `MusicCoordinator` must not touch the player itself. Outside synchronised mode every
/// method returns false and Phase 3 behaviour is bit-for-bit unchanged — including when no peer has
/// ever connected, which is the whole of brief §40's local/synchronised boundary.
///
/// Mirrors `com.ridelink.app.music.SyncPlaybackGate`.
@MainActor
public protocol SyncPlaybackGate {
    func interceptPlay() -> Bool
    func interceptPause() -> Bool
    func interceptSeek(_ positionMs: Int64) -> Bool
    func interceptNext() -> Bool
    func interceptPrevious() -> Bool

    /// A track finished on its own. Locally that means "advance the queue"; in a synchronised session
    /// only the ADR-010 leader may decide what plays next, and it does so by issuing an authoritative
    /// `NEXT` that both phones then schedule. A follower returns true and does nothing — waiting for
    /// the leader's command is correct, not a stall.
    func interceptTrackEnded() -> Bool
}

/// Bridges `MusicCoordinator`'s gate to the coordinator that owns synchronisation.
///
/// **ADR-024 Amendment A14: every answer comes from `SyncPlaybackCoordinator.transportOwnership`,
/// the coordinator's own synchronous mirror of `syncEnabled && role != nil`.** It used to come from
/// `SyncPlaybackPresenter`, which reconstructed it from published diagnostics as
/// `role != nil && syncState != .inactive` — equivalent only while nothing could report scheduling
/// after synchronised mode ended. Once an already-distributed obligation was allowed to finish after
/// End Ride (Amendment A13), its `.scheduled`/`.synced` made that expression true again, the
/// lock-screen Pause was intercepted, and a fresh synchronised `PAUSE` went out with the ride over.
/// `SyncState` is diagnostics; it is not read here at all.
///
/// The read is synchronous — `MusicCoordinator`'s callers, `MPRemoteCommandCenter`'s handlers
/// included, cannot await an actor — and it is only the *first* of two answers. The forwarded press
/// is admitted by `SyncPlaybackCoordinator.admitLocalTransport`, which re-proves ownership where the
/// authority is created, so a press that races a boundary is refused there.
@MainActor
public struct SyncPlaybackGateAdapter: SyncPlaybackGate {
    let sync: SyncPlaybackCoordinator

    public init(sync: SyncPlaybackCoordinator) {
        self.sync = sync
    }

    public func interceptPlay() -> Bool {
        // A local `play` during a synchronised ride resumes *both* phones from the position the
        // authoritative timeline is at — never just this one.
        forward { await $0.resume() }
    }

    public func interceptPause() -> Bool { forward { await $0.pause() } }

    public func interceptSeek(_ positionMs: Int64) -> Bool { forward { await $0.seek(positionMs: positionMs) } }

    public func interceptNext() -> Bool { forward { await $0.next() } }

    public func interceptPrevious() -> Bool { forward { await $0.previous() } }

    public func interceptTrackEnded() -> Bool {
        // One read, so ownership and role come from the same instant: the role travels inside the
        // synchronised case and cannot be paired with a different read's ownership.
        switch sync.transportOwnership.current {
        case .local:
            // Phase 3: `MusicCoordinator` advances its own local queue.
            return false
        case .synchronized(.follower):
            // Only the ADR-010 leader may decide what plays next. A follower still *intercepts* — it
            // must not advance its own queue — and then does nothing, waiting for the leader's
            // authoritative NEXT. That is not a stall, it is the single serialisation point doing
            // its job.
            return true
        case .synchronized(.leader):
            dispatch { await $0.next() }
            return true
        }
    }

    private func forward(_ action: @escaping @Sendable (SyncPlaybackCoordinator) async -> Void) -> Bool {
        guard sync.transportOwnership.current.isSynchronizedModeActive else { return false }
        dispatch(action)
        return true
    }

    /// Only `sync` — an actor, and so `Sendable` — crosses into the task. Nothing else here is,
    /// which is exactly what Swift 6 strict concurrency is for.
    private func dispatch(_ action: @escaping @Sendable (SyncPlaybackCoordinator) async -> Void) {
        let coordinator = sync
        Task { await action(coordinator) }
    }
}
