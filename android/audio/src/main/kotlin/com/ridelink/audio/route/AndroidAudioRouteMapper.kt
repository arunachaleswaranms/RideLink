package com.ridelink.audio.route

import android.media.AudioDeviceInfo
import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.ProfileCoupling
import com.ridelink.core.audiopolicy.RouteState

/**
 * **The one place** Android audio device types become ADR-016 wire vocabulary.
 *
 * ADR-016 requires exactly one such function per platform, for two reasons: no `A2DP`, `HFP`, `SCO`
 * or `AudioManager` string may ever reach the wire, and Phase 0's measured hardware behaviour has to
 * have somewhere to land. Filling in `docs/PHASE0_RESULTS.md` changes this file and this file only —
 * it flips [AudioConfidence.ASSUMED] to [AudioConfidence.MEASURED] and corrects the profile for the
 * real helmet unit, with no protocol change anywhere.
 *
 * Pure by construction: `AudioDeviceInfo.TYPE_*` are compile-time integer constants, so this maps
 * `Int?` and is unit-testable on a laptop with no device and no `android.jar` at runtime.
 *
 * ### What is assumed, and why saying so matters
 *
 * Every value below marked `assumed` is a **reasoned guess about hardware nobody has measured**:
 *
 * - `TYPE_BLUETOOTH_SCO` is mapped to [AudioProfile.DUPLEX_WIDEBAND] because a modern helmet unit
 *   almost certainly negotiates mSBC/wideband speech rather than CVSD. If the OnePlus Nord 5 and the
 *   real unit settle on narrowband, the honest value is [AudioProfile.DUPLEX_NARROWBAND] and this
 *   line is what changes.
 * - `profile_coupling` is [ProfileCoupling.INPUT_FORCES_OUTPUT] for every Bluetooth route, which is
 *   ADR-016's central claim and the product's highest risk. It is asserted here as `assumed`, not as
 *   fact.
 * - `TYPE_BLE_HEADSET` is mapped to [AudioProfile.DUPLEX_WIDEBAND] rather than
 *   [AudioProfile.DUPLEX_WIDE_STEREO]: LE Audio *can* carry media-quality output with usable input,
 *   which would make it the one Bluetooth route that does not degrade music — but claiming that
 *   before measuring it would be exactly the false reassurance ADR-016 exists to prevent.
 */
object AndroidAudioRouteMapper {
    /**
     * @param deviceType `AudioManager.getCommunicationDevice()?.type`, or null when the platform has
     *   not told us — which is a representable answer, not a reason to guess.
     * @param microphoneOpen whether the voice audio session is open. Distinct from whether speech is
     *   being transmitted: PTT, VOX and mute gate transmission, not the device (ARCHITECTURE §6.3).
     * @param inCommunicationMode whether `AudioManager.mode` is `MODE_IN_COMMUNICATION`. When it is
     *   not, no duplex profile is in force regardless of which device is attached.
     */
    fun map(
        deviceType: Int?,
        microphoneOpen: Boolean,
        inCommunicationMode: Boolean,
        sampleRateHz: Int?,
        lastChangeReason: AudioRouteChangeReason,
        routeState: RouteState = RouteState.STABLE,
    ): AudioRouteSnapshot {
        val endpointClass = endpointClass(deviceType)
        val coupling = coupling(endpointClass)
        val duplex = duplexProfile(deviceType)

        // Without MODE_IN_COMMUNICATION there is no duplex profile in force. A Bluetooth endpoint is
        // then on its media profile, which is output-only — reporting a duplex profile here would be
        // the "everything is fine" lie ADR-016 was written to remove, inverted.
        val effectiveOutput =
            when {
                deviceType == null -> AudioProfile.UNKNOWN
                !inCommunicationMode -> mediaProfile(endpointClass)
                else -> duplex
            }
        val effectiveInput =
            when {
                deviceType == null -> AudioProfile.UNKNOWN
                !microphoneOpen -> AudioProfile.NONE
                else -> duplex
            }

        return AudioRouteSnapshot(
            endpointClass = endpointClass,
            microphoneOpen = microphoneOpen,
            effectiveOutputProfile = effectiveOutput,
            effectiveInputProfile = effectiveInput,
            effectiveOutputSampleRateHz = effectiveRate(effectiveOutput, sampleRateHz),
            effectiveInputSampleRateHz = effectiveRate(effectiveInput, sampleRateHz),
            routeState = routeState,
            profileCoupling = coupling,
            // ADR-016 choice 4: `measured` only once real hardware behaviour is recorded in
            // docs/PHASE0_RESULTS.md. Until then `assumed` is the truth.
            confidence = AudioConfidence.ASSUMED,
            lastChangeReason = lastChangeReason,
        )
    }

