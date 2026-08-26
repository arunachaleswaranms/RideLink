import RideLinkCore
import SwiftUI

/// Deliberately minimal (CLAUDE.md Phase 1a scope): device identity, connection status, and a
/// single action. No Ride Mode UI belongs here yet.
struct MainScreen: View {
    let coordinator: SessionCoordinator
    let deviceDescription: String

    var body: some View {
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

            if !coordinator.discoveredPeers.isEmpty {
                Text("Peers seen: \(coordinator.discoveredPeers.count)")
                    .font(.footnote)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
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
