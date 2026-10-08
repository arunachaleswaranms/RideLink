package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ridelink.app.resync.ResyncDiagnostics
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlDiagnostics
import com.ridelink.network.control.PairingPrompt

/**
 * The stationary app. Production session state owns trust and every action; [destination] is only
 * which part of it is on screen (Phase 9A.5 §5).
 *
 * The home screen is a short, bounded column: connection, intercom, Now Playing and links to the
 * long lists. The library and the other phone's music are separate destinations because each is a
 * lazy list that needs a finite height — STATUS §4 problem 114 was the library composed in full
 * inside this screen's scroll.
 */
@Suppress("LongParameterList") // one callback per Activity-owned action; RideLinkRoot passes each through
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
    /** Up Next's "tap an entry to play it", held to the same discipline as [onPlayNow]. */
    onPlayQueueItem: (String) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onPlaySharedTrackLocally: (ManifestEntry) -> Unit,
    /** NFR-08: share the redacted event log. Routed through the Activity, which owns the share sheet. */
    onExportDiagnostics: () -> Unit,
    destination: MainDestination = MainDestination.HOME,
    onNavigate: (MainDestination) -> Unit = {},
) {
    val state by coordinator.state.collectAsState()
    val authenticated = state.status == SessionStatus.CONNECTED || state.status == SessionStatus.RIDE_ACTIVE
    // The other phone's music exists only past the trust gate; losing it returns home rather than
    // leaving a screen whose actions can no longer be admitted.
    LaunchedEffect(destination, authenticated) {
        if (destination == MainDestination.SHARED_MUSIC && !authenticated) onNavigate(MainDestination.HOME)
    }

    // The one player, observed — never a second owner. Shown under the long lists so playback stays
    // reachable while browsing, at a fixed height so the list above it never shifts.
    val miniPlayer: @Composable () -> Unit = {
        MiniPlayer(
            rememberNowPlaying(musicCoordinator),
            onPlay = onPlayMusic,
            onPause = musicCoordinator::pause,
            onNext = musicCoordinator::next,
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (destination) {
                MainDestination.HOME ->
                    HomeContent(
                        coordinator = coordinator,
                        musicCoordinator = musicCoordinator,
                        sharedLibraryCoordinator = sharedLibraryCoordinator,
                        syncPlaybackCoordinator = syncPlaybackCoordinator,
                        resyncCoordinator = resyncCoordinator,
                        deviceDescription = deviceDescription,
                        onStartIntercom = onStartIntercom,
                        onStopIntercom = onStopIntercom,
                        onPlayMusic = onPlayMusic,
                        onExportDiagnostics = onExportDiagnostics,
                        onNavigate = onNavigate,
                    )
                MainDestination.LIBRARY ->
                    LibraryRoute(
                        musicCoordinator = musicCoordinator,
                        actions = libraryActions(musicCoordinator, onNavigate, onPlayNow, onImportFolder, onImportFiles),
                        bottomBar = miniPlayer,
                    )
                MainDestination.UP_NEXT ->
                    UpNextRoute(
                        musicCoordinator = musicCoordinator,
                        synchronized = syncPlaybackCoordinator.isSynchronizedModeActive(),
                        actions =
                            UpNextActions(
                                onBack = { onNavigate(MainDestination.HOME) },
                                onSelect = onPlayQueueItem,
                                onRemove = musicCoordinator::removeFromQueue,
                                onMove = musicCoordinator::moveInQueue,
                                onClear = musicCoordinator::clearQueue,
                                onOpenLibrary = { onNavigate(MainDestination.LIBRARY) },
                            ),
                        bottomBar = miniPlayer,
                    )
                MainDestination.SHARED_MUSIC ->
                    SharedMusicRoute(
                        sharedLibraryCoordinator = sharedLibraryCoordinator,
                        syncPlaybackCoordinator = syncPlaybackCoordinator,
                        musicCoordinator = musicCoordinator,
                        onBack = { onNavigate(MainDestination.HOME) },
                        onPlayHere = onPlaySharedTrackLocally,
                    )
            }
        }
    }
}

@Suppress("LongParameterList") // the section's own inputs, previously inline in MainScreen
@Composable
internal fun ConnectionDiagnosticsSection(
    deviceDescription: String,
    diagnostics: ControlDiagnostics,
    discoveredPeerCount: Int,
    discoveryCount: Int,
    localIdentityPrefix: String,
    resyncDiagnostics: ResyncDiagnostics,
    onExportDiagnostics: () -> Unit,
) {
    DiagnosticDisclosure("connection diagnostics") {
        Text(deviceDescription)
        TransportBanner(diagnostics.transportLabel)
        DiagnosticsCard(
            diagnostics = diagnostics,
            discoveredPeerCount = discoveredPeerCount,
            discoveryCount = discoveryCount,
            localIdentityPrefix = localIdentityPrefix,
        )

        ResyncDiagnosticsCard(resyncDiagnostics)
        DiagnosticsExportCard(onExport = onExportDiagnostics)
    }
}

/** The Library screen's actions, each reaching its existing owner. */
private fun libraryActions(
    musicCoordinator: MusicCoordinator,
    onNavigate: (MainDestination) -> Unit,
    onPlayNow: (LibraryEntry) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
) = LibraryActions(
    onBack = { onNavigate(MainDestination.HOME) },
    onSearchTextChange = musicCoordinator::setSearchText,
    onSortChange = musicCoordinator::setSort,
    import =
        ImportActions(
            onImportFolder = onImportFolder,
            onImportFiles = onImportFiles,
            onConfirm = musicCoordinator.imports::confirm,
            onCancel = musicCoordinator.imports::cancel,
            onDismiss = musicCoordinator.imports::dismiss,
        ),
    onPlayNow = onPlayNow,
    onAddToQueue = musicCoordinator::addToQueue,
    onOpenUpNext = { onNavigate(MainDestination.UP_NEXT) },
)

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

private const val SAS_GROUP_SIZE = 3