    private fun endpointClass(deviceType: Int?): EndpointClass =
        when (deviceType) {
            null -> EndpointClass.UNKNOWN
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> EndpointClass.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> EndpointClass.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            -> EndpointClass.BUILTIN_SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EndpointClass.BUILTIN_EARPIECE
            else -> EndpointClass.OTHER
        }

    /**
     * ADR-016's load-bearing field. `input_forces_output` for Bluetooth: opening the microphone drags
     * the output onto the duplex profile too, so the pillion's music collapses to narrowband at
     * exactly the moment the intercom starts working.
     */
    private fun coupling(endpointClass: EndpointClass): ProfileCoupling =
        when (endpointClass) {
            EndpointClass.BLUETOOTH -> ProfileCoupling.INPUT_FORCES_OUTPUT
            // A wired headset and the built-in speaker/mic carry input without moving the output, so
            // the two directions genuinely are independent there.
            EndpointClass.WIRED,
            EndpointClass.BUILTIN_SPEAKER,
            EndpointClass.BUILTIN_EARPIECE,
            -> ProfileCoupling.INDEPENDENT
            EndpointClass.OTHER, EndpointClass.UNKNOWN -> ProfileCoupling.UNKNOWN
        }

    /** What the route carries **with the microphone open**. See the class doc for what is assumed. */
    private fun duplexProfile(deviceType: Int?): AudioProfile =
        when (deviceType) {
            null -> AudioProfile.UNKNOWN
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            -> AudioProfile.DUPLEX_WIDEBAND
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> AudioProfile.DUPLEX_WIDE_STEREO
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            -> AudioProfile.BUILTIN
            // Output-only devices have no duplex form: headphones with no microphone, A2DP, LE
            // broadcast. With the mic open the platform moves off them entirely.
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> AudioProfile.MEDIA_STEREO
            else -> AudioProfile.UNKNOWN
        }

    /** What the route carries with the microphone closed — the music-only case. */
    private fun mediaProfile(endpointClass: EndpointClass): AudioProfile =
        when (endpointClass) {
            EndpointClass.BLUETOOTH, EndpointClass.WIRED -> AudioProfile.MEDIA_STEREO
            EndpointClass.BUILTIN_SPEAKER, EndpointClass.BUILTIN_EARPIECE -> AudioProfile.BUILTIN
            EndpointClass.OTHER, EndpointClass.UNKNOWN -> AudioProfile.UNKNOWN
        }

    /**
     * The profile, not the platform, decides the rate a duplex Bluetooth route actually carries:
     * `PROPERTY_OUTPUT_SAMPLE_RATE` reports the mixer's rate (typically 48 kHz) whatever the
     * endpoint is doing, so trusting it for a wideband SCO link would overstate the quality by 3×.
     */
    private fun effectiveRate(
        profile: AudioProfile,
        platformRateHz: Int?,
    ): Int? =
        when (profile) {
            AudioProfile.DUPLEX_NARROWBAND -> NARROWBAND_HZ
            AudioProfile.DUPLEX_WIDEBAND -> WIDEBAND_HZ
            AudioProfile.MEDIA_STEREO, AudioProfile.DUPLEX_WIDE_STEREO, AudioProfile.BUILTIN -> platformRateHz
            AudioProfile.NONE, AudioProfile.UNKNOWN -> null
        }

    private const val NARROWBAND_HZ = 8_000
    private const val WIDEBAND_HZ = 16_000
}
