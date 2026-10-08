package com.ridelink.app.ui

import com.ridelink.app.sync.SyncState
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.player.MusicFailure
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.VoiceStatus
import com.ridelink.network.control.ControlState

/** Read-only words, never command admission or authority. */
internal fun connectionHint(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "Put both phones on the same Wi-Fi or hotspot, then tap Find other phone on each."
        SessionStatus.DISCOVERING -> "Looking on this network. Open RideLink on the other phone and tap Find other phone."
        SessionStatus.PAIRING -> "Make sure both phones show the same six digits."
        SessionStatus.CONNECTING -> "Setting up an encrypted connection."
        SessionStatus.CONNECTED -> "Ready. Start the intercom or music, then start your ride."
        SessionStatus.RIDE_ACTIVE -> "Ride in progress."
        SessionStatus.RECONNECTING -> "Trying to reach the other phone. Music on this phone keeps playing."
        SessionStatus.DISCONNECTED -> "The other phone is out of reach. Check both are on the same network, then search again."
        SessionStatus.ENDING -> "Closing the connection."
        SessionStatus.ERROR -> "The session stopped. Connection diagnostics below have the details."
    }

internal fun voiceLabel(status: VoiceStatus): String =
    when (status) {
        VoiceStatus.IDLE -> "Intercom not started"
        VoiceStatus.NEGOTIATING, VoiceStatus.CONNECTING -> "Connecting intercom…"
        VoiceStatus.ACTIVE -> "Intercom active"
        VoiceStatus.FAILED -> "Intercom unavailable"
    }

internal fun voiceFailureLabel(failure: VoiceFailure): String =
    when (failure) {
        VoiceFailure.MIC_PERMISSION_DENIED -> "Allow microphone access in Settings, then start Intercom again."
        VoiceFailure.NO_AUDIO_ENDPOINT -> "Connect an audio device, then try again."
        VoiceFailure.AUDIO_SESSION_ACTIVATION_FAILED -> "Audio is unavailable. Finish other audio activity, then try again."
        VoiceFailure.ROUTE_SELECTION_FAILED -> "Check your audio output, then try again."
        VoiceFailure.CAPTURE_START_FAILED -> "The microphone could not start. Try Intercom again."
        VoiceFailure.WEBRTC_FAILED -> "Intercom could not connect. Try Intercom again."
        VoiceFailure.CONTROL_LINK_LOST -> "The peer connection was lost. Intercom needs a connected peer."
        VoiceFailure.INTERRUPTED -> "Audio was interrupted by another app or call."
        VoiceFailure.MEDIA_SERVICES_RESET -> "Audio was reset by the system. Try Intercom again."
        VoiceFailure.BACKGROUND_START_REFUSED, VoiceFailure.FOREGROUND_SERVICE_START_FAILED ->
            "Bring RideLink to the front, then try again."
        VoiceFailure.SESSION_NOT_AUTHENTICATED -> "Connect and verify your peer before starting Intercom."
    }

internal fun syncLabel(state: SyncState): String =
    when (state) {
        SyncState.INACTIVE -> "Local playback"
        SyncState.CLOCK_UNREADY, SyncState.WAITING_FOR_QUEUE, SyncState.SCHEDULED -> "Preparing synchronized playback…"
        SyncState.WAITING_FOR_CONTENT -> "Waiting for the track to download…"
        SyncState.SYNCED -> "Synchronized"
        SyncState.DESYNCHRONIZED -> "Restoring music sync…"
        SyncState.SYNC_FAILED -> "Music sync paused"
        SyncState.TRANSPORT_FAILED -> "Music command could not reach your peer"
        SyncState.LOCAL_OVERLOAD -> "Music sync is busy. Try again shortly."
    }

internal fun policyLabel(policy: IntercomPolicy): String =
    when (policy.id.name) {
        "MODE_A" -> "A · Continuous / duck music"
        "MODE_B" -> "B · Voice-activated / duck music"
        "MODE_C" -> "C · Push to Talk / duck music"
        "MODE_D" -> "D · Continuous / pause music"
        "MODE_E" -> "E · Music only"
        else -> "Custom intercom mode"
    }

/** Ownership is supplied by its production owner; a late debt diagnostic cannot reactivate it. */
internal fun rideMusicLabel(
    status: SessionStatus,
    state: SyncState,
    ownsTransport: Boolean,
): String =
    when {
        status == SessionStatus.RECONNECTING -> "Music sync waits for connection"
        status != SessionStatus.CONNECTED && status != SessionStatus.RIDE_ACTIVE -> "Peer unavailable · Local controls"
        state in
            setOf(
                SyncState.SYNC_FAILED,
                SyncState.TRANSPORT_FAILED,
                SyncState.LOCAL_OVERLOAD,
                SyncState.DESYNCHRONIZED,
            )
        -> syncLabel(state)
        !ownsTransport -> "Local playback"
        else -> syncLabel(state)
    }

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / MILLIS_PER_SECOND
    return "%d:%02d".format(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

internal fun playerFailureLabel(failure: MusicFailure): String =
    when (failure) {
        MusicFailure.DECODE_FAILED -> "This file could not be played. Try another track."
        MusicFailure.FILE_MISSING -> "This file is no longer on the phone. Import it again."
        MusicFailure.UNSUPPORTED_FORMAT -> "This audio format is not supported."
        MusicFailure.STORAGE_IO -> "The file could not be read."
        MusicFailure.CANCELLED -> "Playback cancelled."
        MusicFailure.FOREGROUND_SERVICE_START_FAILED -> "Bring RideLink to the front and try again."
    }

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

internal fun securityAlertExplanation(code: String): String =
    when (code) {
        "pin_mismatch" ->
            "This peer's identity key has changed. That happens after a reinstall — but it is also " +
                "what an impersonation attempt looks like. RideLink will not reconnect until you " +
                "forget this peer and pair again."
        "certificate_invalid" ->
            "The peer's certificate is outside its validity window. Check the date and time on both phones."
        "identity_mismatch" ->
            "The peer's stated identity did not match its certificate. The connection was refused."
        else -> "The connection was refused."
    }

internal fun controlStateLabel(state: ControlState): String =
    when (state) {
        ControlState.IDLE -> "Idle"
        ControlState.CONNECTING -> "Connecting…"
        ControlState.CONNECTED -> "Connected"
        ControlState.RECONNECTING -> "Reconnecting…"
        ControlState.DISCONNECTED -> "Disconnected"
        ControlState.ENDED -> "Ended"
    }
