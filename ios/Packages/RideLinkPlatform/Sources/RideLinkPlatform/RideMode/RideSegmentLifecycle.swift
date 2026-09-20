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
/// **Independent-review round 5, Blocker 1: the epoch is published where it is accepted.** See
/// `nextRideEpoch()` and `RideEpochBox` — round 4's ownership rule was right and the value it read
/// was stale, because installing the accepted ride was itself asynchronous work a successor could
/// overtake. `startRide`/`beginRideSegment` are gone; a Start Ride's entire synchronisation-lifetime
/// effect is the synchronous epoch publication, so this type now owns exactly one boundary.
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

    /// How many End Ride boundaries the coordinator refused because a strictly newer ride had
    /// already established synchronisation authority of its own (independent-review round 4,
    /// Blocker 1). Nonzero means ride 1's cleanup was correctly stopped from touching ride 2.
    public private(set) var supersededEndRideCount = 0

    public init(syncPlayback: SyncPlaybackCoordinator) {
        self.syncPlayback = syncPlayback
    }

    /// Assigns and **publishes** this ride-lifecycle decision's epoch, in one step.
    ///
    /// **Independent-review round 5, Blocker 1: this is now the whole of Start Ride's effect on the
    /// synchronisation lifetime, and that is the fix.** Round 4 had it mint a private counter here
    /// and then carry the value to `SyncPlaybackCoordinator.beginRideSegment` across an actor hop,
    /// so the ride `SessionFsm` had accepted and the ride the coordinator knew about were two facts
    /// with a window between them — and `recordRideAuthority` stamped ownership from the second.
    /// Authority ride 2 established inside that window was therefore labelled **ride 1**, and ride
    /// 1's late `endRideSegment` then cleared it.
    ///
    /// `RideEpochBox.next()` mints and publishes under one lock, synchronously, on the main actor,
    /// before the caller hands anything to a continuation. `beginRideSegment` is gone because there
    /// is nothing left for it to install: a Start Ride establishes no authority (synchronised
    /// playback is legitimately usable from `CONNECTED`), so once the epoch is published its
    /// asynchronous half was empty. A Start Ride that defers nothing cannot be overtaken.
    public func nextRideEpoch() -> Int64 {
        syncPlayback.rideEpochs.next()
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
