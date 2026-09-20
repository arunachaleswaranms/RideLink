import Foundation

/// The narrow seam between ARCHITECTURE §3's ride lifecycle and Phase 5's synchronisation authority
/// (independent-review round 3, Blocker C; ADR-028 Amendment A2).
///
/// **The defect this exists to close.** `SessionCoordinator.endRide()` produced `RIDE_ACTIVE ->
/// CONNECTED` and nothing else, so nothing in production ever ended the *ride segment's*
/// synchronised-playback authority. `SyncPlaybackCoordinator.currentPlaybackIdentity` deliberately
/// survives an ordinary control-link loss (round-2 Blocker 2B — a leader must still be able to report
/// what it is playing in a `STATE_SNAPSHOT` built moments after a reconnect), so it also survived the
/// end of the ride: ride 1's track was still the value a leader's `STATE_SNAPSHOT` reported after
/// ride 2 had begun, presented as ride 2's authoritative truth. The only production caller of
/// `leaveSynchronizedMode()` was the "Play locally" button; the End Ride ordering existed **only in
/// tests**, which is exactly the "a test proves an order production does not" shape CLAUDE.md's
/// standing lesson warns about.
///
/// **Why a separate type rather than two lines inside `SessionCoordinator`.** `ios/RideLink` has no
/// unit-test target at all (`RideLink.xcodeproj` declares one application target and no test bundle),
/// so anything written there is unreachable from every test in the repository. Putting the ride
/// lifetime's *decisions* here — the epoch, its assignment, and the staleness proof — makes them
/// testable at their real production seam; what remains in `SessionCoordinator` is two calls with no
/// logic in them. The limitation is not removed, it is narrowed to the smallest thing it can be, and
/// it is recorded in `docs/STATUS.md` and `docs/TEST_PLAN.md` rather than papered over.
///
/// **End Ride is not End Session.** Nothing here reaches ADR-026's terminal teardown: the control
/// connection, the pairing and the peer session stay alive, and local music keeps playing exactly as
/// a Phase 3 ride (FR-025's graceful degradation). What ends is ride-segment synchronisation
/// authority — the retained Play, the held authoritative stream, the timeline, the drift correction
/// and `currentPlaybackIdentity` — which is precisely what `leaveSynchronizedMode()` already means.
///
/// **Ownership travels with the work.** `SessionCoordinator.endRide()` cannot `await`, so the call
/// into the coordinator crosses a scheduling hop, and by the time it resumes a second ride may have
/// started. Every ride-lifecycle decision therefore carries a strictly-increasing epoch assigned
/// **synchronously** here, on the main actor, before the hop; both `endRide(epoch:)` below and
/// `SyncPlaybackCoordinator.endRideSegment(rideEpoch:)` compare it rather than re-reading whatever
/// ride happens to be current later. That is CLAUDE.md's standing invariant applied to the ride,
/// which is a lifetime distinct from both the authenticated control generation and the playback epoch.
@MainActor
public final class RideSegmentLifecycle {
    private let syncPlayback: SyncPlaybackCoordinator

    /// Strictly increasing, bumped on **both** Start Ride and End Ride so that "a newer ride-lifecycle
    /// decision has been taken" is one comparison rather than two flags that could disagree.
    private var rideEpoch: Int64 = 0

    /// How many End Ride cleanups were refused because a newer ride-lifecycle decision had already
    /// been taken by the time they ran. Nonzero means ride 1's cleanup was correctly stopped from
    /// touching ride 2.
    public private(set) var supersededEndRideCount = 0

    public init(syncPlayback: SyncPlaybackCoordinator) {
        self.syncPlayback = syncPlayback
    }

    /// Assigns and returns this ride-lifecycle decision's epoch. Synchronous and main-actor isolated:
    /// the caller takes the epoch **before** the scheduling hop that carries the work, so the hop
    /// cannot change which ride the work belongs to.
    public func nextRideEpoch() -> Int64 {
        rideEpoch += 1
        return rideEpoch
    }

    /// `CONNECTED -> RIDE_ACTIVE`. Records the epoch and deliberately changes nothing else — a ride
    /// starting must not disturb synchronised playback, which is legitimately usable from `CONNECTED`
    /// before any ride begins.
    public func startRide(epoch: Int64) async {
        guard epoch == rideEpoch else {
            supersededEndRideCount += 1
            return
        }
        await syncPlayback.beginRideSegment(rideEpoch: epoch)
    }

    /// `RIDE_ACTIVE -> CONNECTED`. Ends ride-segment synchronisation authority, not the session.
    ///
    /// The guard is the whole point of the type: `epoch` was assigned before the hop that got here,
    /// and `rideEpoch` is read **synchronously**, with no `await` between the read and the decision.
    /// A newer ride-lifecycle decision means this cleanup belongs to a ride that is over and must
    /// touch nothing. `SyncPlaybackCoordinator.endRideSegment` re-proves the same fact for itself
    /// across the actor hop, because a check the caller performed says nothing about what suspends
    /// afterwards (ADR-024 Amendment A3 Finding B, the reason every apply path proves for itself).
    public func endRide(epoch: Int64) async {
        guard epoch == rideEpoch else {
            supersededEndRideCount += 1
            return
        }
        await syncPlayback.endRideSegment(rideEpoch: epoch)
    }
}
