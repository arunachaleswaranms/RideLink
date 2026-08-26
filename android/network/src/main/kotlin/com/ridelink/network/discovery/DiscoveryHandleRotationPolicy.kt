package com.ridelink.network.discovery

/**
 * ARCHITECTURE §4.1 / this session's brief §4F: the ephemeral discovery handle rotates whenever
 * advertising starts, and **at least every 15 minutes** while it continues. Pure so the interval
 * decision is testable against an injected clock instead of a real 15-minute wait.
 */
object DiscoveryHandleRotationPolicy {
    const val ROTATION_INTERVAL_MS: Long = 15 * 60 * 1000L

    /** @return true once at least [ROTATION_INTERVAL_MS] has elapsed since [lastRotatedAtMonoMs]. */
    fun isRotationDue(
        nowMonoMs: Long,
        lastRotatedAtMonoMs: Long,
        intervalMs: Long = ROTATION_INTERVAL_MS,
    ): Boolean = nowMonoMs - lastRotatedAtMonoMs >= intervalMs
}
