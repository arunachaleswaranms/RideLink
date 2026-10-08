package com.ridelink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.model.LocalEntryId
import com.ridelink.data.library.ImportProgress
import com.ridelink.data.library.PreparingProgress
import kotlinx.coroutines.launch

/** Everything the Library screen draws. A plain value, so tests can render it with any size. */
internal data class LibraryUiState(
    val query: LibraryQuery,
    val entries: List<LibraryEntry>,
    val totalCount: Int,
    val currentEntryId: LocalEntryId?,
    val importProgress: ImportProgress = ImportProgress.Idle,
    val preparing: PreparingProgress = PreparingProgress(0, 0),
    val importBusy: Boolean = false,
)

/** Everything the Library screen can ask for. Every action reaches an existing owner. */
internal class LibraryActions(
    val onBack: () -> Unit = {},
    val onSearchTextChange: (String) -> Unit = {},
    val onSortChange: (LibrarySort) -> Unit = {},
    val import: ImportActions = ImportActions(),
    val onPlayNow: (LibraryEntry) -> Unit = {},
    val onAddToQueue: (LibraryEntry) -> Unit = {},
    val onOpenUpNext: () -> Unit = {},
)

/** The import half: start one, then answer [ImportStatusPanel]'s summary. */
internal class ImportActions(
    val onImportFolder: () -> Unit = {},
    val onImportFiles: () -> Unit = {},
    val onConfirm: (Boolean) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The Library destination: collects [MusicCoordinator]'s state and hands it to [LibraryContent].
 * It is the only screen that collects [MusicCoordinator.libraryEntries]; leaving it stops the
 * library being re-read and re-sorted on every change.
 */
@Composable
internal fun LibraryRoute(
    musicCoordinator: MusicCoordinator,
    actions: LibraryActions,
    bottomBar: @Composable () -> Unit = {},
) {
    val query by musicCoordinator.query.collectAsState()
    val entries by musicCoordinator.libraryEntries.collectAsState()
    val total by musicCoordinator.libraryCount.collectAsState()
    val current by musicCoordinator.nowPlayingEntry.collectAsState()
    val importProgress by musicCoordinator.imports.progress.collectAsState()
    val preparing by musicCoordinator.imports.preparing.collectAsState()
    LibraryContent(
        state =
            LibraryUiState(
                query = query,
                entries = entries,
                totalCount = total,
                currentEntryId = current?.localEntryId,
                importProgress = importProgress,
                preparing = preparing,
                importBusy = importProgress is ImportProgress.Scanning || importProgress is ImportProgress.Indexing,
            ),
        actions = actions,
        bottomBar = bottomBar,
    )
}

/**
 * The library as a **lazy** list (STATUS §4 problem 114).
 *
 * It used to be a plain `Column` that composed every row inside the home screen's own
 * `verticalScroll`: with 3,460 tracks a cold launch spent ~20 s of main-thread time composing rows
 * nobody could see, and a touch in that window was an ANR. A `LazyColumn` cannot live inside an
 * unbounded `verticalScroll` (Compose refuses the infinite height), so the library is its own
 * destination: this screen fills the window, the list takes the remaining height, and only the rows
 * on screen — plus a small prefetch — exist. Search, sort and clearing a search replace the list's
 * data, never its structure, so none of them composes thousands of rows either.
 */
@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    actions: LibraryActions,
    bottomBar: @Composable () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val onAdd: (LibraryEntry) -> Unit = { entry ->
        actions.onAddToQueue(entry)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Added “${entry.track.title}” to Up Next", actionLabel = "View")
            if (result == SnackbarResult.ActionPerformed) actions.onOpenUpNext()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LibraryHeader(state, actions)
        Column(
            Modifier.padding(horizontal = RideSpace.lg),
            verticalArrangement = Arrangement.spacedBy(RideSpace.sm),
        ) {
            SearchField(state.query.searchText, actions.onSearchTextChange)
            SortChips(state.query.sort, actions.onSortChange)
            ImportStatusPanel(
                progress = state.importProgress,
                preparing = state.preparing,
                onConfirm = actions.import.onConfirm,
                onCancel = actions.import.onCancel,
                onDismiss = actions.import.onDismiss,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.totalCount == 0 && state.entries.isEmpty() -> EmptyLibrary(actions, state.importBusy)
                state.entries.isEmpty() && state.query.searchText.isNotBlank() ->
                    CenteredNote("No tracks match “${state.query.searchText.trim()}”.")
                else ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag(LIBRARY_LIST_TAG),
                        contentPadding = PaddingValues(bottom = RideSpace.sm),
                    ) {
                        item(key = "count", contentType = "count") {
                            Text(
                                libraryCountLabel(state.entries.size, state.totalCount, state.query.searchText),
                                modifier = Modifier.padding(horizontal = RideSpace.lg, vertical = RideSpace.sm),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(state.entries, key = { it.localEntryId.value }, contentType = { "track" }) { entry ->
                            TrackRow(
                                entry = entry,
                                isCurrent = entry.localEntryId == state.currentEntryId,
                                onPlay = { actions.onPlayNow(entry) },
                                onAdd = { onAdd(entry) },
                            )
                        }
                    }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        bottomBar()
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    actions: LibraryActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RideSpace.xs, vertical = RideSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
        Column(Modifier.weight(1f).padding(start = RideSpace.xs)) {
            Text("Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(tracks(state.totalCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !state.importBusy) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = "Import music")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Import a folder…") }, onClick = {
                    menuOpen = false
                    actions.import.onImportFolder()
                })
                DropdownMenuItem(text = { Text("Import files…") }, onClick = {
                    menuOpen = false
                    actions.import.onImportFiles()
                })
            }
        }
    }
}

