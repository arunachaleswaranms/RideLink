package com.ridelink.network.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PROTOCOL §10: `0.5, 1, 2, 4, 8, 8, 8 … s` with ±20% jitter, budget 120 s total, then
 * `DISCONNECTED`. Pure — no clock reads, no [kotlinx.coroutines.delay] — so it is testable
 * without `Thread.sleep()` (CLAUDE.md rule 12 / this session's brief §12).
 */
object ReconnectPolicy {
    // PROTOCOL §10's ladder, spelled out literally — a formula would obscure the exact spec values.
    @Suppress("MagicNumber")
    private val LADDER_MS = longArrayOf(500, 1000, 2000, 4000, 8000)
    const val MAX_TOTAL_BUDGET_MS = 120_000L
    const val JITTER_FRACTION = 0.2

    /** `attempt` is 0-indexed; the ladder holds at its last (8 s) rung beyond its own length. */
    fun baseDelayMs(attempt: Int): Long = LADDER_MS.getOrElse(attempt) { LADDER_MS.last() }

    /** @param randomFraction in `[-1.0, 1.0]`, supplied by the caller's injected [kotlin.random.Random]. */
    fun jitteredDelayMs(
        attempt: Int,
        randomFraction: Double,
    ): Long {
        require(randomFraction in -1.0..1.0) { "randomFraction must be in [-1, 1]" }
        val base = baseDelayMs(attempt)
        val jitter = (base * JITTER_FRACTION * randomFraction).toLong()
        return (base + jitter).coerceAtLeast(0)
    }
}

/** Why a reconnect loop did or did not start (ARCHITECTURE §3 rule 6 / PROTOCOL §4.2, §4.6). */
enum class LinkLossReason { NETWORK, BYE, DUPLICATE_CONNECTION, USER_ENDED }

/**
 * The reconnect ladder's test seams on [ControlSessionManager]. Production passes nothing and gets
 * real [delay] and no hook.
 *
 * @property delayMs how the ladder waits between attempts; a test that must watch a whole 120 s
 *   budget play out supplies a faster clock.
 * @property exhaustionHook runs after a ladder has decided its budget is spent and before that
 *   decision may take effect; a test holds it to place a promotion between the two.
 */
class ReconnectSeams(
    val delayMs: suspend (Long) -> Unit = { ms -> delay(ms) },
    val exhaustionHook: suspend () -> Unit = {},
)

/**
 * One run of the reconnect ladder: the ownership token a run's writes are checked against, and the
 * one an exhaustion hands to its caller so the caller can commit it ([ReconnectController.commitExhaustion]).
 * Compared by identity; it carries nothing, because "whose?" is the only question it answers.
 */
class ReconnectRun internal constructor()

/**
 * Drives [ReconnectPolicy]'s ladder against real time via an injectable delay function, so unit
 * tests can replace [delayMs] with a no-op recorder instead of sleeping (CLAUDE.md rule 12).
 * `BYE`, `duplicate_connection` and a deliberate user end never reach this controller at all —
 * the caller (network) checks [LinkLossReason] before starting it, mirroring the FSM's own
 * suppression (`core.sessionfsm` `LinkLost(BYE)` / `DuplicateConnectionClosed`).
 *
 * **A ladder belongs to one run, and a run ends the moment any connection is promoted**
 * (STATUS §4 problem 125). The ladder is not the only way a reconnect completes: the peer's own
 * ladder can dial in, and that inbound promotion reconnects the session without this ladder's
 * attempt ever succeeding. [connectionPromoted] is therefore the one end of a run that is not the
 * ladder's own — it used to be a bare [reset], which left the ladder dialling inside the live
 * session with a freshly zeroed budget, exhausting 120 s later into a connected session and
 * leaving the *next* link loss with no budget at all. Every write the loop makes — the budget,
 * the attempt counter, [reconnectCount] — is made only while its run is still the current one, so
 * a run that has ended can touch nothing, whether or not it was cancelled.
 *
 * **Exhaustion is a decision here and a commit at the caller.** Deciding the budget is spent and
 * acting on it are two steps, and a promotion can land between them. So `onExhausted` receives the
 * deciding [ReconnectRun] and must commit it with [commitExhaustion] inside the caller's own
 * connection-ownership critical section — the same one in which the caller's promotion calls
 * [connectionPromoted]. Exactly one of the two then ends the run, and the loser acts on nothing.
 */
class ReconnectController(
    private val scope: CoroutineScope,
    private val random: kotlin.random.Random,
    private val delayMs: suspend (Long) -> Unit,
) {
    /** Guards every field below: the manager's scope may be multi-threaded (its tests use IO). */
    private val lock = Any()
    private var job: Job? = null

    /** The run that owns the budget, or null. A run that is not this one may write nothing. */
    private var currentRun: ReconnectRun? = null

    /** The run whose `onAttempt` is executing right now, or null; see [connectionPromoted]. */
    private var attemptInFlightRun: ReconnectRun? = null
    private var attempt = 0
    private var elapsedMs = 0L

    @Volatile
    var reconnectCount: Int = 0
        private set

    /**
     * Starts (or restarts) the retry loop. [onAttempt] returns `true` on success (stops the
     * loop and resets it for next time) or `false` to keep retrying. [onExhausted] is called once
     * the 120 s budget is spent with no success, never for a run that has already ended, and must
     * [commitExhaustion] before acting (see the class comment).
     *
     * A restart continues the budget it finds; only a promoted connection ([connectionPromoted])
     * or [reset] refreshes it. Every production [start] follows a promotion (`RECONNECTING` is
     * reachable only from a connected session), so each link loss gets a full budget.
     */
    fun start(
        onAttempt: suspend () -> Boolean,
        onExhausted: suspend (ReconnectRun) -> Unit,
    ) {
        cancel()
        synchronized(lock) {
            val run = ReconnectRun()
            currentRun = run
            job = scope.launch { runLadder(run, onAttempt, onExhausted) }
        }
    }

    private suspend fun runLadder(
        run: ReconnectRun,
        onAttempt: suspend () -> Boolean,
        onExhausted: suspend (ReconnectRun) -> Unit,
    ) {
        while (true) {
            val delay = nextDelayIfOwned(run) ?: break
            delayMs(delay)
            if (!beginAttemptIfOwned(run, delay)) return
            val succeeded =
                try {
                    onAttempt()
                } finally {
                    synchronized(lock) { if (attemptInFlightRun === run) attemptInFlightRun = null }
                }
            if (succeeded) {
                synchronized(lock) { if (currentRun === run) endRunLocked() }
                return
            }
        }
        // Only a decision: the run stays current until the caller commits it.
        if (synchronized(lock) { currentRun === run }) onExhausted(run)
    }

    /** Null when the budget is spent or [run] has ended. */
    private fun nextDelayIfOwned(run: ReconnectRun): Long? =
        synchronized(lock) {
            if (currentRun !== run || elapsedMs >= ReconnectPolicy.MAX_TOTAL_BUDGET_MS) {
                null
            } else {
                ReconnectPolicy.jitteredDelayMs(attempt, random.nextDouble(-1.0, 1.0))
            }
        }

    private fun beginAttemptIfOwned(
        run: ReconnectRun,
        delay: Long,
    ): Boolean =
        synchronized(lock) {
            if (currentRun !== run) return@synchronized false
            elapsedMs += delay
            attempt += 1
            reconnectCount += 1
            attemptInFlightRun = run
            true
        }

    /**
     * Ends [run] as exhausted if, and only if, it is still the run that owns the budget, and says
     * whether it did. False means a promotion, a restart or a cancel ended it first, and the
     * exhaustion must have no effect. The spent budget is left spent: only a promotion refreshes it.
     */
    fun commitExhaustion(run: ReconnectRun): Boolean =
        synchronized(lock) {
            if (currentRun !== run) return@synchronized false
            currentRun = null
            job = null
            true
        }

    /**
     * A connection was promoted — by this ladder's own attempt, by the peer dialling in, or by a
     * first dial — so whatever run is in progress is over, and the next link loss starts from a
     * full budget. The caller calls this inside the same critical section in which it takes
     * ownership of the connection, so a pending exhaustion either committed before it or cannot.
     *
     * A run parked in its delay holds nothing and is cancelled. A run whose attempt is in flight is
     * **not** cancelled: that attempt may be the very coroutine performing this promotion (its own
     * dial won), or may be partway through a handshake whose socket only it closes. It is retired
     * instead — its run is no longer current — and it ends at its next ownership check without
     * writing anything. Either way, correctness rests on the ownership check, not on cancellation.
     */
    fun connectionPromoted() {
        val parked =
            synchronized(lock) {
                val running = job.takeIf { currentRun != null && attemptInFlightRun !== currentRun }
                endRunLocked()
                running
            }
        parked?.cancel()
    }

    fun cancel() {
        val ending =
            synchronized(lock) {
                val running = job
                job = null
                currentRun = null
                running
            }
        ending?.cancel()
    }

    /** Refreshes the budget. Call only with no run in progress (after [cancel]); see [connectionPromoted]. */
    fun reset() {
        synchronized(lock) {
            attempt = 0
            elapsedMs = 0
        }
    }

    private fun endRunLocked() {
        job = null
        currentRun = null
        attempt = 0
        elapsedMs = 0
    }
}
