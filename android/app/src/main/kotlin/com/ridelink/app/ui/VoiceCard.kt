package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ridelink.app.music.CoexistenceDiagnostics
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.RideStartDecision
import com.ridelink.core.audiopolicy.TransmissionGate
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.voice.VoiceStatus
import com.ridelink.network.voice.VoiceDiagnostics

/**
 * Phase 2b's intercom surface: start/stop, mute, a PTT control, the five modes, and every
 * non-sensitive diagnostic PROTOCOL §7.7 permits — plus the peer's `AUDIO_STATE` (§4.4).
 *
 * **Nothing here renders an SDP, a candidate string, an IP address or a port.** §7.7 gives those no
 * display path any more than a log path, and a diagnostics screen is exactly where someone would be
 * tempted to add one. Candidate *types* are shown; candidates are not. Nothing renders a device name
 * or a Bluetooth address either — ADR-016 forbids it, and the wire carries neither.
 *
 * **No latency figure appears anywhere.** The setup timings below are exactly that: how long the app
 * took to bring voice up. Mouth-to-ear latency is TEST_PLAN A-09/V-11 and needs hardware, so nothing
 * here may be read as bearing on the <200 ms target.
 *
 * Split across composables — and across two files — rather than one, because detekt's `LongMethod`
 * and `TooManyFunctions` both fire otherwise, and because the seam is real: this file has the
 * controls, `IntercomDiagnostics.kt` has everything read-only.
 */
@Composable
internal fun VoiceCard(
    voice: VoiceDiagnostics,
    coexistence: CoexistenceDiagnostics,
    policy: IntercomPolicy,
    peerAudioState: AudioStateMessage?,
    refusal: RideStartDecision.Refused?,
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
    onToggleMute: () -> Unit,
    onPushToTalkHeld: (Boolean) -> Unit,
    onSelectPolicy: (IntercomPolicy) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(RideSpace.lg),
            verticalArrangement = Arrangement.spacedBy(RideSpace.sm),
        ) {
            Text("Intercom", style = MaterialTheme.typography.titleSmall)
            IntercomControls(voice, refusal, onStartIntercom, onStopIntercom, onToggleMute)
            IntercomModeControls(voice, policy, onSelectPolicy, onPushToTalkHeld)
            DiagnosticDisclosure("intercom diagnostics") {
                IntercomDiagnosticsSections(voice, coexistence, peerAudioState)
            }
        }
    }
}

@Composable
private fun IntercomControls(
    voice: VoiceDiagnostics,
    refusal: RideStartDecision.Refused?,
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
    onToggleMute: () -> Unit,
) {
    if (voice.peerRequestedVoice && voice.status == VoiceStatus.IDLE) {
        // ARCHITECTURE §6.4: a peer asking is never enough to open this device's microphone. The
        // prompt is the only legal route, and it says so rather than opening the mic quietly.
        Text(
            "Your peer wants to talk. Start Intercom to open your microphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    refusal?.let { refused ->
        // Named, not "connection failed" (this phase's brief §41). FR-025: the session is untouched.
        Text(
            voiceFailureLabel(refused.failure),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
        if (voice.status == VoiceStatus.IDLE || voice.status == VoiceStatus.FAILED) {
            Button(onClick = onStartIntercom) { Text("Start Intercom") }
        } else {
            OutlinedButton(onClick = onStopIntercom) { Text("Stop Intercom") }
        }
        OutlinedButton(onClick = onToggleMute, enabled = voice.localAudioOpen) {
            Text(if (voice.userMuted) "Unmute" else "Mute")
        }
    }

    Text(voiceLabel(voice.status), style = MaterialTheme.typography.titleMedium)
    Text(
        if (voice.userMuted) {
            "Muted"
        } else if (voice.transmitting) {
            "Transmitting"
        } else {
            micLabel(voice)
        },
    )
    voice.lastFailure?.let { Text(voiceFailureLabel(it), color = MaterialTheme.colorScheme.error) }
    DiagnosticDisclosure("voice details") {
        DiagnosticRow("voice state", voice.status.name)
        DiagnosticRow("role", voice.role?.name ?: "—")
        DiagnosticRow("voice session", voice.voiceSessionPrefix ?: "—")
        DiagnosticRow("peer reports", voice.peerReportedState.name)
        DiagnosticRow("mic (device)", micLabel(voice))
        DiagnosticRow("transmitting", voice.transmitting.toString())
        DiagnosticRow("wire mic_muted", voice.micMuted.toString())
        DiagnosticRow("last failure", voice.lastFailure?.name ?: "none")
    }
}

/** Whether the capture *device* is open — not whether speech is being transmitted (PROTOCOL §4.4). */
private fun micLabel(voice: VoiceDiagnostics): String =
    when {
        !voice.localAudioOpen -> "unavailable"
        voice.userMuted -> "open, muted"
        else -> "open"
    }

@Composable
private fun IntercomModeControls(
    voice: VoiceDiagnostics,
    policy: IntercomPolicy,
    onSelectPolicy: (IntercomPolicy) -> Unit,
    onPushToTalkHeld: (Boolean) -> Unit,
) {
    Text(policyLabel(policy), style = MaterialTheme.typography.bodyMedium)
    DiagnosticDisclosure("intercom modes") {
        Column {
            for (candidate in IntercomPolicy.ALL) {
                FilterChip(
                    selected = candidate.id == policy.id,
                    onClick = { onSelectPolicy(candidate) },
                    label = { Text(policyLabel(candidate)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    DiagnosticDisclosure("policy diagnostics") {
        // The default is Mode C by architecture, not by measurement — docs/PHASE0_RESULTS.md is still
        // awaiting the user's Phase 0 numbers, and saying so here keeps the screen honest.
        DiagnosticRow("policy", "${policy.id.name} (default MODE_C — architecture, not measured)")
        DiagnosticRow("gate", gateLabel(policy))
        DiagnosticRow("full duplex", policy.fullDuplex.toString())
        DiagnosticRow("wire mode", "${voice.mode.name} / ${voice.intercomMode.name}")
    }

    if (policy.gate is TransmissionGate.Vox && !voice.voxLevelSourceAvailable) {
        // ADR-021 §6, stated rather than discovered by silence: the VOX state machine is real and
        // tested, but no microphone-driven level exists on either platform yet, so the gate cannot
        // open. PENDING REAL AUDIO INPUT / LATER HARDENING.
        Text(
            "Voice activation is unavailable. Choose Push to Talk or continuous intercom.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (policy.gate == TransmissionGate.Ptt) {
        PushToTalkControl(voice.localAudioOpen, voice.userMuted, voice.pttHeld, onPushToTalkHeld)
    }
}

private fun gateLabel(policy: IntercomPolicy): String =
    when (val gate = policy.gate) {
        TransmissionGate.None -> "none (full duplex)"
        is TransmissionGate.Vox -> "vox(${gate.thresholdDbfs} dBFS, ${gate.hangoverMs} ms)"
        TransmissionGate.Ptt -> "ptt"
        TransmissionGate.Disabled -> "disabled (music only)"
    }
