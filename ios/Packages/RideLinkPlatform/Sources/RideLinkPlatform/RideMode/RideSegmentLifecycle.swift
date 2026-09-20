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
/// **synchronously** here, on the main actor, before the hop; `SyncPlaybackCoordinator` compares it
/// rather than re-reading whatever ride happens to be current later. That is CLAUDE.md's standing
/// invariant applied to the ride, which is a lifetime distinct from both the authenticated control
/// generation and the playback epoch.
///
/// **Independent-review round 4, Blocker 1: an accepted End Ride establishes the clean boundary, and
/// a Start Ride that merely got there first does not excuse it from doing so.** Round 3 read the hop
/// as "ride 2 is current, so ride 1's cleanup is stale" and refused it outright. That is half the
/// rule. A ride boundary owes two properties, and they are not the same property:
///
/// - **Property A** — ride 1's cleanup must never destroy ride 2's state.
/// - **Property B** — ride 1's state must never survive into ride 2 because its cleanup was delayed.
///
/// `startRide` deliberately establishes nothing (synchronised playback is legitimately usable from
/// `CONNECTED`), so "ride 2 is current" and "ride 2 owns something" are different facts, and only the
/// second one may excuse the boundary. `endRide` below therefore always forwards, and
/// `SyncPlaybackCoordinator.endRideSegment` — the one place that can see *whose* authority is
/// standing — decides. Neither property is bought by weakening the other.
///
/// This type owns ride-segment cleanup **ordering and ownership** and nothing else: it is not a
/// second `SessionFsm`, holds no session or navigation state, and never decides whether a ride may
/// start or end. That remains `SessionFsm`'s, proved before either call below is reached.
@MainActor
public final class RideSegmentLifecycle {
    private let syncPlayback: SyncPlaybackCoordinator

    /// Strictly increasing, bumped on **both** Start Ride and End Ride so that "a newer ride-lifecycle
    /// decision has been taken" is one comparison rather than two flags that could disagree.
    private var rideEpoch: Int64 = 0

    /// How many ride-lifecycle effects were refused because a newer ride had already taken over.
    ///
    /// Two sources, deliberately counted together because they mean the same thing: a stale
    /// `startRide` (refused here, since a Start Ride establishes nothing and re-recording an older
    /// epoch could only move the current ride backwards), and an End Ride the coordinator refused
    /// because a strictly newer ride had already established synchronisation authority of its own
    /// (independent-review round 4, Blocker 1). Nonzero means ride 1's work was correctly stopped
    /// from touching ride 2.
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
    /// **Independent-review round 4, Blocker 1: this no longer refuses a superseded End Ride, and
    /// that is the fix.** It used to `guard epoch == rideEpoch`, which reads "a newer ride-lifecycle
    /// decision has been taken, so this cleanup is stale". That protected ride 2 (Property A) and
    /// broke Property B in the same statement: `startRide` deliberately establishes nothing, so a
    /// Start Ride pressed before this cleanup ran made it "stale" while leaving **ride 1's**
    /// `currentPlaybackIdentity` standing as the only thing ride 2 had to report. An accepted End
    /// Ride that never cleans up is not a safe End Ride; it is a lost one.
    ///
    /// Refusing is still necessary — it just needs a different question, and only the coordinator
    /// can answer it, because only the coordinator knows whether a *newer ride has established
    /// authority of its own*. So this is now a forwarder: the boundary always reaches the one owner
    /// of ride-segment authority, which compares the ride that established what is standing against
    /// the ride this boundary belongs to. `RideBoundaryOutcome` is that answer coming back, and
    /// [supersededEndRideCount] counts it rather than deciding it.
    ///
    /// The epoch is still assigned **synchronously**, before the hop that carries the call here, and
    /// is compared rather than re-read — ADR-024 Amendment A5's rule applied to the ride lifetime.
    /// What moved is *what* it is compared against, not whether it travels with the work.
    public func endRide(epoch: Int64) async {
        let outcome = await syncPlayback.endRideSegment(rideEpoch: epoch)
        if outcome == .supersededByLiveRideAuthority { supersededEndRideCount += 1 }
    }
}
