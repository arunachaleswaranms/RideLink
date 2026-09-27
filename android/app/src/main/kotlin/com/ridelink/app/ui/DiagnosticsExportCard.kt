package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * NFR-08 (ADR-029 Amendment A2): the one control that lets diagnostics leave the phone, and only by
 * the user's choice of share target. Mirrors iOS's `DiagnosticsExportCard`.
 */
@Composable
internal fun DiagnosticsExportCard(onExport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            Text("Diagnostics log", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shares this session's redacted event log as a text file, through the share sheet. " +
                    "Nothing is sent unless you choose where it goes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().heightIn(min = RideSpace.touch)) {
                Text("Export diagnostics log")
            }
        }
    }
}
