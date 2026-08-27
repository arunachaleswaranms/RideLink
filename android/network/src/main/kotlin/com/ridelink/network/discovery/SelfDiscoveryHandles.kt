package com.ridelink.network.discovery

/**
 * Tracks which discovery handle(s) this process currently considers "itself", across a `dh`
 * rotation (this session's brief §8).
 *
 * A momentary self-discovery race exists because unregistering the old mDNS advertisement is
 * asynchronous: [rotate] flips the "current" handle synchronously, but the old advertisement can
 * still be resolvable on the network — via another device's cache, or a resolution already in
 * flight — for a short window afterward. If self-filtering only ever compared against the new
 * "current" handle during that window, a resolution carrying the *old* handle would fail the
 * self-check and RideLink could briefly discover itself as a peer. Keeping the previous handle
 * recognised as self until its own advertisement is confirmed gone ([clearPrevious]) closes that
 * window.
 *
 * Pure and `android.*`-free by design (this session's brief §8's "add a pure testable concept"),
 * so the rotation-transition invariant is a fast JVM unit test rather than something only
 * observable on-device.
 */
class SelfDiscoveryHandles {
    @Volatile
    private var current: String? = null

    @Volatile
    private var previous: String? = null

    /** The handle this controller is currently advertising, or `null` before the first [rotate]. */
    val currentHandle: String? get() = current

    /** Called when a new `dh` becomes active — the initial advertise, and every later rotation. */
    fun rotate(newHandle: String) {
        previous = current
        current = newHandle
    }

    /**
     * Called once the *old* advertisement is confirmed gone (its `RegistrationListener` reports
     * `onServiceUnregistered`/`onUnregistrationFailed`) — or, as a bounded fallback, once the
     * caller decides the transition window has closed. Never removes [current], only [previous].
     */
    fun clearPrevious() {
        previous = null
    }

    /** `true` for the active handle *or* the immediately-previous one, during a transition. */
    fun isSelf(candidate: String): Boolean = candidate == current || candidate == previous

    /** Advertising stopped entirely: neither handle is self any more. */
    fun reset() {
        current = null
        previous = null
    }
}
