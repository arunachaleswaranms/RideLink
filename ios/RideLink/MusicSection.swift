import RideLinkCore
import SwiftUI

/// Local music on the main screen: Now Playing and links to the library and Up Next — mirrors
/// `com.ridelink.app.ui.MusicSection` plus Android's home navigation rows. Untouched by session state
/// (this phase's brief §30's graceful-degradation rule made visible in the layout).
///
/// The library and Up Next are their own destinations, each a lazy `List` (Phase 9A.5 §5). Nothing
/// here reads the search-filtered library list: the current track is `MusicCoordinator.nowPlayingEntry`.
struct MusicSection: View {
    let musicCoordinator: MusicCoordinator
    var sharedEntries: [ManifestEntry] = []
    var synchronized: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.md) {
            Text("Music").font(.title3.weight(.semibold)).foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)

            let cachedEntry = sharedEntries.first { $0.contentHash != nil && $0.contentHash == musicCoordinator.activeExternalCacheHash }
            NowPlayingCard(
                playerState: musicCoordinator.playerState,
                currentEntry: musicCoordinator.currentEntry,
                queueSize: musicCoordinator.queueState.items.count,
                title: musicCoordinator.currentEntry?.track.title ?? cachedEntry?.title,
                artist: musicCoordinator.currentEntry?.track.artist ?? cachedEntry?.artist,
                onPlay: musicCoordinator.play,
                onPause: musicCoordinator.pause,
                onSeek: { musicCoordinator.seek(positionMs: $0) },
                onNext: musicCoordinator.next,
                onPrevious: musicCoordinator.previous
            )

            VStack(spacing: 0) {
                NavigationLink {
                    LibraryScreen(musicCoordinator: musicCoordinator, synchronized: synchronized)
                } label: {
                    navigationRow("Library", systemImage: "music.note.list", detail: trackCount(musicCoordinator.libraryCount))
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, RideDesign.xl + RideDesign.lg)
                NavigationLink {
                    UpNextScreen(musicCoordinator: musicCoordinator, synchronized: synchronized)
                } label: {
                    let queue = musicCoordinator.queueState
                    navigationRow("Up Next", systemImage: "list.number", detail: upNextDetail(queue))
                }
                .buttonStyle(.plain)
            }
            .background(RideDesign.surface, in: RoundedRectangle(cornerRadius: RideDesign.radius))
        }
    }

    private func navigationRow(_ title: String, systemImage: String, detail: String) -> some View {
        HStack(spacing: RideDesign.lg) {
            Image(systemName: systemImage).foregroundStyle(Color.secondary).frame(width: RideDesign.xl)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Color.primary)
                Text(detail).font(.caption).foregroundStyle(Color.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, RideDesign.lg)
        .frame(minHeight: RideDesign.touch + RideDesign.sm)
        .contentShape(Rectangle())
    }

    private func trackCount(_ count: Int) -> String { count == 1 ? "1 track" : "\(count.formatted()) tracks" }

    /// "Track 3 of 10" while something in the queue is current, so the row moves as the queue plays.
    private func upNextDetail(_ queue: LocalQueueState) -> String {
        if queue.items.isEmpty { return "Empty" }
        if let index = queue.currentIndex { return "Track \(index + 1) of \(queue.items.count)" }
        return trackCount(queue.items.count)
    }
}

/// The local queue (Phase 9A.5 §10) — mirrors Android's `UpNextContent`. A `List`, so it is lazy;
/// select, remove (swipe or Edit), reorder (Edit) and clear, each mapped to the one existing
/// `LocalQueue` action.
///
/// Every action names a **queue entry id**: the same track queued twice is two rows with two ids,
/// removed one at a time. This is the local queue only — while synchronised playback is on, the
/// shared queue decides what plays (ADR-024), and the screen says so.
struct UpNextScreen: View {
    let musicCoordinator: MusicCoordinator
    let synchronized: Bool
    var onOpenLibrary: () -> Void = {}

    @State private var confirmClear = false

    var body: some View {
        let queue = musicCoordinator.queueState
        List {
            if synchronized {
                Text("Playing on both phones. Change what plays together from the other phone's music.")
                    .font(.footnote)
                    .foregroundStyle(RideDesign.warning)
            }
            if queue.items.isEmpty {
                VStack(alignment: .leading, spacing: RideDesign.sm) {
                    Text("Up Next is empty").font(.headline)
                    Text("Add tracks from your library with the add button on each track.").foregroundStyle(.secondary)
                    Button("Open library", action: onOpenLibrary).buttonStyle(.bordered)
                }
            }
            ForEach(Array(queue.items.enumerated()), id: \.element.id) { index, item in
                row(index: index, item: item, isCurrent: item.id == queue.currentId)
            }
            .onDelete { offsets in
                offsets.map { queue.items[$0].id }.forEach { musicCoordinator.removeFromQueue(id: $0) }
            }
            .onMove { source, destination in
                guard let from = source.first else { return }
                let to = destination > from ? destination - 1 : destination
                musicCoordinator.moveInQueue(id: queue.items[from].id, toIndex: to)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Up Next")
        .toolbar {
            if !queue.items.isEmpty {
                EditButton()
                Button("Clear") { confirmClear = true }
            }
        }
        .confirmationDialog("Clear Up Next?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { musicCoordinator.clearQueue() }
        } message: {
            Text("This removes every track from Up Next and stops playback.")
        }
    }

    private func row(index: Int, item: LocalQueueItem, isCurrent: Bool) -> some View {
        let entry = musicCoordinator.entry(for: item.localEntryId)
        let title = entry?.track.title ?? "Shared track"
        return Button { musicCoordinator.selectQueueItem(id: item.id) } label: {
            HStack(spacing: RideDesign.md) {
                Text(isCurrent ? "▶" : "\(index + 1)")
                    .frame(width: 28)
                    .foregroundStyle(isCurrent ? RideDesign.primary : Color.secondary)
                ArtworkThumbnail(artworkRef: entry?.track.artworkRef, size: 40)
                VStack(alignment: .leading, spacing: RideDesign.xs) {
                    Text(title).lineLimit(1).foregroundStyle(isCurrent ? RideDesign.primary : Color.primary)
                    Text(isCurrent ? "Now playing" : entry?.track.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play \(title)")
        .accessibilityValue(isCurrent ? "Now playing" : "Position \(index + 1)")
    }
}
