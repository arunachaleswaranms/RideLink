package com.ridelink.core.sync

/**
 * ARCHITECTURE §7.1's `session_time = local_mono + offset_to_leader`, and §7.2's scheduling lead.
 * Pure: no clock reads, no I/O (CLAUDE.md rule 9), and **no wall-clock arithmetic anywhere** — every
 * value here is monotonic or session microseconds, which is why the field names carry `_mono_us`
 * and `_session_us` rather than a bare `time` (PROTOCOL §2 rule 5).
 *
 * The session clock is *the leader's monotonic clock*. The leader's own offset is therefore exactly
 * zero and it needs no estimate to schedule; a follower's offset is [ClockSync]'s estimate of "add
 * this to my clock to get the peer's". There is one estimator ([ClockSync]) and one mapping (this),
 * and nothing else in the codebase may convert between the two timebases.
 */
object SessionClock {
    /** ARCHITECTURE §7.2 / ADR-004: the floor of `LEAD = max(120 ms, 4 x rtt_p95)`. */
    const val MIN_LEAD_US: Long = 120_000

    /** ARCHITECTURE §7.2 / ADR-004: the multiplier applied to `rtt_p95`. */
    const val LEAD_RTT_MULTIPLIER: Long = 4

    /**
     * Defensive ceiling on the computed lead. A pathological `rtt_p95` (a peer stalled for seconds
     * behind a saturated AP) would otherwise schedule a `PLAY` minutes into the future, which reads
     * to the user as "the button did nothing". Capped instead, and the cap is a *scheduling*
     * decision only — it never suppresses the command.
     */
    const val MAX_LEAD_US: Long = 2_000_000

    /**
     * `session_us = local_mono_us + offset_to_leader_us`. On the leader `offsetToLeaderUs` is 0 and
     * this is the identity.
     */
    fun sessionUs(
        localMonoUs: Long,
        offsetToLeaderUs: Long,
    ): Long = localMonoUs + offsetToLeaderUs

    /** The inverse of [sessionUs] — the conversion every scheduled deadline goes through. */
    fun localMonoUs(
        sessionUs: Long,
        offsetToLeaderUs: Long,
    ): Long = sessionUs - offsetToLeaderUs

    /**
     * `LEAD = max(120 ms, 4 x rtt_p95)`, clamped by [MAX_LEAD_US].
     *
     * A `null` [rttP95Us] means no round trip has been measured yet, which yields the floor rather
     * than a fabricated zero — the floor is the safe answer either way, and pretending 0 us of RTT
     * had been *measured* is the kind of quiet lie ADR-016's `assumed`/`measured` split exists to
     * prevent.
     */
    fun leadUs(rttP95Us: Long?): Long {
        val rtt = (rttP95Us ?: 0L).coerceAtLeast(0L)
        val scaled = if (rtt > MAX_LEAD_US) MAX_LEAD_US else rtt * LEAD_RTT_MULTIPLIER
        return scaled.coerceIn(MIN_LEAD_US, MAX_LEAD_US)
    }
}

/**
 * What the session clock currently knows, as one immutable value the playback layer reads.
 *
 * [ready] is the gate this phase's brief §7 requires: a synchronised command is never scheduled
 * against a clock the estimator has not accepted. It is **not** the same as `offsetToLeaderUs != null`
 * — an unconfirmed 30 ms step ([ClockSync.WindowStatus.REJECTED_PENDING_CONFIRMATION]) leaves the
 * last accepted offset in place for playback already in flight while refusing to authorise anything
 * new, which is exactly the distinction between "we have a number" and "we trust it".
 *
 * @property offsetToLeaderUs add to local monotonic microseconds to get session microseconds; always
 *   exactly 0 on the leader, which is why the leader is [ready] the moment it is elected.
 */
data class SessionClockEstimate(
    val offsetToLeaderUs: Long,
    val rttP95Us: Long?,
    val ready: Boolean,
) {
    val leadUs: Long get() = SessionClock.leadUs(rttP95Us)

    fun sessionUs(localMonoUs: Long): Long = SessionClock.sessionUs(localMonoUs, offsetToLeaderUs)

    fun localMonoUs(sessionUs: Long): Long = SessionClock.localMonoUs(sessionUs, offsetToLeaderUs)

    companion object {
        /** The leader's own view: the session clock *is* its monotonic clock, so no estimate is needed. */
        fun leader(rttP95Us: Long?): SessionClockEstimate = SessionClockEstimate(0L, rttP95Us, ready = true)
    }
}
