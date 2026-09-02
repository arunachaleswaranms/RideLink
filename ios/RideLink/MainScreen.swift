import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Deliberately developer-oriented (CLAUDE.md Phase 1b scope): device identity, connection status,
/// the six-digit pairing prompt, security warnings, and diagnostics (peer, RTT, clock
/// offset/jitter, reconnect count, discovery count, transport). No Ride Mode UI belongs here yet.
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

                if let alert = coordinator.securityAlert {
                    SecurityAlertCard(code: alert) { coordinator.dismissSecurityAlert() }
                }

                if let prompt = coordinator.pairingPrompt {
                    PairingCard(prompt: prompt) { coordinator.confirmPairing(accepted: $0) }
                }

                // PROTOCOL §7.1 in the UI: voice controls exist only once the trust gate has passed.
                // A disabled button would still be a button; an absent card cannot be pressed.
                if coordinator.state.status == .connected || coordinator.state.status == .rideActive {
                    VoiceCard(
                        voice: coordinator.voiceDiagnostics,
                        // Routed through the coordinator, which owns the controller's lifetime — the
                        // view never touches `VoiceController` directly (CLAUDE.md rule 8).
                        onStartVoice: { coordinator.startVoice() },
                        onEndVoice: { coordinator.endVoice() },
                        onToggleMute: { coordinator.setMicrophoneMuted(!coordinator.voiceDiagnostics.micMuted) }
                    )
                }

                DiagnosticsCard(
                    diagnostics: coordinator.controlDiagnostics,
                    discoveredPeerCount: coordinator.discoveredPeers.count,
                    discoveryCount: coordinator.discoveryCount,
                    localIdentityPrefix: coordinator.localIdentityPrefix
                )
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Green once the link is TLS 1.3, because the banner's job is to be *accurate*: the Phase 1a
/// version was amber and said `PLAIN / PHASE 1A / NOT SECURE`, and a banner that keeps crying wolf
/// after the transport is secure trains the user to ignore it.
private struct TransportBanner: View {
    let transportLabel: String

    private var isSecure: Bool { transportLabel.hasPrefix("TLS") }

    var body: some View {
        Text("TRANSPORT: \(transportLabel)")
            .font(.caption.bold())
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background((isSecure ? Color.green : Color.yellow).opacity(0.25))
            .cornerRadius(8)
    }
}

/// PROTOCOL §4.5: the two users compare six digits on two screens and both confirm.
///
/// The code is shown large and monospaced because it is read aloud across a car park, and the
/// wording says *compare*, not *enter* — there is nowhere to type it, deliberately: a code that
/// travelled between the devices would prove nothing (PROTOCOL §4.5.1).
private struct PairingCard: View {
    let prompt: PairingPrompt
    let onDecision: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Pair with this device?")
                .font(.headline)
            Text(prompt.peerDisplayName.isEmpty ? "\(prompt.remotePeerId)" : prompt.peerDisplayName)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(prompt.sas6)
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .center)
                .accessibilityLabel(prompt.sas6.map(String.init).joined(separator: " "))
            Text("Both phones must show the same six digits. If they differ, do not confirm.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("They match") { onDecision(true) }
                    .buttonStyle(.borderedProminent)
                Button("They differ") { onDecision(false) }
                    .buttonStyle(.bordered)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.blue.opacity(0.1))
        .cornerRadius(8)
    }
}

/// A refused handshake the user has to see. `pin_mismatch` is the one that matters: ADR-012
/// requires it to surface as a warning and never to be resolved by silently re-pairing.
private struct SecurityAlertCard: View {
    let code: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(code == "pin_mismatch" ? "Security warning" : "Connection refused")
                .font(.headline)
            Text(explanation)
                .font(.footnote)
            Text(code)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
            Button("Dismiss", action: onDismiss)
                .buttonStyle(.bordered)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.12))
        .cornerRadius(8)
    }

    private var explanation: String {
        switch code {
        case "pin_mismatch":
            "This peer's identity key has changed. That happens after a reinstall — but it is also "
                + "what an impersonation attempt looks like. RideLink will not reconnect until you "
                + "forget this peer and pair again."
        case "certificate_invalid":
            "The peer's certificate is outside its validity window. Check the date and time on both phones."
        case "identity_mismatch":
            "The peer's stated identity did not match its certificate. The connection was refused."
        default:
            "The connection was refused."
        }
    }
}

private struct DiagnosticsCard: View {
    let diagnostics: ControlDiagnostics
    let discoveredPeerCount: Int
    let discoveryCount: Int
    let localIdentityPrefix: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Diagnostics (Phase 1b)")
                .font(.headline)
            diagnosticRow("Control state", controlStateLabel(diagnostics.controlState))
            // Both identities are shown redacted to 6 hex, matching the ARCHITECTURE §11 logging
            // rule — enough to compare two screens, far too little to identify a device.
            diagnosticRow("This device", localIdentityPrefix)
            diagnosticRow("Peer identity", diagnostics.peerIdentityPrefix ?? "—")
            diagnosticRow("TLS", diagnostics.negotiatedProtocol ?? "—")
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
