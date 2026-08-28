import Foundation
@testable import RideLinkCore
@testable import RideLinkPlatform

/// `FsmSession`'s own doc comment names exactly why it cannot stand as this fix's regression test:
/// "Hopping through `Task { await ... }` would not [preserve order], and the order is exactly the
/// property under test" — yet `FsmSession.attach()` records every event **synchronously inside**
/// `ControlSessionManager`'s callback, which is precisely the delivery style production never used.
/// `SessionCoordinator` always hopped through a `Task` per event; that hop is what could reorder.
///
/// `OrderedFsmSession` wires a session the way `SessionCoordinator.startDiscovery` actually does
/// post-fix: `ControlSessionManager.emit` calls a `@Sendable` closure that only does
/// `channel.send(event)`, and a single long-lived `Task` drains `channel.stream` with one
/// `for await` loop. If the delivery mechanism ever regressed back to a `Task` per event, this
/// harness — not just `OrderedEventChannelTests` — would be positioned to catch it, because it
/// exercises the exact same `ControlSessionManager` / `SessionGate` / `SessionFsm` wiring
/// `FsmSession`-based tests already cover, through the production delivery path instead of a
/// synchronous stand-in for it.
final class OrderedFsmSession: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [ControlEvent] = []
    private var visited: [SessionStatus] = [.idle]
    private var fsmState: FsmState = .initial
    private let channel = OrderedEventChannel<ControlEvent>()
    private var consumerTask: Task<Void, Never>?

    let peer: TestPeer
    let manager: ControlSessionManager

    init(peer: TestPeer, manager: ControlSessionManager) {
        self.peer = peer
        self.manager = manager
    }

    var status: SessionStatus { lock.withLock { fsmState.status } }
    var events: [ControlEvent] { lock.withLock { recorded } }
    var visitedStatuses: [SessionStatus] { lock.withLock { visited } }
    var trustStore: InMemoryTrustedPeerStore { peer.trustedPeers }

    @discardableResult
    func apply(_ event: SessionEvent) -> Bool {
        lock.withLock { applyLocked(event) }
    }

    private func applyLocked(_ event: SessionEvent) -> Bool {
        if case .transitioned(let newState, _) = SessionFsm.transition(fsmState, event) {
            fsmState = newState
            visited.append(newState.status)
            return true
        }
        return false
    }

    /// The production wiring: `setOnEvent`'s closure only enqueues, and exactly one `Task`
    /// consumes. Nothing here ever processes an event from inside the manager's own callback.
    func attach() async {
        await manager.setOnEvent { [channel] event in channel.send(event) }
        consumerTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.channel.stream {
                self.record(event)
            }
        }
    }

    /// Mirrors `SessionCoordinator.teardownSession`: cancel first, then finish — proving a stale
    /// send after this point cannot mutate `fsmState` (item 5/7 of the acceptance list).
    func detach() {
        consumerTask?.cancel()
        consumerTask = nil
        channel.finish()
    }

    /// Only for the "stale event" test: a send that must be provably discarded once `detach()` has
    /// run, exactly as a leftover `ControlSessionManager` callback reference would be after
    /// `SessionCoordinator` has moved on to a new session.
    func sendDirectly(_ event: ControlEvent) {
        channel.send(event)
    }

    private func record(_ event: ControlEvent) {
        lock.withLock {
            recorded.append(event)
            if let sessionEvent = SessionGate.sessionEvent(for: event, status: fsmState.status) {
                _ = applyLocked(sessionEvent)
            }
        }
    }

    func count(where predicate: (ControlEvent) -> Bool) -> Int { events.filter(predicate).count }

    func hasReached(_ target: SessionStatus) -> Bool { visitedStatuses.contains(target) }

    /// The `Connected`/`PeerTrusted`/`PairingRequired`/`PairingSucceeded` names only, in order.
    var gateEventNames: [String] {
        events.compactMap { event in
            switch event {
            case .connected: return "Connected"
            case .peerTrusted: return "PeerTrusted"
            case .pairingRequired: return "PairingRequired"
            case .pairingSucceeded: return "PairingSucceeded"
            default: return nil
            }
        }
    }

    func awaitStatus(_ target: SessionStatus) async throws {
        _ = try await poll { self.status == target ? true : nil }
    }

    func awaitEvent(where predicate: @escaping @Sendable (ControlEvent) -> Bool) async throws {
        _ = try await poll { self.events.contains(where: predicate) ? true : nil }
    }

    @discardableResult
    func awaitPairingPrompt() async throws -> PairingPrompt {
        try await poll { await self.manager.pairingPrompt }
    }

    private func poll<T>(_ body: @escaping () async -> T?) async throws -> T {
        let deadline = Date().addingTimeInterval(Self.timeoutSeconds)
        while Date() < deadline {
            if let value = await body() { return value }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw ControlTransportError.notReady
    }

    static let timeoutSeconds: Double = 20
}
