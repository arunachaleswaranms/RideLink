package com.ridelink.core.sync

/**
 * PROTOCOL §6 / ARCHITECTURE §7.1 clock-offset estimation. Pure: no clock reads, no I/O
 * (CLAUDE.md rule 9). Every timestamp is monotonic microseconds (never wall-clock).
 *
 * The wire exchange is `PING { t1 } -> PONG { t1, t2, t3 } -> (t4 recorded on receipt)`:
 *  - `t1` — A's monotonic send time
 *  - `t2` — B's monotonic receive time
 *  - `t3` — B's monotonic send time (reply)
 *  - `t4` — A's monotonic receive time
 *
 * `rtt = (t4-t1) - (t3-t2)`, `offset = ((t2-t1)+(t3-t4))/2` (add to A's clock to get B's).
 *
 * This estimator additionally pins two implementation parameters that ARCHITECTURE §7.1 leaves
 * as prose ("two consecutive windows agree", "expose the residual spread as jitter"): jitter is
 * `(max_rtt - min_rtt)/2` among the kept samples, and step confirmation requires the next
 * window's candidate to land within [STEP_CONFIRM_TOLERANCE_US] of the previously rejected one.
 * Neither is a wire value — only [protocol/vectors/clock/clock_vectors.json] needs both platforms
 * to agree on them, which is what pins the numbers here.
 *
 * All arithmetic is exact-rational integer arithmetic (division truncating toward zero, matching
 * Kotlin `Long` and Swift `Int64` division identically) rather than floating point, so both
 * platforms produce byte-identical results from the same vectors.
 */
object ClockSync {
    const val STEP_REJECT_THRESHOLD_US: Long = 30_000
    const val STEP_CONFIRM_TOLERANCE_US: Long = 10_000
    private const val EWMA_ALPHA_NUM: Long = 2
    private const val EWMA_ALPHA_DEN: Long = 10

    /** One `(t1,t2,t3,t4)` round trip, all monotonic microseconds. */
    data class Sample(
        val t1MonoUs: Long,
        val t2MonoUs: Long,
        val t3MonoUs: Long,
        val t4MonoUs: Long,
    ) {
        val rttUs: Long get() = (t4MonoUs - t1MonoUs) - (t3MonoUs - t2MonoUs)
        val offsetUs: Long get() = ((t2MonoUs - t1MonoUs) + (t3MonoUs - t4MonoUs)) / 2
    }

    /** Carried across windows (CONNECTING's 11-sample burst, then every 10 s per PROTOCOL §6). */
    data class EstimatorState(
        val offsetUs: Long,
        val pendingOffsetUs: Long?,
    )

    enum class WindowStatus { ACCEPTED, REJECTED_PENDING_CONFIRMATION, CONFIRMED, NO_ESTIMATE }

    /**
     * @property offsetUs the current best estimate after this window (may equal the previous
     *   state's offset when the window was rejected or produced no estimate), or `null` if there
     *   has never been a valid estimate.
     * @property rttUs / [jitterUs] this window's raw measurement, `null` when [status] is
     *   [WindowStatus.NO_ESTIMATE].
     */
    data class WindowResult(
        val status: WindowStatus,
        val offsetUs: Long?,
        val rttUs: Long?,
        val jitterUs: Long?,
        val newState: EstimatorState?,
    )

    private data class RawEstimate(
        val offsetUs: Long,
        val rttUs: Long,
        val jitterUs: Long,
        val keptCount: Int,
    )

    private fun truncDiv(
        numerator: Long,
        denominator: Long,
    ): Long = numerator / denominator // Kotlin Long division already truncates toward zero.

    private fun rawWindowEstimate(samples: List<Sample>): RawEstimate? {
        val valid = samples.filter { it.rttUs > 0 }
        if (valid.isEmpty()) return null
        val minRtt = valid.minOf { it.rttUs }
        val threshold = 2 * minRtt
        val kept = valid.filter { it.rttUs <= threshold }
        var best = kept[0]
        for (s in kept) if (s.rttUs < best.rttUs) best = s
        val rtts = kept.map { it.rttUs }
        val jitterUs = truncDiv(rtts.max() - rtts.min(), 2)
        return RawEstimate(best.offsetUs, best.rttUs, jitterUs, kept.size)
    }

    /**
     * Processes one window of samples (11 at `CONNECTING`, per PROTOCOL §6 every 10 s
     * thereafter) against the estimator's prior state. Pure and stateless itself — the caller
     * threads [EstimatorState] through successive calls.
     */
    @Suppress("ReturnCount")
    fun applyWindow(
        previous: EstimatorState?,
        samples: List<Sample>,
    ): WindowResult {
        val raw =
            rawWindowEstimate(samples)
                ?: return WindowResult(WindowStatus.NO_ESTIMATE, previous?.offsetUs, null, null, previous)

        if (previous == null) {
            val newState = EstimatorState(raw.offsetUs, pendingOffsetUs = null)
            return WindowResult(WindowStatus.ACCEPTED, raw.offsetUs, raw.rttUs, raw.jitterUs, newState)
        }

        val delta = Math.abs(raw.offsetUs - previous.offsetUs)
        if (delta <= STEP_REJECT_THRESHOLD_US) {
            val smoothed = previous.offsetUs + truncDiv((raw.offsetUs - previous.offsetUs) * EWMA_ALPHA_NUM, EWMA_ALPHA_DEN)
            val newState = EstimatorState(smoothed, pendingOffsetUs = null)
            return WindowResult(WindowStatus.ACCEPTED, smoothed, raw.rttUs, raw.jitterUs, newState)
        }

        val pending = previous.pendingOffsetUs
        if (pending != null && Math.abs(raw.offsetUs - pending) <= STEP_CONFIRM_TOLERANCE_US) {
            val smoothed = previous.offsetUs + truncDiv((raw.offsetUs - previous.offsetUs) * EWMA_ALPHA_NUM, EWMA_ALPHA_DEN)
            val newState = EstimatorState(smoothed, pendingOffsetUs = null)
            return WindowResult(WindowStatus.CONFIRMED, smoothed, raw.rttUs, raw.jitterUs, newState)
        }

        val newState = EstimatorState(previous.offsetUs, pendingOffsetUs = raw.offsetUs)
        return WindowResult(WindowStatus.REJECTED_PENDING_CONFIRMATION, previous.offsetUs, raw.rttUs, raw.jitterUs, newState)
    }
}
