import Foundation
import Observation
import RideLinkCore

/// The `@MainActor`, `@Observable` face of `RideLinkPlatform.SyncPlaybackCoordinator`.
///
/// The coordinator is an `actor` — every distributed decision it makes is serialised, which is what
/// makes Swift 6 strict concurrency a help rather than an obstacle there — and SwiftUI needs a main-
/// actor observable. This holds the two values a view renders and forwards every action; it decides
/// nothing itself, which is why it has no state of its own beyond the last published snapshot.
///
/// **It is not where transport authority comes from** (ADR-024 Amendment A14). It used to be: it
/// gave `SyncPlaybackGateAdapter` "is a synchronised session in force" as
/// `diagnostics.role != nil && diagnostics.syncState != .inactive`, a reconstruction from display
/// fields. That stopped being equivalent to the coordinator's own `syncEnabled && role != nil` the
/// moment an already-distributed obligation was allowed to finish after End Ride (Amendment A13):
/// the role survives End Ride on purpose, and the finishing command reports `.scheduled`/`.synced`,
/// so the reconstruction said "synchronised" with synchronised mode over. The gate now reads
/// `SyncPlaybackCoordinator.transportOwnership` directly, and `isSynchronizedModeActive` below reads
/// the same mirror — a copy of one source, never derived from `diagnostics`.
@MainActor
@Observable
public final class SyncPlaybackPresenter {
    public private(set) var diagnostics = SyncPlaybackDiagnostics()
    public private(set) var queueState = SharedQueueState()

    private let coordinator: SyncPlaybackCoordinator

    public init(
        coordinator: SyncPlaybackCoordinator,
        onDiagnostics: (@MainActor (SyncPlaybackDiagnostics) -> Void)? = nil
    ) {
        self.coordinator = coordinator
        Task { [weak self] in
            await coordinator.setDiagnosticsObserver { value in
                Task { @MainActor in
                    self?.diagnostics = value
                    onDiagnostics?(value)
                }
            }
            await coordinator.setQueueObserver { value in
                Task { @MainActor in self?.queueState = value }
            }
        }
    }

    /// Whether a synchronised session currently owns transport control (brief §39/§40), read
    /// synchronously from the coordinator's own mirror (ADR-024 Amendment A14) — never from
    /// `diagnostics`, whose `syncState` may legitimately say `.scheduled` or `.synced` while an old
    /// obligation finishes with synchronised mode already ended, and whose `role` survives End Ride.
    ///
    /// Not observed by SwiftUI: it is read on demand, and nothing renders it.
    public var isSynchronizedModeActive: Bool { coordinator.transportOwnership.current.isSynchronizedModeActive }

    public func playSynchronized(_ contentHash: ContentHash) {
        Task { await coordinator.playSynchronized(contentHash) }
    }

    public func enqueue(_ contentHash: ContentHash) {
        Task { await coordinator.enqueue(contentHash) }
    }

    public func removeFromQueue(_ queueItemId: String) {
        Task { await coordinator.removeFromQueue(queueItemId) }
    }

    // The synchronised-playback controls. Each is a fresh local press, so the coordinator admits it
    // only while a synchronised session owns transport control (ADR-024 Amendment A14) and drops it
    // otherwise — a press here never becomes synchronised authority merely because a role exists.
    // Local music is `MusicCoordinator`'s, through its gate.

    public func pause() { Task { await coordinator.pause() } }

    public func resume() { Task { await coordinator.resume() } }

    public func seek(positionMs: Int64) { Task { await coordinator.seek(positionMs: positionMs) } }

    public func next() { Task { await coordinator.next() } }

    public func previous() { Task { await coordinator.previous() } }

    public func leaveSynchronizedMode() { Task { await coordinator.leaveSynchronizedMode() } }
}
