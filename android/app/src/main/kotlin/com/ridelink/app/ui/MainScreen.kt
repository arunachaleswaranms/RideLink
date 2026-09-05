package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlDiagnostics
import com.ridelink.network.control.ControlState
import com.ridelink.network.control.PairingPrompt

/**
 * Deliberately developer-oriented (CLAUDE.md Phase 2a/2b scope): device identity, connection status,
 * the six-digit pairing prompt, security warnings, control diagnostics, and — new in Phase 2b — an
 * intercom card with Start/Stop, mute, a PTT control, mode selection, the FR-023 media and route
 * diagnostics, and the peer's `AUDIO_STATE`.
 *
 * **No Ride Mode UI belongs here yet.** This is a diagnostics surface, which ADR-020 says is what
 * this phase's UI should be: enough to drive and observe the intercom on two phones, and no design
 * decisions about a real riding screen made before anyone has ridden with it.
 *
 * Nothing here renders an SDP, a candidate string, an IP address or a port — PROTOCOL §7.7 gives
 * those no display path any more than a log path — and nothing renders a device name or a Bluetooth
 * address, which ADR-016 forbids for the same reason.
 */
@Composable
fun MainScreen(
    coordinator: SessionCoordinator,
    musicCoordinator: MusicCoordinator,
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    deviceDescription: String,
    /**
     * Routed through the Activity on purpose. ARCHITECTURE §6.4 steps 4–6: the microphone foreground
     * service must be started while the app is foreground-visible, and only a resumed Activity can
     * honestly claim that — a composable cannot.
     */
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
    /** Same foreground-visible discipline, applied to music (this phase's brief §16) — see
     *  [MainActivity.attemptMusicPlay]. */
    onPlayMusic: () -> Unit,
    /** The library screen's "tap a row to play it now" affordance, held to the exact same
     *  foreground-visible discipline as [onPlayMusic] — see [MainActivity.attemptPlayNow]. */
    onPlayNow: (LibraryEntry) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onPlaySharedTrackLocally: (ManifestEntry) -> Unit,
) {
    val state by coordinator.state.collectAsState()
    val peers by coordinator.discoveredPeers.collectAsState()
    val discoveryCount by coordinator.discoveryCount.collectAsState()
    val diagnostics by coordinator.controlDiagnostics.collectAsState()
    val pairingPrompt by coordinator.pairingPrompt.collectAsState()
    val securityAlert by coordinator.securityAlert.collectAsState()
    val voice by coordinator.voiceDiagnostics.collectAsState()
    val policy by coordinator.intercomPolicy.collectAsState()
    val peerAudioState by coordinator.peerAudioState.collectAsState()
    val intercomRefusal by coordinator.lastIntercomRefusal.collectAsState()
    val remoteEntries by sharedLibraryCoordinator.remoteEntries.collectAsState()
    val downloadStates by sharedLibraryCoordinator.downloadStates.collectAsState()
    val localEntries by musicCoordinator.libraryEntries.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)

            Text("Device:")
            Text(deviceDescription, style = MaterialTheme.typography.bodyLarge)

            Text("Connection:")
            Text(connectionLabel(state.status), style = MaterialTheme.typography.bodyLarge)

            Button(onClick = {
                if (state.status == SessionStatus.DISCOVERING) coordinator.cancelDiscovery() else coordinator.startDiscovery()
            }) {
                Text(if (state.status == SessionStatus.DISCOVERING) "Stop Discovery" else "Start Discovery")
            }

            TransportBanner(diagnostics.transportLabel)

            securityAlert?.let { code ->
                SecurityAlertCard(code = code, onDismiss = coordinator::dismissSecurityAlert)
            }

            pairingPrompt?.let { prompt ->
                PairingCard(prompt = prompt, onDecision = coordinator::confirmPairing)
            }

            // PROTOCOL §7.1 in the UI: voice controls exist only once the trust gate has passed.
            // A disabled button would still be a button; an absent card cannot be pressed.
            if (state.status == SessionStatus.CONNECTED || state.status == SessionStatus.RIDE_ACTIVE) {
                VoiceCard(
                    voice = voice,
                    policy = policy,
                    peerAudioState = peerAudioState,
                    refusal = intercomRefusal,
                    onStartIntercom = onStartIntercom,
                    onStopIntercom = onStopIntercom,
                    // The user's own Mute latch, not the wire's `mic_muted` — under PTT the latter is
                    // true whenever the button is not held, and toggling from it would be a coin flip.
                    onToggleMute = { coordinator.setMicrophoneMuted(!voice.userMuted) },
                    onPushToTalkHeld = coordinator::setPushToTalkHeld,
                    onSelectPolicy = coordinator::selectIntercomPolicy,
                )

                // PROTOCOL §8's catalogue plane, gated the same way voice is: brief §22, an
                // unpaired peer must never receive or exchange the shared library.
                SharedLibraryScreen(
                    remoteEntries = remoteEntries,
                    localEntries = localEntries,
                    downloadStates = downloadStates,
                    onDownload = sharedLibraryCoordinator::requestDownload,
                    onCancel = { entry -> entry.contentHash?.let(sharedLibraryCoordinator::cancelDownload) },
                    onPlayLocally = onPlaySharedTrackLocally,
                )
            }

            DiagnosticsCard(
                diagnostics = diagnostics,
                discoveredPeerCount = peers.size,
                discoveryCount = discoveryCount,
                localIdentityPrefix = coordinator.localIdentityPrefix,
            )

            // Deliberately independent of `state.status` — this phase's brief §28/§30: local music
            // must be fully usable in airplane mode, with no peer, regardless of session state.
            MusicSection(
                musicCoordinator = musicCoordinator,
                onPlayMusic = onPlayMusic,
                onPlayNow = onPlayNow,
                onImportFolder = onImportFolder,
                onImportFiles = onImportFiles,
            )
        }
    }
}

