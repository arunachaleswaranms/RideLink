package com.ridelink.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.resync.ResyncCoordinator
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry

/**
 * The single screen switch (Phase 7, ADR-028): Ride Mode versus the developer/diagnostics
 * [MainScreen], decided **entirely** by [SessionCoordinator.state] via [nextRideModeVisibility] —
 * never a second navigation flag a tap could desync from the FSM. [MainActivity] calls this in
 * place of [MainScreen] directly; every parameter below is exactly what [MainScreen] already took,
 * plus the two Ride Mode actions ([onStartRide]/[onEndRide]) that dispatch through
 * [SessionCoordinator.startRide]/[SessionCoordinator.endRide] and nothing else.
 */
@Suppress("LongParameterList") // one per existing MainScreen parameter, unchanged, plus the two new FSM actions
@Composable
fun RideLinkRoot(
    coordinator: SessionCoordinator,
    musicCoordinator: MusicCoordinator,
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    syncPlaybackCoordinator: SyncPlaybackCoordinator,
    resyncCoordinator: ResyncCoordinator,
    deviceDescription: String,
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
    onPlayMusic: () -> Unit,
    onPlayNow: (LibraryEntry) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onPlaySharedTrackLocally: (ManifestEntry) -> Unit,
) {
    val state by coordinator.state.collectAsState()
    var showRideMode by remember { mutableStateOf(false) }
    LaunchedEffect(state.status, state.returnTo) {
        showRideMode = nextRideModeVisibility(showRideMode, state.status, state.returnTo)
    }

    if (showRideMode) {
        RideModeScreen(
            coordinator = coordinator,
            musicCoordinator = musicCoordinator,
            onPlayMusic = onPlayMusic,
        )
    } else {
        MainScreen(
            coordinator = coordinator,
            musicCoordinator = musicCoordinator,
            sharedLibraryCoordinator = sharedLibraryCoordinator,
            syncPlaybackCoordinator = syncPlaybackCoordinator,
            resyncCoordinator = resyncCoordinator,
            deviceDescription = deviceDescription,
            onStartIntercom = onStartIntercom,
            onStopIntercom = onStopIntercom,
            onPlayMusic = onPlayMusic,
            onPlayNow = onPlayNow,
            onImportFolder = onImportFolder,
            onImportFiles = onImportFiles,
            onPlaySharedTrackLocally = onPlaySharedTrackLocally,
        )
    }
}
