package com.ridelink.network.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TEST_PLAN §4 "Discovery privacy": the advertised TXT key set is **exactly** `{v, dh, plat}`.
 * Runs against [buildTxtRecord] — the same function [NsdDiscoveryController.advertise] calls —
 * so an accidental future addition (`peer_id`, a device name, a library count) fails this test,
 * not merely a manual review.
 */
class DiscoveryPrivacyTest {
    @Test
    fun `TXT key set is exactly v, dh, plat`() {
        val record = buildTxtRecord("0123456789abcdef0123456789abcdef")
        assertEquals(setOf("v", "dh", "plat"), record.keys)
    }

    @Test
    fun `plat is always android on this platform`() {
        assertEquals("android", buildTxtRecord("anything")["plat"])
    }

    @Test
    fun `no TXT value equals or contains a peer_id- or SPKI-shaped 16 or 64 hex string`() {
        val record = buildTxtRecord("0123456789abcdef0123456789abcdef")
        val peerIdShape = Regex("^[0-9a-f]{16}$")
        val spkiShape = Regex("^[0-9a-f]{64}$")
        record.values.forEach { value ->
            assertEquals(false, peerIdShape.matches(value), "TXT value '$value' looks like a peer_id")
            assertEquals(false, spkiShape.matches(value), "TXT value '$value' looks like an SPKI hash")
        }
    }
}
