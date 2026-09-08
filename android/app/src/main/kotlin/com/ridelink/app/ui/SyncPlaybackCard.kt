package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridelink.app.library.DownloadState
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.app.sync.SyncState
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.playback.PlaybackRole

/**
 * Phase 5's minimal affordance: enough to *drive and observe* synchronised playback on two phones,
 * and nothing more.
 *
 * **This is deliberately not a ride screen.** Phase 7 owns Ride Mode, and a riding UI designed
 * before anyone has ridden with this would be guesswork — the same reasoning ADR-020 already
 * applied to the intercom card next to it.
 *
 * **The internal leader is never presented as a master.** ADR-010's rule is that both users get
 * identical, fully capable controls; the role is shown only in the diagnostics block, as a fact
 * about command ordering, and every control below works the same on both phones.
 */
@Composable
fun SyncPlaybackCard(
    sync: SyncPlaybackCoordinator,
    sharedEntries: List<ManifestEntry>,
    localHashes: Set<String>,
) {
    val diagnostics by sync.diagnostics.collectAsState()
    val queue by sync.queueState.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Synchronized Playback", style = MaterialTheme.typography.titleMedium)
            Text(syncStateLabel(diagnostics.syncState), style = MaterialTheme.typography.bodyMedium)

            if (diagnostics.role == null) {
                Text(
                    "No peer session — local playback only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sync.previous() }) { Text("Prev") }
                OutlinedButton(onClick = { sync.pause() }) { Text("Pause") }
                OutlinedButton(onClick = { sync.resume() }) { Text("Resume") }
                OutlinedButton(onClick = { sync.next() }) { Text("Next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sync.seek(0) }) { Text("Seek 0:00") }
                OutlinedButton(onClick = { sync.leaveSynchronizedMode() }) { Text("Play locally") }
            }

            Text("Shared queue (revision ${diagnostics.queueRevision})", style = MaterialTheme.typography.titleSmall)
            if (queue.items.isEmpty()) {
                Text("Empty", style = MaterialTheme.typography.bodySmall)
            }
            queue.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val marker = if (item.queueItemId == queue.currentItemId) "▶ " else ""
                    // The hash prefix, not the filename: ADR-005's authoritative identity is what the
                    // queue is keyed on, and a filename would invite the user to believe otherwise.
                    Text("$marker${item.trackHash.hex.take(HASH_PREFIX_CHARS)}…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { sync.removeFromQueue(item.queueItemId) }) { Text("Remove") }
                }
            }

            Text("Playable on both phones", style = MaterialTheme.typography.titleSmall)
            val playable = sharedEntries.filter { it.contentHash != null && it.contentHash!!.value in localHashes }
            if (playable.isEmpty()) {
                Text(
                    "None yet — a track must be present on both phones before synchronized play (REQUIREMENTS §9.4)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            playable.forEach { entry ->
                val hash = entry.contentHash ?: return@forEach
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { sync.playSynchronized(hash) }) { Text("Play synced") }
                    OutlinedButton(onClick = { sync.enqueue(hash) }) { Text("Queue") }
                }
            }

            SyncDiagnosticsBlock(sync)
        }
    }
}

/**
 * FR-023's Phase 5 half. Every number is a measurement or a count of something refused — there is no
 * claim here about audible alignment, which only the real-device gate can produce.
 */
