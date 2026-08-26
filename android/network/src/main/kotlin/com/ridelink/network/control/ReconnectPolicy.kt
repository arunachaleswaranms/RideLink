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
 */
class ReconnectController(
    private val scope: CoroutineScope,
    private val random: kotlin.random.Random,
    private val delayMs: suspend (Long) -> Unit,
) {
    private var job: Job? = null
    private var attempt = 0
    private var elapsedMs = 0L

    var reconnectCount: Int = 0
        private set

    /**
     * Starts (or restarts) the retry loop. [onAttempt] returns `true` on success (stops the
     * loop and resets it for next time) or `false` to keep retrying. [onExhausted] fires once
     * the 120 s budget is spent with no success.
     */
    fun start(
        onAttempt: suspend () -> Boolean,
        onExhausted: suspend () -> Unit,
    ) {
        cancel()
        job =
            scope.launch {
                while (elapsedMs < ReconnectPolicy.MAX_TOTAL_BUDGET_MS) {
                    val fraction = random.nextDouble(-1.0, 1.0)
                    val delay = ReconnectPolicy.jitteredDelayMs(attempt, fraction)
                    delayMs(delay)
                    elapsedMs += delay
                    attempt += 1
                    reconnectCount += 1
                    if (onAttempt()) {
                        reset()
                        return@launch
                    }
                }
                onExhausted()
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun reset() {
        attempt = 0
        elapsedMs = 0
    }
}
