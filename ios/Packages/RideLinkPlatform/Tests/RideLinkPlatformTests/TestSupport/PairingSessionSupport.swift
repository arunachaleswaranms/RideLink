import Foundation
@testable import RideLinkCore
@testable import RideLinkPlatform

/// One phone, as the app actually wires it: a real `ControlSessionManager` over a real TLS channel,
/// with its `ControlEvent`s driven through the real `SessionGate` into the real `SessionFsm`.
///
/// The few lines of `record(_:)` are the entirety of what `SessionCoordinator` does with a control
/// event's *state* half — side effects (raising a security alert) are the coordinator's and are not
/// what these tests are about.
///
/// Deliberately lock-based rather than an actor: the manager emits serially, and recording
/// synchronously inside its callback is what preserves the **order** of the events. Hopping through
/// `Task { await ... }` would not, and the order is exactly the property under test.
final class FsmSession: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [ControlEvent] = []
    private var visited: [SessionStatus] = [.idle]
    private var fsmState: FsmState = .initial

    let peer: TestPeer
    let manager: ControlSessionManager

    init(peer: TestPeer, manager: ControlSessionManager) {
        self.peer = peer
        self.manager = manager
    }

    var status: SessionStatus { lock.withLock { fsmState.status } }
    var events: [ControlEvent] { lock.withLock { recorded } }
    /// Every status this session has ever been in, in order. Absence proofs are made against this.
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

    func record(_ event: ControlEvent) {
        lock.withLock {
            recorded.append(event)
            if let sessionEvent = SessionGate.sessionEvent(for: event, status: fsmState.status) {
                _ = applyLocked(sessionEvent)
            }
        }
    }

    func attach() async {
        await manager.setOnEvent { [weak self] event in self?.record(event) }
    }

    func count(where predicate: (ControlEvent) -> Bool) -> Int { events.filter(predicate).count }

    func hasReached(_ target: SessionStatus) -> Bool { visitedStatuses.contains(target) }

    /// The `Connected`/`PeerTrusted`/`PairingRequired`/`PairingSucceeded` names only, in order —
    /// the trust-gate events, which is what the invariant is about.
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

    @discardableResult
    func awaitPairingPrompt() async throws -> PairingPrompt {
        try await poll { await self.manager.pairingPrompt }
    }

    func awaitStatus(_ target: SessionStatus) async throws {
        _ = try await poll { self.status == target ? true : nil }
    }

    func awaitEvent(where predicate: @escaping @Sendable (ControlEvent) -> Bool) async throws {
        _ = try await poll { self.events.contains(where: predicate) ? true : nil }
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
    /// Long enough for a `PAIR_CONFIRM` to cross loopback and be acted on, so that "still
    /// `.pairing`" afterwards is a real absence rather than a race the assertion won.
    static let settleNs: UInt64 = 500_000_000
}

/// Wraps a `ControlChannel` and counts how many connections it opens.
///
/// It is how "pairing completing must not open a second TLS connection" is asserted as a fact
/// rather than as a code reading: the six digits are bound to *one* exporter (PROTOCOL §4.5.1), so
/// a second handshake after confirmation would silently mean the users approved a session that is
/// no longer the one in use.
final class CountingControlChannel: ControlChannel, @unchecked Sendable {
    private let delegate: any ControlChannel
    private let lock = NSLock()
    private var dialCount = 0

    init(_ delegate: any ControlChannel) { self.delegate = delegate }

    var dials: Int { lock.withLock { dialCount } }
    var transportLabel: String { delegate.transportLabel }
    var isSecure: Bool { delegate.isSecure }

    func bind() async throws -> ControlListener { try await delegate.bind() }

    func connect(host: String, port: UInt16) async throws -> ControlConnection {
        lock.withLock { dialCount += 1 }
        return try await delegate.connect(host: host, port: port)
    }
}
