import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// The whole local-library surface (this phase's brief §21): search, sort, artwork, an import entry
/// point, and a track list with an add-to-queue affordance on each row. Narrow by design — no Ride
/// Mode polish yet (Phase 7's job), just enough to browse and play what was imported. Mirrors
/// `com.ridelink.app.ui.LibraryScreen`.
struct LibraryView: View {
    let query: LibraryQuery
    let entries: [LibraryEntry]
    let onSearchTextChange: (String) -> Void
    let onSortChange: (LibrarySort) -> Void
    let onImportFolder: () -> Void
    let onImportFiles: () -> Void
    let onAddToQueue: (LibraryEntry) -> Void
    let onPlayNow: (LibraryEntry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button("Import Folder", action: onImportFolder).buttonStyle(.bordered)
                Button("Import Files", action: onImportFiles).buttonStyle(.bordered)
            }

            TextField("Search title, artist, album, filename", text: Binding(get: { query.searchText }, set: onSearchTextChange))
                .textFieldStyle(.roundedBorder)

            HStack(spacing: 8) {
                ForEach(sortOptions, id: \.self) { sort in
                    Button(sortLabel(sort), action: { onSortChange(sort) })
                        .buttonStyle(.bordered)
                        .disabled(query.sort == sort)
                }
            }

            Text(entries.isEmpty ? "No tracks yet — import a folder or file to get started." : "\(entries.count) track(s)")
                .font(.caption)
                .foregroundStyle(.secondary)

            // A plain VStack, not a List/LazyVStack inside this screen's own parent ScrollView — the
            // same reasoning `LibraryScreen.kt`'s doc comment gives for a plain Column on Android:
            // fine for a "realistic personal library size" without virtualization, and a dedicated
            // lazy library screen outside the shared scroll container is a Ride-Mode-era (Phase 7)
            // concern, not this one.
            VStack(alignment: .leading, spacing: 4) {
                ForEach(entries, id: \.track.quickId) { entry in
                    TrackRow(entry: entry, onAddToQueue: { onAddToQueue(entry) }, onPlayNow: { onPlayNow(entry) })
                }
            }
        }
    }

    private let sortOptions: [LibrarySort] = [.title, .artist, .album, .recentlyAdded]

    private func sortLabel(_ sort: LibrarySort) -> String {
        switch sort {
        case .title: "Title"
        case .artist: "Artist"
        case .album: "Album"
        case .recentlyAdded: "Recent"
        }
    }
}

private struct TrackRow: View {
    let entry: LibraryEntry
    let onAddToQueue: () -> Void
    let onPlayNow: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ArtworkThumbnail(artworkRef: entry.track.artworkRef)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.track.title).font(.body).lineLimit(1)
                Text("\(entry.track.artist) — \(entry.track.album)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if entry.decodeStatus != .indexed {
                    Text(decodeStatusLabel(entry.decodeStatus)).font(.caption2)
                }
            }
            Spacer()
            Button("Queue", action: onAddToQueue)
                .buttonStyle(.bordered)
                .disabled(entry.decodeStatus != .indexed)
        }
        .padding(8)
        .background(Color.gray.opacity(0.08))
        .cornerRadius(8)
        .contentShape(Rectangle())
        .onTapGesture(perform: onPlayNow)
    }

    private func decodeStatusLabel(_ status: DecodeStatus) -> String {
        switch status {
        case .indexed: ""
        case .unsupported: "Unsupported format"
        case .corrupt: "File looks damaged"
        case .missing: "File not found"
        }
    }
}

/// Bounded by `ArtworkProcessor` long before this ever loads it — this view just needs a
/// placeholder for the (common) no-artwork case, per this phase's brief §18. Decoded off the main
/// thread via `.task(id:)`, matching `LibraryScreen.kt`'s "no image-loading library for one small,
/// already-bounded cache file" reasoning.
private struct ArtworkThumbnail: View {
    let artworkRef: String?

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Color.gray.opacity(0.2).overlay(Text("♪"))
            }
        }
        .frame(width: 48, height: 48)
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .task(id: artworkRef) {
            guard let artworkRef else {
                image = nil
                return
            }
            let cache = ArtworkCache()
            image = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: cache.fileURL(for: artworkRef).path)
            }.value
        }
    }
}
