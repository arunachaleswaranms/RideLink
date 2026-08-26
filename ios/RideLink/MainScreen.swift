import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Deliberately developer-oriented (CLAUDE.md Phase 1a scope / this session's brief §17): device
/// identity, connection status, and Phase 1a diagnostics (peer, RTT, clock offset/jitter,
/// reconnect count, discovery count, transport). No Ride Mode UI belongs here yet.
struct MainScreen: View {
    let coordinator: SessionCoordinator
    let deviceDescription: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("RideLink")
                    .font(.largeTitle)

                Text("Device:")
                Text(deviceDescription)
                    .font(.body)

                Text("Connection:")
                Text(connectionLabel(coordinator.state.status))
                    .font(.body)

                Button(coordinator.state.status == .discovering ? "Stop Discovery" : "Start Discovery") {
                    if coordinator.state.status == .discovering {
                        coordinator.cancelDiscovery()
                    } else {
                        coordinator.startDiscovery()
                    }
                }
                .buttonStyle(.borderedProminent)

                TransportBanner(transportLabel: coordinator.controlDiagnostics.transportLabel)

                DiagnosticsCard(
                    diagnostics: coordinator.controlDiagnostics,
                    discoveredPeerCount: coordinator.discoveredPeers.count,
                    discoveryCount: coordinator.discoveryCount
                )
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct TransportBanner: View {
    let transportLabel: String

    var body: some View {
        Text("TRANSPORT: \(transportLabel)")
            .font(.caption.bold())
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.yellow.opacity(0.25))
            .cornerRadius(8)
    }
}

private struct DiagnosticsCard: View {
    let diagnostics: ControlDiagnostics
    let discoveredPeerCount: Int
    let discoveryCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Diagnostics (Phase 1a)")
                .font(.headline)
            diagnosticRow("Control state", controlStateLabel(diagnostics.controlState))
            diagnosticRow("Peer", diagnostics.remotePeerId ?? "—")
            diagnosticRow("Local leader", diagnostics.isLocalLeader.map { $0 ? "true" : "false" } ?? "—")
            diagnosticRow("RTT", diagnostics.rttMs.map { String(format: "%.1f ms", $0) } ?? "—")
            diagnosticRow("Clock offset", diagnostics.clockOffsetUs.map { "\($0) µs" } ?? "—")
            diagnosticRow("Clock jitter", diagnostics.clockJitterUs.map { "\($0) µs" } ?? "—")
            diagnosticRow("Reconnect count", "\(diagnostics.reconnectCount)")
            diagnosticRow("Discovered peers (current)", "\(discoveredPeerCount)")
            diagnosticRow("Discovery count (cumulative)", "\(discoveryCount)")
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.1))
        .cornerRadius(8)
    }

    private func diagnosticRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }
}

private func connectionLabel(_ status: SessionStatus) -> String {
    switch status {
    case .idle: "Idle"
    case .discovering: "Discovering…"
    case .pairing: "Pairing…"
    case .connecting: "Connecting…"
    case .connected: "Connected"
    case .rideActive: "Ride Active"
    case .reconnecting: "Reconnecting…"
    case .disconnected: "Disconnected"
    case .ending: "Ending…"
    case .error: "Error"
    }
}

private func controlStateLabel(_ state: ControlState) -> String {
    switch state {
    case .idle: "Idle"
    case .connecting: "Connecting…"
    case .connected: "Connected"
    case .reconnecting: "Reconnecting…"
    case .disconnected: "Disconnected"
    case .ended: "Ended"
    }
}
