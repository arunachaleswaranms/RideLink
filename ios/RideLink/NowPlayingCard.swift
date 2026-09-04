import RideLinkCore
import SwiftUI

/// Play/pause/seek/next/previous — mirrors `com.ridelink.app.ui.NowPlayingCard`.
struct NowPlayingCard: View {
    let playerState: PlayerState
    let currentEntry: LibraryEntry?
    let queueSize: Int
    let onPlay: () -> Void
    let onPause: () -> Void
    let onSeek: (Int64) -> Void
    let onNext: () -> Void
    let onPrevious: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Now Playing").font(.headline)
            if let currentEntry {
                Text("\(currentEntry.track.title) — \(currentEntry.track.artist)").font(.body)
            } else {
                Text("Nothing loaded").font(.body).foregroundStyle(.secondary)
            }

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

            HStack(spacing: 12) {
                Button("Previous", action: onPrevious).buttonStyle(.bordered)
                Button(playerState.playing ? "Pause" : "Play", action: playerState.playing ? onPause : onPlay)
                    .buttonStyle(.borderedProminent)
                Button("Next", action: onNext).buttonStyle(.bordered)
            }

            Text("Queue: \(queueSize) item(s)").font(.caption).foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.1))
        .cornerRadius(8)
    }

    private func formatMs(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
