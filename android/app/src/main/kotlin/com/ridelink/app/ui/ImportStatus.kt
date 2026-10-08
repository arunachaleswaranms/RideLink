package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.ridelink.data.library.ImportProgress
import com.ridelink.data.library.PreparingProgress

/**
 * What an import is doing, in words and real numbers (Phase 9A.5 §8/§9). Renders nothing when idle.
 *
 * Every figure comes from [ImportProgress] and is one the indexer actually knows — a walk shows a
 * running count and an indeterminate bar, never a guessed percentage. The confirmation step says
 * plainly that subfolders are included and names recording-like folders, with a switch the user
 * controls. Internal identifiers (quick IDs, content hashes, URIs) never appear here.
 */
@Composable
internal fun ImportStatusPanel(
    progress: ImportProgress,
    preparing: PreparingProgress,
    onConfirm: (skipRecordings: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (progress) {
        ImportProgress.Idle -> if (preparing.active) PreparingLine(preparing, modifier)
        is ImportProgress.AwaitingConfirmation -> ImportConfirmation(progress, onConfirm, onCancel, modifier)
        else -> ImportLine(progress, onCancel, onDismiss, modifier)
    }
}

@Composable
private fun ImportLine(
    progress: ImportProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val running = progress is ImportProgress.Scanning || progress is ImportProgress.Indexing
    StatusSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(RideSpace.xs),
            ) {
                Text(importHeadline(progress), style = MaterialTheme.typography.titleSmall)
                importDetail(progress)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
        when (progress) {
            is ImportProgress.Scanning -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ImportProgress.Indexing ->
                LinearProgressIndicator(
                    progress = { fraction(progress.done, progress.total) },
                    modifier = Modifier.fillMaxWidth(),
                )
            else -> Unit
        }
    }
}

@Composable
private fun ImportConfirmation(
    summary: ImportProgress.AwaitingConfirmation,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    var skipRecordings by rememberSaveable(summary) { mutableStateOf(summary.skipRecordingsSuggested) }
    val importCount = if (skipRecordings) summary.trackCount - summary.recordingTrackCount else summary.trackCount
    StatusSurface(modifier) {
        Text(
            if (summary.folderName.isBlank()) "Import this folder?" else "Import “${summary.folderName}”?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (summary.trackCount == 0) {
                "No supported audio files were found in this folder or its subfolders."
            } else {
                "${tracks(summary.trackCount)} in this folder and its subfolders. RideLink imports every " +
                    "supported audio file it finds there."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (summary.recordingFolders.isNotEmpty()) {
            Text(
                "Some folders look like phone recordings: " +
                    summary.recordingFolders.joinToString { "${it.name} (${count(it.trackCount)})" } + ".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = skipRecordings, role = Role.Switch, onValueChange = { skipRecordings = it })
                    .padding(vertical = RideSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RideSpace.md),
            ) {
                Text(
                    "Leave out recordings (${tracks(summary.recordingTrackCount)})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = skipRecordings, onCheckedChange = null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(RideSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            if (importCount > 0) {
                Button(onClick = { onConfirm(skipRecordings) }) { Text("Import ${tracks(importCount)}") }
            }
            TextButton(onClick = onCancel) { Text(if (importCount > 0) "Cancel" else "OK") }
        }
    }
}

@Composable
private fun PreparingLine(
    preparing: PreparingProgress,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
        Text(
            "Preparing tracks for sharing… ${count(preparing.done)} of ${count(preparing.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { fraction(preparing.done, preparing.total) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) { content() }
    }
}

internal fun importHeadline(progress: ImportProgress): String =
    when (progress) {
        ImportProgress.Idle -> ""
        is ImportProgress.Scanning -> if (progress.folderName.isBlank()) "Scanning folder…" else "Scanning “${progress.folderName}”…"
        is ImportProgress.AwaitingConfirmation -> "Ready to import"
        is ImportProgress.Indexing ->
            when (progress.stage) {
                ImportProgress.Stage.CHECKING -> "Checking ${count(progress.done)} of ${count(progress.total)}"
                ImportProgress.Stage.READING -> "Indexing ${count(progress.done)} of ${count(progress.total)}"
            }
        is ImportProgress.Complete ->
            if (progress.folderName.isNullOrBlank()) "Import finished" else "Imported “${progress.folderName}”"
        is ImportProgress.Failed -> "Import failed"
        ImportProgress.Cancelled -> "Import cancelled"
    }

internal fun importDetail(progress: ImportProgress): String? =
    when (progress) {
        is ImportProgress.Scanning -> "Found ${tracks(progress.found)}"
        is ImportProgress.Indexing ->
            when (progress.stage) {
                ImportProgress.Stage.CHECKING -> "Looking for new and changed files"
                ImportProgress.Stage.READING -> "Reading titles and artwork"
            }
        is ImportProgress.Complete ->
            buildList {
                add(tracks(progress.trackCount))
                add("${count(progress.added)} new")
                if (progress.missing > 0) add("${count(progress.missing)} no longer found")
            }.joinToString(" · ")
        is ImportProgress.Failed -> "Pick the folder again to retry."
        ImportProgress.Cancelled -> "Tracks already read stay in your library."
        else -> null
    }

internal fun count(value: Int): String = "%,d".format(value)

internal fun tracks(value: Int): String = if (value == 1) "1 track" else "${count(value)} tracks"

private fun fraction(
    done: Int,
    total: Int,
): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
