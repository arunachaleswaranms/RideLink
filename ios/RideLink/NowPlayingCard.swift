import RideLinkCore
import SwiftUI

/// Play/pause/seek/next/previous — mirrors `com.ridelink.app.ui.NowPlayingCard`.
struct NowPlayingCard: View {
    let playerState: PlayerState
    let currentEntry: LibraryEntry?
    let queueSize: Int
    var title: String? = nil
    var artist: String? = nil
    let onPlay: () -> Void
    let onPause: () -> Void
    let onSeek: (Int64) -> Void
    let onNext: () -> Void
    let onPrevious: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Now Playing").font(.headline)
            Text(title ?? currentEntry?.track.title ?? (playerState.localEntryId == nil ? "Nothing playing" : "Shared track")).font(.title3.bold())
            if let artist = artist ?? currentEntry?.track.artist, !artist.isEmpty { Text(artist).foregroundStyle(.secondary) }
            if let error = playerState.error { Text(musicFailureLabel(error)).foregroundStyle(.red) }

            if playerState.durationMs > 0 {
                Slider(
                    value: Binding(
                        get: { Double(playerState.positionMs) },
                        set: { onSeek(Int64($0)) }
                    ),
                    in: 0...Double(playerState.durationMs)
                )
                Text("\(formatMs(playerState.positionMs)) / \(formatMs(playerState.durationMs))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: RideDesign.md) {
                transport("Previous", icon: "backward.end.fill", action: onPrevious).disabled(queueSize == 0)
                transport(playerState.playing ? "Pause" : "Play", icon: playerState.playing ? "pause.fill" : "play.fill", action: playerState.playing ? onPause : onPlay)
                    .disabled(playerState.localEntryId == nil && currentEntry == nil && !playerState.playing)
                transport("Next", icon: "forward.end.fill", action: onNext).disabled(queueSize == 0)
            }

            Text(queueSize == 0 ? "Queue is empty" : "\(queueSize) items in queue").font(.caption).foregroundStyle(.secondary)
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RideDesign.surface)
        .cornerRadius(RideDesign.radius)
    }

    private func transport(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: RideDesign.xs) {
                Image(systemName: icon).font(.title3)
                Text(label).font(.caption)
            }.frame(maxWidth: .infinity, minHeight: RideDesign.touch)
        }.buttonStyle(.bordered).controlSize(.regular).accessibilityLabel(label)
    }

    private func formatMs(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

private func musicFailureLabel(_ failure: MusicFailure) -> String {
    switch failure {
    case .decodeFailed: "This audio file could not be played. Try another track."
    case .fileMissing: "The audio file is missing. Import it again."
    case .unsupportedFormat: "This audio format is unsupported."
    case .storageIo: "The audio file could not be read. Check available storage."
    case .cancelled: "Playback cancelled."
    }
}
