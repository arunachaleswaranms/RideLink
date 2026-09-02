package com.ridelink.audio.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.voice.VoiceAudioSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android half of the audio route: `AudioManager` focus, communication mode and device
 * selection, mapped into ADR-016's platform-neutral vocabulary by [AndroidAudioRouteMapper].
 *
 * Deliberately **not** the microphone itself. WebRTC's `JavaAudioDeviceModule` owns `AudioRecord`
 * and `AudioTrack` (ADR-003), so what this class owns is the *session*: audio focus,
 * `MODE_IN_COMMUNICATION`, and which device the communication route points at. The split matters
 * for a specific reason — the thing that thrashes a Bluetooth endpoint between its media and duplex
 * profiles is this session changing, and it must therefore survive a control-plane blip
 * (`VoiceEngine.stop` versus `release`).
 *
 * API level: [AudioManager.setCommunicationDevice] and
 * [AudioManager.getAvailableCommunicationDevices] are API 31+, which is exactly the ADR-011
 * `minSdk`. That is one of the reasons that baseline was chosen: below it, routing a helmet unit
 * means the deprecated `startBluetoothSco()` sequence, and no such path exists in this file.
 *
 * **Nothing here has run on a phone.** `AudioManager` cannot be exercised by a JVM unit test and no
 * device or emulator is available in this environment. [AndroidAudioRouteMapper] is pure and is
 * unit-tested; everything in this class is **REAL-DEVICE AUDIO GATE PENDING**.
 */
class AndroidVoiceAudioSession(
    private val context: Context,
) : VoiceAudioSession {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var sink: ((AudioRouteSnapshot) -> Unit)? = null

    @Volatile
    private var open = false

    @Volatile
    private var snapshot = AudioRouteSnapshot()

    override val isOpen: Boolean get() = open

    override val route: AudioRouteSnapshot get() = snapshot

    override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
        this.sink = sink
    }

    /**
     * Must be called while the app is foreground-visible (ARCHITECTURE §6.4 step 6).
     *
     * Returns a failure rather than throwing, and never proceeds *pretending* to have a microphone:
     * a denied `RECORD_AUDIO` means the ride continues music-only with an amber status, which is
     * FR-025's graceful degradation, not an exception to swallow.
     */
    override suspend fun open(): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (open) return@withContext Result.success(Unit)
            if (!hasRecordAudioPermission()) {
                publish(snapshot.copy(microphoneOpen = false, interrupted = false))
                return@withContext Result.failure(MicrophonePermissionMissing)
            }
            runCatching {
                previousMode = audioManager.mode
                val request =
                    AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        // ARCHITECTURE §6.1: false so *we* control ducking with our own
                        // 150–250 ms ramp rather than letting the platform pause us.
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(::onFocusChange)
                        .build()
                focusRequest = request
                if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    error("audio focus denied")
                }
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                selectCommunicationDevice()
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
                open = true
                refresh(AudioRouteChangeReason.CATEGORY_CHANGE)
            }
        }

    override suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            if (!open) return@withContext
            open = false
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching { audioManager.clearCommunicationDevice() }
            runCatching { audioManager.mode = previousMode }
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focusRequest = null
            refresh(AudioRouteChangeReason.CATEGORY_CHANGE)
        }
    }

    /**
     * Picks the communication device without seizing one the user did not ask for.
     *
     * Preference order is Bluetooth, then wired, then whatever the platform already had — but only
     * among devices the platform lists as *available for communication*, and only when the app is
     * entering a voice session, which is an explicit user action. Nothing here scans, pairs or
     * connects a Bluetooth device.
     */
    private fun selectCommunicationDevice() {
        val available = audioManager.availableCommunicationDevices
        val preferred =
            available.firstOrNull { it.type.isBluetoothCommunicationType() }
                ?: available.firstOrNull { it.type.isWiredType() }
        if (preferred != null) {
            audioManager.setCommunicationDevice(preferred)
        }
    }

    private fun onFocusChange(change: Int) {
        // Focus loss is a platform interruption — a call, Siri's equivalent, another app taking the
        // session. ADR-016 keeps that in the *route* report, not in VOICE_STATE (PROTOCOL §7.4).
        val interrupted =
            change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        publish(
            snapshot.copy(
                interrupted = interrupted,
                lastChangeReason =
                    if (interrupted) {
                        AudioRouteChangeReason.INTERRUPTION_BEGAN
                    } else {
                        AudioRouteChangeReason.INTERRUPTION_ENDED
                    },
            ),
        )
    }

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                refresh(AudioRouteChangeReason.NEW_DEVICE_AVAILABLE)
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                refresh(AudioRouteChangeReason.OLD_DEVICE_UNAVAILABLE)
            }
        }

    /**
     * Recomputes the snapshot from the platform's current view. A route change is published twice —
     * once as `transitioning` and once as `stable` — because ADR-016 makes a route change a
     * first-class state rather than a moment when every other field is quietly stale, and because
     * ARCHITECTURE §7.3 suspends the drift ladder while either peer is transitioning.
     */
    private fun refresh(reason: AudioRouteChangeReason) {
        val communicationDevice = runCatching { audioManager.communicationDevice }.getOrNull()
        val mapped =
            AndroidAudioRouteMapper.map(
                deviceType = communicationDevice?.type,
                microphoneOpen = open,
                inCommunicationMode = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION,
                sampleRateHz = platformOutputSampleRateHz(),
                lastChangeReason = reason,
            )
        publish(mapped.copy(interrupted = snapshot.interrupted))
    }

    private fun platformOutputSampleRateHz(): Int? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    private fun publish(next: AudioRouteSnapshot) {
        snapshot = next
        sink?.invoke(next)
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private object MicrophonePermissionMissing : Exception("RECORD_AUDIO not granted")
}

private fun Int.isBluetoothCommunicationType(): Boolean =
    this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        this == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        this == AudioDeviceInfo.TYPE_BLE_SPEAKER

private fun Int.isWiredType(): Boolean =
    this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        this == AudioDeviceInfo.TYPE_USB_HEADSET ||
        this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
