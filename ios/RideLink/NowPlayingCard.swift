import RideLinkCore
import SwiftUI

/// Now Playing — mirrors `com.ridelink.app.ui.NowPlayingCard` (Phase 9A.5 §12/§13).
///
/// Previous · Play/Pause · Next with Play/Pause the one prominent control. The seek bar and both
/// times are always present (disabled until there is a duration), so the card keeps its height when
/// playback starts and nothing below it moves. Play is enabled whenever pressing it can start
/// something — including a queue with nothing selected, which `LocalQueueAction.play` starts from the
/// first item. Seeking happens once, when the drag ends.
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

    @State private var dragging: Double?

    private var canPlay: Bool { playerState.playing || playerState.localEntryId != nil || queueSize > 0 }
    private var canSkip: Bool { queueSize > 0 }
    private var duration: Double { Double(max(playerState.durationMs, 0)) }

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.md) {
            VStack(alignment: .leading, spacing: RideDesign.xs) {
                Text(displayTitle).font(.title3.bold()).lineLimit(2)
                Text(displayArtist).foregroundStyle(.secondary).lineLimit(1)
            }
            if let error = playerState.error { Text(musicFailureLabel(error)).font(.footnote).foregroundStyle(.red) }

            VStack(spacing: RideDesign.xs) {
                Slider(
                    value: Binding(
                        get: { dragging ?? Double(min(max(playerState.positionMs, 0), max(playerState.durationMs, 0))) },
                        set: { dragging = $0 }
                    ),
                    in: 0...max(duration, 1),
                    onEditingChanged: { editing in
                        if !editing, let value = dragging {
                            onSeek(Int64(value))
                            dragging = nil
                        }
                    }
                )
                .disabled(duration <= 0)
                .accessibilityLabel("Seek")
                HStack {
                    Text(formatMs(duration > 0 ? Int64(dragging ?? Double(playerState.positionMs)) : 0))
                    Spacer()
                    Text(formatMs(Int64(duration)))
                }.font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }

            HStack(spacing: RideDesign.xl) {
                Spacer()
                Button(action: onPrevious) { Image(systemName: "backward.end.fill").font(.title2).frame(width: 56, height: 56) }
                    .disabled(!canSkip).accessibilityLabel("Previous track")
                Button(action: playerState.playing ? onPause : onPlay) {
                    Image(systemName: playerState.playing ? "pause.fill" : "play.fill")
                        .font(.title)
                        .frame(width: 72, height: 72)
                        .foregroundStyle(RideDesign.onPrimary)
                        .background(Circle().fill(canPlay ? RideDesign.primary : Color.gray.opacity(0.4)))
                }
                .disabled(!canPlay)
                .accessibilityLabel(playerState.playing ? "Pause" : "Play")
                .accessibilityValue(playerState.playing ? "Playing" : "Paused")
                Button(action: onNext) { Image(systemName: "forward.end.fill").font(.title2).frame(width: 56, height: 56) }
                    .disabled(!canSkip).accessibilityLabel("Next track")
                Spacer()
            }
            .buttonStyle(.plain)
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RideDesign.surface)
        .cornerRadius(RideDesign.radius)
    }

    private var displayTitle: String {
        if let title = title ?? currentEntry?.track.title, !title.isEmpty { return title }
        return playerState.localEntryId == nil ? "Nothing playing" : "Untitled track"
    }

    private var displayArtist: String {
        if let artist = artist ?? currentEntry?.track.artist, !artist.isEmpty { return artist }
        return playerState.localEntryId == nil ? "Pick a track in your library" : " "
    }

    private func formatMs(_ ms: Int64) -> String {
        let totalSeconds = max(ms, 0) / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

private func musicFailureLabel(_ failure: MusicFailure) -> String {
    switch failure {
    case .decodeFailed: "This file could not be played. Try another track."
    case .fileMissing: "This file is no longer on the phone. Import it again."
    case .unsupportedFormat: "This audio format is not supported."
    case .storageIo: "The file could not be read."
    case .cancelled: "Playback cancelled."
    }
}