@Composable
private fun SyncDiagnosticsBlock(sync: SyncPlaybackCoordinator) {
    val d by sync.diagnostics.collectAsState()
    Text("Sync diagnostics", style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SyncDiagnosticRow("role", if (d.role == PlaybackRole.LEADER) "orders commands" else "sends intents")
        SyncDiagnosticRow("clock ready", d.clockReady.toString())
        SyncDiagnosticRow("clock offset", d.clockOffsetUs?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("rtt p95", d.rttP95Us?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("scheduling lead", d.leadUs?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("last command_seq", d.lastAppliedCommandSeq?.toString() ?: "—")
        SyncDiagnosticRow("late commands", d.lateCommandCount.toString())
        SyncDiagnosticRow("duplicate / stale", "${d.duplicateCommandCount} / ${d.staleCommandCount}")
        SyncDiagnosticRow("role violations", d.roleViolationCount.toString())
        SyncDiagnosticRow("stale revisions", d.staleRevisionCount.toString())
        SyncDiagnosticRow("queue revision / size", "${d.queueRevision} / ${d.queueSize}")
        SyncDiagnosticRow("local drift", d.localDriftMs?.let { "$it ms" } ?: "—")
        SyncDiagnosticRow("peer drift", d.peerDriftMs?.let { "$it ms" } ?: "—")
        SyncDiagnosticRow("last correction", d.lastCorrection.name)
        SyncDiagnosticRow("playback rate", d.playbackRate.toString())
        SyncDiagnosticRow("hard seeks", d.hardSeekCount.toString())
        SyncDiagnosticRow("schedule error", d.lastScheduleErrorUs?.let { "$it us (software only)" } ?: "—")
        SyncDiagnosticRow("route transitioning", d.routeTransitioning.toString())
        SyncDiagnosticRow("correction ticks", d.correctionTickCount.toString())
        SyncDiagnosticRow("session generation", d.sessionGeneration.toString())
    }
}

@Composable
private fun SyncDiagnosticRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun syncStateLabel(state: SyncState): String =
    when (state) {
        SyncState.INACTIVE -> "LOCAL — not synchronized"
        SyncState.CLOCK_UNREADY -> "CLOCK NOT READY — no command will be scheduled against it"
        SyncState.WAITING_FOR_CONTENT -> "WAITING FOR CONTENT — transferring; play starts by itself"
        SyncState.WAITING_FOR_QUEUE -> "WAITING FOR QUEUE — the leader has not confirmed the track yet"
        SyncState.SCHEDULED -> "SCHEDULED — waiting for the effective instant"
        SyncState.SYNCED -> "SYNCHRONIZED"
        SyncState.SYNC_FAILED -> "SYNC FAILED — local playback continues, correction stopped"
        SyncState.DESYNCHRONIZED -> "RESYNCHRONIZING — waiting for authoritative state; local playback continues"
    }

private const val HASH_PREFIX_CHARS = 8

/**
 * The two authenticated-session sections that sit below the intercom card: PROTOCOL §8's catalogue
 * plane and PROTOCOL §5/§9's synchronisation plane.
 *
 * Extracted from `MainScreen` rather than inlined because adding the sync card took that composable
 * past detekt's `LongMethod` ceiling — the same extract-rather-than-raise discipline
 * `config/detekt/detekt.yml` records for `ControlSessionManager`, applied one layer up.
 */
@Composable
fun SharedLibraryAndSyncSections(
    sharedLibraryCoordinator: SharedLibraryCoordinator,
    syncPlaybackCoordinator: SyncPlaybackCoordinator,
    remoteEntries: List<ManifestEntry>,
    localEntries: List<LibraryEntry>,
    downloadStates: Map<String, DownloadState>,
    cachedHashes: Set<String>,
    onPlaySharedTrackLocally: (ManifestEntry) -> Unit,
) {
    SharedLibraryScreen(
        remoteEntries = remoteEntries,
        localEntries = localEntries,
        downloadStates = downloadStates,
        cachedHashes = cachedHashes,
        onDownload = sharedLibraryCoordinator::requestDownload,
        onCancel = { entry -> entry.contentHash?.let(sharedLibraryCoordinator::cancelDownload) },
        onPlayLocally = onPlaySharedTrackLocally,
    )

    SyncPlaybackCard(
        sync = syncPlaybackCoordinator,
        sharedEntries = remoteEntries,
        // Both provenances brief §19 admits: a Phase 3 library row with an authoritative hash, or a
        // Phase 4 verified cache entry.
        localHashes = localEntries.mapNotNull { it.track.contentHash?.value }.toSet() + cachedHashes,
    )
}
