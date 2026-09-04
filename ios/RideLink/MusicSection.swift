import SwiftUI
import UniformTypeIdentifiers

/// Local music, entirely below the intercom/session UI and untouched by its state — this phase's
/// brief §30's graceful-degradation rule made visible in the layout, not just in the coordinator
/// wiring. Mirrors `com.ridelink.app.ui.MusicSection`.
///
/// Owns the two document-picker presentations itself (`.fileImporter`, SwiftUI's own wrapper over
/// `UIDocumentPickerViewController`) — there is no separate DI container/Activity-result-contract
/// system on iOS the way `MainActivity` provides on Android, so the picker lives with the one view
/// that needs it, and hands `MusicCoordinator` the resulting security-scoped URLs directly.
struct MusicSection: View {
    let musicCoordinator: MusicCoordinator

    @State private var showingFilePicker = false
    @State private var showingFolderPicker = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Local Music").font(.title2)

            NowPlayingCard(
                playerState: musicCoordinator.playerState,
                currentEntry: musicCoordinator.currentEntry,
                queueSize: musicCoordinator.queueState.items.count,
                onPlay: musicCoordinator.play,
                onPause: musicCoordinator.pause,
                onSeek: { musicCoordinator.seek(positionMs: $0) },
                onNext: musicCoordinator.next,
                onPrevious: musicCoordinator.previous
            )

            LibraryView(
                query: musicCoordinator.query,
                entries: musicCoordinator.libraryEntries,
                onSearchTextChange: musicCoordinator.setSearchText,
                onSortChange: musicCoordinator.setSort,
                onImportFolder: { showingFolderPicker = true },
                onImportFiles: { showingFilePicker = true },
                onAddToQueue: musicCoordinator.addToQueue,
                onPlayNow: musicCoordinator.playNow
            )
        }
        .fileImporter(isPresented: $showingFilePicker, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result, !urls.isEmpty { musicCoordinator.importFiles(urls) }
        }
        .fileImporter(isPresented: $showingFolderPicker, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { musicCoordinator.importFolder(url) }
        }
    }
}
