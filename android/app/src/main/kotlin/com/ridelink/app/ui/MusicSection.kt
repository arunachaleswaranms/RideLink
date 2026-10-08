package com.ridelink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ridelink.app.music.MusicCoordinator

/**
 * Local music on the home screen: Now Playing and its transport. Untouched by session state — this
 * phase's brief §30's graceful-degradation rule made visible in the layout, not just in the
 * coordinator wiring. The library and Up Next are their own destinations.
 *
 * Nothing here collects [MusicCoordinator.libraryEntries]: the current track comes from
 * [MusicCoordinator.nowPlayingEntry], which does not depend on the Library screen's search.
 */
@Composable
fun MusicSection(
    musicCoordinator: MusicCoordinator,
    onPlayMusic: () -> Unit,
) {
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

    NowPlayingCard(
        ui = rememberNowPlaying(musicCoordinator),
        onPlay = onPlayMusic,
        onPause = musicCoordinator::pause,
        onSeek = musicCoordinator::seek,
        onNext = musicCoordinator::next,
        onPrevious = musicCoordinator::previous,
    )
}
