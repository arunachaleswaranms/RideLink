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
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            HStack(spacing: RideDesign.sm) {
                Button("Import Folder", action: onImportFolder).buttonStyle(.bordered)
                Button("Import Files", action: onImportFiles).buttonStyle(.bordered)
            }

            TextField("Search your music", text: Binding(get: { query.searchText }, set: onSearchTextChange))
                .textFieldStyle(.roundedBorder)

            Picker("Sort by", selection: Binding(get: { query.sort }, set: onSortChange)) {
                ForEach(sortOptions, id: \.self) { sort in Text(sortLabel(sort)).tag(sort) }
            }.pickerStyle(.menu)

            Text(entries.isEmpty ? (query.searchText.isEmpty ? "No music yet. Import files to build your library." : "No matching tracks. Try another search.") : "\(entries.count) track(s)")
                .font(.caption)
                .foregroundStyle(.secondary)

            // A plain VStack, not a List/LazyVStack inside this screen's own parent ScrollView — the
            // same reasoning `LibraryScreen.kt`'s doc comment gives for a plain Column on Android:
            // fine for a "realistic personal library size" without virtualization, and a dedicated
            // lazy library screen outside the shared scroll container is a Ride-Mode-era (Phase 7)
            // concern, not this one.
            VStack(alignment: .leading, spacing: RideDesign.xs) {
                // Keyed by localEntryId, not track.quickId (ADR-005 Amendment A1) — quickId is not
                // guaranteed unique across entries, and a duplicate SwiftUI `ForEach` id is undefined
                // behaviour, not merely a display glitch.
                ForEach(entries, id: \.localEntryId) { entry in
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
        HStack(spacing: RideDesign.md) {
            Button(action: onPlayNow) {
                HStack {
                    ArtworkThumbnail(artworkRef: entry.track.artworkRef)
                    VStack(alignment: .leading, spacing: RideDesign.xs) {
                        Text(entry.track.title).font(.body).lineLimit(2)
                        Text("\(entry.track.artist) — \(entry.track.album)").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        if entry.decodeStatus != .indexed {
                            Text(decodeStatusLabel(entry.decodeStatus)).font(.caption2)
                        }
                    }
                }
            }.buttonStyle(.plain).accessibilityLabel("Play \(entry.track.title)")
            Spacer()
            Button("Queue", action: onAddToQueue)
                .buttonStyle(.bordered)
                .disabled(entry.decodeStatus != .indexed)
        }
        .padding(RideDesign.sm)
        .background(Color.gray.opacity(0.08))
        .cornerRadius(RideDesign.radius)
        .contentShape(Rectangle())
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
