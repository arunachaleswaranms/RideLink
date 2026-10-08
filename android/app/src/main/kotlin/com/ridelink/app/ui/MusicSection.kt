package com.ridelink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.manifest.ManifestEntry

/**
 * Local music on the home screen: Now Playing and its transport. Untouched by session state — this
 * phase's brief §30's graceful-degradation rule made visible in the layout, not just in the
 * coordinator wiring. The library itself is its own destination ([LibraryRoute]).
 *
 * Nothing here collects [MusicCoordinator.libraryEntries]: the current track comes from
 * [MusicCoordinator.nowPlayingEntry], which does not depend on the Library screen's search.
 */
@Composable
fun MusicSection(
    musicCoordinator: MusicCoordinator,
    onPlayMusic: () -> Unit,
    sharedEntries: List<ManifestEntry> = emptyList(),
) {
    val queueState by musicCoordinator.queueState.collectAsState()
    val playerState by musicCoordinator.playerState.collectAsState()
    val currentEntry by musicCoordinator.nowPlayingEntry.collectAsState()
    val lastMusicStartRefusal by musicCoordinator.lastMusicStartRefusal.collectAsState()

    // This phase's closure-audit hardening pass (Finding E): named rather than silent when Android
    // refuses to start the ride foreground service for music — never retried automatically.
    if (lastMusicStartRefusal != null) {
        Text(
            "Could not start music. Bring RideLink to the front and try again.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    val cachedEntry = sharedEntries.firstOrNull { it.contentHash == musicCoordinator.activeExternalCacheHash() && it.contentHash != null }
    NowPlayingCard(
        playerState = playerState,
        currentEntry = currentEntry,
        queueSize = queueState.items.size,
        title = currentEntry?.track?.title ?: cachedEntry?.title,
        artist = currentEntry?.track?.artist ?: cachedEntry?.artist,
        onPlay = onPlayMusic,
        onPause = musicCoordinator::pause,
        onSeek = musicCoordinator::seek,
        onNext = musicCoordinator::next,
        onPrevious = musicCoordinator::previous,
    )

    if (queueState.items.isNotEmpty()) {
        val titles by produceState(emptyMap(), queueState.items) {
            value = musicCoordinator.entriesFor(queueState.items.map { it.localEntryId }).mapValues { it.value.track.title }
        }
        DiagnosticDisclosure("local queue") {
            queueState.items.forEachIndexed { index, item ->
                val title = titles[item.localEntryId] ?: "Shared track"
                Text("${if (item.id == queueState.currentId) "Current" else "${index + 1}"} · $title")
            }
        }
    }
}
