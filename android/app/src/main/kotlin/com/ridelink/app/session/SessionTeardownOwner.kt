package com.ridelink.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * **The one place a session's teardown runs, and the one thing that says when it is over.**
 *
 * `SessionFsm` has had `ENDING -> IDLE` on `TeardownComplete` since Phase 1a and nothing in the app
 * ever emitted it (`docs/STATUS.md` §4 problem 53), so a session that ended stayed ended until the
 * user force-quit. Emitting it is easy; emitting it *truthfully* is the whole problem, because the
 * event's name is an ownership claim:
 *
 * > **`TeardownComplete` may be emitted only when every effect owned by the ending session is
 * > complete, and no continuation from that session can subsequently mutate coordinator, relay,
 * > voice, playback, discovery, foreground-service or control-session state.**
 *
 * That is what this type is for. It holds the **latest** teardown, so:
 *
 * 1. [retire] chains — a teardown waits for the previous one before it starts, so two of them can
 *    never interleave their steps on the one shared `ControlSessionManager`;
 * 2. [pending] is joinable — a *successor* session waits on it before touching anything shared, so
 *    "Session B cannot start until Session A is terminal" is a structural property rather than a
 *    claim about which coroutine happens to run first.
 *
 * **Cancellation is not completion.** A [Job] that has been cancelled has only been *asked* to
 * stop; `finally` blocks, `awaitClose` handlers and non-cancellable platform calls all still run
 * (ADR-024 Amendment A3 records what that cost Phase 5). Every caller therefore uses
 * `cancelAndJoin`, never `cancel` — joining is the proof, and cancelling is only what makes the
 * proof arrive promptly.
 *
 * `RideLinkPlatform.SessionTeardownOwner` is the mirror. It lives in the Swift *package* rather than
 * beside the iOS coordinator because `ios/RideLink.xcodeproj` still has no test target over the app's
 * own Swift sources (`docs/STATUS.md` §4 problem 22); this one lives beside the Android coordinator
 * because `:app` has unit tests that drive it directly.
 */
class SessionTeardownOwner(
    private val scope: CoroutineScope,
) {
    /**
     * The most recent teardown, or null if no session has ever been retired. Join it — do not
     * inspect it: "has it completed" is a question whose answer can change between asking and
     * acting on it, and `join()` is the form that cannot.
     */
    var pending: Job? = null
        private set

    /**
     * Runs [body] as **the** teardown of the session being retired, after any earlier teardown has
     * finished.
     *
     * The returned job completes only when [body] has returned, so joining it is exactly the
     * guarantee at the top of this file. Callers must have already detached, *synchronously*,
     * everything the retiring session owns — this runs after a dispatch, and anything it reads from
     * live state at that point could belong to a successor.
     */
    fun retire(body: suspend () -> Unit): Job {
        val previous = pending
        val job =
            scope.launch {
                previous?.join()
                body()
            }
        pending = job
        return job
    }
}
