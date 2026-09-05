import RideLinkCore
import SwiftUI

/// Phase 4's minimum usable shared-library surface (brief §27): the connected peer's catalogue,
/// each track's availability, and download/cancel/play affordances. **Not** Ride Mode — no
/// synchronized controls, no playback state shared with the peer, shown only once the trust gate
/// has passed (the caller gates that, the same way `VoiceCard` is gated in `MainScreen`).
///
/// Availability comes entirely from `SharedLibraryCoordinator.availability(for:)` — never invented
/// locally — matching brief §7's "do not infer cached availability until… the final cache object
/// was committed successfully." Mirrors Android's `SharedLibraryScreen` exactly.
struct SharedLibraryView: View {
    let coordinator: SharedLibraryCoordinator
    let onPlayLocally: (ManifestEntry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Shared Library").font(.headline)
            if coordinator.remoteEntries.isEmpty {
                Text("No shared catalogue yet.").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("\(coordinator.remoteEntries.count) track(s) on the connected peer")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            VStack(spacing: 4) {
                ForEach(coordinator.remoteEntries, id: \.quickId.value) { entry in
                    SharedTrackRow(
                        entry: entry,
                        availability: coordinator.availability(for: entry),
                        download: entry.contentHash.flatMap { coordinator.downloadStates[$0.value] },
                        onDownload: { coordinator.requestDownload(entry) },
                        onCancel: { entry.contentHash.map(coordinator.cancelDownload) },
                        onPlayLocally: { onPlayLocally(entry) }
                    )
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.purple.opacity(0.08))
        .cornerRadius(8)
    }
}

private let activeStatuses: Set<TransferStatus> = [.queued, .negotiating, .transferring, .verifying]

private struct SharedTrackRow: View {
    let entry: ManifestEntry
    let availability: Availability
    let download: DownloadState?
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onPlayLocally: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.title).font(.body).lineLimit(1)
            Text("\(entry.artist) — \(entry.album)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(availabilityLabel).font(.caption2)
            if let download, activeStatuses.contains(download.status), download.totalBytes > 0 {
                ProgressView(value: Double(download.bytesReceived), total: Double(download.totalBytes))
            }
            HStack(spacing: 8) {
                if availability.hasLocal {
                    // brief §19: playback reuses the one existing player/queue, which is keyed on a
                    // Phase 3 LocalEntryId — a cache-only file (never imported) has none yet, so
                    // "Play" is offered only once the same content_hash also exists as a local row.
                    // Wiring cache-only playback through the player is a deliberate next step, not
                    // done in this minimal pass (see docs/STATUS.md).
                    Button("Play", action: onPlayLocally).buttonStyle(.borderedProminent)
                } else if let download, activeStatuses.contains(download.status) {
                    Button("Cancel", action: onCancel).buttonStyle(.bordered)
                } else if !availability.hasCached {
                    Button("Download", action: onDownload)
                        .buttonStyle(.bordered)
                        .disabled(entry.contentHash == nil)
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.08))
        .cornerRadius(6)
    }

    private var availabilityLabel: String {
        if availability.hasLocal { return "Local" }
        if availability.hasCached { return "Downloaded" }
        guard let download else { return "Remote only" }
        switch download.status {
        case .failed: return "Failed (\(download.error?.rawValue ?? "unknown"))"
        case .cancelled: return "Cancelled"
        case .queued: return "Queued"
        case .negotiating: return "Starting…"
        case .transferring: return "Downloading…"
        case .verifying: return "Verifying…"
        case .complete: return "Downloaded"
        case .idle: return "Remote only"
        }
    }
}
