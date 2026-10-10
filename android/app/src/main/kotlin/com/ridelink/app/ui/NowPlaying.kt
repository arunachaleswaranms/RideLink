package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.PlayerState

/** What Now Playing shows, already resolved. The UI observes the one player; it owns nothing. */
internal data class NowPlayingUi(
    val player: PlayerState,
    val title: String?,
    val artist: String?,
    val artworkRef: String?,
    val transport: TransportAvailability,
)

/** Which transport controls can act. A pure function of queue and player state, unit-tested. */
internal data class TransportAvailability(
    val canPlayPause: Boolean,
    val canSkip: Boolean,
)

/**
 * Phase 9A.5 §11: Play is available whenever there is something it can start — including a queue
 * with nothing selected, which [com.ridelink.core.player.LocalQueueAction.Play] now starts from the
 * first item. Previous/Next act on the queue, so they need one. In a synchronised session the gate
 * takes these presses before the local queue sees them; the local queue then holds the one
 * materialised track, so the same rule keeps them enabled.
 *
 * PR #18 review round 4: under local ownership an empty queue's Play does nothing (`LocalQueue.Play`),
 * so a track the player merely still holds after a Clear is not offered — it was removed. While
 * [synchronized] (display ownership, rendering only) Play is forwarded to the synchronised session
 * instead, so a loaded track keeps it enabled as before.
 */
internal fun transportAvailability(
    queue: LocalQueueState,
    player: PlayerState,
    synchronized: Boolean,
): TransportAvailability =
    TransportAvailability(
        canPlayPause = player.playing || queue.items.isNotEmpty() || (synchronized && player.localEntryId != null),
        canSkip = queue.items.isNotEmpty(),
    )

/** Collects the one player's state for [NowPlayingCard] and [MiniPlayer]. */
@Composable
internal fun rememberNowPlaying(
    musicCoordinator: MusicCoordinator,
    synchronized: Boolean,
): NowPlayingUi {
    val queue by musicCoordinator.queueState.collectAsState()
    val player by musicCoordinator.playerState.collectAsState()
    val entry by musicCoordinator.nowPlayingEntry.collectAsState()
    // A verified Phase 4 cache file is not a library row; its title travels with its queue entry.
    val external = queue.currentItem?.localEntryId?.let(musicCoordinator::externalTitleFor)
    return NowPlayingUi(
        player = player,
        title = entry?.track?.title ?: external?.first,
        artist = entry?.track?.artist ?: external?.second,
        artworkRef = entry?.track?.artworkRef,
        transport = transportAvailability(queue, player, synchronized),
    )
}

/**
 * Now Playing on the home screen.
 *
 * Its height does not change when playback starts (Phase 9A.5 §13): the seek bar and both times are
 * always present, disabled until there is a duration, so nothing below the card moves under the
 * user's finger. The transport is the conventional Previous · Play/Pause · Next, with Play/Pause the
 * one filled, larger control.
 */
@Composable
internal fun NowPlayingCard(
    ui: NowPlayingUi,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RideSpace.lg)) {
                Artwork(ui.artworkRef, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlayingTitle(ui),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ui.artist?.takeIf { it.isNotBlank() } ?: if (ui.player.localEntryId ==
                            null
                        ) {
                            "Pick a track in your library"
                        } else {
                            " "
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ui.player.error?.let { error ->
                Text(playerFailureLabel(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            SeekBar(ui.player, onSeek)
            TransportRow(ui, onPlay, onPause, onNext, onPrevious)
        }
    }
}

/**
 * A fixed-height player bar for the list screens, so playback stays one tap away while browsing.
 * It is present even when nothing is playing, so the list above it never jumps when playback starts.
 */
@Composable
internal fun MiniPlayer(
    ui: NowPlayingUi,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            LinearProgressIndicator(
                progress = { fraction(ui.player.positionMs, ui.player.durationMs) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT).padding(start = RideSpace.lg, end = RideSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RideSpace.md),
            ) {
                Artwork(ui.artworkRef, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(nowPlayingTitle(ui), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                PlayPauseIcon(ui, onPlay, onPause, Modifier.size(RideSpace.touch), filled = false)
                IconButton(onClick = onNext, enabled = ui.transport.canSkip) {
                    Icon(painterResource(R.drawable.ic_transport_next), contentDescription = "Next track")
                }
            }
        }
    }
}

@Composable
private fun SeekBar(
    player: PlayerState,
    onSeek: (Long) -> Unit,
) {
    // Seek once, when the drag ends — not on every pixel of it.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(0)
    val position = player.positionMs.coerceIn(0, duration.coerceAtLeast(0)).toFloat()
    Column {
        Slider(
            value = dragging ?: position,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0,
            modifier = Modifier.semantics { contentDescription = "Seek" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragging?.toLong() ?: player.positionMs
            Text(formatMs(if (duration > 0) shown else 0), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportRow(
    ui: NowPlayingUi,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RideSpace.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = ui.transport.canSkip, modifier = Modifier.size(SECONDARY_CONTROL)) {
            Icon(painterResource(R.drawable.ic_transport_previous), contentDescription = "Previous track", Modifier.size(28.dp))
        }
        PlayPauseIcon(ui, onPlay, onPause, Modifier.size(PRIMARY_CONTROL), filled = true)
        IconButton(onClick = onNext, enabled = ui.transport.canSkip, modifier = Modifier.size(SECONDARY_CONTROL)) {
            Icon(painterResource(R.drawable.ic_transport_next), contentDescription = "Next track", Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PlayPauseIcon(
    ui: NowPlayingUi,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier,
    filled: Boolean,
) {
    val playing = ui.player.playing
    val icon = if (playing) R.drawable.ic_transport_pause else R.drawable.ic_transport_play
    val label = if (playing) "Pause" else "Play"
    val action = if (playing) onPause else onPlay
    val semantics = Modifier.semantics { stateDescription = if (playing) "Playing" else "Paused" }
    if (filled) {
        FilledIconButton(onClick = action, enabled = ui.transport.canPlayPause, modifier = modifier.then(semantics), shape = CircleShape) {
            Icon(painterResource(icon), contentDescription = label, Modifier.size(32.dp))
        }
    } else {
        IconButton(onClick = action, enabled = ui.transport.canPlayPause, modifier = modifier.then(semantics)) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

private fun nowPlayingTitle(ui: NowPlayingUi): String =
    ui.title?.takeIf { it.isNotBlank() } ?: if (ui.player.localEntryId != null) "Untitled track" else "Nothing playing"

private fun fraction(
    position: Long,
    duration: Long,
): Float = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

private val PRIMARY_CONTROL = 72.dp
private val SECONDARY_CONTROL = 56.dp
private val MINI_PLAYER_HEIGHT = 64.dp
