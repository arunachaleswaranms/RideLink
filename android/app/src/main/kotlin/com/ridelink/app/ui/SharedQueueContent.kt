package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.ridelink.app.R
import com.ridelink.core.playback.SharedQueueState

/**
 * A short, bounded preview of the shared queue for the home screen; the full queue is a lazy section
 * of the other phone's music screen. Metadata is presentation only. Duplicate hashes retain distinct
 * item IDs and removal callbacks.
 */
@Composable
internal fun SharedQueueContent(
    queue: SharedQueueState,
    titles: Map<String, String>,
    onRemove: (String) -> Unit,
    previewLimit: Int = Int.MAX_VALUE,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
        Text("Shared queue", style = MaterialTheme.typography.titleSmall)
        if (queue.items.isEmpty()) {
            Text(
                "Nothing queued. Add a track from the other phone's music.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        queue.items.take(previewLimit).forEach { item ->
            key(item.queueItemId) {
                val title = titles[item.trackHash.value]?.takeIf { it.isNotBlank() } ?: "Shared track"
                val current = item.queueItemId == queue.currentItemId
                Row(Modifier.fillMaxWidth().padding(vertical = RideSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current) {
                            Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { onRemove(item.queueItemId) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $title from the shared queue")
                    }
                }
            }
        }
        val hidden = queue.items.size - previewLimit
        if (hidden > 0) {
            Text(
                "and ${count(hidden)} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
