package com.ridelink.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.audiopolicy.IntercomPolicy

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
) {
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionBanner(ui.connectionHealth, ui.reconnectCount)

            NowPlayingSummary(ui)

            PlaybackControls(
                isPlaying = ui.isPlaying,
                hasTrackLoaded = ui.hasTrackLoaded,
                onPrevious = musicCoordinator::previous,
                onPlay = onPlayMusic,
                onPause = musicCoordinator::pause,
                onNext = musicCoordinator::next,
            )

            MicrophoneControl(
                ui = ui,
                onToggleMute = { coordinator.setMicrophoneMuted(!voice.userMuted) },
                onPushToTalkHeld = coordinator::setPushToTalkHeld,
            )

            IntercomModeRow(
                currentPolicy = voice.policy,
                label = ui.intercomModeLabel,
                onSelectPolicy = coordinator::selectIntercomPolicy,
            )

            AudioHealthRow(ui)

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = coordinator::endRide,
                    modifier = Modifier.fillMaxWidth().height(RIDE_TOUCH_TARGET_DP.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("End Ride", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(
    health: RideConnectionHealth,
    reconnectCount: Int,
) {
    val (background, text, label) =
        when (health) {
            RideConnectionHealth.HEALTHY -> Triple(BannerColors.SecureBackground, BannerColors.SecureText, "Connected")
            RideConnectionHealth.DEGRADED -> Triple(BannerColors.InsecureBackground, BannerColors.InsecureText, "Reconnecting…")
            RideConnectionHealth.DISCONNECTED -> Triple(BannerColors.AlertBackground, BannerColors.AlertText, "Disconnected")
        }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall, color = text)
            // PROTOCOL §10: a transient loss is passive and expected for up to 120 s — no action
            // demanded here. Only the exhausted-budget case gets one, and it is the existing Retry
            // affordance already on MainScreen once the FSM actually reaches DISCONNECTED.
            if (health == RideConnectionHealth.DEGRADED) {
                Text(
                    "Your peer will reconnect automatically. Music keeps playing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = text,
                )
            }
            if (health == RideConnectionHealth.DISCONNECTED) {
                Text(
                    "Reconnect window used up. Bring RideLink to the front to retry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = text,
                )
            }
            if (reconnectCount > 0) {
                Text("Reconnected $reconnectCount time(s) this ride", style = MaterialTheme.typography.labelSmall, color = text)
            }
        }
    }
}

@Composable
private fun NowPlayingSummary(ui: RideModeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                ui.trackTitle ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Start,
            )
            if (ui.trackArtist != null) {
                Text(ui.trackArtist, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (ui.isPlaying) {
                    "Playing"
                } else if (ui.hasTrackLoaded) {
                    "Paused"
                } else {
                    "—"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    hasTrackLoaded: Boolean,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RideControlButton("Prev", Modifier.weight(1f), onClick = onPrevious)
        if (isPlaying) {
            RideControlButton("Pause", Modifier.weight(1f), onClick = onPause)
        } else {
            RideControlButton("Play", Modifier.weight(1f), enabled = hasTrackLoaded, onClick = onPlay)
        }
        RideControlButton("Next", Modifier.weight(1f), onClick = onNext)
    }
}

@Composable
private fun RideControlButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(RIDE_TOUCH_TARGET_DP.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MicrophoneControl(
    ui: RideModeUiState,
    onToggleMute: () -> Unit,
    onPushToTalkHeld: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.pttMode) {
            RidePushToTalkButton(ui, onPushToTalkHeld)
        } else {
            Button(
                onClick = onToggleMute,
                enabled = ui.micAvailable,
                modifier = Modifier.fillMaxWidth().height(RIDE_TOUCH_TARGET_DP.dp),
                colors =
                    if (ui.micMuted) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(
                    when {
                        !ui.micAvailable -> "Microphone unavailable"
                        ui.micMuted -> "Unmute"
                        else -> "Mute"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * The Ride Mode-sized twin of `VoiceCard`'s `PushToTalkButton` — identical gesture-safety shape
 * (every way a hold can end maps to "not held"; disposal releases it), calling the same
 * [onPushToTalkHeld] entry point into [com.ridelink.core.audiopolicy.IntercomTransmission]. Kept as
 * its own composable rather than reusing the `private` original because the two screens' sizing and
 * density are deliberately different (brief: "no tiny controls... usable at a glance while riding").
 */
@Composable
private fun RidePushToTalkButton(
    ui: RideModeUiState,
    onPushToTalkHeld: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose { onPushToTalkHeld(false) }
    }
    Button(
        onClick = {},
        enabled = ui.micAvailable,
        modifier =
            Modifier
                .fillMaxWidth()
                .height((RIDE_TOUCH_TARGET_DP * PTT_HEIGHT_MULTIPLIER).dp)
                .semantics { role = Role.Button }
                .pointerInput(ui.micAvailable) {
                    if (!ui.micAvailable) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPushToTalkHeld(true)
                        waitForUpOrCancellation()
                        onPushToTalkHeld(false)
                    }
                },
        colors =
            if (ui.pttHeld) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            } else {
                ButtonDefaults.buttonColors()
            },
    ) {
        Text(
            if (ui.pttHeld) "TALKING — release to stop" else "HOLD TO TALK",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun IntercomModeRow(
    currentPolicy: IntercomPolicy,
    label: String,
    onSelectPolicy: (IntercomPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Intercom: $label", style = MaterialTheme.typography.titleSmall)
        // Complex per-mode configuration (VOX threshold, hangover) stays out of Ride Mode
        // (REQUIREMENTS §10.2) — this is a simple switch among the existing IntercomPolicy presets.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (candidate in IntercomPolicy.ALL) {
                FilterChip(
                    selected = candidate.id == currentPolicy.id,
                    onClick = { onSelectPolicy(candidate) },
                    label = { Text(candidate.id.name.removePrefix("MODE_")) },
                )
            }
        }
    }
}

@Composable
private fun AudioHealthRow(ui: RideModeUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AudioHealthChip("You", ui.localAudioDegraded)
        AudioHealthChip("Peer", ui.peerAudioDegraded)
    }
}

@Composable
private fun AudioHealthChip(
    label: String,
    degraded: Boolean,
) {
    Card(
        modifier = Modifier.aspectRatio(AUDIO_HEALTH_CHIP_ASPECT_RATIO),
        colors =
            CardDefaults.cardColors(
                containerColor = if (degraded) BannerColors.InsecureBackground else BannerColors.SecureBackground,
            ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (degraded) "Reduced quality" else "Good",
                style = MaterialTheme.typography.bodySmall,
                color = if (degraded) BannerColors.InsecureText else BannerColors.SecureText,
            )
        }
    }
}

/** REQUIREMENTS §10.3: no tiny controls, usable at a glance while riding. */
private const val RIDE_TOUCH_TARGET_DP = 72
private const val PTT_HEIGHT_MULTIPLIER = 1.3
private const val AUDIO_HEALTH_CHIP_ASPECT_RATIO = 3f