/**
 * Shown instead of [MainScreen] when the device identity could not be created or loaded — which is
 * the only way a session can now fail to assemble, since the transport itself is no longer
 * conditional (see `di.SecureTransportPolicy`).
 *
 * There is deliberately no "continue without security" affordance. ADR-007 Amendment A1 forbids a
 * plaintext fallback outright, so the honest thing for this screen to do is say what failed and
 * stop.
 */
@Composable
fun SecureTransportUnavailableScreen(reason: String = "") {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)
            Text("Secure transport unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                "RideLink could not create or load this device's identity key, so it cannot open " +
                    "an authenticated connection. There is no unencrypted fallback.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (reason.isNotEmpty()) {
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Suppress("MagicNumber") // named colours for the transport banner and the security cards
internal object BannerColors {
    val InsecureBackground = Color(0xFFFFF3CD)
    val InsecureText = Color(0xFF7A5B00)
    val SecureBackground = Color(0xFFDFF3E0)
    val SecureText = Color(0xFF1B5E20)
    val AlertBackground = Color(0xFFFBE3E3)
    val AlertText = Color(0xFF8B1A1A)
    val PairingBackground = Color(0xFFE3EEFB)
}

/**
 * Green once the link is TLS 1.3, because the banner's job is to be *accurate*: the Phase 1a
 * version was permanently amber and said `PLAIN / PHASE 1A / NOT SECURE`, and a banner that keeps
 * crying wolf after the transport is secure trains the user to ignore it.
 */
@Composable
private fun TransportBanner(transportLabel: String) {
    val secure = transportLabel.startsWith("TLS")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (secure) BannerColors.SecureBackground else BannerColors.InsecureBackground,
            ),
    ) {
        Text(
            "TRANSPORT: $transportLabel",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (secure) BannerColors.SecureText else BannerColors.InsecureText,
        )
    }
}

