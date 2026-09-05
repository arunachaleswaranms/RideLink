package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.player.PlayerState

/**
 * Play/pause/seek/next/previous (this phase's brief §21) — REQUIREMENTS §10.2's Ride Mode gets a
 * simplified version of this later (Phase 7); this is the developer/browsing-mode surface, the same
 * scope [MainScreen]'s existing cards are.
 */
@Composable
fun NowPlayingCard(
    playerState: PlayerState,
    currentEntry: LibraryEntry?,
    queueSize: Int,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now Playing", style = MaterialTheme.typography.titleMedium)
            Text(
                currentEntry?.let { "${it.track.title} — ${it.track.artist}" } ?: "Nothing loaded",
                style = MaterialTheme.typography.bodyLarge,
            )
            playerState.error?.let { error ->
                Text("Playback error: ${playerFailureLabel(error)}", style = MaterialTheme.typography.bodySmall)
            }

            if (playerState.durationMs > 0) {
                Slider(
                    value = playerState.positionMs.coerceIn(0, playerState.durationMs).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..playerState.durationMs.toFloat(),
                )
                Text(
                    "${formatMs(playerState.positionMs)} / ${formatMs(playerState.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrevious) { Text("Previous") }
                if (playerState.playing) {
                    Button(onClick = onPause) { Text("Pause") }
                } else {
                    Button(onClick = onPlay, enabled = currentEntry != null) { Text("Play") }
                }
                Button(onClick = onNext) { Text("Next") }
            }

            Text("Queue: $queueSize item(s)", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private fun playerFailureLabel(failure: com.ridelink.core.player.MusicFailure): String =
    when (failure) {
        com.ridelink.core.player.MusicFailure.DECODE_FAILED -> "could not decode this file"
        com.ridelink.core.player.MusicFailure.FILE_MISSING -> "file not found"
        com.ridelink.core.player.MusicFailure.UNSUPPORTED_FORMAT -> "unsupported format"
        com.ridelink.core.player.MusicFailure.STORAGE_IO -> "storage error"
        com.ridelink.core.player.MusicFailure.CANCELLED -> "cancelled"
        com.ridelink.core.player.MusicFailure.FOREGROUND_SERVICE_START_FAILED -> "bring RideLink to the front and try again"
    }

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
