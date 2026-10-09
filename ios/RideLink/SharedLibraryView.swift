import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// The other phone's music — Phase 4's catalogue and Phase 5's shared queue — as one lazy `List`
/// destination (Phase 9A.5; the Android twin is `SharedMusicContent`). It used to be an eager stack of
/// every catalogue entry inside the main screen's scroll, beside a second eager "playable on both
/// phones" list: the shape of STATUS §4 problem 114 with the other phone's library as input. Shown
/// only past the trust gate; the caller gates that, as before.
///
/// Availability comes entirely from `SharedLibraryCoordinator.availability(for:)` — never invented
/// locally — matching brief §7's "do not infer cached availability until… the final cache object
/// was committed successfully."
struct SharedMusicScreen: View {
    let coordinator: SharedLibraryCoordinator
    let syncPlayback: SyncPlaybackPresenter?
    let onPlayLocally: (ManifestEntry) -> Void

    var body: some View {
        List {
            if let syncPlayback, syncPlayback.diagnostics.role != nil {
                Section("Shared queue") {
                    SharedQueueRows(queue: syncPlayback.queueState, titles: titles, onRemove: syncPlayback.removeFromQueue)
                }
            }
            Section("Tracks") {
                if coordinator.remoteEntries.isEmpty {
                    Text("No shared music yet. Import music on either phone.").foregroundStyle(.secondary)
                }
                // Closure-audit Finding F: `quickId` alone is not guaranteed unique across entries
                // (ADR-005 Amendment A1) — `rowId` is the stable, collision-resistant identity.
                ForEach(coordinator.remoteEntries, id: \.rowId) { entry in
                    let availability = coordinator.availability(for: entry)
                    let bothPhones = entry.contentHash.map { availability.playableLocally && coordinator.peerHasContent($0) } ?? false
                    SharedTrackRow(
                        entry: entry,
                        availability: availability,
                        download: entry.contentHash.flatMap { coordinator.downloadStates[$0.value] },
                        onDownload: { coordinator.requestDownload(entry) },
                        onCancel: { entry.contentHash.map(coordinator.cancelDownload) },
                        onPlayLocally: { onPlayLocally(entry) },
                        playHereOffered: syncPlayback?.localQueueLocked != true,
                        onPlayOnBoth: bothPhones && syncPlayback?.diagnostics.role != nil
                            ? entry.contentHash.map { hash in { syncPlayback?.playSynchronized(hash) } } : nil,
                        onAddToSharedQueue: bothPhones && syncPlayback?.diagnostics.role != nil
                            ? entry.contentHash.map { hash in { syncPlayback?.enqueue(hash) } } : nil
                    )
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Other phone's music")
    }

    private var titles: [String: String] {
        Dictionary(coordinator.remoteEntries.compactMap { entry in entry.contentHash.map { ($0.value, entry.title) } },
                   uniquingKeysWith: { first, _ in first })
    }
}

private let activeStatuses: Set<TransferStatus> = [.queued, .negotiating, .transferring, .verifying]

struct SharedTrackRow: View {
    let entry: ManifestEntry
    let availability: Availability
    let download: DownloadState?
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onPlayLocally: () -> Void
    /// False while synchronised transport owns playback: playing here would change only this phone
    /// (PR #18 review). `MusicCoordinator` refuses it regardless.
    var playHereOffered = true
    var onPlayOnBoth: (() -> Void)?
    var onAddToSharedQueue: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.xs) {
            HStack(spacing: RideDesign.sm) {
                VStack(alignment: .leading, spacing: RideDesign.xs) {
                    Text(entry.title).font(.body).lineLimit(1)
                    Text("\(entry.artist) · \(availabilityLabel)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                // Closure-audit Finding G: playback reuses the one existing player/queue for both a
                // Phase 3 imported row (`hasLocal`) *and* a verified Phase-4 cache-only file that
                // was never imported (`hasCached`) — see `MusicCoordinator.playExternalVerifiedCachedTrack`.
                if availability.hasLocal || availability.hasCached {
                    if playHereOffered { Button("Play here", action: onPlayLocally).buttonStyle(.borderless) }
                } else if let download, activeStatuses.contains(download.status) {
                    Button("Cancel", action: onCancel).buttonStyle(.borderless)
                } else {
                    Button("Download", action: onDownload)
                        .buttonStyle(.borderless)
                        .disabled(entry.contentHash == nil)
                }
                if onPlayOnBoth != nil || onAddToSharedQueue != nil {
                    Menu {
                        if let onPlayOnBoth { Button("Play on both phones", action: onPlayOnBoth) }
                        if let onAddToSharedQueue { Button("Add to shared queue", action: onAddToSharedQueue) }
                    } label: {
                        Image(systemName: "ellipsis.circle").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("More for \(entry.title)")
                }
            }
            if let download, activeStatuses.contains(download.status), download.totalBytes > 0 {
                ProgressView(value: Double(download.bytesReceived), total: Double(download.totalBytes))
            }
            if let error = download?.error { DisclosureGroup("Transfer details") { Text(error.rawValue) } }
        }
    }

    private var availabilityLabel: String {
        if availability.hasLocal { return "On this phone" }
        if availability.hasCached { return "Downloaded" }
        guard let download else { return "On the other phone" }
        switch download.status {
        case .failed: return "Download failed"
        case .cancelled: return "Download cancelled"
        case .queued: return "Waiting to download"
        case .negotiating: return "Starting download…"
        case .transferring: return "Downloading…"
        case .verifying: return "Checking download…"
        case .complete: return "Downloaded"
        case .idle: return "On the other phone"
        }
    }
}
