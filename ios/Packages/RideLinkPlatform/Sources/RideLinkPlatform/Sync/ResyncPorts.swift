import Foundation
import RideLinkCore

/// `ResyncCoordinator`'s exact call surface on `ControlSessionManager`'s `ResyncRelay`, mirroring
/// `SyncPlaybackChannel`/`SyncSessionPort`'s narrow-seam pattern exactly and for the same reason:
/// `ResyncCoordinator` depends on a concrete production class in `RideLinkPlatform` with no reason
/// to know a test double exists.
public protocol ResyncChannel: Sendable {
    func setSink(_ sink: (any ResyncSink)?) async
    @discardableResult func send(_ message: ResyncMessage) async -> Bool
}

/// `ResyncCoordinator`'s exact call surface on `ControlSessionManager`. Session **lifecycle** is
/// deliberately not here, for the reason `SyncSessionPort` already records: `ControlSessionManager
/// .onEvent` is a single mutable callback slot on this platform, and the app's `SessionCoordinator`
/// already owns it — so it forwards `.connected` explicitly to `ResyncCoordinator.onConnected`,
/// exactly as it already does for `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`.
public protocol ResyncSessionPort: Sendable {
    var channel: any ResyncChannel { get }
    func currentAuthGeneration() async -> Int64
    /// ADR-025 §1: see `SyncSessionPort`'s equivalent contract — the liveness half, re-proved
    /// immediately before a leader's `STATE_SNAPSHOT` reply is sent.
    func liveAuthenticatedGeneration() -> Int64?
}

/// Zero-behaviour-change wrapper reaching the manager's `ResyncRelay`.
public struct ControlSessionResyncChannel: ResyncChannel {
    private let manager: ControlSessionManager

    public init(manager: ControlSessionManager) { self.manager = manager }

    public func setSink(_ sink: (any ResyncSink)?) async { await manager.resyncRelay().setSink(sink) }

    @discardableResult
    public func send(_ message: ResyncMessage) async -> Bool { await manager.resyncRelay().send(message) }
}

/// Zero-behaviour-change wrapper — the app composition root's production call site.
public struct ControlSessionResyncPort: ResyncSessionPort {
    private let manager: ControlSessionManager
    public let channel: any ResyncChannel

    public init(manager: ControlSessionManager) {
        self.manager = manager
        channel = ControlSessionResyncChannel(manager: manager)
    }

    public func currentAuthGeneration() async -> Int64 { await manager.currentAuthGeneration }

    public func liveAuthenticatedGeneration() -> Int64? { manager.liveAuthenticatedGeneration() }
}
