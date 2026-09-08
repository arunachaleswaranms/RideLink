import Foundation
import Observation
import RideLinkCore
import RideLinkPlatform

/// The `@MainActor`, `@Observable` face of `RideLinkPlatform.SyncPlaybackCoordinator`.
///
/// The coordinator is an `actor` — every distributed decision it makes is serialised, which is what
/// makes Swift 6 strict concurrency a help rather than an obstacle there — and SwiftUI needs a main-
/// actor observable. This holds the two values a view renders and forwards every action; it decides
/// nothing itself, which is why it has no state of its own beyond the last published snapshot.
///
/// It also gives `SyncPlaybackGateAdapter` its two **synchronous** reads. `MusicCoordinator`'s
/// callers — including `MPRemoteCommandCenter`'s handlers — are synchronous and cannot await an
/// actor, so "is a synchronised session in force, and what is our role" has to be answerable without
/// suspending. Both are published by the coordinator itself on every change, so this mirror can
/// never disagree with it for longer than one hop.
@MainActor
@Observable
public final class SyncPlaybackPresenter {
    public private(set) var diagnostics = SyncPlaybackDiagnostics()
    public private(set) var queueState = SharedQueueState()

    private let coordinator: SyncPlaybackCoordinator

    public init(coordinator: SyncPlaybackCoordinator) {
        self.coordinator = coordinator
        Task { [weak self] in
            await coordinator.setDiagnosticsObserver { value in
                Task { @MainActor in self?.diagnostics = value }
            }
            await coordinator.setQueueObserver { value in
                Task { @MainActor in self?.queueState = value }
            }
        }
    }

    /// Whether a synchronised session currently owns transport control (brief §39/§40). Derived from
    /// the last published diagnostics rather than awaited, for the reason this type's doc comment
    /// gives: the gate's callers are synchronous.
    public var isSynchronizedModeActive: Bool {
        diagnostics.role != nil && diagnostics.syncState != .inactive
    }

    public var role: PlaybackRole? { diagnostics.role }

    public func playSynchronized(_ contentHash: ContentHash) {
        Task { await coordinator.playSynchronized(contentHash) }
    }

    public func enqueue(_ contentHash: ContentHash) {
        Task { await coordinator.enqueue(contentHash) }
    }

    public func removeFromQueue(_ queueItemId: String) {
        Task { await coordinator.removeFromQueue(queueItemId) }
    }

    public func pause() { Task { await coordinator.pause() } }

    public func resume() { Task { await coordinator.resume() } }

    public func seek(positionMs: Int64) { Task { await coordinator.seek(positionMs: positionMs) } }

    public func next() { Task { await coordinator.next() } }

    public func previous() { Task { await coordinator.previous() } }

    public func leaveSynchronizedMode() { Task { await coordinator.leaveSynchronizedMode() } }
}
