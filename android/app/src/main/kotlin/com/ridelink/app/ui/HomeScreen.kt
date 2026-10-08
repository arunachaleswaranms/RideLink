package com.ridelink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ridelink.app.R
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.resync.ResyncCoordinator
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.sessionfsm.SessionStatus

/**
 * The home destination: connection first, then the intercom and Now Playing, then links to the long
 * lists. Bounded content only — every list that can grow with a library is a separate, lazy
 * destination (STATUS §4 problem 114).
 */
@Suppress("LongParameterList", "LongMethod") // the home screen's own inputs; each section is its own composable
@Composable
internal fun HomeContent(
    coordinator: SessionCoordinator,
    musicCoordinator: MusicCoordinator,
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    syncPlaybackCoordinator: SyncPlaybackCoordinator,
    resyncCoordinator: ResyncCoordinator,
    deviceDescription: String,
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
    onPlayMusic: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
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
    val libraryCount by musicCoordinator.libraryCount.collectAsState()
    val importProgress by musicCoordinator.imports.progress.collectAsState()
    val preparing by musicCoordinator.imports.preparing.collectAsState()
    val resyncDiagnostics by resyncCoordinator.diagnostics.collectAsState()
    val authenticated = state.status == SessionStatus.CONNECTED || state.status == SessionStatus.RIDE_ACTIVE

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RideSpace.lg, vertical = RideSpace.lg),
        verticalArrangement = Arrangement.spacedBy(RideSpace.xl),
    ) {
        Text("RideLink", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

        Column(verticalArrangement = Arrangement.spacedBy(RideSpace.md)) {
            ConnectionSummary(state.status, peers.size)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RideSpace.sm), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
                StartRideButton(status = state.status, onStartRide = coordinator::startRide)
                SessionActionButton(status = state.status, coordinator = coordinator, secondary = state.status == SessionStatus.CONNECTED)
            }
        }

        securityAlert?.let { code -> SecurityAlertCard(code = code, onDismiss = coordinator::dismissSecurityAlert) }
        pairingPrompt?.let { prompt -> PairingCard(prompt = prompt, onDecision = coordinator::confirmPairing) }

        // PROTOCOL §7.1 in the UI: voice controls exist only once the trust gate has passed.
        // A disabled button would still be a button; an absent section cannot be pressed.
        if (authenticated) {
            HomeSection("Intercom") {
                AuthenticatedIntercomSection(
                    coordinator = coordinator,
                    voice = voice,
                    coexistence = coexistence,
                    policy = policy,
                    peerAudioState = peerAudioState,
                    intercomRefusal = intercomRefusal,
                    onStartIntercom = onStartIntercom,
                    onStopIntercom = onStopIntercom,
                )
            }
        }

        // Deliberately independent of `state.status` — local music must be fully usable in
        // airplane mode, with no peer, regardless of session state.
        HomeSection("Music") {
            MusicSection(musicCoordinator = musicCoordinator, onPlayMusic = onPlayMusic, sharedEntries = remoteEntries)
            ImportStatusPanel(
                progress = importProgress,
                preparing = preparing,
                onConfirm = musicCoordinator.imports::confirm,
                onCancel = musicCoordinator.imports::cancel,
                onDismiss = musicCoordinator.imports::dismiss,
            )
            NavigationGroup {
                NavigationRow(R.drawable.ic_library, "Library", tracks(libraryCount)) { onNavigate(MainDestination.LIBRARY) }
                if (authenticated) {
                    NavigationRow(R.drawable.ic_library, "Other phone's music", tracks(remoteEntries.size)) {
                        onNavigate(MainDestination.SHARED_MUSIC)
                    }
                }
            }
            // PROTOCOL §8's catalogue plane and §5/§9's synchronisation plane are gated the same way
            // voice is: an unpaired phone never receives the library and never moves this one's music.
            if (authenticated) {
                SyncPlaybackCard(
                    sync = syncPlaybackCoordinator,
                    titles = remoteEntries.associate { it.contentHash?.value.orEmpty() to it.title },
                )
            }
        }

        ConnectionDiagnosticsSection(
            deviceDescription = deviceDescription,
            diagnostics = diagnostics,
            discoveredPeerCount = peers.size,
            discoveryCount = discoveryCount,
            localIdentityPrefix = coordinator.localIdentityPrefix,
            resyncDiagnostics = resyncDiagnostics,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

/** A titled home-screen section: a heading and spacing, not a bordered box. */
@Composable
private fun HomeSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RideSpace.md)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/** Links to the long lists, grouped on one tonal surface like a settings group. */
@Composable
internal fun NavigationGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
internal fun NavigationRow(
    icon: Int,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RideSpace.touch + RideSpace.sm)
            .clickable(onClickLabel = "Open", onClick = onClick)
            .padding(horizontal = RideSpace.lg, vertical = RideSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RideSpace.lg),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
