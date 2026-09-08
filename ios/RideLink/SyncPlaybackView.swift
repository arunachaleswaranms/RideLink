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
        VStack(alignment: .leading, spacing: 6) {
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
                diagnosticsSection
            }
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button("Prev") { presenter.previous() }.buttonStyle(.bordered)
                Button("Pause") { presenter.pause() }.buttonStyle(.bordered)
                Button("Resume") { presenter.resume() }.buttonStyle(.bordered)
                Button("Next") { presenter.next() }.buttonStyle(.bordered)
            }
            HStack(spacing: 8) {
                Button("Seek 0:00") { presenter.seek(positionMs: 0) }.buttonStyle(.bordered)
                Button("Play locally") { presenter.leaveSynchronizedMode() }.buttonStyle(.bordered)
            }
        }
    }

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Shared queue (revision \(presenter.diagnostics.queueRevision))").font(.subheadline)
            if presenter.queueState.items.isEmpty {
                Text("Empty").font(.caption)
            }
            ForEach(presenter.queueState.items, id: \.queueItemId) { item in
                HStack {
                    // The hash prefix, not the filename: ADR-005's authoritative identity is what the
                    // queue is keyed on, and a filename would invite the user to believe otherwise.
                    Text("\(item.queueItemId == presenter.queueState.currentItemId ? "▶ " : "")\(item.trackHash.hex.prefix(8))…")
                        .font(.caption)
                    Spacer()
                    Button("Remove") { presenter.removeFromQueue(item.queueItemId) }.buttonStyle(.bordered)
                }
            }
        }
    }

    private var playableSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Playable on both phones").font(.subheadline)
            let playable = sharedLibrary.remoteEntries.filter { entry in
                guard let hash = entry.contentHash else { return false }
                return sharedLibrary.availability(for: entry).playableLocally && sharedLibrary.peerHasContent(hash)
            }
            if playable.isEmpty {
                Text("None yet — a track must be present on both phones before synchronized play (REQUIREMENTS §9.4)")
                    .font(.caption)
            }
            ForEach(playable, id: \.rowId) { entry in
                if let hash = entry.contentHash {
                    HStack {
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
        return VStack(alignment: .leading, spacing: 2) {
            Text("Sync diagnostics").font(.subheadline)
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

    private var stateLabel: String {
        switch presenter.diagnostics.syncState {
        case .inactive: return "LOCAL — not synchronized"
        case .clockUnready: return "CLOCK NOT READY — no command will be scheduled against it"
        case .waitingForContent: return "WAITING FOR CONTENT — both phones must hold the track"
        case .scheduled: return "SCHEDULED — waiting for the effective instant"
        case .synced: return "SYNCHRONIZED"
        case .syncFailed: return "SYNC FAILED — local playback continues, correction stopped"
        }
    }
}
