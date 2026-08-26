import Foundation
import Observation
import RideLinkCore
import RideLinkPlatform

/// The single owner of session state (CLAUDE.md rule 8 / ARCHITECTURE §3 rule 4). No SwiftUI view
/// holds connection state of its own; every screen observes this coordinator directly.
///
/// Phase 1a scope only: discovery in, nothing else wired yet (no pairing, no control channel).
@Observable
@MainActor
public final class SessionCoordinator {
    public private(set) var state: FsmState = .initial
    public private(set) var discoveredPeers: [DiscoveredPeer] = []

    private let discovery = BonjourDiscovery()
    private let logger: StructuredLogger

    public init() {
        let sink = InMemoryLogSink()
        // DispatchTime's uptimeNanoseconds is mach_absolute_time-backed — monotonic, matching
        // ARCHITECTURE §7's "monotonic clocks only" rule.
        logger = StructuredLogger(sink: sink) { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
    }

    public func startDiscovery() {
        guard applyEvent(.startDiscovery) else { return }
        discoveredPeers = []
        discovery.startBrowsing { [weak self] peer in
            Task { @MainActor in
                self?.addDiscoveredPeer(peer)
            }
        }
    }

    public func cancelDiscovery() {
        _ = applyEvent(.cancelDiscovery)
        discovery.stopBrowsing()
        discoveredPeers = []
    }

    private func addDiscoveredPeer(_ peer: DiscoveredPeer) {
        guard !discoveredPeers.contains(where: { $0.discoveryHandle == peer.discoveryHandle }) else { return }
        discoveredPeers.append(peer)
    }

    @discardableResult
    private func applyEvent(_ event: SessionEvent) -> Bool {
        switch SessionFsm.transition(state, event) {
        case .transitioned(let newState, let effects):
            state = newState
            effects.forEach(runEffect)
            return true
        case .rejected:
            logger.warn("SessionCoordinator", "rejected \(event) from \(state.status)")
            return false
        case .ignored(_, _, let reason):
            logger.debug("SessionCoordinator", "ignored \(event) from \(state.status): \(reason)")
            return false
        }
    }

    private func runEffect(_ effect: Effect) {
        switch effect {
        case .logTransition(let from, let to, let trigger):
            logger.info("SessionCoordinator", "\(from.status) -> \(to.status) (\(trigger))")
        case .releaseAudioAndStopForegroundService:
            logger.info("SessionCoordinator", "release audio session (not yet implemented, Phase 1a)")
        }
    }
}
