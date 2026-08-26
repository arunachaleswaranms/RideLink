package com.ridelink.network.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ARCHITECTURE §4.1 / this session's brief §4F. Deterministic — an injected clock, never a real 15-minute wait. */
class DiscoveryHandleRotationPolicyTest {
    @Test
    fun `not due before the interval elapses`() {
        assertFalse(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoMs = 1_000, lastRotatedAtMonoMs = 0, intervalMs = 900_000))
        assertFalse(
            DiscoveryHandleRotationPolicy.isRotationDue(
                nowMonoMs = 899_999,
                lastRotatedAtMonoMs = 0,
                intervalMs = 900_000,
            ),
        )
    }

    @Test
    fun `due at exactly the interval`() {
        assertTrue(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoMs = 900_000, lastRotatedAtMonoMs = 0, intervalMs = 900_000))
    }

    @Test
    fun `due well past the interval`() {
        assertTrue(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoMs = 5_000_000, lastRotatedAtMonoMs = 0, intervalMs = 900_000))
    }

    @Test
    fun `default interval is exactly 15 minutes`() {
        kotlin.test.assertEquals(15 * 60 * 1000L, DiscoveryHandleRotationPolicy.ROTATION_INTERVAL_MS)
    }
}
