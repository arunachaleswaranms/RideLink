package com.ridelink.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.data.library.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The whole local-library surface (this phase's brief §21): search, sort, artwork, an import
 * entry point, and a track list with an add-to-queue affordance on each row. Narrow by design — no
 * Ride Mode polish yet (Phase 7's job), just enough to browse and play what was imported.
 */
@Composable
fun LibraryScreen(
    query: LibraryQuery,
    entries: List<LibraryEntry>,
    onSearchTextChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onAddToQueue: (LibraryEntry) -> Unit,
    onPlayNow: (LibraryEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportFolder) { Text("Import Folder") }
            Button(onClick = onImportFiles) { Text("Import Files") }
        }

        OutlinedTextField(
            value = query.searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search title, artist, album, filename") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibrarySort.entries.forEach { sort ->
                val selected = query.sort == sort
                Button(onClick = { onSortChange(sort) }, enabled = !selected) { Text(sortLabel(sort)) }
            }
        }

        Text(
            if (entries.isEmpty()) "No tracks yet — import a folder or file to get started." else "${entries.size} track(s)",
            style = MaterialTheme.typography.bodySmall,
        )

        // A plain Column, not LazyColumn: this screen already lives inside MainScreen's own
        // Modifier.verticalScroll() Column, and nesting a lazy list inside a scrolling Column gives
        // it an infinite height measurement constraint, which Compose refuses outright — a real
        // crash found only by actually running this on the emulator (IllegalStateException:
        // "Vertically scrollable component was measured with an infinity maximum height
        // constraints"), not something a `./gradlew assembleDebug` compile catches. Fine for a
        // "realistic personal library size" (REQUIREMENTS' own phrase) without virtualization; a
        // dedicated lazy library screen outside the shared scroll container is a Ride-Mode-era
        // (Phase 7) UI concern, not this one.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { entry ->
                TrackRow(entry = entry, onAddToQueue = { onAddToQueue(entry) }, onPlayNow = { onPlayNow(entry) })
            }
        }
    }
}

@Composable
private fun TrackRow(
    entry: LibraryEntry,
    onAddToQueue: () -> Unit,
    onPlayNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlayNow)) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkThumbnail(entry)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text("${entry.track.artist} — ${entry.track.album}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (entry.decodeStatus != DecodeStatus.INDEXED) {
                    Text(decodeStatusLabel(entry.decodeStatus), style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(onClick = onAddToQueue, enabled = entry.decodeStatus == DecodeStatus.INDEXED) { Text("Queue") }
        }
    }
}

/**
 * Bounded by [com.ridelink.data.library.ArtworkProcessor] long before this ever loads it — this
 * composable just needs a placeholder for the (common) no-artwork case, per this phase's brief
 * §18. Decoded with plain [BitmapFactory] off the main thread via [produceState] — a project this
 * size does not need an image-loading library for one small, already-bounded cache file.
 */
@Composable
private fun ArtworkThumbnail(entry: LibraryEntry) {
    val context = LocalContext.current
    val ref = entry.track.artworkRef
    val bitmap by
        produceState<Bitmap?>(initialValue = null, key1 = ref) {
            value =
                ref?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(ArtworkCache(context).fileFor(it).absolutePath) }.getOrNull()
                    }
                }
        }
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            // A plain glyph rather than a vector-icon dependency — this app needs exactly one
            // "no artwork" placeholder, which does not justify material-icons-extended.
            Text("♪", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private fun sortLabel(sort: LibrarySort): String =
    when (sort) {
        LibrarySort.TITLE -> "Title"
        LibrarySort.ARTIST -> "Artist"
        LibrarySort.ALBUM -> "Album"
        LibrarySort.RECENTLY_ADDED -> "Recent"
    }

private fun decodeStatusLabel(status: DecodeStatus): String =
    when (status) {
        DecodeStatus.INDEXED -> ""
        DecodeStatus.UNSUPPORTED -> "Unsupported format"
        DecodeStatus.CORRUPT -> "File looks damaged"
        DecodeStatus.MISSING -> "File not found"
    }
