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
            syncText: UiPresentation.rideMusicLabel(status: coordinator.state.status,
                state: syncPlayback?.diagnostics.syncState ?? .inactive,
                ownsTransport: syncPlayback?.isSynchronizedModeActive == true),
            voiceText: UiPresentation.voiceLabel(voice.status),
            microphoneText: !voice.localAudioOpen ? "Microphone unavailable"
                : voice.userMuted ? "Muted" : voice.transmitting ? "Transmitting" : "Microphone ready",
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
                Text("RIDE MODE").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                connection
                VStack(alignment: .leading, spacing: RideDesign.sm) {
                    Text("NOW PLAYING").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(title.flatMap { $0.isEmpty ? nil : $0 } ?? (hasTrack ? "Untitled track" : "Nothing playing"))
                        .font(.title.weight(.bold)).fixedSize(horizontal: false, vertical: true)
                    if let artist, !artist.isEmpty { Text(artist).font(.headline).foregroundStyle(.secondary) }
                    Text(playing ? "Playing" : hasTrack ? "Paused" : "Choose music in setup before your ride.")
                    Label(syncText, systemImage: "music.note").font(.subheadline).foregroundStyle(RideDesign.primary)
                }.rideSurface()
                HStack(spacing: RideDesign.md) {
                    transport("Previous", icon: "backward.end.fill", action: onPrevious)
                    transport(playing ? "Pause" : "Play", icon: playing ? "pause.fill" : "play.fill", action: onPlayPause)
                        .disabled(!hasTrack && !playing)
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
                    Label("End Ride", systemImage: "stop.circle")
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
            if health == .reconnecting { Text("Trying to reconnect automatically.") }
            if health == .disconnected {
                Text("Peer features are unavailable. Check the shared network.")
                Button("Reconnect", action: onReconnect).buttonStyle(.bordered).controlSize(.large)
            }
        }
    }

    private func transport(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: RideDesign.sm) {
                Image(systemName: icon).font(.title2)
                Text(label).font(.caption.weight(.semibold))
            }.frame(maxWidth: .infinity, minHeight: RideDesign.rideTouch)
        }.buttonStyle(.bordered).accessibilityLabel(label)
    }

}
