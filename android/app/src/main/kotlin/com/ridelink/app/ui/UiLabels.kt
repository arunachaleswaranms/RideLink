package com.ridelink.app.ui

import com.ridelink.app.sync.SyncState
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
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
        VoiceStatus.IDLE -> "Intercom off"
        VoiceStatus.NEGOTIATING, VoiceStatus.CONNECTING -> "Connecting the intercom…"
        VoiceStatus.ACTIVE -> "Intercom on"
        VoiceStatus.FAILED -> "Intercom unavailable"
    }

/** The capture device's state in words — whether speech is being *sent* is a separate fact. */
internal fun microphoneLabel(voice: com.ridelink.network.voice.VoiceDiagnostics): String =
    when {
        !voice.localAudioOpen -> "Microphone off"
        voice.userMuted -> "Microphone muted"
        voice.transmitting -> "Talking"
        else -> "Microphone on"
    }

internal fun voiceFailureLabel(failure: VoiceFailure): String =
    when (failure) {
        VoiceFailure.MIC_PERMISSION_DENIED -> "Allow microphone access in Settings, then start the intercom again."
        VoiceFailure.NO_AUDIO_ENDPOINT -> "Connect your helmet unit or earphones, then try again."
        VoiceFailure.AUDIO_SESSION_ACTIVATION_FAILED -> "Audio is busy. Finish other audio activity, then try again."
        VoiceFailure.ROUTE_SELECTION_FAILED -> "Check your audio output, then try again."
        VoiceFailure.CAPTURE_START_FAILED -> "The microphone did not start. Try the intercom again."
        VoiceFailure.WEBRTC_FAILED -> "The intercom could not connect. Try again."
        VoiceFailure.CONTROL_LINK_LOST -> "The connection to the other phone was lost."
        VoiceFailure.INTERRUPTED -> "Audio was interrupted by another app or a call."
        VoiceFailure.MEDIA_SERVICES_RESET -> "The system reset audio. Try the intercom again."
        VoiceFailure.BACKGROUND_START_REFUSED, VoiceFailure.FOREGROUND_SERVICE_START_FAILED ->
            "Bring RideLink to the front, then try again."
        VoiceFailure.SESSION_NOT_AUTHENTICATED -> "Connect to the other phone and check the code first."
    }

internal fun syncLabel(state: SyncState): String =
    when (state) {
        SyncState.INACTIVE -> "Playing on this phone"
        SyncState.CLOCK_UNREADY, SyncState.WAITING_FOR_QUEUE, SyncState.SCHEDULED -> "Getting both phones in step…"
        SyncState.WAITING_FOR_CONTENT -> "Waiting for the track to download…"
        SyncState.SYNCED -> "Playing on both phones"
        SyncState.DESYNCHRONIZED -> "Getting back in step…"
        SyncState.SYNC_FAILED -> "Music sync paused"
        SyncState.TRANSPORT_FAILED -> "Music command did not reach the other phone"
        SyncState.LOCAL_OVERLOAD -> "Music sync is busy. Try again shortly."
    }

internal fun policyLabel(policy: IntercomPolicy): String =
    when (policy.id.name) {
        "MODE_A" -> "Always on · music lowered"
        "MODE_B" -> "Voice-activated · music lowered"
        "MODE_C" -> "Push to talk · music lowered"
        "MODE_D" -> "Always on · music paused"
        "MODE_E" -> "Music only · intercom off"
        else -> "Custom intercom mode"
    }

/** Ownership is supplied by its production owner; a late debt diagnostic cannot reactivate it. */
internal fun rideMusicLabel(
    status: SessionStatus,
    state: SyncState,
    ownsTransport: Boolean,
): String =
    when {
        status == SessionStatus.RECONNECTING -> "Music sync resumes when reconnected"
        status != SessionStatus.CONNECTED && status != SessionStatus.RIDE_ACTIVE -> "Other phone unavailable · Playing on this phone"
        state in
            setOf(
                SyncState.SYNC_FAILED,
                SyncState.TRANSPORT_FAILED,
                SyncState.LOCAL_OVERLOAD,
                SyncState.DESYNCHRONIZED,
            )
        -> syncLabel(state)
        !ownsTransport -> "Playing on this phone"
        else -> syncLabel(state)
    }

internal fun securityAlertExplanation(code: String): String =
    when (code) {
        "pin_mismatch" ->
            "The other phone's identity key has changed. That happens after a reinstall — but it is " +
                "also what an impersonation attempt looks like. RideLink will not reconnect until you " +
                "forget this phone and pair again."
        "certificate_invalid" ->
            "The other phone's certificate is outside its validity window. Check the date and time on both phones."
        "identity_mismatch" ->
            "The other phone's stated identity did not match its certificate. The connection was refused."
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
