package com.ridelink.audio.route

import android.media.AudioDeviceInfo
import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.ProfileCoupling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **only** place Android device types become ADR-016 wire vocabulary, so it is the only place a
 * mapping error can hide — and it is pure, so it can be exhausted on a laptop.
 *
 * `AudioDeviceInfo.TYPE_*` are compile-time integer constants, which is why this runs as a plain JVM
 * unit test with no device, no emulator and no Robolectric.
 *
 * **What this does not prove.** Every expectation below is about the *mapping*, not about the
 * hardware: whether a real helmet unit actually negotiates wideband rather than narrowband, and
 * whether opening its microphone really drags the output onto the duplex profile, are measurements
 * nobody has taken. `confidence` is asserted to be `assumed` for exactly that reason — see
 * `docs/PHASE0_RESULTS.md`.
 */
class AndroidAudioRouteMapperTest {
    @Test
    fun `bluetooth with the microphone open is duplex, wideband and reduced quality`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.CATEGORY_CHANGE,
            )
        assertEquals(EndpointClass.BLUETOOTH, snapshot.endpointClass)
        assertEquals(AudioProfile.DUPLEX_WIDEBAND, snapshot.effectiveOutputProfile)
        assertEquals(AudioProfile.DUPLEX_WIDEBAND, snapshot.effectiveInputProfile)
        // ADR-016's central claim, and the product's highest risk, stated on the wire.
        assertEquals(ProfileCoupling.INPUT_FORCES_OUTPUT, snapshot.profileCoupling)
        // The honest answer: the pillion's music has collapsed to narrowband.
        assertEquals(MediaQuality.REDUCED, snapshot.mediaQuality)
        // The mixer says 48 kHz whatever the endpoint is doing; trusting it here would overstate
        // the quality by 3x.
        assertEquals(16_000, snapshot.effectiveOutputSampleRateHz)
        assertEquals(16_000, snapshot.effectiveInputSampleRateHz)
    }

    @Test
    fun `bluetooth with the microphone closed is media-quality output only`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                microphoneOpen = false,
                inCommunicationMode = false,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.CATEGORY_CHANGE,
            )
        assertEquals(AudioProfile.MEDIA_STEREO, snapshot.effectiveOutputProfile)
        assertEquals(AudioProfile.NONE, snapshot.effectiveInputProfile)
        assertEquals(MediaQuality.FULL, snapshot.mediaQuality)
        assertEquals(48_000, snapshot.effectiveOutputSampleRateHz)
        assertNull(snapshot.effectiveInputSampleRateHz)
    }

    /**
     * The one Bluetooth-shaped route that does *not* degrade music: a wired headset carries
     * media-quality output and usable input at once, so the two directions genuinely are
     * independent.
     */
    @Test
    fun `a wired headset is duplex wide stereo and independent`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NEW_DEVICE_AVAILABLE,
            )
        assertEquals(EndpointClass.WIRED, snapshot.endpointClass)
        assertEquals(AudioProfile.DUPLEX_WIDE_STEREO, snapshot.effectiveOutputProfile)
        assertEquals(ProfileCoupling.INDEPENDENT, snapshot.profileCoupling)
        assertEquals(MediaQuality.FULL, snapshot.mediaQuality)
        assertEquals(48_000, snapshot.effectiveOutputSampleRateHz)
    }

    @Test
    fun `wired headphones with no microphone stay output-only even with the mic open`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NEW_DEVICE_AVAILABLE,
            )
        assertEquals(AudioProfile.MEDIA_STEREO, snapshot.effectiveOutputProfile)
        assertEquals(MediaQuality.FULL, snapshot.mediaQuality)
    }

    @Test
    fun `nothing attached is the builtin route`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NO_SUITABLE_ROUTE,
            )
        assertEquals(EndpointClass.BUILTIN_SPEAKER, snapshot.endpointClass)
        assertEquals(AudioProfile.BUILTIN, snapshot.effectiveOutputProfile)
        assertEquals(AudioProfile.BUILTIN, snapshot.effectiveInputProfile)
        assertEquals(ProfileCoupling.INDEPENDENT, snapshot.profileCoupling)
        assertEquals(MediaQuality.FULL, snapshot.mediaQuality)
    }

    /** ADR-016 choice 4: "the platform did not tell us" is a representable answer, not a guess. */
    @Test
    fun `an unknown device is reported as unknown rather than guessed`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = null,
                microphoneOpen = false,
                inCommunicationMode = false,
                sampleRateHz = null,
                lastChangeReason = AudioRouteChangeReason.UNKNOWN,
            )
        assertEquals(EndpointClass.UNKNOWN, snapshot.endpointClass)
        assertEquals(AudioProfile.UNKNOWN, snapshot.effectiveOutputProfile)
        assertEquals(AudioProfile.UNKNOWN, snapshot.effectiveInputProfile)
        assertEquals(ProfileCoupling.UNKNOWN, snapshot.profileCoupling)
        assertEquals(MediaQuality.UNKNOWN, snapshot.mediaQuality)
        assertNull(snapshot.effectiveOutputSampleRateHz)
    }

    /**
     * Without `MODE_IN_COMMUNICATION` no duplex profile is in force whatever is attached. Reporting a
     * duplex profile here would be the inverse of the false reassurance ADR-016 removed: claiming the
     * music is degraded when it is not.
     */
    @Test
    fun `no duplex profile is claimed when the audio mode is not in-communication`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                microphoneOpen = false,
                inCommunicationMode = false,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.CATEGORY_CHANGE,
            )
        assertEquals(AudioProfile.MEDIA_STEREO, snapshot.effectiveOutputProfile)
        assertEquals(MediaQuality.FULL, snapshot.mediaQuality)
    }

    @Test
    fun `LE Audio is not yet claimed to be the route that keeps music intact`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BLE_HEADSET,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NEW_DEVICE_AVAILABLE,
            )
        // LE Audio *can* carry media-quality output with usable input, which would make it the one
        // Bluetooth route that does not degrade music. Claiming that before measuring it is exactly
        // the false reassurance ADR-016 exists to prevent.
        assertEquals(AudioProfile.DUPLEX_WIDEBAND, snapshot.effectiveOutputProfile)
        assertEquals(MediaQuality.REDUCED, snapshot.mediaQuality)
    }

    /**
     * ADR-016 choice 4, asserted rather than assumed: nothing this mapper produces may claim to be a
     * measurement until `docs/PHASE0_RESULTS.md` is filled in. This test is what will fail — loudly,
     * and in the right place — when someone edits the mapping without recording the measurement.
     */
    @Test
    fun `every mapping reports assumed confidence until Phase 0 results are recorded`() {
        val types =
            listOf(
                null,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_HDMI,
            )
        for (type in types) {
            for (micOpen in listOf(true, false)) {
                for (inComms in listOf(true, false)) {
                    val snapshot =
                        AndroidAudioRouteMapper.map(
                            deviceType = type,
                            microphoneOpen = micOpen,
                            inCommunicationMode = inComms,
                            sampleRateHz = 48_000,
                            lastChangeReason = AudioRouteChangeReason.UNKNOWN,
                        )
                    assertEquals(AudioConfidence.ASSUMED, snapshot.confidence, "type=$type mic=$micOpen comms=$inComms")
                }
            }
        }
    }

    /**
     * A privacy property, not a formatting one: ADR-016 rejected free-text route descriptions because
     * a device *name* is personally identifying. Every field this mapper produces is an enum or an
     * integer, so there is nothing for a name to travel in.
     */
    @Test
    fun `the snapshot carries no free-text platform string anywhere`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                microphoneOpen = true,
                inCommunicationMode = true,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NEW_DEVICE_AVAILABLE,
            )
        val rendered = snapshot.toString()
        for (forbidden in listOf("A2DP", "HFP", "SCO", "AudioManager", "AudioDeviceInfo", "Bluetooth ")) {
            assertTrue(
                !rendered.contains(forbidden),
                "the snapshot rendered a platform string ($forbidden), which must never reach the wire: $rendered",
            )
        }
    }

    @Test
    fun `an HDMI or other unexpected route is other, not silently bluetooth`() {
        val snapshot =
            AndroidAudioRouteMapper.map(
                deviceType = AudioDeviceInfo.TYPE_HDMI,
                microphoneOpen = false,
                inCommunicationMode = false,
                sampleRateHz = 48_000,
                lastChangeReason = AudioRouteChangeReason.NEW_DEVICE_AVAILABLE,
            )
        assertEquals(EndpointClass.OTHER, snapshot.endpointClass)
        assertEquals(ProfileCoupling.UNKNOWN, snapshot.profileCoupling)
    }
}
