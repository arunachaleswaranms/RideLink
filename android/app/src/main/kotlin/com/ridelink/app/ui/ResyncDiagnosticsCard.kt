package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ridelink.app.resync.ResyncDiagnostics
import com.ridelink.app.resync.ResyncOutcome

/**
 * PROTOCOL §10 / FR-023 (Phase 7, ADR-028): `STATE_REQUEST`/`STATE_SNAPSHOT` status. Every field
 * comes straight from `ResyncCoordinator.diagnostics` — nothing computed twice, and no wire value
 * (SDP, candidate, token, path) appears here any more than anywhere else in this screen.
 *
 * Its own file rather than a block inside [MainScreen], for the same reason [SessionActionButton]
 * already is: `config/detekt/detekt.yml`'s extract-rather-than-raise discipline.
 */
@Composable
internal fun ResyncDiagnosticsCard(diagnostics: ResyncDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            Text("Resync (Phase 7)", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Request outstanding", diagnostics.requestPending.toString())
            DiagnosticRow("Last outcome", resyncOutcomeLabel(diagnostics.lastOutcome))
            DiagnosticRow("Reconnect-triggered requests", diagnostics.reconnectRequestCount.toString())
            DiagnosticRow("Desync-triggered requests", diagnostics.desyncRequestCount.toString())
            DiagnosticRow("Role violations (stray STATE_REQUEST)", diagnostics.roleViolationCount.toString())
            DiagnosticRow("Last snapshot command_seq", diagnostics.lastSnapshotCommandSeq?.toString() ?: "—")
            DiagnosticRow("Last snapshot manifest_revision", diagnostics.lastSnapshotManifestRevision?.toString() ?: "—")
        }
    }
}

private fun resyncOutcomeLabel(outcome: ResyncOutcome): String =
    when (outcome) {
        ResyncOutcome.NONE -> "None yet"
        ResyncOutcome.REQUESTED -> "Requested — awaiting the leader's answer"
        ResyncOutcome.RECONCILED -> "Reconciled"
        ResyncOutcome.SEND_FAILED -> "Send failed — will retry on the next trigger"
        ResyncOutcome.DEFERRED -> "Snapshot received — reconciliation pending a trustworthy clock"
        ResyncOutcome.CANCELLED -> "Cancelled — the ride or session it belonged to ended"
    }
