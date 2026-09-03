package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceSetupTimeline
import com.ridelink.network.voice.VoiceDiagnostics
import java.util.Locale

/**
 * The read-only half of the intercom card: media, setup timing, this device's route, the peer's
 * `AUDIO_STATE`, and signalling counters.
 *
 * Split out of `VoiceCard.kt` because detekt's `TooManyFunctions` fired on one file holding both the
 * controls and all five diagnostics sections — and the split is the natural seam anyway: nothing here
 * has an `onClick`.
 *
 * **Nothing here renders an SDP, a candidate string, an IP address or a port** (PROTOCOL §7.7), and
 * nothing renders a device name or a Bluetooth address (ADR-016). Candidate *types* are shown;
 * candidates are not. **No latency figure appears anywhere** — see [VoiceSetupDiagnostics].
 */
@Composable
internal fun IntercomDiagnosticsSections(
    voice: VoiceDiagnostics,
    peerAudioState: AudioStateMessage?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VoiceMediaDiagnostics(voice.engine)
        VoiceSetupDiagnostics(voice.setup)
        VoiceRouteDiagnostics(voice.route)
        PeerAudioStateDiagnostics(peerAudioState)
        VoiceSignallingDiagnostics(voice)
    }
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
    DiagnosticRow("aec hardware", engine.audioProcessing.hardwareEchoCancellation?.toString() ?: "—")
}

private fun codecLabel(engine: VoiceEngineDiagnostics): String =
    engine.negotiatedCodec?.let { codec ->
        listOfNotNull(
            codec,
            engine.negotiatedClockRateHz?.let { "$it Hz" },
            engine.negotiatedChannels?.let { "${it}ch" },
        ).joinToString(" ")
    } ?: "—"

/**
 * Software **setup** timings, and labelled as such.
 *
 * `media rtt` above and every figure here are network and software measurements. Mouth-to-ear latency
 * (TEST_PLAN A-09/V-11) includes two Bluetooth hops, an encoder, a jitter buffer and a decoder, and
 * cannot be inferred from any of them — see [VoiceSetupTimeline].
 */
@Composable
private fun VoiceSetupDiagnostics(setup: VoiceSetupTimeline) {
    Text("SETUP TIMING (not latency)", style = MaterialTheme.typography.labelSmall)
    DiagnosticRow("capture open", formatMs(setup.captureOpenMs))
    DiagnosticRow("local sdp", formatMs(setup.localDescriptionMs))
    DiagnosticRow("signalling", formatMs(setup.signallingMs))
    DiagnosticRow("media connected", formatMs(setup.mediaConnectedMs))
    DiagnosticRow("remote track", formatMs(setup.setupMs))
    Text(
        "Mouth-to-ear latency is NOT measured here and is not inferable from these figures " +
            "(TEST_PLAN A-09/V-11 — real hardware required).",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun VoiceRouteDiagnostics(route: AudioRouteSnapshot) {
    Text("ROUTE (this device)", style = MaterialTheme.typography.labelSmall)
    DiagnosticRow("endpoint", route.endpointClass.name)
    DiagnosticRow(
        "out / in profile",
        "${route.effectiveOutputProfile.name} / ${route.effectiveInputProfile.name}",
    )
    DiagnosticRow(
        "out / in rate",
        "${route.effectiveOutputSampleRateHz ?: "—"} / ${route.effectiveInputSampleRateHz ?: "—"}",
    )
    // ADR-016's whole point rendered in one row: with the microphone open on Bluetooth the honest
    // answer is `REDUCED`, and both users can see it. The old model could only reassure falsely.
    DiagnosticRow("media quality", route.mediaQuality.name)
    DiagnosticRow("coupling", route.profileCoupling.name)
    DiagnosticRow("route state", route.routeState.name)
    DiagnosticRow("last transition", formatMs(route.lastTransitionDurationUs?.let { it / MICROS_PER_MS }))
    DiagnosticRow("confidence", route.confidence.name)
    DiagnosticRow("last change", route.lastChangeReason.name)
    DiagnosticRow("interrupted", route.interrupted.toString())
}

/**
 * The peer's `AUDIO_STATE` (PROTOCOL §4.4) — ADR-016's reason for existing: "the pillion can hear you
 * but her music went narrowband" is a diagnosis, not a guess.
 *
 * Every value is the shared platform-neutral vocabulary. There is nothing here that could identify a
 * device, because §4.4 carries nothing of the kind.
 */
@Composable
private fun PeerAudioStateDiagnostics(peer: AudioStateMessage?) {
    Text("PEER AUDIO_STATE", style = MaterialTheme.typography.labelSmall)
    if (peer == null) {
        DiagnosticRow("received", "none yet")
        return
    }
    DiagnosticRow("revision", peer.revision.toString())
    DiagnosticRow("endpoint", peer.endpointClass.name)
    DiagnosticRow("mic open", peer.microphoneOpen.toString())
    DiagnosticRow(
        "out / in profile",
        "${peer.effectiveOutputProfile.name} / ${peer.effectiveInputProfile.name}",
    )
    DiagnosticRow(
        "out / in rate",
        "${peer.effectiveOutputSampleRateHz ?: "—"} / ${peer.effectiveInputSampleRateHz ?: "—"}",
    )
    DiagnosticRow("media quality", peer.mediaQuality.name)
    DiagnosticRow("route state", peer.routeState.name)
    DiagnosticRow("intercom mode", peer.intercomMode.name)
    DiagnosticRow("confidence", peer.confidence.name)
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

private const val MICROS_PER_MS = 1_000.0

private fun formatMs(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"
