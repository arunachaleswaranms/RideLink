import RideLinkCore
import RideLinkPlatform
import SwiftUI

/// Phase 5's synchronised playback on the main screen: whether both phones play together, a way back
/// to this phone only, and a short preview of the shared queue. Mirrors Android's `SyncPlaybackCard`.
/// The full queue and the catalogue are `SharedMusicScreen`, which is lazy (Phase 9A.5). The
/// transport row moved into diagnostics: `MusicCoordinator`'s gate already routes the main Now
/// Playing controls through the leader-ordered path while synchronised mode is active (ADR-024 A14).
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
            Text(stateLabel).font(.subheadline)
            if presenter.diagnostics.role != nil {
                if presenter.isSynchronizedModeActive {
                    Button("Play on this phone only") { presenter.leaveSynchronizedMode() }.buttonStyle(.bordered)
                }
                SharedQueueRows(queue: presenter.queueState, titles: titles, onRemove: presenter.removeFromQueue, previewLimit: 3)
                DisclosureGroup("Sync diagnostics") {
                    directCommands
                    diagnosticsSection
                }
            }
        }
    }

    private var titles: [String: String] {
        Dictionary(sharedLibrary.remoteEntries.compactMap { entry in entry.contentHash.map { ($0.value, entry.title) } },
                   uniquingKeysWith: { first, _ in first })
    }

    private var directCommands: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Direct synchronized commands").font(.caption).foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110))], alignment: .leading, spacing: RideDesign.sm) {
                Button("Previous") { presenter.previous() }.buttonStyle(.bordered)
                Button("Pause") { presenter.pause() }.buttonStyle(.bordered)
                Button("Resume") { presenter.resume() }.buttonStyle(.bordered)
                Button("Next") { presenter.next() }.buttonStyle(.bordered)
                Button("Seek 0:00") { presenter.seek(positionMs: 0) }.buttonStyle(.bordered)
            }.disabled(!presenter.isSynchronizedModeActive)
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

/// Shared-queue rows. Duplicate hashes keep distinct queue item IDs; removal names the item, never the
/// track. `previewLimit` bounds the main screen's preview; the full list is `SharedMusicScreen`.
struct SharedQueueRows: View {
    let queue: SharedQueueState
    let titles: [String: String]
    let onRemove: (String) -> Void
    var previewLimit: Int = .max

    var body: some View {
        if queue.items.isEmpty {
            Text("Nothing queued. Add a track from the other phone's music.").font(.subheadline).foregroundStyle(.secondary)
        }
        ForEach(Array(queue.items.prefix(previewLimit)), id: \.queueItemId) { item in
            let title = titles[item.trackHash.value].flatMap { $0.isEmpty ? nil : $0 } ?? "Shared track"
            HStack {
                VStack(alignment: .leading, spacing: RideDesign.xs) {
                    Text(title).font(.body).lineLimit(1)
                    if item.queueItemId == queue.currentItemId {
                        Text("Now playing").font(.caption).foregroundStyle(RideDesign.primary)
                    }
                }
                Spacer()
                Button { onRemove(item.queueItemId) } label: { Image(systemName: "xmark.circle").frame(width: 44, height: 44) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Remove \(title) from the shared queue")
            }
        }
        if queue.items.count > previewLimit {
            Text("and \(queue.items.count - previewLimit) more").font(.caption).foregroundStyle(.secondary)
        }
    }
}
