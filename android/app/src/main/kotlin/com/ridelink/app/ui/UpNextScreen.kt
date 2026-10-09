package com.ridelink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueueState

/** One Up Next row, resolved for display. [id] is the queue entry's own id — never the track's. */
internal data class UpNextRow(
    val id: String,
    val title: String,
    val artist: String?,
    val artworkRef: String?,
)

internal class UpNextActions(
    val onBack: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onMove: (String, Int) -> Unit = { _, _ -> },
    val onClear: () -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
)

/** Collects the local queue and resolves each entry's title by [LocalEntryId] — never by scanning. */
@Composable
internal fun UpNextRoute(
    musicCoordinator: MusicCoordinator,
    synchronized: Boolean,
    actions: UpNextActions,
    bottomBar: @Composable () -> Unit = {},
) {
    val queue by musicCoordinator.queueState.collectAsState()
    val entries by produceState(emptyMap<LocalEntryId, com.ridelink.core.library.LibraryEntry>(), queue.items) {
        value = musicCoordinator.entriesFor(queue.items.map { it.localEntryId })
    }
    val rows =
        queue.items.map { item ->
            val entry = entries[item.localEntryId]
            val external = musicCoordinator.externalTitleFor(item.localEntryId)
            UpNextRow(
                id = item.id,
                title = entry?.track?.title ?: external?.first ?: "Untitled track",
                artist = entry?.track?.artist ?: external?.second,
                artworkRef = entry?.track?.artworkRef,
            )
        }
    UpNextContent(rows, queue, synchronized, actions, bottomBar)
}

/**
 * The local queue (Phase 9A.5 §10), lazy, with exactly the operations [com.ridelink.core.player.LocalQueue]
 * already has: select, remove, move, clear.
 *
 * **Read-only while [synchronized]** (PR #18 review): no row plays, and there is no Remove, Move or
 * Clear — not merely disabled, absent, so accessibility services cannot reach them either. The
 * authority is not this screen: [com.ridelink.app.music.MusicCoordinator] refuses every local edit
 * while synchronised transport owns playback, whatever any screen offers.
 *
 * Every action names a **queue entry id**. The same track queued twice is two entries with two ids,
 * shown twice and removed one at a time — nothing here collapses duplicates or acts on "the" track.
 * Reordering is Move up / Move down, which a screen reader can operate and which maps one-to-one to
 * `LocalQueueAction.Move`.
 *
 * This is the **local** queue only. While synchronised playback is on, what plays is the shared
 * queue, decided by the leader (ADR-024); this screen says so rather than pretending to edit it.
 */
@Composable
internal fun UpNextContent(
    rows: List<UpNextRow>,
    queue: LocalQueueState,
    synchronized: Boolean,
    actions: UpNextActions,
    bottomBar: @Composable () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(RideSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = RideSpace.xs)) {
                Text("Up Next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(tracks(rows.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rows.isNotEmpty() && !synchronized) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (synchronized) {
            Text(
                "Playing on both phones. Change what plays together from the other phone's music. " +
                    "This phone's Up Next can't be changed until you choose Play on this phone only.",
                modifier = Modifier.padding(horizontal = RideSpace.lg, vertical = RideSpace.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(RideSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(RideSpace.md, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Up Next is empty", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add tracks from your library with the add button on each track.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = actions.onOpenLibrary) { Text("Open library") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag(UP_NEXT_LIST_TAG), contentPadding = PaddingValues(bottom = RideSpace.sm)) {
                    itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, _ -> "entry" }) { index, row ->
                        UpNextItem(
                            row = row,
                            position = index,
                            last = index == rows.lastIndex,
                            isCurrent = row.id == queue.currentId,
                            actions = actions,
                            readOnly = synchronized,
                        )
                    }
                }
            }
        }
        bottomBar()
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Up Next?") },
            text = { Text("This removes every track from Up Next and stops playback.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UpNextItem(
    row: UpNextRow,
    position: Int,
    last: Boolean,
    isCurrent: Boolean,
    actions: UpNextActions,
    readOnly: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(UP_NEXT_ROW_TAG)
            .heightIn(min = 64.dp)
            .then(if (readOnly) Modifier else Modifier.clickable(onClickLabel = "Play", onClick = { actions.onSelect(row.id) }))
            .padding(start = RideSpace.md, end = RideSpace.xs)
            .semantics(mergeDescendants = true) { if (isCurrent) stateDescription = "Now playing" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RideSpace.sm),
    ) {
        Text(
            if (isCurrent) "▶" else "${position + 1}",
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Artwork(row.artworkRef, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurrent) "Now playing" else row.artist.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (readOnly) return@Row
        IconButton(onClick = { actions.onMove(row.id, position - 1) }, enabled = position > 0) {
            Icon(painterResource(R.drawable.ic_chevron_up), contentDescription = "Move ${row.title} up")
        }
        IconButton(onClick = { actions.onMove(row.id, position + 1) }, enabled = !last) {
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = "Move ${row.title} down")
        }
        IconButton(onClick = { actions.onRemove(row.id) }) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove ${row.title} from Up Next")
        }
    }
}

internal const val UP_NEXT_LIST_TAG = "up-next-list"
internal const val UP_NEXT_ROW_TAG = "up-next-row"
