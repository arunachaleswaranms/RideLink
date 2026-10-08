package com.ridelink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    onExportDiagnostics: () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    var showRideMode by remember { mutableStateOf(false) }
    // Presentation-only: which part of the stationary app is on screen. Never session state, never
    // authority — Ride Mode above still comes from the FSM alone.
    var destination by rememberSaveable { mutableStateOf(MainDestination.HOME) }
    BackHandler(enabled = !showRideMode && destination != MainDestination.HOME) { destination = MainDestination.HOME }
    LaunchedEffect(state.status, state.returnTo) {
        showRideMode = nextRideModeVisibility(showRideMode, state.status, state.returnTo)
    }

    if (showRideMode) {
        RideModeScreen(
            coordinator = coordinator,
            musicCoordinator = musicCoordinator,
            onPlayMusic = onPlayMusic,
            syncPlaybackCoordinator = syncPlaybackCoordinator,
            sharedLibraryCoordinator = sharedLibraryCoordinator,
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
            onExportDiagnostics = onExportDiagnostics,
            destination = destination,
            onNavigate = { destination = it },
        )
    }
}

/**
 * The stationary app's destinations (Phase 9A.5 §5). Each long list lives on its own destination so
 * it can be a lazy list with a finite height — the home screen's scroll cannot host one.
 */
enum class MainDestination { HOME, LIBRARY, SHARED_MUSIC }
