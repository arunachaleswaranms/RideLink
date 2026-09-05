package com.ridelink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.library.LibraryEntry

/**
 * Local music, entirely below the intercom/session UI and untouched by its state — this phase's
 * brief §30's graceful-degradation rule made visible in the layout, not just in the coordinator
 * wiring.
 */
@Composable
fun MusicSection(
    musicCoordinator: MusicCoordinator,
    onPlayMusic: () -> Unit,
    onPlayNow: (LibraryEntry) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
) {
    val query by musicCoordinator.query.collectAsState()
    val entries by musicCoordinator.libraryEntries.collectAsState()
    val queueState by musicCoordinator.queueState.collectAsState()
    val playerState by musicCoordinator.playerState.collectAsState()
    val lastMusicStartRefusal by musicCoordinator.lastMusicStartRefusal.collectAsState()
    // Matched by localEntryId, not quickId (ADR-005 Amendment A1) — quickId is not guaranteed
    // unique across entries, so matching on it could show the wrong track's metadata/artwork here.
    val currentEntry = queueState.currentItem?.let { item -> entries.firstOrNull { it.localEntryId == item.localEntryId } }

    Text("Local Music", style = MaterialTheme.typography.headlineSmall)

    // This phase's closure-audit hardening pass (Finding E): named rather than silent when Android
    // refuses to start the ride foreground service for music — never retried automatically.
    if (lastMusicStartRefusal != null) {
        Text(
            "Could not start music — bring RideLink to the front and try again",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    NowPlayingCard(
        playerState = playerState,
        currentEntry = currentEntry,
        queueSize = queueState.items.size,
        onPlay = onPlayMusic,
        onPause = musicCoordinator::pause,
        onSeek = musicCoordinator::seek,
        onNext = musicCoordinator::next,
        onPrevious = musicCoordinator::previous,
    )

    LibraryScreen(
        query = query,
        entries = entries,
        onSearchTextChange = musicCoordinator::setSearchText,
        onSortChange = musicCoordinator::setSort,
        onImportFolder = onImportFolder,
        onImportFiles = onImportFiles,
        onAddToQueue = musicCoordinator::addToQueue,
        onPlayNow = onPlayNow,
    )
}
