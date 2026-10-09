import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// The library as its own destination, rendered by a `List` — lazy, so only visible rows exist
/// (Phase 9A.5 §5; the Android twin is `LibraryContent`). It used to be a `VStack` of every row inside
/// the main screen's `ScrollView`, the same eager shape that, on Android with 3,460 tracks, spent ~20 s
/// composing on a cold launch (STATUS §4 problem 114). No iPhone has run either version; the change
/// removes the shape rather than claiming a measurement.
///
/// Owns the two document pickers (`.fileImporter`), handing `MusicCoordinator` the security-scoped
/// URLs directly, as before.
struct LibraryScreen: View {
    let musicCoordinator: MusicCoordinator
    var synchronized: Bool = false

    @State private var showingFilePicker = false
    @State private var showingFolderPicker = false
    @State private var lastAdded: String?

    var body: some View {
        let entries = musicCoordinator.libraryEntries
        let query = musicCoordinator.query
        List {
            Section {
                Picker("Sort by", selection: Binding(get: { query.sort }, set: { musicCoordinator.setSort($0) })) {
                    ForEach(sortOptions, id: \.self) { sort in Text(sortLabel(sort)).tag(sort) }
                }
                .pickerStyle(.segmented)
                Text(countLabel(shown: entries.count, total: musicCoordinator.libraryCount, searching: !query.searchText.isEmpty))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if entries.isEmpty {
                Section {
                    if query.searchText.isEmpty {
                        VStack(alignment: .leading, spacing: RideDesign.sm) {
                            Text("No music yet").font(.headline)
                            Text("Import a folder or pick files. Your music stays on this phone.")
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text("No tracks match “\(query.searchText.trimmingCharacters(in: .whitespaces))”.")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                // Keyed by localEntryId, not track.quickId (ADR-005 Amendment A1) — quickId is not
                // guaranteed unique across entries, and a duplicate SwiftUI `ForEach` id is undefined
                // behaviour, not merely a display glitch.
                ForEach(entries, id: \.localEntryId) { entry in
                    TrackRow(
                        entry: entry,
                        isCurrent: entry.localEntryId == musicCoordinator.nowPlayingEntry?.localEntryId,
                        onPlayNow: { musicCoordinator.playNow(entry) },
                        onAddToQueue: {
                            musicCoordinator.addToQueue(entry)
                            lastAdded = entry.track.title
                        }
                    )
                }
            }
        }
        .listStyle(.plain)
        // Explicit closures, not method references: Xcode 26.6's Swift 6.3.3 crashes in IRGen on the
        // @isolated(any) reabstraction thunk a main-actor method reference needs here (CI, PR #18).
        .searchable(text: Binding(get: { query.searchText }, set: { musicCoordinator.setSearchText($0) }), prompt: "Search title, artist or album")
        .navigationTitle("Library")
        .toolbar {
            Menu {
                Button("Import a folder…") { showingFolderPicker = true }
                Button("Import files…") { showingFilePicker = true }
            } label: {
                Label("Import music", systemImage: "folder.badge.plus")
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let lastAdded {
                HStack {
                    Text("Added “\(lastAdded)” to Up Next").lineLimit(1)
                    Spacer()
                    NavigationLink("View") { UpNextScreen(musicCoordinator: musicCoordinator, synchronized: synchronized) }
                }
                .padding(RideDesign.md)
                .background(RideDesign.surface, in: RoundedRectangle(cornerRadius: RideDesign.radius))
                .padding(RideDesign.md)
                .task(id: lastAdded) {
                    try? await Task.sleep(for: .seconds(3))
                    self.lastAdded = nil
                }
                .accessibilityElement(children: .combine)
            }
        }
        .fileImporter(isPresented: $showingFilePicker, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result, !urls.isEmpty { musicCoordinator.importFiles(urls) }
        }
        .fileImporter(isPresented: $showingFolderPicker, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { musicCoordinator.importFolder(url) }
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

    private func countLabel(shown: Int, total: Int, searching: Bool) -> String {
        let tracks = total == 1 ? "1 track" : "\(total.formatted()) tracks"
        return searching ? "\(shown.formatted()) of \(tracks)" : tracks
    }
}

private struct TrackRow: View {
    let entry: LibraryEntry
    let isCurrent: Bool
    let onPlayNow: () -> Void
    let onAddToQueue: () -> Void

    private var playable: Bool { entry.decodeStatus == .indexed }

    var body: some View {
        HStack(spacing: RideDesign.md) {
            Button(action: onPlayNow) {
                HStack(spacing: RideDesign.md) {
                    ArtworkThumbnail(artworkRef: entry.track.artworkRef)
                    VStack(alignment: .leading, spacing: RideDesign.xs) {
                        Text(entry.track.title).font(.body).lineLimit(1)
                            .foregroundStyle(isCurrent ? RideDesign.primary : Color.primary)
                        Text(playable ? "\(entry.track.artist) · \(entry.track.album)" : decodeStatusLabel(entry.decodeStatus))
                            .font(.caption)
                            .foregroundStyle(playable ? Color.secondary : Color.red)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(!playable)
            .accessibilityLabel("Play \(entry.track.title)")
            .accessibilityValue(isCurrent ? "Now playing" : "")
            Button(action: onAddToQueue) { Image(systemName: "text.badge.plus").font(.title3).frame(width: 44, height: 44) }
                .buttonStyle(.borderless)
                .disabled(!playable)
                .accessibilityLabel("Add \(entry.track.title) to Up Next")
        }
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
/// placeholder for the (common) no-artwork case. Decoded off the main thread via `.task(id:)`, and
/// only for rows the `List` actually shows.
struct ArtworkThumbnail: View {
    let artworkRef: String?
    var size: CGFloat = 48

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Color.gray.opacity(0.2).overlay(Text("♪"))
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .accessibilityHidden(true)
        .task(id: artworkRef) {
            guard let artworkRef else {
                image = nil
                return
            }
            let cache = ArtworkCache()
            let pixels = size * 3
            image = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: cache.fileURL(for: artworkRef).path)?.preparingThumbnail(of: CGSize(width: pixels, height: pixels))
            }.value
        }
    }
}
