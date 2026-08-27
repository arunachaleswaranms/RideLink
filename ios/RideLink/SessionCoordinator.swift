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
/// Phase 1b scope: discovery -> TLS 1.3 handshake -> SPKI pin check -> HELLO/dedup -> either a
/// silent trusted connect or PROTOCOL §4.5 pairing with a six-digit SAS -> `CONNECTED`.
///
/// The Phase 1a shortcut where `pairingSucceeded` fired automatically right after `peerSelected`
/// is **gone**: the FSM now stays in `.pairing` until the pin check says the peer is already
/// trusted, or until both users have confirmed the code. That was a placeholder for exactly this,
/// and removing it is what makes the `.pairing` state mean what ARCHITECTURE §3 says it means.
@Observable
@MainActor
public final class SessionCoordinator {
    public private(set) var state: FsmState = .initial
    public private(set) var discoveredPeers: [DiscoveredPeer] = []
    public private(set) var discoveryCount = 0
    public private(set) var controlDiagnostics = ControlDiagnostics()

    /// Non-nil only while two users are being asked to compare six digits (PROTOCOL §4.5).
    public private(set) var pairingPrompt: PairingPrompt?

    /// A refused handshake the user needs to see rather than a transient failure to retry.
    /// `pin_mismatch` above all: ADR-012 requires it to surface as a security warning and never to
    /// be auto-resolved by re-pairing.
    public private(set) var securityAlert: String?

    /// This device's own `identity_spki_sha256`, redacted to 6 hex for display (ARCHITECTURE §11).
    public let localIdentityPrefix: String

    private let discovery = BonjourDiscovery()
    private let trustedPeers: any TrustedPeerStore
    private let controlSessionManager: ControlSessionManager
    private let localIdentity: LocalHandshakeIdentity
    private let logger: StructuredLogger

    private var connectAttempted = false
    private var lastPeerHost: String?
    private var lastPeerPort: UInt16?
    private var sessionTask: Task<Void, Never>?

    /// Assembles the security wiring, and nothing else does: the Keychain identity (ADR-017), the
    /// one production `ControlChannel` — TLS 1.3 — and the trusted-peer store the SPKI pin is
    /// checked against (ADR-012).
    ///
    /// Throws if the device identity cannot be created. That is deliberately fatal to the session
    /// rather than degraded: without an identity there is no certificate, no pin and no channel
    /// binding, and PROTOCOL §1 admits no plaintext alternative to fall back to.
    public init() throws {
        let sink = InMemoryLogSink()
        // DispatchTime's uptimeNanoseconds is mach_absolute_time-backed — monotonic, matching
        // ARCHITECTURE §7's "monotonic clocks only" rule.
        let monotonicNowUs: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
        logger = StructuredLogger(sink: sink, monotonicNowUs: monotonicNowUs)

        let directory = Self.securityDirectory()
        // The one wall-clock read in the app, and only X.509 uses it — see `UtcTime` for why
        // certificate validity is the single permitted exception to the monotonic-clocks rule.
        let identity = try DeviceIdentityStore().loadOrCreate(now: UtcTime(Int64(Date().timeIntervalSince1970)))
        localIdentityPrefix = identity.identitySpkiSha256.description

        let channel = TlsControlChannel(identity: identity)
        SecureTransportPolicy.requireSecure(channel)

        trustedPeers = FileTrustedPeerStore(url: directory.appendingPathComponent("trusted_peers.json"))
        let localPeerId = LocalPeerIdStore(url: directory.appendingPathComponent("peer_id")).loadOrCreate()

        controlSessionManager = ControlSessionManager(
            localPeerId: localPeerId,
            channel: channel,
            trustedPeers: trustedPeers,
            monotonicNowUs: monotonicNowUs
        )
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
            connTiebreak: ConnTiebreakGenerator.generate(),
            identitySpkiSha256: identity.identitySpkiSha256
        )
    }

    private static func securityDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let directory = base.appendingPathComponent("RideLink/security", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// The user's answer on the pairing screen. Both peers must answer before any pin is written.
    public func confirmPairing(accepted: Bool) {
        let manager = controlSessionManager
        Task { await manager.confirmPairing(accepted: accepted) }
    }

    public func forgetPeer(_ peer: TrustedPeer) {
        trustedPeers.forget(peer.peerId)
        securityAlert = nil
    }

    public func dismissSecurityAlert() {
        securityAlert = nil
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
        Task { [weak self] in
            await manager.setOnPairingPromptChanged { prompt in
                Task { @MainActor in self?.pairingPrompt = prompt }
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
        // ARCHITECTURE §4.1: a discovered peer cannot be labelled "known" before a connection
        // exists, because the mDNS TXT record deliberately carries nothing durable (ADR-002 A1).
        // Whether this ends in a silent trusted connect or a pairing prompt is decided *after* the
        // TLS handshake, by the SPKI pin — so `.pairingSucceeded` is no longer applied here.
        _ = applyEvent(.peerSelected)
        let manager = controlSessionManager
        let identity = localIdentity
        Task { await manager.connectTo(host: peer.host, port: port, local: identity) }
    }

    private func handleControlEvent(_ event: ControlEvent) {
        switch event {
        case .connected:
            // A trusted peer never raises `.pairingRequired`, so reaching `.connected` from
            // `.pairing` without a code is the silent-connect path of ARCHITECTURE §4.3, not a
            // skipped check: the pin already matched inside the handshake.
            if state.status == .pairing { _ = applyEvent(.pairingSucceeded) }
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
        case .pairingRequired(let remotePeerId):
            logger.info("SessionCoordinator", "pairing required with \(remotePeerId)")
        case .pairingSucceeded(let peer):
            logger.info("SessionCoordinator", "paired with \(peer.peerId) (\(peer.identitySpkiSha256))")
        case .pairingFailed(let code):
            securityAlert = code
            _ = applyEvent(.pairingRejectedOrTimeout)
        case .handshakeRefused(let code):
            // pin_mismatch is the one that must never be quietly retried (ADR-012): it means the
            // key behind a familiar peer_id changed, which is either a reinstall or an attack, and
            // only the user can tell those apart.
            securityAlert = code
            logger.warn("SessionCoordinator", "handshake refused: \(code)")
            if state.status == .pairing { _ = applyEvent(.pairingRejectedOrTimeout) }
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
