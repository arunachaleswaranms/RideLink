import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Phase 2b's intercom surface: start/stop, mute, a PTT control, the five modes, and every
/// non-sensitive diagnostic PROTOCOL §7.7 permits — plus the peer's `AUDIO_STATE` (§4.4).
///
/// **Nothing here renders an SDP, a candidate string, an IP address or a port.** §7.7 gives those no
/// display path any more than a log path, and a diagnostics screen is exactly where someone would be
/// tempted to add one. Candidate *types* are shown; candidates are not. Nothing renders a device name or
/// a Bluetooth address either — ADR-016 forbids it, and the wire carries neither.
///
/// **No latency figure appears anywhere.** The setup timings below are exactly that: how long the app
/// took to bring voice up. Mouth-to-ear latency is TEST_PLAN A-09/V-11 and needs hardware, so nothing
/// here may be read as bearing on the <200 ms target.
///
/// The Kotlin mirror is `com.ridelink.app.ui.VoiceCard` plus `IntercomDiagnostics.kt`, showing the same
/// rows.
struct VoiceCard: View {
    let voice: VoiceDiagnostics
    let policy: IntercomPolicy
    let peerAudioState: AudioStateMessage?
    let refusal: VoiceFailure?
    let onStartIntercom: () -> Void
    let onStopIntercom: () -> Void
    let onToggleMute: () -> Void
    let onPushToTalkHeld: (Bool) -> Void
    let onSelectPolicy: (IntercomPolicy) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Intercom (Phase 2b)").font(.headline)
            controls
            mode
            media
            setup
            route
            peer
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
            Text("Your peer wants to talk. Start Intercom to open your microphone.")
                .font(.subheadline)
        }
        if let refusal {
            // Named, not "connection failed" (this phase's brief §41). FR-025: the session is untouched.
            Text("Intercom unavailable — \(refusal.rawValue). The peer session is unaffected.")
                .font(.caption)
                .foregroundStyle(.red)
        }
        HStack(spacing: 8) {
            if voice.status == .idle || voice.status == .failed {
                Button("Start Intercom", action: onStartIntercom).buttonStyle(.borderedProminent)
            } else {
                Button("Stop Intercom", action: onStopIntercom).buttonStyle(.bordered)
            }
            Button(voice.userMuted ? "Unmute" : "Mute", action: onToggleMute)
                .buttonStyle(.bordered)
                .disabled(!voice.localAudioOpen)
        }
        row("voice state", voice.status.rawValue)
        row("role", voice.role?.rawValue ?? "—")
        row("voice session", voice.voiceSessionPrefix ?? "—")
        row("peer reports", voice.peerReportedState.wire)
        row("mic (device)", micLabel)
        row("transmitting", "\(voice.transmitting)")
        row("wire mic_muted", "\(voice.micMuted)")
        row("last failure", voice.lastFailure?.rawValue ?? "none")
    }

    /// Whether the capture *device* is open — not whether speech is being transmitted (PROTOCOL §4.4).
    private var micLabel: String {
        if !voice.localAudioOpen { return "unavailable" }
        return voice.userMuted ? "open, muted" : "open"
    }

    @ViewBuilder
    private var mode: some View {
        Text("MODE").font(.caption2).foregroundStyle(.secondary)
        HStack(spacing: 4) {
            ForEach(IntercomPolicy.all, id: \.id) { candidate in
                Button(candidate.id.rawValue.replacingOccurrences(of: "MODE_", with: "")) {
                    onSelectPolicy(candidate)
                }
                .buttonStyle(.bordered)
                .tint(candidate.id == policy.id ? .accentColor : .gray)
            }
        }
        // The default is Mode C by architecture, not by measurement — docs/PHASE0_RESULTS.md is still
        // awaiting the user's Phase 0 numbers, and saying so here keeps the screen honest.
        row("policy", "\(policy.id.rawValue) (default MODE_C — architecture, not measured)")
        row("gate", gateLabel)
        row("full duplex", "\(policy.fullDuplex)")
        row("wire mode", "\(voice.mode.wire) / \(voice.intercomMode.wire)")

        if case .vox = policy.gate, !voice.voxLevelSourceAvailable {
            // ADR-021 §6, stated rather than discovered by silence: the VOX state machine is real and
            // tested, but no microphone-driven level exists on either platform yet, so the gate cannot
            // open. PENDING REAL AUDIO INPUT / LATER HARDENING.
            Text(
                "VOX: no microphone level source on this platform yet, so the gate cannot open — "
                    + "PENDING REAL AUDIO INPUT (ADR-021 §6). Use PTT or continuous."
            )
            .font(.caption)
            .foregroundStyle(.red)
        }

        if policy.gate == .ptt {
            PushToTalkButton(voice: voice, onPushToTalkHeld: onPushToTalkHeld)
        }
    }

    private var gateLabel: String {
        switch policy.gate {
        case .none: return "none (full duplex)"
        case .vox(let threshold, let hangover): return "vox(\(threshold) dBFS, \(hangover) ms)"
        case .ptt: return "ptt"
        case .disabled: return "disabled (music only)"
        }
    }

    /// Software **setup** timings, and labelled as such.
    ///
    /// `media rtt` above and every figure here are network and software measurements. Mouth-to-ear
    /// latency (TEST_PLAN A-09/V-11) includes two Bluetooth hops, an encoder, a jitter buffer and a
    /// decoder, and cannot be inferred from any of them — see `VoiceSetupTimeline`.
    @ViewBuilder
    private var setup: some View {
        Text("SETUP TIMING (not latency)").font(.caption2).foregroundStyle(.secondary)
        row("capture open", msLabel(voice.setup.captureOpenMs))
        row("local sdp", msLabel(voice.setup.localDescriptionMs))
        row("signalling", msLabel(voice.setup.signallingMs))
        row("media connected", msLabel(voice.setup.mediaConnectedMs))
        row("remote track", msLabel(voice.setup.setupMs))
        Text(
            "Mouth-to-ear latency is NOT measured here and is not inferable from these figures "
                + "(TEST_PLAN A-09/V-11 — real hardware required)."
        )
        .font(.caption)
    }

    /// The peer's `AUDIO_STATE` (PROTOCOL §4.4) — ADR-016's reason for existing: "the pillion can hear
    /// you but her music went narrowband" is a diagnosis, not a guess.
    @ViewBuilder
    private var peer: some View {
        Text("PEER AUDIO_STATE").font(.caption2).foregroundStyle(.secondary)
        if let peerAudioState {
            row("revision", "\(peerAudioState.revision)")
            row("endpoint", peerAudioState.endpointClass.wire)
            row("mic open", "\(peerAudioState.microphoneOpen)")
            row(
                "out / in profile",
                "\(peerAudioState.effectiveOutputProfile.wire) / \(peerAudioState.effectiveInputProfile.wire)"
            )
            row(
                "out / in rate",
                "\(rateLabel(peerAudioState.effectiveOutputSampleRateHz)) / "
                    + "\(rateLabel(peerAudioState.effectiveInputSampleRateHz))"
            )
            row("media quality", peerAudioState.mediaQuality.wire)
            row("route state", peerAudioState.routeState.wire)
            row("intercom mode", peerAudioState.intercomMode.wire)
            row("confidence", peerAudioState.confidence.wire)
        } else {
            row("received", "none yet")
        }
    }

    private func rateLabel(_ value: Int?) -> String { value.map(String.init) ?? "—" }

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
        row("aec hardware", voice.engine.audioProcessing.hardwareEchoCancellation.map(String.init) ?? "—")
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
        Text("ROUTE (this device)").font(.caption2).foregroundStyle(.secondary)
        row("endpoint", voice.route.endpointClass.wire)
        row(
            "out / in profile",
            "\(voice.route.effectiveOutputProfile.wire) / \(voice.route.effectiveInputProfile.wire)"
        )
        row(
            "out / in rate",
            "\(rateLabel(voice.route.effectiveOutputSampleRateHz)) / "
                + "\(rateLabel(voice.route.effectiveInputSampleRateHz))"
        )
        // ADR-016's whole point rendered in one row: with the microphone open on Bluetooth the honest
        // answer is `reduced`, and both users can see it. The old model could only reassure falsely.
        row("media quality", voice.route.mediaQuality.wire)
        row("coupling", voice.route.profileCoupling.wire)
        row("route state", voice.route.routeState.wire)
        row("last transition", msLabel(voice.route.lastTransitionDurationUs.map { Double($0) / 1_000 }))
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

