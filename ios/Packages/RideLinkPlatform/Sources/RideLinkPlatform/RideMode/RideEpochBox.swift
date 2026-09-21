import Foundation

/// The one place the ride-segment epoch lives, published **synchronously** at the instant
/// `SessionFsm` accepts a Start Ride or an End Ride (independent-review round 5, Blocker 1;
/// ADR-028 Amendment A4).
///
/// **Why this is not just a counter on `RideSegmentLifecycle`.** Round 4 gave the ride an epoch and
/// had `SyncPlaybackCoordinator.recordRideAuthority` stamp ownership from
/// `lastRideLifecycleEpoch` — a field only a successful `beginRideSegment` could move, and
/// `beginRideSegment` reached the coordinator across an actor hop. So the accepted ride and the
/// *installed* ride were two different facts with a window between them, and every piece of
/// authority established inside that window was stamped with the **predecessor** ride:
///
/// ```
/// ride 1 established X                 rideAuthorityEpoch = 1
/// End Ride  accepted, epoch 2          cleanup parked in launchInSession
/// Start Ride accepted, epoch 3         beginRideSegment(3) parked too
/// ride 2 established Y                 rideAuthorityEpoch = 1   ← the defect
/// End Ride(2) finally runs             1 <= 2, so it cleared Y
/// ```
///
/// That is the repository's standing lifetime rule violated in its own fix: ownership was
/// reconstructed from a mutable live value that had not caught up yet, rather than travelling with
/// the work. `RideEpochBox` removes the window rather than widening a comparison — `next()` mints
/// **and publishes** in one lock-held step, on the main actor, before any continuation the ride
/// hands off can run, so there is no instant at which a ride has been accepted and the coordinator
/// does not know it. `beginRideSegment` is gone for the same reason: with the epoch already
/// installed, Start Ride had literally nothing left to do asynchronously, and a Start Ride that
/// defers nothing cannot be overtaken.
///
/// Both ride-boundary properties then hold by construction rather than by ordering luck:
///
/// - **Property A** — authority ride 2 establishes is stamped with ride 2's epoch, so ride 1's late
///   `endRideSegment` finds a strictly newer owner and refuses.
/// - **Property B** — if ride 2 establishes nothing, what stands is still stamped ride 1, so ride
///   1's late cleanup clears it exactly as it must.
///
/// `@unchecked Sendable` confined to a single `Int64` that is only ever touched under `lock`, the
/// same shape `SeqCounter` and `AuthenticatedConnectionBox` already use in this package.
///
/// This is **not** a second lifecycle owner. `SessionFsm` alone decides whether a ride may start or
/// end; nothing here is consulted before that decision, and `next()` is only ever called after it.
public final class RideEpochBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0

    public init() {}

    /// Mints the next strictly-increasing ride epoch **and publishes it in the same step**. Called
    /// once per accepted `CONNECTED -> RIDE_ACTIVE` and once per accepted `RIDE_ACTIVE ->
    /// CONNECTED`, synchronously, before either hands any work to a continuation.
    public func next() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }

    /// The ride that is current *now* — read only where ride-scoped authority is being established,
    /// adjacent to the write it labels. Never read to re-label work that was authorised earlier:
    /// that is the defect this type exists to remove, not a use of it.
    public var current: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