/**
 * PROTOCOL §4.5: the two users compare six digits on two screens and both confirm.
 *
 * The code is shown large and monospaced because it is read aloud across a car park, and the
 * wording says *compare*, not *enter* — there is nowhere to type it, deliberately: a code that
 * travelled between the devices would prove nothing (PROTOCOL §4.5.1).
 */
@Composable
private fun PairingCard(
    prompt: PairingPrompt,
    onDecision: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BannerColors.PairingBackground),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pair with this device?", style = MaterialTheme.typography.titleMedium)
            Text(
                prompt.peerDisplayName.ifEmpty { prompt.remotePeerId.toString() },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                prompt.sas6,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                "Both phones must show the same six digits. If they differ, do not confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDecision(true) }) { Text("They match") }
                OutlinedButton(onClick = { onDecision(false) }) { Text("They differ") }
            }
        }
    }
}

/**
 * A refused handshake the user has to see. `pin_mismatch` is the one that matters: ADR-012
 * requires it to surface as a warning and never to be resolved by silently re-pairing.
 */
@Composable
private fun SecurityAlertCard(
    code: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BannerColors.AlertBackground),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (code == "pin_mismatch") "Security warning" else "Connection refused",
                style = MaterialTheme.typography.titleMedium,
                color = BannerColors.AlertText,
            )
            Text(securityAlertExplanation(code), style = MaterialTheme.typography.bodySmall)
            Text(code, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun securityAlertExplanation(code: String): String =
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

@Composable
private fun DiagnosticsCard(
    diagnostics: ControlDiagnostics,
    discoveredPeerCount: Int,
    discoveryCount: Int,
    localIdentityPrefix: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostics (Phase 1b)", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Control state", controlStateLabel(diagnostics.controlState))
            // Both identities are shown redacted to 6 hex, matching the ARCHITECTURE §11 logging
            // rule — enough to compare two screens, far too little to identify a device.
            DiagnosticRow("This device", localIdentityPrefix)
            DiagnosticRow("Peer identity", diagnostics.peerIdentityPrefix ?: "—")
            DiagnosticRow("Cipher suite", diagnostics.cipherSuite ?: "—")
            DiagnosticRow("Peer", diagnostics.remotePeerId ?: "—")
            DiagnosticRow("Local leader", diagnostics.isLocalLeader?.toString() ?: "—")
            DiagnosticRow("RTT", diagnostics.rttMs?.let { "%.1f ms".format(it) } ?: "—")
            DiagnosticRow("Clock offset", diagnostics.clockOffsetUs?.let { "$it µs" } ?: "—")
            DiagnosticRow("Clock jitter", diagnostics.clockJitterUs?.let { "$it µs" } ?: "—")
            DiagnosticRow("Reconnect count", diagnostics.reconnectCount.toString())
            DiagnosticRow("Discovered peers (current)", discoveredPeerCount.toString())
            DiagnosticRow("Discovery count (cumulative)", discoveryCount.toString())
        }
    }
}

@Composable
internal fun DiagnosticRow(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun connectionLabel(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "Idle"
        SessionStatus.DISCOVERING -> "Discovering…"
        SessionStatus.PAIRING -> "Pairing…"
        SessionStatus.CONNECTING -> "Connecting…"
        SessionStatus.CONNECTED -> "Connected"
        SessionStatus.RIDE_ACTIVE -> "Ride Active"
        SessionStatus.RECONNECTING -> "Reconnecting…"
        SessionStatus.DISCONNECTED -> "Disconnected"
        SessionStatus.ENDING -> "Ending…"
        SessionStatus.ERROR -> "Error"
    }

private fun controlStateLabel(state: ControlState): String =
    when (state) {
        ControlState.IDLE -> "Idle"
        ControlState.CONNECTING -> "Connecting…"
        ControlState.CONNECTED -> "Connected"
        ControlState.RECONNECTING -> "Reconnecting…"
        ControlState.DISCONNECTED -> "Disconnected"
        ControlState.ENDED -> "Ended"
    }
