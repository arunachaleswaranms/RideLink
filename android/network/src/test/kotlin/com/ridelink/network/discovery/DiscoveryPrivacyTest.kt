package com.ridelink.network.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TEST_PLAN §4 "Discovery privacy": the advertised TXT key set is **exactly** `{v, dh, plat}`,
 * and (this session's brief §6) the Bonjour/mDNS **instance name** carries no device model,
 * manufacturer, user-chosen name, username or other durable identifier either. Runs against
 * [buildTxtRecord] / [instanceServiceName] — the same functions [NsdDiscoveryController.advertise]
 * calls — so an accidental future addition (`peer_id`, a device name, a library count) fails this
 * test, not merely a manual review.
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

    @Test
    fun `instance service name contains no known device or user values`() {
        val deviceValues =
            listOf(
                "OnePlus", // Build.MANUFACTURER shape
                "CPH2751", // Build.MODEL shape
                "OnePlus CPH2751", // Build.MANUFACTURER + Build.MODEL shape
                "Arun", // user-chosen display name
                "Arun's Phone", // user-chosen display name
                "Rider", // role-chosen display name
                "Pillion", // role-chosen display name
            )
        val name = instanceServiceName("0123456789abcdef0123456789abcdef")
        deviceValues.forEach { forbidden ->
            assertFalse(
                name.contains(forbidden, ignoreCase = true),
                "instance name '$name' must not contain device/user value '$forbidden'",
            )
        }
    }

    @Test
    fun `instance service name does not carry the full 32-character discovery handle`() {
        val dh = "0123456789abcdef0123456789abcdef"
        val name = instanceServiceName(dh)
        assertFalse(name.contains(dh), "the full dh is unnecessarily durable-looking in an instance name")
    }

    @Test
    fun `instance service name rotates when the discovery handle rotates`() {
        val nameA = instanceServiceName("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val nameB = instanceServiceName("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertTrue(nameA != nameB, "a rotated dh must produce a different instance name")
    }
}
