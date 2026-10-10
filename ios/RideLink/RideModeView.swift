import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Reads production truth; all music actions retain MusicCoordinator's TransportOwnership gate.
struct RideModeView: View {
    let coordinator: SessionCoordinator
    let music: MusicCoordinator
    let syncPlayback: SyncPlaybackPresenter?

    var body: some View {
        let voice = coordinator.voiceDiagnostics
        let cachedEntry = coordinator.sharedLibrary?.remoteEntries.first { $0.contentHash != nil && $0.contentHash == music.activeExternalCacheHash }
        RideModeContent(
            health: RideModePresentation.connectionHealth(coordinator.state.status),
            title: music.currentEntry?.track.title ?? cachedEntry?.title,
            artist: music.currentEntry?.track.artist ?? cachedEntry?.artist,
            playing: music.playerState.playing,
            hasTrack: music.playerState.localEntryId != nil,
            // PR #18 review round 4 (mirrors Android's `transportAvailability`): under local ownership
            // an empty queue's Play does nothing, so a track merely still held after a Clear is not
            // offered; while synchronised, Play is the session's.
            canStart: !music.queueState.items.isEmpty
                || (syncPlayback?.isSynchronizedModeActive == true && music.playerState.localEntryId != nil),
            syncText: UiPresentation.rideMusicLabel(status: coordinator.state.status,
                state: syncPlayback?.diagnostics.syncState ?? .inactive,
                ownsTransport: syncPlayback?.isSynchronizedModeActive == true),
            voiceText: UiPresentation.voiceLabel(voice.status),
            microphoneText: UiPresentation.microphoneLabel(
                localAudioOpen: voice.localAudioOpen, userMuted: voice.userMuted, transmitting: voice.transmitting
            ),
            micAvailable: voice.localAudioOpen,
            muted: voice.userMuted,
            ptt: coordinator.intercomPolicy.gate == .ptt,
            held: voice.pttHeld,
            policyText: UiPresentation.policyLabel(coordinator.intercomPolicy),
            audioDegraded: RideModePresentation.audioHealth(route: voice.route) == .degraded,
            onPrevious: music.previous,
            onPlayPause: { if music.playerState.playing { music.pause() } else { music.play() } },
            onNext: music.next,
            onToggleMute: { coordinator.setMicrophoneMuted(!voice.userMuted) },
            onPushToTalkHeld: coordinator.setPushToTalkHeld,
            onReconnect: coordinator.retryDiscovery,
            onEndRide: coordinator.endRide
        )
    }
}

/// Immutable rendering input and callbacks, also rendered by the standalone simulator QA host.
struct RideModeContent: View {
    let health: RideModePresentation.ConnectionHealth
    let title: String?
    let artist: String?
    let playing: Bool
    let hasTrack: Bool
    /// Phase 9A.5 §11: a queued track can be started even with nothing selected yet.
    var canStart: Bool = false
    let syncText: String
    let voiceText: String
    let microphoneText: String
    let micAvailable: Bool
    let muted: Bool
    let ptt: Bool
    let held: Bool
    let policyText: String
    let audioDegraded: Bool
    let onPrevious: () -> Void
    let onPlayPause: () -> Void
    let onNext: () -> Void
    let onToggleMute: () -> Void
    let onPushToTalkHeld: (Bool) -> Void
    let onReconnect: () -> Void
    let onEndRide: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: RideDesign.xl) {
                Text("Riding").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                connection
                VStack(alignment: .leading, spacing: RideDesign.sm) {
                    Text("NOW PLAYING").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(title.flatMap { $0.isEmpty ? nil : $0 } ?? (hasTrack ? "Untitled track" : "Nothing playing"))
                        .font(.title.weight(.bold)).fixedSize(horizontal: false, vertical: true)
                    if let artist, !artist.isEmpty { Text(artist).font(.headline).foregroundStyle(.secondary) }
                    Text(playing ? "Playing" : hasTrack ? "Paused" : "Pick music before you ride.")
                    Label(syncText, systemImage: "music.note").font(.subheadline).foregroundStyle(RideDesign.primary)
                }.rideSurface()
                // Previous · Play/Pause · Next with Play/Pause the filled, larger control (Phase 9A.5 §12).
                HStack(alignment: .center, spacing: RideDesign.md) {
                    transport("Previous", icon: "backward.end.fill", action: onPrevious)
                    transport(playing ? "Pause" : "Play", icon: playing ? "pause.fill" : "play.fill", prominent: true, action: onPlayPause)
                        .disabled(!playing && !canStart)
                        .layoutPriority(1)
                    transport("Next", icon: "forward.end.fill", action: onNext)
                }
                VStack(alignment: .leading, spacing: RideDesign.md) {
                    Text(voiceText).font(.title2.bold())
                    Label(microphoneText, systemImage: muted ? "mic.slash.fill" : "mic.fill")
                        .font(.headline).foregroundStyle(RideDesign.primary)
                    if ptt { PushToTalkControl(available: micAvailable, muted: muted, held: held, onHeld: onPushToTalkHeld) }
                    Button(action: onToggleMute) {
                        Label(muted ? "Unmute microphone" : "Mute microphone", systemImage: muted ? "mic.fill" : "mic.slash.fill")
                            .frame(maxWidth: .infinity, minHeight: RideDesign.rideTouch)
                    }.buttonStyle(.bordered).disabled(!micAvailable)
                    Text(policyText).font(.subheadline).foregroundStyle(.secondary)
                    if audioDegraded {
                        Label("Audio quality reduced · Check audio devices when stopped", systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline).foregroundStyle(RideDesign.warning)
                    }
                }
                Divider()
                Button(role: .destructive, action: onEndRide) {
                    Label("End ride", systemImage: "stop.circle")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: RideDesign.rideTouch)
                }.buttonStyle(.bordered).tint(RideDesign.error)
            }.padding(RideDesign.xl)
        }
        .background(RideDesign.background)
        .tint(RideDesign.primary)
        .preferredColorScheme(.dark)
        .buttonBorderShape(.roundedRectangle)
        .navigationBarBackButtonHidden(true)
    }

    private var connection: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Label(health == .healthy ? "Connected" : health == .reconnecting ? "Reconnecting…" : "Disconnected",
                  systemImage: health == .healthy ? "checkmark.circle.fill" : "antenna.radiowaves.left.and.right.slash")
                .font(.title2.bold())
                .foregroundStyle(health == .healthy ? RideDesign.primary : health == .reconnecting ? RideDesign.warning : RideDesign.error)
            if health == .reconnecting { Text("Trying to reach the other phone. Music keeps playing.") }
            if health == .disconnected {
                Text("The other phone is out of reach. Check both are on the same network.")
                Button("Search again", action: onReconnect).buttonStyle(.bordered).controlSize(.large)
            }
        }
    }

    @ViewBuilder
    private func transport(_ label: String, icon: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        let content = VStack(spacing: RideDesign.sm) {
            Image(systemName: icon).font(prominent ? .title : .title2)
            Text(label).font(.caption.weight(.semibold))
        }.frame(maxWidth: .infinity, minHeight: prominent ? RideDesign.rideTouch + RideDesign.lg : RideDesign.rideTouch)
        if prominent {
            Button(action: action) { content }.buttonStyle(.borderedProminent).foregroundStyle(RideDesign.onPrimary).accessibilityLabel(label)
        } else {
            Button(action: action) { content }.buttonStyle(.bordered).accessibilityLabel(label)
        }
    }

}
