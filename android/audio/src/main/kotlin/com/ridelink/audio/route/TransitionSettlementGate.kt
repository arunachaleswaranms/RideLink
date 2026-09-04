package com.ridelink.audio.route

import kotlinx.coroutines.CompletableDeferred

/**
 * The suspend/resume half of "wait for the platform's confirming callback, or the failure-protection
 * timeout, before doing anything that would stop either one from arriving" — pulled out of
 * [AndroidVoiceAudioSession] so the *ordering* itself is provable by a JVM unit test.
 * [AndroidVoiceAudioSession] cannot be constructed off-device at all (it opens a real
 * `AudioManager` in its constructor), so this class holds no Android type and makes no decision of
 * its own: [isSettled] reads state [AndroidVoiceAudioSession] already owns via
 * `AudioSessionLifecycle`, and this class only owns the plumbing that lets a suspended `close()`
 * resume exactly once that state says settled.
 *
 * This phase's final route-close hardening pass: the previous close order unregistered
 * `OnCommunicationDeviceChangedListener` immediately after making the platform calls that could
 * provoke it, not after the confirmation those calls could still deliver *asynchronously*. A normal,
 * successful platform confirmation arriving after that point had nowhere left to land, and the
 * five-second [com.ridelink.core.audiopolicy.RouteTransitionTracker] timeout — meant only as failure
 * protection — silently became the ordinary close path instead.
 */
internal class TransitionSettlementGate(
    private val isSettled: () -> Boolean,
) {
    private var pending: CompletableDeferred<Unit>? = null

    /**
     * Suspends until [isSettled] is true, returning immediately without ever suspending if it
     * already is — the case where the platform's confirming callback fired synchronously, inside the
     * platform call that requested the change, before this was ever reached.
     */
    suspend fun awaitSettled() {
        if (isSettled()) return
        val deferred = CompletableDeferred<Unit>()
        pending = deferred
        deferred.await()
    }

    /**
     * Call once after every event reaches the shared reducer — from the platform's confirming
     * callback and from the failure-protection timeout alike. A no-op unless both [isSettled] is now
     * true and a [awaitSettled] call is actually waiting, so an event that does not settle anything,
     * or one that arrives after [awaitSettled] already returned synchronously, does nothing.
     */
    fun onSettlementObserved() {
        if (!isSettled()) return
        val deferred = pending ?: return
        pending = null
        deferred.complete(Unit)
    }
}
