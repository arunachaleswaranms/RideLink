package com.ridelink.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.app.library.DownloadState
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.model.ContentHash
import com.ridelink.core.playback.SharedQueueState
import com.ridelink.core.transfer.TransferStatus

/** What the other phone's catalogue screen draws. */
internal data class SharedMusicUiState(
    val remoteEntries: List<ManifestEntry>,
    /** Content hashes playable here: a usable library row or a verified cache file. */
    val playableHere: Set<String>,
    /** The subset of [playableHere] that came from this phone's own library. */
    val inLibrary: Set<String>,
    val downloadStates: Map<String, DownloadState>,
    val sharedQueue: SharedQueueState,
    /** Whether a synchronised session exists at all — ADR-010's role is shown nowhere. */
    val syncAvailable: Boolean,
)

internal class SharedMusicActions(
    val onBack: () -> Unit = {},
    val onDownload: (ManifestEntry) -> Unit = {},
    val onCancelDownload: (ContentHash) -> Unit = {},
    val onPlayHere: (ManifestEntry) -> Unit = {},
    val onPlayOnBoth: (ContentHash) -> Unit = {},
    val onAddToSharedQueue: (ContentHash) -> Unit = {},
    val onRemoveFromSharedQueue: (String) -> Unit = {},
)

/** Collects Phase 4/5 state for [SharedMusicContent]. Shown only past the trust gate. */
@Composable
internal fun SharedMusicRoute(
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    syncPlaybackCoordinator: SyncPlaybackCoordinator,
    musicCoordinator: MusicCoordinator,
    onBack: () -> Unit,
    onPlayHere: (ManifestEntry) -> Unit,
) {
    val remote by sharedLibraryCoordinator.remoteEntries.collectAsState()
    val downloads by sharedLibraryCoordinator.downloadStates.collectAsState()
    val cached by sharedLibraryCoordinator.cachedHashes.collectAsState()
    val local by musicCoordinator.localContentHashes.collectAsState()
    val queue by syncPlaybackCoordinator.queueState.collectAsState()
    val diagnostics by syncPlaybackCoordinator.diagnostics.collectAsState()
    SharedMusicContent(
        SharedMusicUiState(remote, local + cached, local, downloads, queue, syncAvailable = diagnostics.role != null),
        SharedMusicActions(
            onBack = onBack,
            onDownload = sharedLibraryCoordinator::requestDownload,
            onCancelDownload = sharedLibraryCoordinator::cancelDownload,
            onPlayHere = onPlayHere,
            onPlayOnBoth = syncPlaybackCoordinator::playSynchronized,
            onAddToSharedQueue = syncPlaybackCoordinator::enqueue,
            onRemoveFromSharedQueue = { syncPlaybackCoordinator.removeFromQueue(it) },
        ),
    )
}

/**
 * The other phone's music (Phase 4's catalogue and Phase 5's shared queue), as one lazy list.
 *
 * Phase 4 drew every catalogue entry eagerly inside the home screen's scroll, and Phase 5 drew a
 * second eager "playable on both phones" list beside it — the same shape as problem 114 with the
 * other phone's library as the input. Both are rows of this list now, each with the actions that
 * apply to it. Availability is computed from owned state only (a usable local row, a verified cache
 * file, or a live transfer), never inferred.
 */
