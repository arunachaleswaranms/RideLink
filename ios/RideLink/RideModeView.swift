import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// FR-018's simplified riding surface (Phase 7, ADR-028). Deliberately not a dense diagnostics
/// screen — see `MainScreen`'s `DiagnosticsCard`/`VoiceCard` for that. Every control here calls
/// through an **existing** production entry point (`SessionCoordinator`, `SyncPlaybackPresenter`,
/// `IntercomPolicy`); this view invents no session, playback or intercom state of its own, and every
/// derivation is `RideLinkPlatform.RideModePresentation` — the same pure mapping a test exercises
/// without rendering anything.
///
/// **Visibility is driven by `SessionFsm`, never by local view state** (this phase's brief §19):
/// there is no `@State` tracking "is Ride Mode showing" — `MainScreen` presents this view exactly
/// while `coordinator.state.status == .rideActive`, so a reconnect, a recovery or an ended ride all
/// show correctly with no view-owned flag that could disagree with the FSM.
struct RideModeView: View {
    let coordinator: SessionCoordinator
    let music: MusicCoordinator
    let syncPlayback: SyncPlaybackPresenter?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                connectionBanner
                Text(RideModePresentation.syncLabel(
                    status: coordinator.state.status,
                    syncState: syncPlayback?.diagnostics.syncState ?? .inactive
                ))
                .font(.caption)
                nowPlayingSection
                playbackControls
                microphoneSection
                intercomSection
                audioHealthRow
                endRideButton
            }
            .padding(20)
        }
        .navigationBarBackButtonHidden(true)
    }

    // MARK: - Connection (large, tri-state — brief §5: no raw protocol complexity here)

    private var connectionBanner: some View {
        let health = RideModePresentation.connectionHealth(coordinator.state.status)
        return VStack(spacing: 8) {
            Text(connectionLabel(health))
                .font(.title2.bold())
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(connectionColor(health).opacity(0.2))
                .clipShape(RoundedRectangle(cornerRadius: 16))

            // Passive during an ordinary transient loss (brief §15: up to PROTOCOL §10's 120 s
            // budget) — no modal, no repeated dialog. Only once the budget is genuinely exhausted
            // does an explicit action appear.
            if RideModePresentation.reconnectBudgetExhausted(coordinator.state.status) {
                Button("Reconnect") { coordinator.retryDiscovery() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
        }
    }

    private func connectionLabel(_ health: RideModePresentation.ConnectionHealth) -> String {
        switch health {
        case .healthy: "Connected"
        case .reconnecting: "Reconnecting…"
        case .disconnected: "Disconnected"
        }
    }

    private func connectionColor(_ health: RideModePresentation.ConnectionHealth) -> Color {
        switch health {
        case .healthy: .green
        case .reconnecting: .yellow
        case .disconnected: .red
        }
    }

    // MARK: - Now playing

    private var nowPlayingSection: some View {
        let playing = RideModePresentation.nowPlaying(entry: music.currentEntry, playerState: music.playerState)
        return VStack(spacing: 4) {
            Text(playing.title ?? "Nothing loaded")
                .font(.title3.bold())
                .lineLimit(1)
            if let artist = playing.artist {
                Text(artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text(playing.playing ? "Playing" : "Paused")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Playback controls (the EXISTING Phase 5 command path only — never a second one)

    private var playbackControls: some View {
        HStack(spacing: 32) {
            largeButton(systemName: "backward.fill") { syncPlayback?.previous() }
            largeButton(systemName: music.playerState.playing ? "pause.fill" : "play.fill") {
                if music.playerState.playing {
                    syncPlayback?.pause()
                } else {
                    syncPlayback?.resume()
                }
            }
            .frame(width: 88, height: 88)
            largeButton(systemName: "forward.fill") { syncPlayback?.next() }
        }
        .disabled(syncPlayback == nil)
    }

    // MARK: - Microphone (existing VoiceController/intercom entry points only)

    private var microphoneSection: some View {
        let state = RideModePresentation.microphoneState(voice: coordinator.voiceDiagnostics, policy: coordinator.intercomPolicy)
        return VStack(spacing: 8) {
            if coordinator.intercomPolicy.gate == .ptt {
                pttButton(state)
            } else {
                muteButton(state)
            }
            Text(microphoneLabel(state)).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func muteButton(_ state: RideModePresentation.MicrophoneState) -> some View {
        Button {
            // The user's own Mute latch, not the wire's `mic_muted` — same distinction `VoiceCard`
            // already draws.
            coordinator.setMicrophoneMuted(state != .mutedIdle)
        } label: {
            Image(systemName: state == .mutedIdle ? "mic.slash.fill" : "mic.fill")
                .font(.system(size: 36))
                .frame(width: 88, height: 88)
        }
        .buttonStyle(.borderedProminent)
        .tint(state == .mutedIdle ? .red : .accentColor)
        .disabled(state == .unavailable)
    }

    /// Press-and-hold, mirroring `VoiceCard`'s `PushToTalkButton` exactly — every way a hold can end
    /// (release, gesture cancellation, view disappearance) maps to the same "not held" assignment, so
    /// there is no path through this view that leaves the gate open. Gates the outbound track only;
    /// never touches capture device lifecycle.
    private func pttButton(_ state: RideModePresentation.MicrophoneState) -> some View {
        Button {} label: {
            Image(systemName: state == .pttTalking ? "mic.fill" : "mic")
                .font(.system(size: 36))
                .frame(width: 88, height: 88)
        }
        .buttonStyle(.borderedProminent)
        .tint(state == .pttTalking ? .red : .accentColor)
        .disabled(state == .unavailable)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard state != .unavailable else { return }
                    coordinator.setPushToTalkHeld(true)
                }
                .onEnded { _ in coordinator.setPushToTalkHeld(false) }
        )
        .onDisappear { coordinator.setPushToTalkHeld(false) }
    }

    private func microphoneLabel(_ state: RideModePresentation.MicrophoneState) -> String {
        switch state {
        case .unavailable: "Microphone unavailable"
        case .mutedIdle: "Muted"
        case .unmutedIdle: "Unmuted"
        case .pttIdle: "Hold to talk"
        case .pttTalking: "Talking"
        }
    }

    // MARK: - Intercom mode (existing IntercomPolicy mechanism only)

    private var intercomSection: some View {
        VStack(spacing: 8) {
            Text(RideModePresentation.intercomModeLabel(coordinator.intercomPolicy))
                .font(.headline)
            if coordinator.intercomPolicy.intercomEnabled {
                HStack(spacing: 8) {
                    ForEach(IntercomPolicy.all, id: \.id) { candidate in
                        Button(candidate.id.rawValue.replacingOccurrences(of: "MODE_", with: "")) {
                            coordinator.selectIntercomPolicy(candidate)
                        }
                        .buttonStyle(.bordered)
                        .tint(candidate.id == coordinator.intercomPolicy.id ? .accentColor : .gray)
                    }
                }
            } else {
                Text("Intercom disabled — music only").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Audio/connection health (never fabricated — brief §5)

    private var audioHealthRow: some View {
        let health = RideModePresentation.audioHealth(route: coordinator.voiceDiagnostics.route)
        return HStack(spacing: 6) {
            Circle()
                .fill(audioHealthColor(health))
                .frame(width: 10, height: 10)
            Text(audioHealthLabel(health)).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func audioHealthLabel(_ health: RideModePresentation.AudioHealth) -> String {
        switch health {
        case .normal: "Audio route normal"
        case .degraded: "Audio route degraded"
        case .unknown: "Audio route unknown"
        }
    }

    private func audioHealthColor(_ health: RideModePresentation.AudioHealth) -> Color {
        switch health {
        case .normal: .green
        case .degraded: .yellow
        case .unknown: .gray
        }
    }

    // MARK: - End Ride

    private var endRideButton: some View {
        Button("End Ride") { coordinator.endRide() }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
    }

    private func largeButton(systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 28))
                .frame(width: 64, height: 64)
        }
        .buttonStyle(.bordered)
    }
}
