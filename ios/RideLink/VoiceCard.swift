import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Phase 2a's voice surface: two buttons, a mute toggle, and every non-sensitive diagnostic
/// PROTOCOL §7.7 permits.
///
/// **Nothing here renders an SDP, a candidate string, an IP address or a port.** §7.7 gives those no
/// display path any more than a log path, and a diagnostics screen is exactly where someone would be
/// tempted to add one. Candidate *types* are shown; candidates are not.
///
/// The Kotlin mirror is `com.ridelink.app.ui.VoiceCard`, showing the same rows.
struct VoiceCard: View {
    let voice: VoiceDiagnostics
    let onStartVoice: () -> Void
    let onEndVoice: () -> Void
    let onToggleMute: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Voice (Phase 2a)").font(.headline)
            controls
            media
            route
            signalling
        }
        .padding()
        .background(Color(white: 0.95))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private var controls: some View {
        if voice.peerRequestedVoice, voice.status == .idle {
            // ARCHITECTURE §6.4: a peer asking is never enough to open this device's microphone. The
            // prompt is the only legal route, and it says so rather than opening the mic quietly.
            Text("Your peer wants to talk. Start Voice to open your microphone.")
                .font(.subheadline)
        }
        HStack(spacing: 8) {
            if voice.status == .idle || voice.status == .failed {
                Button("Start Voice", action: onStartVoice).buttonStyle(.borderedProminent)
            } else {
                Button("End Voice", action: onEndVoice).buttonStyle(.bordered)
            }
            Button(voice.micMuted ? "Unmute" : "Mute", action: onToggleMute)
                .buttonStyle(.bordered)
                .disabled(!voice.localAudioOpen)
        }
        row("voice state", voice.status.rawValue)
        row("role", voice.role?.rawValue ?? "—")
        row("voice session", voice.voiceSessionPrefix ?? "—")
        row("peer reports", voice.peerReportedState.wire)
        row("mic", micLabel)
        row("mode", voice.mode.wire)
    }

    private var micLabel: String {
        if !voice.localAudioOpen { return "unavailable" }
        return voice.micMuted ? "muted" : "open"
    }

    @ViewBuilder
    private var media: some View {
        Text("MEDIA").font(.caption2).foregroundStyle(.secondary)
        row("peer connection", voice.engine.transportState.rawValue)
        row("ice gathering", voice.engine.iceGatheringState.rawValue)
        row("dtls", voice.engine.dtlsState ?? "—")
        row("srtp cipher", voice.engine.srtpCipher ?? "—")
        row("dtls cipher", voice.engine.dtlsCipher ?? "—")
        row("selected candidate", selectedCandidateLabel)
        row("codec", codecLabel)
        row(
            "local / remote track",
            "\(voice.engine.localAudioTrackPresent) / \(voice.engine.remoteAudioTrackPresent)"
        )
        row("packets sent / recv", "\(countLabel(voice.engine.packetsSent)) / \(countLabel(voice.engine.packetsReceived))")
        row("loss / jitter", "\(countLabel(voice.engine.packetsLost)) / \(msLabel(voice.engine.jitterMs))")
        row("media rtt", msLabel(voice.engine.roundTripTimeMs))
        row("aec / ns / agc", processingLabel)
    }

    private var selectedCandidateLabel: String {
        let parts = [voice.engine.selectedLocalType?.rawValue, voice.engine.selectedRemoteType?.rawValue]
            .compactMap { $0 }
        return parts.isEmpty ? "—" : parts.joined(separator: " / ")
    }

    private var codecLabel: String {
        guard let codec = voice.engine.negotiatedCodec else { return "—" }
        var parts = [codec]
        if let rate = voice.engine.negotiatedClockRateHz { parts.append("\(rate) Hz") }
        if let channels = voice.engine.negotiatedChannels { parts.append("\(channels)ch") }
        return parts.joined(separator: " ")
    }

    private var processingLabel: String {
        [
            voice.engine.audioProcessing.echoCancellationEnabled,
            voice.engine.audioProcessing.noiseSuppressionEnabled,
            voice.engine.audioProcessing.autoGainControlEnabled,
        ]
        .map { $0.map(String.init) ?? "—" }
        .joined(separator: " / ")
    }

    @ViewBuilder
    private var route: some View {
        Text("ROUTE").font(.caption2).foregroundStyle(.secondary)
        row("endpoint", voice.route.endpointClass.wire)
        row(
            "out / in profile",
            "\(voice.route.effectiveOutputProfile.wire) / \(voice.route.effectiveInputProfile.wire)"
        )
        // ADR-016's whole point rendered in one row: with the microphone open on Bluetooth the honest
        // answer is `reduced`, and both users can see it. The old model could only reassure falsely.
        row("media quality", voice.route.mediaQuality.wire)
        row("coupling", voice.route.profileCoupling.wire)
        row("route state", voice.route.routeState.wire)
        row("confidence", voice.route.confidence.wire)
        row("last change", "\(voice.route.lastChangeReason)")
        row("interrupted", "\(voice.route.interrupted)")
    }

    @ViewBuilder
    private var signalling: some View {
        Text("SIGNALLING").font(.caption2).foregroundStyle(.secondary)
        row("queued candidates", "\(voice.queuedCandidates) (dropped \(voice.droppedQueuedCandidates))")
        row("rebuilds", "\(voice.rebuildCount)")
        row("dropped signals", droppedSignalsLabel)
        if voice.unexpectedCandidateTypeSeen {
            // PROTOCOL §7.6 configures no STUN and no TURN, so a reflexive or relayed candidate would
            // mean something contacted a server outside the local network. Reported loudly rather than
            // made fatal: a false alarm on a ride is better than a crash.
            Text(
                "UNEXPECTED ICE CANDIDATE TYPE — RideLink configures no STUN or TURN server, so only "
                    + "host candidates should appear (PROTOCOL §7.6)."
            )
            .font(.caption)
            .foregroundStyle(.red)
        }
    }

    private var droppedSignalsLabel: String {
        if voice.droppedSignals.isEmpty { return "none" }
        return voice.droppedSignals
            .sorted { $0.key.rawValue < $1.key.rawValue }
            .map { "\($0.key.rawValue)=\($0.value)" }
            .joined(separator: ", ")
    }

    private func countLabel(_ value: Int64?) -> String { value.map(String.init) ?? "—" }

    private func msLabel(_ value: Double?) -> String {
        value.map { String(format: "%.1f ms", $0) } ?? "—"
    }

    private func row(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }
}
