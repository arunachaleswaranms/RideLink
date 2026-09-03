package com.ridelink.audio.route

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AndroidCommunicationDeviceSelector] — the pure half of Android's route selection.
 *
 * It is pure because `AudioDeviceInfo.TYPE_*` are compile-time integer constants, which is what lets
 * "which endpoint does the intercom route to?" be a laptop test rather than a device fact. What is
 * **not** covered here is the `AudioManager.setCommunicationDevice` call itself, or whether the
 * platform actually lists a helmet unit as available for communication — those are
 * **REAL-DEVICE AUDIO GATE PENDING** (TEST_PLAN A-12, docs/STATUS.md §4 problem 23).
 */
class AndroidCommunicationDeviceSelectorTest {
    @Test
    fun `bluetooth is preferred over wired when the ride asks for the helmet unit`() {
        val available = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AndroidCommunicationDeviceSelector.select(available, AudioEndpointPreference.AUTO_PREFER_BLUETOOTH),
        )
    }

    @Test
    fun `wired is the fallback when no bluetooth communication device is listed`() {
        val available = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_USB_HEADSET)
        assertEquals(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AndroidCommunicationDeviceSelector.select(available, AudioEndpointPreference.AUTO_PREFER_BLUETOOTH),
        )
    }

    /**
     * **Null means "leave the platform's own choice alone", which is a representable answer rather
     * than a failure.** This phase's brief §9: RideLink must not seize arbitrary hardware, so when
     * nothing matches the stated intent it changes nothing.
     */
    @Test
    fun `nothing matching the preference leaves the platform's choice alone`() {
        val available = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertNull(
            AndroidCommunicationDeviceSelector.select(available, AudioEndpointPreference.AUTO_PREFER_BLUETOOTH),
        )
    }

    @Test
    fun `an empty device list selects nothing`() {
        for (preference in AudioEndpointPreference.entries) {
            assertNull(AndroidCommunicationDeviceSelector.select(emptyList(), preference), "$preference")
        }
    }

    /** `PLATFORM_DEFAULT` never calls `setCommunicationDevice` at all, whatever is attached. */
    @Test
    fun `the platform-default preference never selects anything`() {
        val available =
            listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        assertNull(AndroidCommunicationDeviceSelector.select(available, AudioEndpointPreference.PLATFORM_DEFAULT))
    }

    @Test
    fun `the wired preference ignores bluetooth entirely`() {
        val available = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertEquals(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AndroidCommunicationDeviceSelector.select(available, AudioEndpointPreference.PREFER_WIRED),
        )
        assertNull(
            AndroidCommunicationDeviceSelector.select(
                listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                AudioEndpointPreference.PREFER_WIRED,
            ),
        )
    }

    /**
     * **A2DP is deliberately not a communication type.** ADR-016 names a route for what it can
     * *carry*, and a media-only endpoint has no microphone to route to — selecting it for the
     * intercom would be asking the platform for a duplex path over something that has none.
     */
    @Test
    fun `a media-only bluetooth endpoint is never chosen for communication`() {
        assertFalse(AndroidCommunicationDeviceSelector.isBluetoothCommunicationType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(AndroidCommunicationDeviceSelector.isBluetoothCommunicationType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertNull(
            AndroidCommunicationDeviceSelector.select(
                listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                AudioEndpointPreference.AUTO_PREFER_BLUETOOTH,
            ),
        )
    }

    @Test
    fun `every bluetooth communication type is recognised`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )) {
            assertTrue(AndroidCommunicationDeviceSelector.isBluetoothCommunicationType(type), "$type")
        }
    }

    /**
     * Feeds `RideStartRequest.audioEndpointPresent`, so "no audio endpoint" becomes a **named**
     * refusal (this phase's brief §41) rather than a silent start with nowhere to speak.
     */
    @Test
    fun `endpoint presence is exactly whether the platform listed anything`() {
        assertFalse(AndroidCommunicationDeviceSelector.hasUsableEndpoint(emptyList()))
        assertTrue(AndroidCommunicationDeviceSelector.hasUsableEndpoint(listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
    }
}