@Composable
private fun SearchField(
    text: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search title, artist or album") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun SortChips(
    selected: LibrarySort,
    onChange: (LibrarySort) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
        LibrarySort.entries.forEach { sort ->
            FilterChip(
                selected = selected == sort,
                onClick = { onChange(sort) },
                label = { Text(sortLabel(sort)) },
                leadingIcon =
                    if (selected == sort) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.padding(2.dp)) }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun TrackRow(
    entry: LibraryEntry,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
) {
    val playable = entry.decodeStatus == DecodeStatus.INDEXED
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(LIBRARY_ROW_TAG)
            .heightIn(min = 64.dp)
            .clickable(enabled = playable, onClickLabel = "Play", onClick = onPlay)
            .padding(start = RideSpace.lg, end = RideSpace.xs, top = RideSpace.xs, bottom = RideSpace.xs)
            .semantics(mergeDescendants = true) {
                if (isCurrent) stateDescription = "Now playing"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RideSpace.md),
    ) {
        Artwork(entry.track.artworkRef, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(
                entry.track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playable) "${entry.track.artist} · ${entry.track.album}" else decodeStatusLabel(entry.decodeStatus),
                style = MaterialTheme.typography.bodySmall,
                color = if (playable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onAdd,
            enabled = playable,
            modifier = Modifier.semantics { contentDescription = "Add ${entry.track.title} to Up Next" },
        ) {
            Icon(painterResource(R.drawable.ic_queue_add), contentDescription = null)
        }
    }
}

@Composable
private fun EmptyLibrary(
    actions: LibraryActions,
    importBusy: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(RideSpace.xl),
        verticalArrangement = Arrangement.spacedBy(RideSpace.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No music yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Import a folder or pick files. Your music stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            OutlinedButton(onClick = actions.import.onImportFolder, enabled = !importBusy) { Text("Import a folder") }
            OutlinedButton(onClick = actions.import.onImportFiles, enabled = !importBusy) { Text("Import files") }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(RideSpace.xl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal const val LIBRARY_LIST_TAG = "library-list"
internal const val LIBRARY_ROW_TAG = "library-row"
