package com.ridelink.app.ui

import com.ridelink.app.sync.SyncState
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.TransmissionGate
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.player.PlayerState
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.VoiceStatus
import com.ridelink.network.voice.VoiceDiagnostics

/**
 * Phase 7's Ride Mode presentation state (ADR-028; REQUIREMENTS FR-018/§10.2) — every field is
 * derived, never a second source of truth. [rideModeUiState] is the one place that derivation
 * happens, deliberately pure (no `StateFlow`, no Compose) so it is unit-testable the way
 * `docs/STATUS.md`'s "state logic should remain testable independently from rendering" asks:
 * [RideModeScreen] does nothing but collect the existing coordinators' flows and call it.
 *
 * **No field here is a second command path or a second status source** — this type only *labels*
 * values [SessionCoordinator]/[com.ridelink.app.music.MusicCoordinator]/[VoiceController] already
 * hold; every action a rendered screen offers still goes straight through those, unchanged.
 */
enum class RideConnectionHealth { HEALTHY, DEGRADED, DISCONNECTED }

data class RideModeUiState(
    val connectionHealth: RideConnectionHealth,
    val reconnectCount: Int,
    val trackTitle: String?,
    val trackArtist: String?,
    val isPlaying: Boolean,
    val hasTrackLoaded: Boolean,
    val micAvailable: Boolean,
    val micMuted: Boolean,
    val pttMode: Boolean,
    val pttHeld: Boolean,
    val intercomDisabled: Boolean,
    val intercomModeLabel: String,
    val localAudioDegraded: Boolean,
    val peerAudioDegraded: Boolean,
)

/**
 * PROTOCOL §10: `RECONNECTING` is a passive, expected state for up to 120 s and must never read as
 * an error; only budget exhaustion (`DISCONNECTED`) is. Any other status reaching this function is
 * a defensive fallback — [nextRideModeVisibility] should already have taken the screen off it by
 * then — and reads as [RideConnectionHealth.DEGRADED] rather than a false [HEALTHY].
 */
fun rideConnectionHealth(status: SessionStatus): RideConnectionHealth =
    when (status) {
        SessionStatus.RIDE_ACTIVE -> RideConnectionHealth.HEALTHY
        SessionStatus.RECONNECTING -> RideConnectionHealth.DEGRADED
        SessionStatus.DISCONNECTED -> RideConnectionHealth.DISCONNECTED
        else -> RideConnectionHealth.DEGRADED
    }

/**
 * Whether the Ride Mode screen should be showing, given the previous frame's answer and the FSM's
 * current status — the pure half of [RideLinkRoot]'s screen selection (ARCHITECTURE §3 rule 1:
 * "RECONNECTING returns to the state it left"). [DISCONNECTED][SessionStatus.DISCONNECTED]
 * deliberately preserves [previous] rather than resetting it: the FSM's own `returnTo` marker does
 * not survive budget exhaustion (ARCHITECTURE's "awaiting user" reading of that state), so Ride Mode
 * has to remember it was riding across that edge itself, or the rider would be dropped back to the
 * developer/diagnostics screen exactly when the budget-exhausted banner (brief §15) needs to appear.
 */
fun nextRideModeVisibility(
    previous: Boolean,
    status: SessionStatus,
    returnTo: SessionStatus?,
): Boolean =
    when (status) {
        SessionStatus.RIDE_ACTIVE -> true
        SessionStatus.RECONNECTING -> returnTo == SessionStatus.RIDE_ACTIVE
        SessionStatus.DISCONNECTED -> previous
        else -> false
    }

@Suppress("LongParameterList") // one per existing StateFlow this screen reads and labels, nothing invented
fun rideModeUiState(
    status: SessionStatus,
    reconnectCount: Int,
    playerState: PlayerState,
    currentEntry: LibraryEntry?,
    voice: VoiceDiagnostics,
    peerAudioState: AudioStateMessage?,
): RideModeUiState {
    val policy = voice.policy
    return RideModeUiState(
        connectionHealth = rideConnectionHealth(status),
        reconnectCount = reconnectCount,
        trackTitle = currentEntry?.track?.title,
        trackArtist = currentEntry?.track?.artist,
        isPlaying = playerState.playing,
        hasTrackLoaded = currentEntry != null || playerState.localEntryId != null,
        micAvailable = voice.localAudioOpen,
        micMuted = voice.userMuted,
        pttMode = policy.gate == TransmissionGate.Ptt,
        pttHeld = voice.pttHeld,
        intercomDisabled = policy.gate == TransmissionGate.Disabled || voice.status == VoiceStatus.IDLE,
        intercomModeLabel = intercomModeLabel(policy),
        localAudioDegraded = voice.route.mediaQuality == MediaQuality.REDUCED || voice.route.mediaQuality == MediaQuality.UNAVAILABLE,
        peerAudioDegraded = peerAudioState?.mediaQuality?.let { it == MediaQuality.REDUCED || it == MediaQuality.UNAVAILABLE } ?: false,
    )
}

private fun intercomModeLabel(policy: IntercomPolicy): String =
    when (policy.gate) {
        TransmissionGate.None -> "Full duplex"
        is TransmissionGate.Vox -> "Voice-activated"
        TransmissionGate.Ptt -> "Push-to-talk"
        TransmissionGate.Disabled -> "Intercom off"
    }

/** Connection loss always outranks a possibly late playback-status publication. */
fun rideSyncLabel(
    status: SessionStatus,
    syncState: SyncState,
): String =
    when {
        status == SessionStatus.RECONNECTING -> "Synchronizing when connection returns"
        status != SessionStatus.RIDE_ACTIVE && status != SessionStatus.CONNECTED -> "Waiting for peer"
        else ->
            when (syncState) {
                SyncState.INACTIVE -> "Local music"
                SyncState.CLOCK_UNREADY, SyncState.WAITING_FOR_QUEUE, SyncState.SCHEDULED -> "Synchronizing"
                SyncState.WAITING_FOR_CONTENT -> "Waiting for content"
                SyncState.SYNCED -> "Synchronized"
                SyncState.SYNC_FAILED -> "Sync failed — local music continues"
                SyncState.DESYNCHRONIZED,
                SyncState.TRANSPORT_FAILED,
                SyncState.LOCAL_OVERLOAD,
                -> "Sync unavailable — local music continues"
            }
    }
