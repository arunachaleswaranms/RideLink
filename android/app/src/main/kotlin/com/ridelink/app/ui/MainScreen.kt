package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlDiagnostics
import com.ridelink.network.control.ControlState

/**
 * Deliberately developer-oriented (CLAUDE.md Phase 1a scope / this session's brief §17): device
 * identity, connection status, and Phase 1a diagnostics (peer, RTT, clock offset/jitter,
 * reconnect count, discovery count, transport). No Ride Mode UI belongs here yet.
 */
@Composable
fun MainScreen(
    coordinator: SessionCoordinator,
    deviceDescription: String,
) {
    val state by coordinator.state.collectAsState()
    val peers by coordinator.discoveredPeers.collectAsState()
    val discoveryCount by coordinator.discoveryCount.collectAsState()
    val diagnostics by coordinator.controlDiagnostics.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
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

            TransportBanner(diagnostics.transportLabel)

            DiagnosticsCard(diagnostics = diagnostics, discoveredPeerCount = peers.size, discoveryCount = discoveryCount)
        }
    }
}

@Suppress("MagicNumber") // named colors for the Phase 1a insecure-transport banner
private val InsecureBannerBackground = Color(0xFFFFF3CD)

@Suppress("MagicNumber")
private val InsecureBannerText = Color(0xFF7A5B00)

@Composable
private fun TransportBanner(transportLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InsecureBannerBackground),
    ) {
        Text(
            "TRANSPORT: $transportLabel",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = InsecureBannerText,
        )
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: ControlDiagnostics,
    discoveredPeerCount: Int,
    discoveryCount: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostics (Phase 1a)", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Control state", controlStateLabel(diagnostics.controlState))
            DiagnosticRow("Peer", diagnostics.remotePeerId ?: "—")
            DiagnosticRow("Local leader", diagnostics.isLocalLeader?.toString() ?: "—")
            DiagnosticRow("RTT", diagnostics.rttMs?.let { "%.1f ms".format(it) } ?: "—")
            DiagnosticRow("Clock offset", diagnostics.clockOffsetUs?.let { "$it µs" } ?: "—")
            DiagnosticRow("Clock jitter", diagnostics.clockJitterUs?.let { "$it µs" } ?: "—")
            DiagnosticRow("Reconnect count", diagnostics.reconnectCount.toString())
            DiagnosticRow("Discovered peers (current)", discoveredPeerCount.toString())
            DiagnosticRow("Discovery count (cumulative)", discoveryCount.toString())
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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

private fun controlStateLabel(state: ControlState): String =
    when (state) {
        ControlState.IDLE -> "Idle"
        ControlState.CONNECTING -> "Connecting…"
        ControlState.CONNECTED -> "Connected"
        ControlState.RECONNECTING -> "Reconnecting…"
        ControlState.DISCONNECTED -> "Disconnected"
        ControlState.ENDED -> "Ended"
    }
