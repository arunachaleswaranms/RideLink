package com.ridelink.app.service

import com.ridelink.network.voice.StopReleaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The one owner of "end the intercom, then let the foreground service drop its `microphone` type".
 *
 * Releasing capture is asynchronous (`endIntercomAndAwaitRelease` suspends until
 * `engine.release()`/`audioSession.close()` have actually completed), and the service may only be
 * told the intercom ended once that is true — a microphone service with no microphone, or a
 * microphone with no service, are the two orphans ARCHITECTURE §6.4's failure table forbids.
 *
 * The await runs on the **process** scope it is given, never on its caller's. The in-app End
 * button used to run the same await on `MainActivity.lifecycleScope`: an Activity recreated or
 * destroyed inside the release window (rotation, a theme change, the system finishing it)
 * cancelled the coroutine after the release had been queued, so capture was released and
 * [releaseForegroundService] never ran — the service stayed foreground with the `microphone` type
 * and its ongoing notification until the process died. Every entry point (the in-app button and
 * the notification's End action) now calls [requestStop], so none of them can reintroduce that.
 *
 * A stop belongs to the intercom it was requested against. If the user starts the intercom again
 * while that release is still pending, the late release must not strip the `microphone` type from
 * the **new** intercom's service — capture would then be silenced once the screen locks. So every
 * start is recorded with [noteStart], a stop captures the start epoch it was asked to end, and its
 * release applies only if no start has happened since.
 *
 * A timed-out release is never treated as proof the microphone is safe to reclaim: the stop is
 * skipped, and the diagnostics card already shows the stalled route transition.
 */
class IntercomStopOwner(
    private val scope: CoroutineScope,
    private val endIntercomAndAwaitRelease: suspend () -> StopReleaseResult,
    private val releaseForegroundService: () -> Unit,
) {
    private val startEpoch = AtomicLong(0)

    /** Records that a new intercom has been started, superseding any stop still waiting to release. */
    fun noteStart() {
        startEpoch.incrementAndGet()
    }

    fun requestStop(): Job {
        val stoppingEpoch = startEpoch.get()
        return scope.launch {
            when (endIntercomAndAwaitRelease()) {
                StopReleaseResult.Released, StopReleaseResult.AlreadyReleased ->
                    if (startEpoch.get() == stoppingEpoch) releaseForegroundService()
                StopReleaseResult.TimedOut -> Unit
            }
        }
    }
}
