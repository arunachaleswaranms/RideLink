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
import com.ridelink.core.sessionfsm.SessionStatus

@Composable
internal fun ConnectionSummary(
    status: SessionStatus,
    peerCount: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(RideSpace.lg), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            Text(connectionTitle(status), style = MaterialTheme.typography.titleLarge)
            Text(connectionHint(status), style = MaterialTheme.typography.bodyMedium)
            if (status == SessionStatus.DISCOVERING && peerCount > 0) {
                Text("Peer found · Connecting automatically", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

internal fun connectionTitle(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "No peer connected"
        SessionStatus.DISCOVERING -> "Finding your peer…"
        SessionStatus.CONNECTING -> "Connecting…"
        SessionStatus.PAIRING -> "Verify pairing"
        SessionStatus.CONNECTED, SessionStatus.RIDE_ACTIVE -> "Connected"
        SessionStatus.RECONNECTING -> "Reconnecting…"
        SessionStatus.DISCONNECTED -> "Disconnected"
        SessionStatus.ENDING -> "Ending session…"
        SessionStatus.ERROR -> "Connection problem"
    }
