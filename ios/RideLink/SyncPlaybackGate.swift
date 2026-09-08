import Foundation

/// The one place a transport action can be taken over by a synchronised session.
///
/// **This exists so there is exactly one command path, not two** (this phase's brief §39). Both the
/// in-app controls and `MPRemoteCommandCenter` (via `NowPlayingController`) already funnel every
/// play/pause/seek/next/previous into `MusicCoordinator`; a lock-screen tap and an in-app tap are
/// the same call. Rather than adding a second, synchronisation-aware path beside it,
/// `MusicCoordinator` asks this gate first — so a lock-screen *pause* during a synchronised ride
/// becomes a leader-ordered `PAUSE` on both phones, and cannot silently mutate one.
///
/// Declared next to the coordinator that consults it, rather than in `RideLinkPlatform`: this is
/// `MusicCoordinator`'s requirement on whatever owns synchronisation, and the dependency points that
/// way. `SyncPlaybackGateAdapter` implements it over `RideLinkPlatform.SyncPlaybackCoordinator`.
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
