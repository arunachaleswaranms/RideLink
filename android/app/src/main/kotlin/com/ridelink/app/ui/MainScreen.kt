package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.resync.ResyncCoordinator
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlDiagnostics
import com.ridelink.network.control.ControlState
import com.ridelink.network.control.PairingPrompt

/** Stationary setup. Production session state owns navigation, trust and all actions. */
@Composable
fun MainScreen(
    coordinator: SessionCoordinator,
    musicCoordinator: MusicCoordinator,
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    syncPlaybackCoordinator: SyncPlaybackCoordinator,
    resyncCoordinator: ResyncCoordinator,
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
    val coexistence by coordinator.coexistenceDiagnostics.collectAsState()
    val policy by coordinator.intercomPolicy.collectAsState()
    val peerAudioState by coordinator.peerAudioState.collectAsState()
    val intercomRefusal by coordinator.lastIntercomRefusal.collectAsState()
    val remoteEntries by sharedLibraryCoordinator.remoteEntries.collectAsState()
    val downloadStates by sharedLibraryCoordinator.downloadStates.collectAsState()
    val cachedHashes by sharedLibraryCoordinator.cachedHashes.collectAsState()
    val localEntries by musicCoordinator.libraryEntries.collectAsState()
    val resyncDiagnostics by resyncCoordinator.diagnostics.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(RideSpace.xl),
            verticalArrangement = Arrangement.spacedBy(RideSpace.md, Alignment.Top),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)

            Text("Your ride, together", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConnectionSummary(state.status, peers.size)

            SessionActionButton(status = state.status, coordinator = coordinator)
            StartRideButton(status = state.status, onStartRide = coordinator::startRide)

            securityAlert?.let { code ->
                SecurityAlertCard(code = code, onDismiss = coordinator::dismissSecurityAlert)
            }

            pairingPrompt?.let { prompt ->
                PairingCard(prompt = prompt, onDecision = coordinator::confirmPairing)
            }

            // PROTOCOL §7.1 in the UI: voice controls exist only once the trust gate has passed.
            // A disabled button would still be a button; an absent card cannot be pressed.
            if (state.status == SessionStatus.CONNECTED || state.status == SessionStatus.RIDE_ACTIVE) {
                AuthenticatedSections(
                    coordinator = coordinator,
                    sharedLibraryCoordinator = sharedLibraryCoordinator,
                    syncPlaybackCoordinator = syncPlaybackCoordinator,
                    voice = voice,
                    coexistence = coexistence,
                    policy = policy,
                    peerAudioState = peerAudioState,
                    intercomRefusal = intercomRefusal,
                    remoteEntries = remoteEntries,
                    localEntries = localEntries,
                    downloadStates = downloadStates,
                    cachedHashes = cachedHashes,
                    onStartIntercom = onStartIntercom,
                    onStopIntercom = onStopIntercom,
                    onPlaySharedTrackLocally = onPlaySharedTrackLocally,
                )
            }

            // Deliberately independent of `state.status` — this phase's brief §28/§30: local music
            // must be fully usable in airplane mode, with no peer, regardless of session state.
            MusicSection(
                musicCoordinator = musicCoordinator,
                onPlayMusic = onPlayMusic,
                onPlayNow = onPlayNow,
                onImportFolder = onImportFolder,
                onImportFiles = onImportFiles,
                sharedEntries = remoteEntries,
            )
            DiagnosticDisclosure("connection diagnostics") {
                Text(deviceDescription)
                TransportBanner(diagnostics.transportLabel)
                DiagnosticsCard(
                    diagnostics = diagnostics,
                    discoveredPeerCount = peers.size,
                    discoveryCount = discoveryCount,
                    localIdentityPrefix = coordinator.localIdentityPrefix,
                )

                ResyncDiagnosticsCard(resyncDiagnostics)
            }
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
            modifier = Modifier.fillMaxSize().padding(RideSpace.xl),
            verticalArrangement = Arrangement.spacedBy(RideSpace.md, Alignment.CenterVertically),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)
            Text("Secure transport unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                "RideLink could not create or load this device's identity key, so it cannot open " +
                    "an authenticated connection. There is no unencrypted fallback.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (reason.isNotEmpty()) {
                DiagnosticDisclosure { Text(reason, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Semantic pairs remain legible in both appearances. */
internal object BannerColors {
    val InsecureBackground @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
    val InsecureText @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer
    val SecureBackground @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val SecureText @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val AlertBackground @Composable get() = MaterialTheme.colorScheme.errorContainer
    val AlertText @Composable get() = MaterialTheme.colorScheme.onErrorContainer
    val PairingBackground @Composable get() = MaterialTheme.colorScheme.primaryContainer
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
            modifier = Modifier.padding(RideSpace.md),
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
internal fun PairingCard(
    prompt: PairingPrompt,
    onDecision: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = BannerColors.PairingBackground,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(RideSpace.lg),
            verticalArrangement = Arrangement.spacedBy(RideSpace.md),
        ) {
            Text("Verify your peer", style = MaterialTheme.typography.titleMedium)
            Text(
                prompt.peerDisplayName.ifEmpty { "Nearby phone" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                prompt.sas6.chunked(SAS_GROUP_SIZE).joinToString(" "),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = prompt.sas6.toList().joinToString(" ") },
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                "Both phones must show the same six digits. If they differ, do not confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(RideSpace.md),
                verticalArrangement = Arrangement.spacedBy(RideSpace.sm),
            ) {
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
internal fun SecurityAlertCard(
    code: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BannerColors.AlertBackground, contentColor = BannerColors.AlertText),
    ) {
        Column(
            modifier = Modifier.padding(RideSpace.lg),
            verticalArrangement = Arrangement.spacedBy(RideSpace.sm),
        ) {
            Text(
                if (code == "pin_mismatch") "Security warning" else "Connection refused",
                style = MaterialTheme.typography.titleMedium,
                color = BannerColors.AlertText,
            )
            Text(securityAlertExplanation(code), style = MaterialTheme.typography.bodySmall)
            DiagnosticDisclosure(
                "security details",
            ) { Text(code, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
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
        Column(modifier = Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            Text("Connection diagnostics", style = MaterialTheme.typography.titleMedium)
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

private fun controlStateLabel(state: ControlState): String =
    when (state) {
        ControlState.IDLE -> "Idle"
        ControlState.CONNECTING -> "Connecting…"
        ControlState.CONNECTED -> "Connected"
        ControlState.RECONNECTING -> "Reconnecting…"
        ControlState.DISCONNECTED -> "Disconnected"
        ControlState.ENDED -> "Ended"
    }

private const val SAS_GROUP_SIZE = 3
