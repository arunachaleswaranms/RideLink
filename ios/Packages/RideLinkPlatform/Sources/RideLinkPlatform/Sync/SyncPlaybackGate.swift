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
@MainActor
public struct SyncPlaybackGateAdapter: SyncPlaybackGate {
    let sync: SyncPlaybackCoordinator
    /// Read synchronously so the gate can answer without suspending — `MusicCoordinator`'s callers
    /// (including `MPRemoteCommandCenter`'s handlers) are synchronous and cannot await an actor.
    let isActive: () -> Bool
    let role: () -> PlaybackRole?

    public init(sync: SyncPlaybackCoordinator, isActive: @escaping () -> Bool, role: @escaping () -> PlaybackRole?) {
        self.sync = sync
        self.isActive = isActive
        self.role = role
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
        guard isActive() else { return false }
        // Only the ADR-010 leader may decide what plays next. A follower still *intercepts* — it
        // must not advance its own queue — and then does nothing, waiting for the leader's
        // authoritative NEXT. That is not a stall, it is the single serialisation point doing its
        // job. The role is read here, on the main actor, rather than inside the task below: it is a
        // non-`Sendable` closure and cannot cross into one.
        guard role() == .leader else { return true }
        return forward { await $0.next() }
    }

    /// Only `sync` — an actor, and so `Sendable` — crosses into the task. Nothing else here is,
    /// which is exactly what Swift 6 strict concurrency is for.
    private func forward(_ action: @escaping @Sendable (SyncPlaybackCoordinator) async -> Void) -> Bool {
        guard isActive() else { return false }
        let coordinator = sync
        Task { await action(coordinator) }
        return true
    }
}
