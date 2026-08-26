package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.sessionfsm.SessionStatus

/**
 * Deliberately minimal (CLAUDE.md Phase 1a scope): device identity, connection status, and a
 * single action. No Ride Mode UI belongs here yet.
 */
@Composable
fun MainScreen(
    coordinator: SessionCoordinator,
    deviceDescription: String,
) {
    val state by coordinator.state.collectAsState()
    val peers by coordinator.discoveredPeers.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)

            Text("Device:")
            Text(deviceDescription, style = MaterialTheme.typography.bodyLarge)

            Text("Connection:")
            Text(connectionLabel(state.status), style = MaterialTheme.typography.bodyLarge)

            Button(onClick = {
                if (state.status == SessionStatus.DISCOVERING) coordinator.cancelDiscovery() else coordinator.startDiscovery()
            }) {
                Text(if (state.status == SessionStatus.DISCOVERING) "Stop Discovery" else "Start Discovery")
            }

            if (peers.isNotEmpty()) {
                Text("Peers seen: ${peers.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun connectionLabel(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "Idle"
        SessionStatus.DISCOVERING -> "Discovering…"
        SessionStatus.PAIRING -> "Pairing…"
        SessionStatus.CONNECTING -> "Connecting…"
        SessionStatus.CONNECTED -> "Connected"
        SessionStatus.RIDE_ACTIVE -> "Ride Active"
        SessionStatus.RECONNECTING -> "Reconnecting…"
        SessionStatus.DISCONNECTED -> "Disconnected"
        SessionStatus.ENDING -> "Ending…"
        SessionStatus.ERROR -> "Error"
    }
