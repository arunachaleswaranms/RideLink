package com.ridelink.app.sync

import java.util.concurrent.atomic.AtomicLong

/**
 * The one place the ride-segment epoch lives, published **synchronously** at the instant
 * `SessionFsm` accepts a Start Ride or an End Ride (independent-review round 5, Blocker 1;
 * ADR-028 Amendment A4). The exact mirror of iOS's `RideEpochBox`.
 *
 * **The defect it removes, which is iOS's.** Round 4 gave the ride an epoch and had
 * [SyncPlaybackCoordinator.recordRideAuthority] stamp ownership from `lastRideLifecycleEpoch` — a
 * field only a successful `beginRideSegment` could move. On iOS that call crossed an actor hop, so
 * "the ride `SessionFsm` accepted" and "the ride the coordinator knows about" were two facts with a
 * window between them, and authority a successor ride established inside that window was stamped
 * with the **predecessor** ride and destroyed by the predecessor's late cleanup.
 *
 * **Android was structurally safe and is mirrored anyway, deliberately.** Here `startRide()` calls
 * straight through on the main thread, so the window never existed. But that safety rested on an
 * *implementation* property (two synchronous statements on one dispatcher) rather than on a stated
 * invariant, which is exactly the "one platform relies on synchronous execution as an undocumented
 * accident" shape this repository's parity rule forbids. With the epoch minted and published in one
 * atomic step, both platforms make the same claim for the same reason, and neither depends on
 * scheduling.
 *
 * [AtomicLong] rather than a plain `Long`: `next()` is called from the main thread while
 * [current] is read from whatever coroutine is establishing authority. Both are main-dispatched in
 * production today, and this is what stops that from being load-bearing.
 *
 * This is **not** a second lifecycle owner. `SessionFsm` alone decides whether a ride may start or
 * end; nothing here is consulted before that decision, and [next] is only ever called after it.
 */
class RideEpochBox {
    private val value = AtomicLong(0)

    /**
     * Mints the next strictly-increasing ride epoch **and publishes it in the same step**. Called
     * once per accepted `CONNECTED -> RIDE_ACTIVE` and once per accepted `RIDE_ACTIVE -> CONNECTED`.
     */
    fun next(): Long = value.incrementAndGet()

    /**
     * The ride that is current *now* — read only where ride-scoped authority is being established,
     * adjacent to the write it labels. Never read to re-label work that was authorised earlier:
     * that is the defect this type exists to remove, not a use of it.
     */
    val current: Long get() = value.get()
}
