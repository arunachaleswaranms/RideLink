package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceStatus
import com.ridelink.network.voice.VoiceDiagnostics
import java.util.Locale

/**
 * Phase 2a's voice surface: two buttons, a mute toggle, and every non-sensitive diagnostic
 * PROTOCOL §7.7 permits.
 *
 * **Nothing here renders an SDP, a candidate string, an IP address or a port.** §7.7 gives those no
 * display path any more than a log path, and a diagnostics screen is exactly where someone would be
 * tempted to add one. Candidate *types* are shown; candidates are not.
 *
 * Split across four composables rather than one because detekt's `LongMethod` fired on the first
 * version — a 99-line composable is hard to read whatever the tool thinks, and the four sections
 * below are the natural seams: controls, media, route, signalling.
 */
@Composable
internal fun VoiceCard(
    voice: VoiceDiagnostics,
    onStartVoice: () -> Unit,
    onEndVoice: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("VOICE (Phase 2a)", style = MaterialTheme.typography.titleSmall)
            VoiceControls(voice, onStartVoice, onEndVoice, onToggleMute)
            VoiceMediaDiagnostics(voice.engine)
            VoiceRouteDiagnostics(voice.route)
            VoiceSignallingDiagnostics(voice)
        }
    }
}

@Composable
private fun VoiceControls(
    voice: VoiceDiagnostics,
    onStartVoice: () -> Unit,
    onEndVoice: () -> Unit,
    onToggleMute: () -> Unit,
) {
    if (voice.peerRequestedVoice && voice.status == VoiceStatus.IDLE) {
        // ARCHITECTURE §6.4: a peer asking is never enough to open this device's microphone. The
        // prompt is the only legal route, and it says so rather than opening the mic quietly.
        Text(
            "Your peer wants to talk. Start Voice to open your microphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (voice.status == VoiceStatus.IDLE || voice.status == VoiceStatus.FAILED) {
            Button(onClick = onStartVoice) { Text("Start Voice") }
        } else {
            OutlinedButton(onClick = onEndVoice) { Text("End Voice") }
        }
        OutlinedButton(onClick = onToggleMute, enabled = voice.localAudioOpen) {
            Text(if (voice.micMuted) "Unmute" else "Mute")
        }
    }

    DiagnosticRow("voice state", voice.status.name)
    DiagnosticRow("role", voice.role?.name ?: "—")
    DiagnosticRow("voice session", voice.voiceSessionPrefix ?: "—")
    DiagnosticRow("peer reports", voice.peerReportedState.name)
    DiagnosticRow("mic", micLabel(voice))
    DiagnosticRow("mode", voice.mode.name)
}

private fun micLabel(voice: VoiceDiagnostics): String =
    when {
        !voice.localAudioOpen -> "unavailable"
        voice.micMuted -> "muted"
        else -> "open"
    }

@Composable
private fun VoiceMediaDiagnostics(engine: VoiceEngineDiagnostics) {
    Text("MEDIA", style = MaterialTheme.typography.labelSmall)
    DiagnosticRow("peer connection", engine.transportState.name)
    DiagnosticRow("ice gathering", engine.iceGatheringState.name)
    DiagnosticRow("dtls", engine.dtlsState ?: "—")
    DiagnosticRow("srtp cipher", engine.srtpCipher ?: "—")
    DiagnosticRow("dtls cipher", engine.dtlsCipher ?: "—")
    DiagnosticRow(
        "selected candidate",
        listOfNotNull(engine.selectedLocalType?.name, engine.selectedRemoteType?.name)
            .joinToString(" / ")
            .ifEmpty { "—" },
    )
    DiagnosticRow("codec", codecLabel(engine))
    DiagnosticRow("local / remote track", "${engine.localAudioTrackPresent} / ${engine.remoteAudioTrackPresent}")
    DiagnosticRow("packets sent / recv", "${engine.packetsSent ?: "—"} / ${engine.packetsReceived ?: "—"}")
    DiagnosticRow("loss / jitter", "${engine.packetsLost ?: "—"} / ${formatMs(engine.jitterMs)}")
    DiagnosticRow("media rtt", formatMs(engine.roundTripTimeMs))
    DiagnosticRow(
        "aec / ns / agc",
        listOf(
            engine.audioProcessing.echoCancellationEnabled,
            engine.audioProcessing.noiseSuppressionEnabled,
            engine.audioProcessing.autoGainControlEnabled,
        ).joinToString(" / ") { it?.toString() ?: "—" },
    )
}

private fun codecLabel(engine: VoiceEngineDiagnostics): String =
    engine.negotiatedCodec?.let { codec ->
        listOfNotNull(
            codec,
            engine.negotiatedClockRateHz?.let { "$it Hz" },
            engine.negotiatedChannels?.let { "${it}ch" },
        ).joinToString(" ")
    } ?: "—"

@Composable
private fun VoiceRouteDiagnostics(route: AudioRouteSnapshot) {
    Text("ROUTE", style = MaterialTheme.typography.labelSmall)
    DiagnosticRow("endpoint", route.endpointClass.name)
    DiagnosticRow(
        "out / in profile",
        "${route.effectiveOutputProfile.name} / ${route.effectiveInputProfile.name}",
    )
    // ADR-016's whole point rendered in one row: with the microphone open on Bluetooth the honest
    // answer is `REDUCED`, and both users can see it. The old model could only reassure falsely.
    DiagnosticRow("media quality", route.mediaQuality.name)
    DiagnosticRow("coupling", route.profileCoupling.name)
    DiagnosticRow("route state", route.routeState.name)
    DiagnosticRow("confidence", route.confidence.name)
    DiagnosticRow("last change", route.lastChangeReason.name)
    DiagnosticRow("interrupted", route.interrupted.toString())
}

@Composable
private fun VoiceSignallingDiagnostics(voice: VoiceDiagnostics) {
    Text("SIGNALLING", style = MaterialTheme.typography.labelSmall)
    DiagnosticRow(
        "queued candidates",
        "${voice.queuedCandidates} (dropped ${voice.droppedQueuedCandidates})",
    )
    DiagnosticRow("rebuilds", voice.rebuildCount.toString())
    DiagnosticRow(
        "dropped signals",
        voice.droppedSignals.entries
            .joinToString(", ") { "${it.key.name}=${it.value}" }
            .ifEmpty { "none" },
    )

    if (voice.unexpectedCandidateTypeSeen) {
        // PROTOCOL §7.6 configures no STUN and no TURN, so a reflexive or relayed candidate would
        // mean something contacted a server outside the local network. Reported loudly rather than
        // made fatal: a false alarm on a ride is better than a crash.
        Text(
            "UNEXPECTED ICE CANDIDATE TYPE — RideLink configures no STUN or TURN server, so only " +
                "host candidates should appear (PROTOCOL §7.6).",
            color = BannerColors.AlertText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatMs(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"