@Composable
internal fun SharedMusicContent(
    state: SharedMusicUiState,
    actions: SharedMusicActions,
) {
    val titles = remember(state.remoteEntries) { state.remoteEntries.associate { it.contentHash?.value.orEmpty() to it.title } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(RideSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = RideSpace.xs)) {
                Text("Other phone's music", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    tracks(state.remoteEntries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.weight(1f).testTag(SHARED_LIST_TAG), contentPadding = PaddingValues(bottom = RideSpace.lg)) {
            if (state.syncAvailable) {
                item(key = "queue-header", contentType = "header") { SectionHeader("Shared queue", state.sharedQueue.items.size) }
                if (state.sharedQueue.items.isEmpty()) {
                    item(key = "queue-empty", contentType = "note") { Note("Nothing queued. Add a track available on both phones.") }
                }
                items(state.sharedQueue.items, key = { "q:" + it.queueItemId }, contentType = { "queue" }) { item ->
                    SharedQueueRow(
                        title = titles[item.trackHash.value]?.takeIf { it.isNotBlank() } ?: "Shared track",
                        isCurrent = item.queueItemId == state.sharedQueue.currentItemId,
                        onRemove = { actions.onRemoveFromSharedQueue(item.queueItemId) },
                    )
                }
            }
            item(key = "tracks-header", contentType = "header") { SectionHeader("Tracks", state.remoteEntries.size) }
            if (state.remoteEntries.isEmpty()) {
                item(key = "tracks-empty", contentType = "note") { Note("No shared music yet. Import music on either phone.") }
            }
            // Positional keys on purpose: the catalogue is the other phone's input, and two of its
            // entries may legitimately share a content hash. A content-derived key would crash on
            // that duplicate (LazyColumn requires unique keys); a position cannot repeat.
            items(state.remoteEntries, contentType = { "track" }) { entry ->
                SharedTrackRow(entry, state, actions)
            }
        }
    }
}

@Composable
private fun SharedTrackRow(
    entry: ManifestEntry,
    state: SharedMusicUiState,
    actions: SharedMusicActions,
) {
    val hash = entry.contentHash
    val inLibrary = hash != null && hash.value in state.inLibrary
    val playableHere = hash != null && hash.value in state.playableHere
    val download = hash?.let { state.downloadStates[it.value] }
    val downloading = download != null && download.status in ACTIVE_STATUSES
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(start = RideSpace.lg, end = RideSpace.xs, top = RideSpace.xs, bottom = RideSpace.xs)) {
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.artist} · ${availabilityLabel(inLibrary, playableHere && !inLibrary, download)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                playableHere -> TextButton(onClick = { actions.onPlayHere(entry) }) { Text("Play here") }
                downloading -> TextButton(onClick = { hash?.let(actions.onCancelDownload) }) { Text("Cancel") }
                else -> TextButton(onClick = { actions.onDownload(entry) }, enabled = hash != null) { Text("Download") }
            }
            if (playableHere && state.syncAvailable && hash != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "More for ${entry.title}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Play on both phones") }, onClick = {
                            menuOpen = false
                            actions.onPlayOnBoth(hash)
                        })
                        DropdownMenuItem(text = { Text("Add to shared queue") }, onClick = {
                            menuOpen = false
                            actions.onAddToSharedQueue(hash)
                        })
                    }
                }
            }
        }
        if (downloading && download.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { (download.bytesReceived.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = RideSpace.md),
            )
        }
        if (download?.error != null) {
            DiagnosticDisclosure("transfer details") { Text(download.error.toString(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Duplicate hashes keep distinct queue item IDs; removal names the item, never the track. */
@Composable
private fun SharedQueueRow(
    title: String,
    isCurrent: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = RideSpace.lg, end = RideSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            onClick = onRemove,
        ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $title from the shared queue") }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    size: Int,
) {
    Text(
        "$title · ${count(size)}",
        modifier = Modifier.padding(horizontal = RideSpace.lg, vertical = RideSpace.sm).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = RideSpace.lg, vertical = RideSpace.xs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val ACTIVE_STATUSES =
    setOf(TransferStatus.QUEUED, TransferStatus.NEGOTIATING, TransferStatus.TRANSFERRING, TransferStatus.VERIFYING)

internal fun availabilityLabel(
    inLibrary: Boolean,
    downloaded: Boolean,
    download: DownloadState?,
): String =
    when {
        inLibrary -> "On this phone"
        downloaded -> "Downloaded"
        download == null -> "On the other phone"
        download.status == TransferStatus.FAILED -> "Download failed"
        download.status == TransferStatus.CANCELLED -> "Download cancelled"
        else -> downloadStatusLabel(download.status)
    }

private fun downloadStatusLabel(status: TransferStatus): String =
    when (status) {
        TransferStatus.QUEUED -> "Waiting to download"
        TransferStatus.NEGOTIATING -> "Starting download…"
        TransferStatus.TRANSFERRING -> "Downloading…"
        TransferStatus.VERIFYING -> "Checking download…"
        TransferStatus.COMPLETE -> "Downloaded"
        TransferStatus.FAILED -> "Download failed"
        TransferStatus.CANCELLED -> "Download cancelled"
        TransferStatus.IDLE -> "On the other phone"
    }

internal const val SHARED_LIST_TAG = "shared-list"
