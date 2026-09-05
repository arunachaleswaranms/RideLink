package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridelink.app.library.DownloadState
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.transfer.TransferStatus

/**
 * Phase 4's minimum usable shared-library surface (brief §27): the connected peer's catalogue,
 * each track's availability, and download/cancel/play affordances. **Not** Ride Mode — no
 * synchronized controls, no playback state shared with the peer, shown only once the trust gate
 * has passed (this screen's caller gates that, the same way [VoiceCard] is gated in [MainScreen]).
 *
 * Availability is computed here from three already-owned pieces of state, never invented locally:
 * whether a local (Phase 3 imported) row shares this `content_hash`, whether this session's
 * [DownloadState] map says `COMPLETE`, and the remote entry's own presence — matching brief §7's
 * "do not infer cached availability until… the final cache object was committed successfully."
 */
@Composable
fun SharedLibraryScreen(
    remoteEntries: List<ManifestEntry>,
    localEntries: List<LibraryEntry>,
    downloadStates: Map<String, DownloadState>,
    /** Closure-audit Finding H: the persisted verified-cache truth
     *  ([com.ridelink.app.library.SharedLibraryCoordinator.cachedHashes]) — sourced from
     *  [com.ridelink.data.transfer.TransferCacheRepository], not from [downloadStates] alone, so a
     *  hash committed in an earlier session or before a process restart still shows as cached. */
    cachedHashes: Set<String>,
    onDownload: (ManifestEntry) -> Unit,
    onCancel: (ManifestEntry) -> Unit,
    onPlayLocally: (ManifestEntry) -> Unit,
) {
    val localHashes = localEntries.mapNotNull { it.track.contentHash?.value }.toSet()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shared Library", style = MaterialTheme.typography.titleMedium)
        if (remoteEntries.isEmpty()) {
            Text("No shared catalogue yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${remoteEntries.size} track(s) on the connected peer", style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            remoteEntries.forEach { entry ->
                val hash = entry.contentHash
                val isLocal = hash != null && hash.value in localHashes
                val download = hash?.let { downloadStates[it.value] }
                // Finding H: persisted cache truth, not session-scoped download status alone — a
                // `download?.status == COMPLETE` fallback is kept only for the same-session case
                // where `refreshCachedHashes()`'s own async re-query hasn't yet landed.
                val isCached = (hash != null && hash.value in cachedHashes) || download?.status == TransferStatus.COMPLETE
                SharedTrackRow(
                    entry = entry,
                    isLocal = isLocal,
                    isCached = isCached,
                    download = download,
                    onDownload = { onDownload(entry) },
                    onCancel = { onCancel(entry) },
                    onPlayLocally = { onPlayLocally(entry) },
                )
            }
        }
    }
}

@Composable
private fun SharedTrackRow(
    entry: ManifestEntry,
    isLocal: Boolean,
    isCached: Boolean,
    download: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPlayLocally: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${entry.artist} — ${entry.album}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(availabilityLabel(isLocal, isCached, download), style = MaterialTheme.typography.labelSmall)
            if (download != null && download.status in ACTIVE_STATUSES && download.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (download.bytesReceived.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    // Closure-audit Finding G: playback reuses the one existing player/queue for
                    // both a Phase 3 imported row (`isLocal`) *and* a verified Phase-4 cache-only
                    // file that was never imported (`isCached`) — see
                    // `MusicCoordinator.playExternalVerifiedCachedTrack`. Provenance stays distinct
                    // internally (never a fake imported row), but both are "Play" from here.
                    isLocal || isCached -> Button(onClick = onPlayLocally) { Text("Play") }
                    download != null && download.status in ACTIVE_STATUSES ->
                        Button(onClick = onCancel) { Text("Cancel") }
                    else -> Button(onClick = onDownload, enabled = entry.contentHash != null) { Text("Download") }
                }
            }
        }
    }
}

private val ACTIVE_STATUSES =
    setOf(TransferStatus.QUEUED, TransferStatus.NEGOTIATING, TransferStatus.TRANSFERRING, TransferStatus.VERIFYING)

private fun availabilityLabel(
    isLocal: Boolean,
    isCached: Boolean,
    download: DownloadState?,
): String =
    when {
        isLocal -> "Local"
        isCached -> "Downloaded"
        download == null -> "Remote only"
        download.status == TransferStatus.FAILED -> "Failed (${download.error ?: "unknown"})"
        download.status == TransferStatus.CANCELLED -> "Cancelled"
        else -> downloadStatusLabel(download.status)
    }

private fun downloadStatusLabel(status: TransferStatus): String =
    when (status) {
        TransferStatus.QUEUED -> "Queued"
        TransferStatus.NEGOTIATING -> "Starting…"
        TransferStatus.TRANSFERRING -> "Downloading…"
        TransferStatus.VERIFYING -> "Verifying…"
        TransferStatus.COMPLETE -> "Downloaded"
        TransferStatus.FAILED -> "Failed"
        TransferStatus.CANCELLED -> "Cancelled"
        TransferStatus.IDLE -> "Remote only"
    }
