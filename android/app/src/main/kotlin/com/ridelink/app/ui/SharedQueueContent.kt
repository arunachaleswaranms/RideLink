package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.ridelink.core.playback.SharedQueueState

/** Metadata is presentation only. Duplicate hashes retain distinct item IDs and removal callbacks. */
@Composable
internal fun SharedQueueContent(
    queue: SharedQueueState,
    titles: Map<String, String>,
    onRemove: (String) -> Unit,
) {
    Text("Shared queue", style = MaterialTheme.typography.titleMedium)
    if (queue.items.isEmpty()) Text("Queue is empty. Add a track from the shared library.")
    queue.items.forEachIndexed { index, item ->
        key(item.queueItemId) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = RideSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(RideSpace.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        titles[item.trackHash.value]?.takeIf { it.isNotBlank() } ?: "Shared track",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (item.queueItemId == queue.currentItemId) "Current item" else "Queue item ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(onClick = { onRemove(item.queueItemId) }) { Text("Remove") }
            }
        }
    }
}
