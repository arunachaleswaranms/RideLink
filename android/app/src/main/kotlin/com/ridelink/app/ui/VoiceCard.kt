package com.ridelink.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("INTERCOM (Phase 2b)", style = MaterialTheme.typography.titleSmall)
            IntercomControls(voice, refusal, onStartIntercom, onStopIntercom, onToggleMute)
            IntercomModeControls(voice, policy, onSelectPolicy, onPushToTalkHeld)
            IntercomDiagnosticsSections(voice, peerAudioState)
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
            "Intercom unavailable — ${refused.failure.name}. The peer session is unaffected.",
            color = BannerColors.AlertText,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (voice.status == VoiceStatus.IDLE || voice.status == VoiceStatus.FAILED) {
            Button(onClick = onStartIntercom) { Text("Start Intercom") }
        } else {
            OutlinedButton(onClick = onStopIntercom) { Text("Stop Intercom") }
        }
        OutlinedButton(onClick = onToggleMute, enabled = voice.localAudioOpen) {
            Text(if (voice.userMuted) "Unmute" else "Mute")
        }
    }

    DiagnosticRow("voice state", voice.status.name)
    DiagnosticRow("role", voice.role?.name ?: "—")
    DiagnosticRow("voice session", voice.voiceSessionPrefix ?: "—")
    DiagnosticRow("peer reports", voice.peerReportedState.name)
    DiagnosticRow("mic (device)", micLabel(voice))
    DiagnosticRow("transmitting", voice.transmitting.toString())
    DiagnosticRow("wire mic_muted", voice.micMuted.toString())
    DiagnosticRow("last failure", voice.lastFailure?.name ?: "none")
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
    Text("MODE", style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (candidate in IntercomPolicy.ALL) {
            FilterChip(
                selected = candidate.id == policy.id,
                onClick = { onSelectPolicy(candidate) },
                label = { Text(candidate.id.name.removePrefix("MODE_")) },
            )
        }
    }
    // The default is Mode C by architecture, not by measurement — docs/PHASE0_RESULTS.md is still
    // awaiting the user's Phase 0 numbers, and saying so here keeps the screen honest.
    DiagnosticRow("policy", "${policy.id.name} (default MODE_C — architecture, not measured)")
    DiagnosticRow("gate", gateLabel(policy))
    DiagnosticRow("full duplex", policy.fullDuplex.toString())
    DiagnosticRow("wire mode", "${voice.mode.name} / ${voice.intercomMode.name}")

    if (policy.gate is TransmissionGate.Vox && !voice.voxLevelSourceAvailable) {
        // ADR-021 §6, stated rather than discovered by silence: the VOX state machine is real and
        // tested, but no microphone-driven level exists on either platform yet, so the gate cannot
        // open. PENDING REAL AUDIO INPUT / LATER HARDENING.
        Text(
            "VOX: no microphone level source on this platform yet, so the gate cannot open — " +
                "PENDING REAL AUDIO INPUT (ADR-021 §6). Use PTT or continuous.",
            color = BannerColors.AlertText,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (policy.gate == TransmissionGate.Ptt) {
        PushToTalkButton(voice, onPushToTalkHeld)
    }
}

private fun gateLabel(policy: IntercomPolicy): String =
    when (val gate = policy.gate) {
        TransmissionGate.None -> "none (full duplex)"
        is TransmissionGate.Vox -> "vox(${gate.thresholdDbfs} dBFS, ${gate.hangoverMs} ms)"
        TransmissionGate.Ptt -> "ptt"
        TransmissionGate.Disabled -> "disabled (music only)"
    }

/**
 * Press-and-hold, with every way a hold can end mapped to the same "not held" assignment.
 *
 * This phase's brief §25 in one composable:
 *
 * - [waitForUpOrCancellation] returns null on a *cancelled* gesture — a drag off the button, a
 *   scroll taking over the pointer — and that is treated exactly as an up. There is no path through
 *   this function that leaves the gate open.
 * - The [DisposableEffect] releases on dispose, so navigating away or the composable leaving the tree
 *   while held cannot strand transmission on. Backgrounding is handled one level up, in the Activity's
 *   `onPause`, because a composable is not told about that.
 * - `semantics { role = Role.Button }` keeps it operable by an accessibility service; an activation
 *   from one produces a down and an up like any other, so it cannot create a stuck press either.
 *
 * It gates the outbound track and nothing else: no capture reopen, no peer-connection rebuild.
 */
@Composable
private fun PushToTalkButton(
    voice: VoiceDiagnostics,
    onPushToTalkHeld: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose { onPushToTalkHeld(false) }
    }
    Button(
        onClick = {},
        enabled = voice.localAudioOpen,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { role = Role.Button }
                .pointerInput(voice.localAudioOpen) {
                    if (!voice.localAudioOpen) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPushToTalkHeld(true)
                        // Null means the gesture was cancelled rather than released. Both end the
                        // hold, which is the only safe reading.
                        waitForUpOrCancellation()
                        onPushToTalkHeld(false)
                    }
                },
    ) {
        Text(if (voice.pttHeld) "TALKING — release to stop" else "HOLD TO TALK")
    }
}
