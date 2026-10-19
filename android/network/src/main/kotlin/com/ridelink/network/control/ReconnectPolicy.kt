package com.ridelink.network.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * the attempt counter, [reconnectCount], [onExhausted] — is made only while its run is still the
 * current one, so a run that has ended can touch nothing, whether or not it was cancelled.
 */
class ReconnectController(
    private val scope: CoroutineScope,
    private val random: kotlin.random.Random,
    private val delayMs: suspend (Long) -> Unit,
) {
    /** Guards every field below: the manager's scope may be multi-threaded (its tests use IO). */
    private val lock = Any()
    private var job: Job? = null
    private var lastRun = NO_RUN

    /** The run that owns the budget, or [NO_RUN]. A run that is not this one may write nothing. */
    private var currentRun = NO_RUN

    /** The run whose `onAttempt` is executing right now, or [NO_RUN]; see [connectionPromoted]. */
    private var attemptInFlightRun = NO_RUN
    private var attempt = 0
    private var elapsedMs = 0L

    @Volatile
    var reconnectCount: Int = 0
        private set

    /**
     * Starts (or restarts) the retry loop. [onAttempt] returns `true` on success (stops the
     * loop and resets it for next time) or `false` to keep retrying. [onExhausted] fires once
     * the 120 s budget is spent with no success — and never for a run that has ended.
     *
     * A restart continues the budget it finds; only a promoted connection ([connectionPromoted])
     * or [reset] refreshes it. Every production [start] follows a promotion (`RECONNECTING` is
     * reachable only from a connected session), so each link loss gets a full budget.
     */
    fun start(
        onAttempt: suspend () -> Boolean,
        onExhausted: suspend () -> Unit,
    ) {
        cancel()
        synchronized(lock) {
            lastRun += 1
            val run = lastRun
            currentRun = run
            job = scope.launch { runLadder(run, onAttempt, onExhausted) }
        }
    }

    private suspend fun runLadder(
        run: Long,
        onAttempt: suspend () -> Boolean,
        onExhausted: suspend () -> Unit,
    ) {
        while (true) {
            val delay = nextDelayIfOwned(run) ?: break
            delayMs(delay)
            if (!beginAttemptIfOwned(run, delay)) return
            val succeeded =
                try {
                    onAttempt()
                } finally {
                    synchronized(lock) { if (attemptInFlightRun == run) attemptInFlightRun = NO_RUN }
                }
            if (succeeded) {
                synchronized(lock) { if (currentRun == run) endRunLocked() }
                return
            }
        }
        val exhausted = synchronized(lock) { (currentRun == run).also { owned -> if (owned) currentRun = NO_RUN } }
        if (exhausted) onExhausted()
    }

    /** Null when the budget is spent or [run] has ended. */
    private fun nextDelayIfOwned(run: Long): Long? =
        synchronized(lock) {
            if (currentRun != run || elapsedMs >= ReconnectPolicy.MAX_TOTAL_BUDGET_MS) {
                null
            } else {
                ReconnectPolicy.jitteredDelayMs(attempt, random.nextDouble(-1.0, 1.0))
            }
        }

    private fun beginAttemptIfOwned(
        run: Long,
        delay: Long,
    ): Boolean =
        synchronized(lock) {
            if (currentRun != run) return@synchronized false
            elapsedMs += delay
            attempt += 1
            reconnectCount += 1
            attemptInFlightRun = run
            true
        }

    /**
     * A connection was promoted — by this ladder's own attempt, by the peer dialling in, or by a
     * first dial — so whatever run is in progress is over, and the next link loss starts from a
     * full budget.
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
                val running = job.takeIf { currentRun != NO_RUN && attemptInFlightRun != currentRun }
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
                currentRun = NO_RUN
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
        currentRun = NO_RUN
        attempt = 0
        elapsedMs = 0
    }

    private companion object {
        const val NO_RUN = 0L
    }
}
