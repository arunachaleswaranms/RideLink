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
 * A [StopReleaseResult.TimedOut] is never treated as proof the microphone is safe to reclaim: the
 * stop is skipped. **But a slow release is not a timed-out one** (STATUS §4 problem 129). The
 * production `endIntercomAndAwaitRelease` (`SessionCoordinator`'s) used to return `TimedOut` after a
 * 5 s caller window that is structurally at or before the release's own route-settlement window, and
 * this owner then never asked again — so an End whose route confirmation was late left the
 * `microphone` service and an *Intercom on — Starting the microphone…* notification behind, with
 * capture already released. It now waits for that same release to finish and only then answers, so
 * `TimedOut` reaches here only from a caller that genuinely gave up.
 *
 * [recordDecision] reports which branch was taken, because problem 129 was undiagnosable without
 * it. A closed enum rather than a string, so this cannot become a second free-text log path.
 */
class IntercomStopOwner(
    private val scope: CoroutineScope,
    private val endIntercomAndAwaitRelease: suspend () -> StopReleaseResult,
    private val releaseForegroundService: () -> Unit,
    private val recordDecision: (IntercomServiceDecision) -> Unit = {},
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
                    if (startEpoch.get() == stoppingEpoch) {
                        recordDecision(IntercomServiceDecision.RELEASED)
                        releaseForegroundService()
                    } else {
                        recordDecision(IntercomServiceDecision.KEPT_FOR_NEWER_START)
                    }
                StopReleaseResult.TimedOut -> recordDecision(IntercomServiceDecision.KEPT_RELEASE_UNPROVEN)
            }
        }
    }
}

/** What [IntercomStopOwner] did with the foreground service after an End (STATUS §4 problem 129). */
enum class IntercomServiceDecision {
    /** Capture is released and no newer start exists: the intercom's hold on the service was dropped. */
    RELEASED,

    /** Capture is released, but the user started the intercom again meanwhile; the new one keeps it. */
    KEPT_FOR_NEWER_START,

    /** The release was not proven, so the microphone may still be held; the service is kept. */
    KEPT_RELEASE_UNPROVEN,
}
