import Foundation
import Observation
import RideLinkCore
import RideLinkPlatform
#if canImport(UIKit)
import UIKit
#endif

/// The single owner of session state (CLAUDE.md rule 8 / ARCHITECTURE §3 rule 4). No SwiftUI view
/// holds connection state of its own; every screen observes this coordinator directly.
///
/// Phase 1a scope: discovery -> `PlainControlTransportPhase1a` HELLO/dedup/PING-PONG ->
/// `CONNECTED`. There is no pairing UI yet (CLAUDE.md rule 28 / this session's brief §9): the
/// first discovered peer is dialled automatically and `pairingSucceeded` is applied immediately
/// after `peerSelected`, since Phase 1a's plaintext transport has no trust to establish yet. That
/// is a Phase 1a *simplification*, not a change to the FSM's legal transitions — real pairing
/// (SAS confirmation) arrives with Phase 1b.
@Observable
@MainActor
public final class SessionCoordinator {
    public private(set) var state: FsmState = .initial
    public private(set) var discoveredPeers: [DiscoveredPeer] = []
    public private(set) var discoveryCount = 0
    public private(set) var controlDiagnostics = ControlDiagnostics()

    private let discovery = BonjourDiscovery()
    private let controlSessionManager: ControlSessionManager
    private let localIdentity: LocalHandshakeIdentity
    private let logger: StructuredLogger

    private var connectAttempted = false
    private var lastPeerHost: String?
    private var lastPeerPort: UInt16?
    private var sessionTask: Task<Void, Never>?

    public init() {
        let sink = InMemoryLogSink()
        // DispatchTime's uptimeNanoseconds is mach_absolute_time-backed — monotonic, matching
        // ARCHITECTURE §7's "monotonic clocks only" rule.
        let monotonicNowUs: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
        logger = StructuredLogger(sink: sink, monotonicNowUs: monotonicNowUs)
        controlSessionManager = ControlSessionManager(monotonicNowUs: monotonicNowUs)
        #if canImport(UIKit)
        let deviceName = UIDevice.current.name
        let osVersion = UIDevice.current.systemVersion
        #else
        let deviceName = ProcessInfo.processInfo.hostName
        let osVersion = ProcessInfo.processInfo.operatingSystemVersionString
        #endif
        localIdentity = LocalHandshakeIdentity(
            displayName: deviceName,
            platform: "ios",
            osVersion: osVersion,
            appVersion: "0.1.0",
            connTiebreak: ConnTiebreakGenerator.generate()
        )
    }

    public func startDiscovery() {
        guard applyEvent(.startDiscovery) else { return }
        connectAttempted = false
        discoveredPeers = []
        discoveryCount = 0

        let manager = controlSessionManager
        let discoverySession = discovery
        let identity = localIdentity
        Task { [weak self] in
            await manager.setOnEvent { event in
                Task { @MainActor in self?.handleControlEvent(event) }
            }
        }
        Task { [weak self] in
            await manager.setOnDiagnosticsChanged { diagnostics in
                Task { @MainActor in self?.controlDiagnostics = diagnostics }
            }
        }

        sessionTask = Task { [weak self] in
            guard (try? await manager.startListening(local: identity)) != nil else { return }
            if let listener = await manager.underlyingListener() {
                discoverySession.startAdvertising(on: listener) { advertiseState in
                    Task { @MainActor in self?.logger.debug("SessionCoordinator", "advertise: \(advertiseState)") }
                }
            }
            discoverySession.startBrowsing { event in
                Task { @MainActor in self?.handleDiscoveryEvent(event) }
            }
        }
    }

    public func cancelDiscovery() {
        _ = applyEvent(.cancelDiscovery)
        teardownSession()
        discoveredPeers = []
    }

    private func teardownSession() {
        sessionTask?.cancel()
        sessionTask = nil
        discovery.stopBrowsing()
        discovery.stopAdvertising()
        let manager = controlSessionManager
        Task { await manager.shutdown() }
    }

    private func handleDiscoveryEvent(_ event: DiscoveryEvent) {
        switch event {
        case .found(let peer):
            discoveredPeers.removeAll { $0.discoveryHandle == peer.discoveryHandle }
            discoveredPeers.append(peer)
            discoveryCount += 1
            maybeConnect(peer)
        case .updated(let peer):
            if let index = discoveredPeers.firstIndex(where: { $0.discoveryHandle == peer.discoveryHandle }) {
                discoveredPeers[index] = peer
            }
        case .lost(let discoveryHandle):
            discoveredPeers.removeAll { $0.discoveryHandle == discoveryHandle }
        }
    }

    private func maybeConnect(_ peer: DiscoveredPeer) {
        guard !connectAttempted, state.status == .discovering else { return }
        guard let port = UInt16(exactly: peer.port) else { return }
        connectAttempted = true
        lastPeerHost = peer.host
        lastPeerPort = port
        _ = applyEvent(.peerSelected)
        _ = applyEvent(.pairingSucceeded)
        let manager = controlSessionManager
        let identity = localIdentity
        Task { await manager.connectTo(host: peer.host, port: port, local: identity) }
    }

    private func handleControlEvent(_ event: ControlEvent) {
        switch event {
        case .connected:
            switch state.status {
            case .connecting: _ = applyEvent(.connectionEstablished)
            case .reconnecting: _ = applyEvent(.reconnectSucceeded)
            default: break
            }
        case .linkLost(let reason):
            switch reason {
            case .network:
                if state.status == .connecting {
                    _ = applyEvent(.connectionFailed)
                } else {
                    _ = applyEvent(.linkLost(reason: .network))
                }
                beginReconnectIfPossible()
            case .bye:
                _ = applyEvent(.linkLost(reason: .bye))
            case .duplicateConnection, .userEnded:
                break
            }
        case .duplicateConnectionClosed:
            _ = applyEvent(.duplicateConnectionClosed)
        case .reconnectBudgetExhausted:
            _ = applyEvent(.reconnectBudgetExhausted)
        }
    }

    private func beginReconnectIfPossible() {
        guard let host = lastPeerHost, let port = lastPeerPort, state.status == .reconnecting else { return }
        let manager = controlSessionManager
        let identity = localIdentity
        Task { await manager.beginReconnect(local: identity, host: host, port: port) }
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
            teardownSession()
        }
    }
}
