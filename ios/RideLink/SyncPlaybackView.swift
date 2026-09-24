import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Phase 5's minimal affordance: enough to *drive and observe* synchronised playback on two phones,
/// and nothing more. Mirrors Android's `SyncPlaybackCard`.
///
/// **This is deliberately not a ride screen.** Phase 7 owns Ride Mode, and a riding UI designed
/// before anyone has ridden with this would be guesswork — the same reasoning ADR-020 already
/// applied to the intercom card next to it.
///
/// **The internal leader is never presented as a master.** ADR-010's rule is that both users get
/// identical, fully capable controls; the role appears only in the diagnostics block, as a fact
/// about command ordering, and every control below works the same on both phones.
struct SyncPlaybackView: View {
    let presenter: SyncPlaybackPresenter
    let sharedLibrary: SharedLibraryCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Synchronized Playback").font(.headline)
            Text(stateLabel).font(.subheadline)

            if presenter.diagnostics.role == nil {
                Text("No peer session — local playback only")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                controls
                queueSection
                playableSection
                DisclosureGroup("Sync diagnostics") { diagnosticsSection }
            }
        }
        .padding(RideDesign.md)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], alignment: .leading, spacing: RideDesign.sm) {
                Button("Previous") { presenter.previous() }.buttonStyle(.bordered)
                Button("Pause") { presenter.pause() }.buttonStyle(.bordered)
                Button("Resume") { presenter.resume() }.buttonStyle(.bordered)
                Button("Next") { presenter.next() }.buttonStyle(.bordered)
            }.disabled(!presenter.isSynchronizedModeActive)
            HStack(spacing: RideDesign.sm) {
                Button("Seek 0:00") { presenter.seek(positionMs: 0) }.buttonStyle(.bordered).disabled(!presenter.isSynchronizedModeActive)
                Button("Play locally") { presenter.leaveSynchronizedMode() }.buttonStyle(.bordered)
            }
        }
    }

    private var queueSection: some View {
        SharedQueueContent(queue: presenter.queueState,
            titles: Dictionary(sharedLibrary.remoteEntries.compactMap { entry in
                entry.contentHash.map { ($0.value, entry.title) }
            }, uniquingKeysWith: { first, _ in first }), onRemove: presenter.removeFromQueue)
    }

    private var playableSection: some View {
        VStack(alignment: .leading, spacing: RideDesign.xs) {
            Text("Playable on both phones").font(.subheadline)
            let playable = sharedLibrary.remoteEntries.filter { entry in
                guard let hash = entry.contentHash else { return false }
                return sharedLibrary.availability(for: entry).playableLocally && sharedLibrary.peerHasContent(hash)
            }
            if playable.isEmpty {
                Text("Download a shared track to make it available on both phones.")
                    .font(.caption)
            }
            ForEach(playable, id: \.rowId) { entry in
                if let hash = entry.contentHash {
                    VStack(alignment: .leading, spacing: RideDesign.sm) {
                        Text(entry.title).font(.caption)
                        Spacer()
                        Button("Play synced") { presenter.playSynchronized(hash) }.buttonStyle(.bordered)
                        Button("Queue") { presenter.enqueue(hash) }.buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    /// FR-023's Phase 5 half. Every number is a measurement or a count of something refused — there
    /// is no claim here about audible alignment, which only the real-device gate can produce.
    private var diagnosticsSection: some View {
        let diagnostics = presenter.diagnostics
        return VStack(alignment: .leading, spacing: RideDesign.xs) {
            Text("Sync diagnostics").font(.subheadline)
            row("sync state", String(describing: diagnostics.syncState))
            row("transport ownership", presenter.isSynchronizedModeActive ? "synchronized" : "local")
            row("role", diagnostics.role == .leader ? "orders commands" : "sends intents")
            row("clock ready", String(diagnostics.clockReady))
            row("clock offset", diagnostics.clockOffsetUs.map { "\($0) us" } ?? "—")
            row("rtt p95", diagnostics.rttP95Us.map { "\($0) us" } ?? "—")
            row("scheduling lead", diagnostics.leadUs.map { "\($0) us" } ?? "—")
            row("last command_seq", diagnostics.lastAppliedCommandSeq.map(String.init) ?? "—")
            row("late commands", String(diagnostics.lateCommandCount))
            row("duplicate / stale", "\(diagnostics.duplicateCommandCount) / \(diagnostics.staleCommandCount)")
            row("role violations", String(diagnostics.roleViolationCount))
            row("stale revisions", String(diagnostics.staleRevisionCount))
            row("queue revision / size", "\(diagnostics.queueRevision) / \(diagnostics.queueSize)")
            row("local drift", diagnostics.localDriftMs.map { "\($0) ms" } ?? "—")
            row("peer drift", diagnostics.peerDriftMs.map { "\($0) ms" } ?? "—")
            row("last correction", diagnostics.lastCorrection.rawValue)
            row("playback rate", String(diagnostics.playbackRate))
            row("hard seeks", String(diagnostics.hardSeekCount))
            row("schedule error", diagnostics.lastScheduleErrorUs.map { "\($0) us (software only)" } ?? "—")
            row("route transitioning", String(diagnostics.routeTransitioning))
            row("correction ticks", String(diagnostics.correctionTickCount))
            row("session generation", String(diagnostics.sessionGeneration))
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.caption)
            Spacer()
            Text(value).font(.caption)
        }
    }

    private var stateLabel: String { UiPresentation.rideMusicLabel(status: .connected, state: presenter.diagnostics.syncState, ownsTransport: presenter.isSynchronizedModeActive) }
}

struct SharedQueueContent: View {
    let queue: SharedQueueState
    let titles: [String: String]
    let onRemove: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.md) {
            Text("Shared queue").font(.headline)
            if queue.items.isEmpty { Text("Queue is empty. Add a track from the shared library.").font(.subheadline) }
            ForEach(Array(queue.items.enumerated()), id: \.element.queueItemId) { index, item in
                HStack {
                    VStack(alignment: .leading, spacing: RideDesign.xs) {
                        Text(titles[item.trackHash.value].flatMap { $0.isEmpty ? nil : $0 } ?? "Shared track").font(.body)
                        Text(item.queueItemId == queue.currentItemId ? "Current item" : "Queue item \(index + 1)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Remove") { onRemove(item.queueItemId) }.buttonStyle(.bordered)
                }
            }
        }
    }
}
