package com.ridelink.app.ui

import com.ridelink.app.sync.SyncState
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.VoiceStatus

/** Read-only words, never command admission or authority. */
internal fun connectionHint(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "Connect both phones to the same Wi-Fi or hotspot, then find your peer."
        SessionStatus.DISCOVERING -> "Looking for the other phone. Open RideLink and find peers there too."
        SessionStatus.PAIRING -> "Compare the pairing digits on both phones."
        SessionStatus.CONNECTING -> "Connecting securely to your peer…"
        SessionStatus.CONNECTED -> "Paired and connected. Set up audio and music, then start your ride."
        SessionStatus.RIDE_ACTIVE -> "Your ride is active."
        SessionStatus.RECONNECTING -> "Trying to reconnect automatically. Local controls remain available."
        SessionStatus.DISCONNECTED -> "Peer features are unavailable. Check the shared network and retry."
        SessionStatus.ENDING -> "Ending the session…"
        SessionStatus.ERROR -> "The session could not continue. See diagnostics for details."
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
