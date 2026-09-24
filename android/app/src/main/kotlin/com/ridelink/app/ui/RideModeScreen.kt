package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.session.SessionCoordinator

/**
 * REQUIREMENTS FR-018 / §10.2's simplified riding screen (Phase 7, ADR-028): connection status,
 * now playing, large playback/mic controls, intercom mode and End Ride — and nothing else.
 * Detailed diagnostics stay on [MainScreen]; this screen never renders a raw protocol value.
 *
 * **Every control here calls straight through an existing entry point** — [MusicCoordinator] for
 * transport (the same calls [MusicSection] already makes), [SessionCoordinator] for the microphone
 * gate and mode ([VoiceController]/[com.ridelink.core.audiopolicy.IntercomTransmission] unchanged),
 * and [SessionCoordinator.endRide] for the FSM's own `RIDE_ACTIVE -> CONNECTED` transition. Nothing
 * here mutates a `StateFlow` directly or invents a second path for anything it shows.
 */
@Composable
fun RideModeScreen(
    coordinator: SessionCoordinator,
    musicCoordinator: MusicCoordinator,
    onPlayMusic: () -> Unit,
    syncPlaybackCoordinator: com.ridelink.app.sync.SyncPlaybackCoordinator,
    sharedLibraryCoordinator: com.ridelink.app.library.SharedLibraryCoordinator,
) {
    val sharedEntries by sharedLibraryCoordinator.remoteEntries.collectAsState()
    val syncDiagnostics by syncPlaybackCoordinator.diagnostics.collectAsState()
    val fsmState by coordinator.state.collectAsState()
    val diagnostics by coordinator.controlDiagnostics.collectAsState()
    val voice by coordinator.voiceDiagnostics.collectAsState()
    val peerAudioState by coordinator.peerAudioState.collectAsState()
    val playerState by musicCoordinator.playerState.collectAsState()
    val queueState by musicCoordinator.queueState.collectAsState()
    val entries by musicCoordinator.libraryEntries.collectAsState()
    val currentEntry = queueState.currentItem?.let { item -> entries.firstOrNull { it.localEntryId == item.localEntryId } }

    val ui =
        rideModeUiState(
            status = fsmState.status,
            reconnectCount = diagnostics.reconnectCount,
            playerState = playerState,
            currentEntry = currentEntry,
            voice = voice,
            peerAudioState = peerAudioState,
        )

    val cachedEntry = sharedEntries.firstOrNull { it.contentHash != null && it.contentHash == musicCoordinator.activeExternalCacheHash() }
    RideModeContent(
        ui = ui.copy(trackTitle = ui.trackTitle ?: cachedEntry?.title, trackArtist = ui.trackArtist ?: cachedEntry?.artist),
        syncText = rideMusicLabel(fsmState.status, syncDiagnostics.syncState, syncPlaybackCoordinator.isSynchronizedModeActive()),
        voiceText = voiceLabel(voice.status),
        microphoneText =
            when {
                !voice.localAudioOpen -> "Microphone unavailable"
                voice.userMuted -> "Muted"
                voice.transmitting -> "Transmitting"
                else -> "Microphone ready"
            },
        policyText = policyLabel(voice.policy),
        onPrevious = musicCoordinator::previous,
        onPlayPause = { if (ui.isPlaying) musicCoordinator.pause() else onPlayMusic() },
        onNext = musicCoordinator::next,
        onToggleMute = { coordinator.setMicrophoneMuted(!voice.userMuted) },
        onPushToTalkHeld = coordinator::setPushToTalkHeld,
        onReconnect = coordinator::retryDiscovery,
        onEndRide = coordinator::endRide,
    )
}

/** Passive rendering seam also used by emulator visual tests. Callbacks retain production ownership. */
@Composable
internal fun RideModeContent(
    ui: RideModeUiState,
    syncText: String,
    voiceText: String,
    microphoneText: String,
    policyText: String,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleMute: () -> Unit,
    onPushToTalkHeld: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onEndRide: () -> Unit,
) {
    RideLinkTheme(dark = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(RideSpace.xl),
                verticalArrangement = Arrangement.spacedBy(RideSpace.xl),
            ) {
                Text("RIDE MODE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RideConnectionBanner(ui.connectionHealth, onReconnect)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(RideSpace.xl), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge)
                        Text(
                            ui.trackTitle?.takeIf { it.isNotBlank() } ?: if (ui.hasTrackLoaded) "Untitled track" else "Nothing playing",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        ui.trackArtist?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (ui.isPlaying) {
                                "Playing"
                            } else if (ui.hasTrackLoaded) {
                                "Paused"
                            } else {
                                "Choose music in setup before your ride."
                            },
                        )
                        Text(syncText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RideSpace.md)) {
                    RideTransportButton("Previous", R.drawable.ic_transport_previous, Modifier.weight(1f), onPrevious)
                    RideTransportButton(
                        if (ui.isPlaying) "Pause" else "Play",
                        if (ui.isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play,
                        Modifier.weight(1f),
                        onPlayPause,
                        enabled = ui.hasTrackLoaded || ui.isPlaying,
                    )
                    RideTransportButton("Next", R.drawable.ic_transport_next, Modifier.weight(1f), onNext)
                }
                Column(verticalArrangement = Arrangement.spacedBy(RideSpace.md)) {
                    Text(voiceText, style = MaterialTheme.typography.titleLarge)
                    Text(microphoneText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (ui.pttMode) PushToTalkControl(ui.micAvailable, ui.micMuted, ui.pttHeld, onPushToTalkHeld)
                    OutlinedButton(
                        onClick = onToggleMute,
                        enabled = ui.micAvailable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = RideSpace.rideTouch),
                    ) {
                        Text(if (ui.micMuted) "Unmute microphone" else "Mute microphone")
                    }
                    Text(policyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.localAudioDegraded || ui.peerAudioDegraded) {
                        Text("Audio quality reduced · Check audio devices when stopped", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                HorizontalDivider()
                OutlinedButton(
                    onClick = onEndRide,
                    modifier = Modifier.fillMaxWidth().heightIn(min = RideSpace.rideTouch),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text("End Ride", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun RideConnectionBanner(
    health: RideConnectionHealth,
    onReconnect: () -> Unit,
) {
    val label =
        when (health) {
            RideConnectionHealth.HEALTHY -> "Connected"
            RideConnectionHealth.DEGRADED -> "Reconnecting…"
            RideConnectionHealth.DISCONNECTED -> "Disconnected"
        }
    val color =
        when (health) {
            RideConnectionHealth.HEALTHY -> MaterialTheme.colorScheme.primary
            RideConnectionHealth.DEGRADED -> MaterialTheme.colorScheme.tertiary
            RideConnectionHealth.DISCONNECTED -> MaterialTheme.colorScheme.error
        }
    Column(verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = color)
        if (health == RideConnectionHealth.DEGRADED) Text("Trying to reconnect automatically.")
        if (health == RideConnectionHealth.DISCONNECTED) {
            Text("Peer features are unavailable. Check the shared network.")
            Button(onClick = onReconnect, modifier = Modifier.heightIn(min = RideSpace.rideTouch)) { Text("Reconnect") }
        }
    }
}

@Composable
internal fun RideTransportButton(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = RideSpace.rideTouch),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(RideSpace.sm),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
