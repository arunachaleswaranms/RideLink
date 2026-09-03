package com.ridelink.audio.route

import android.media.AudioDeviceInfo

/**
 * Which endpoint the intercom should route to, as an **explicit intent** rather than a guess.
 *
 * This phase's brief §9: "do not automatically choose arbitrary Bluetooth hardware; the selected
 * endpoint should come from explicit session/readiness intent." Starting the intercom for a ride *is*
 * that intent — the rider's helmet unit is the whole point of the product — so [AUTO_PREFER_BLUETOOTH]
 * is what `AppContainer` supplies. It is a named, testable choice rather than an implicit preference
 * buried in a `firstOrNull`.
 */
enum class AudioEndpointPreference {
    /**
     * Prefer a Bluetooth communication device, then a wired one, then leave the platform's own choice
     * alone. Only ever chosen from among devices the platform lists as available *for communication*,
     * and only on an explicit Start Intercom.
     */
    AUTO_PREFER_BLUETOOTH,

    /** Prefer a wired headset, then leave the platform's choice alone. */
    PREFER_WIRED,

    /** Never call `setCommunicationDevice` at all: whatever the platform picked stands. */
    PLATFORM_DEFAULT,
}

/**
 * The pure half of Android's communication-device selection.
 *
 * `AudioDeviceInfo.TYPE_*` are compile-time integer constants, so this maps `Int` and is unit-testable
 * on a laptop with no device — the same reason [AndroidAudioRouteMapper] is pure. What is left in
 * [AndroidVoiceAudioSession] is the `AudioManager` call itself, which is
 * **REAL-DEVICE AUDIO GATE PENDING** like everything else that touches the platform.
 *
 * **Nothing here scans, pairs or connects a Bluetooth device.** It chooses among endpoints the platform
 * already lists as available for communication, which is a different and much narrower act.
 */
object AndroidCommunicationDeviceSelector {
    /**
     * @param availableTypes the `type` of every device in `AudioManager.getAvailableCommunicationDevices`.
     * @return the type to pass to `setCommunicationDevice`, or **null** meaning "leave the platform's
     *   current choice alone" — a representable answer, not a failure.
     */
    fun select(
        availableTypes: List<Int>,
        preference: AudioEndpointPreference,
    ): Int? =
        when (preference) {
            AudioEndpointPreference.AUTO_PREFER_BLUETOOTH ->
                availableTypes.firstOrNull(::isBluetoothCommunicationType)
                    ?: availableTypes.firstOrNull(::isWiredType)
            AudioEndpointPreference.PREFER_WIRED -> availableTypes.firstOrNull(::isWiredType)
            AudioEndpointPreference.PLATFORM_DEFAULT -> null
        }

    /**
     * Whether there is any endpoint at all to route the intercom to. Feeds
     * [com.ridelink.core.audiopolicy.RideStartRequest.audioEndpointPresent], so "no audio endpoint" is
     * a named refusal rather than a silent start with nowhere to speak.
     */
    fun hasUsableEndpoint(availableTypes: List<Int>): Boolean = availableTypes.isNotEmpty()

    /**
     * `TYPE_BLUETOOTH_SCO` is the type a classic hands-free unit reports as *available for
     * communication*; `TYPE_BLUETOOTH_A2DP` is deliberately absent, because a media-only endpoint has
     * no microphone to route to (ADR-016 — a route is named for what it can carry).
     */
    fun isBluetoothCommunicationType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER

    fun isWiredType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
}