/// Press-and-hold, with every way a hold can end mapped to the same "not held" assignment.
///
/// This phase's brief §25 in one view:
///
/// - `DragGesture(minimumDistance: 0)`'s `onEnded` fires on release **and** when the gesture is
///   cancelled by the system, and both are treated as an up. There is no path through this view that
///   leaves the gate open.
/// - `onDisappear` releases, so navigating away while held cannot strand transmission on. Backgrounding
///   is handled one level up, from the scene phase, because a view is not told about that.
/// - It is a `Button`, so VoiceOver and Switch Control operate it; an activation from either produces the
///   same down/up pair, so it cannot create a stuck press either.
///
/// It gates the outbound track and nothing else: no capture reopen, no peer-connection rebuild.
private struct PushToTalkButton: View {
    let voice: VoiceDiagnostics
    let onPushToTalkHeld: (Bool) -> Void

    var body: some View {
        Button(voice.pttHeld ? "TALKING — release to stop" : "HOLD TO TALK") {}
            .buttonStyle(.borderedProminent)
            .disabled(!voice.localAudioOpen)
            .frame(maxWidth: .infinity)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard voice.localAudioOpen else { return }
                        onPushToTalkHeld(true)
                    }
                    // Fires on release and on cancellation alike. Both end the hold, which is the only
                    // safe reading.
                    .onEnded { _ in onPushToTalkHeld(false) }
            )
            .onDisappear { onPushToTalkHeld(false) }
    }
}
