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
/// **Which FSM event a control event implies is not decided here.** That table is `SessionGate` —
/// pure, shared with Android case for case, and exhausted by unit tests on both platforms —
/// because it is where the Phase 1b security property lives: `.connected` is never read as
/// implicit pairing success, so an unknown peer cannot reach `CONNECTED` before both users have
/// confirmed the six digits and the pin has been written. What stays here is ownership of the
/// state itself (CLAUDE.md rule 8) and the side effects a control event carries: raising a
/// security alert, and starting a reconnect.
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

    /// FR-023 voice diagnostics. Empty until an authenticated session exists (PROTOCOL §7.1).
    public private(set) var voiceDiagnostics = VoiceDiagnostics()

    private let discovery = BonjourDiscovery()
    private let trustedPeers: any TrustedPeerStore
    private let controlSessionManager: ControlSessionManager
    private let localIdentity: LocalHandshakeIdentity
    private let logger: StructuredLogger

    private var connectAttempted = false
    private var lastPeerHost: String?
    private var lastPeerPort: UInt16?
    private var sessionTask: Task<Void, Never>?

    /// Ordered delivery for `ControlEvent`/`PairingPrompt` (see `OrderedEventChannel`): each gets
    /// its own channel plus exactly one long-lived consumer `Task`, replacing a
    /// `Task { @MainActor in ... }` per event, which only preserved *creation* order, not the
    /// *execution* order the trust gate depends on. Both are recreated per `startDiscovery()` and
    /// torn down in `teardownSession()`, so a stale event from a torn-down session cannot mutate
    /// the next one: the old channel is finished (further sends become no-ops) and the old
    /// consumer task is cancelled before a new pair is created.
    private var controlEventChannel: OrderedEventChannel<ControlEvent>?
    private var controlEventTask: Task<Void, Never>?
    private var pairingPromptChannel: OrderedEventChannel<PairingPrompt?>?
    private var pairingPromptTask: Task<Void, Never>?

    /// Phase 2a. Built per authenticated session by `attachVoice` and torn down with it, so there is
    /// exactly one per two-person session and none at all before the trust gate has passed
    /// (PROTOCOL §7.1).
    ///
    /// This coordinator contains no voice logic: it starts and stops the controller, tells it when the
    /// control link goes, and exposes its diagnostics. Every voice decision is in the pure
    /// `VoiceNegotiation` table, for the reason STATUS §4 problem 20 gives.
    private var voice: VoiceController?

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

        // Exactly one consumer per channel, draining in a single `for await` loop — see
        // `OrderedEventChannel`'s doc comment for why a `Task` per event cannot make this
        // guarantee. `send` itself is a plain synchronous call, so it is safe to invoke directly
        // from `ControlSessionManager`'s actor-isolated `emit`/`updatePairingPrompt`.
        let events = OrderedEventChannel<ControlEvent>()
        controlEventChannel = events
        controlEventTask?.cancel()
        controlEventTask = Task { @MainActor [weak self] in
            for await event in events.stream {
                self?.handleControlEvent(event)
            }
        }

        let prompts = OrderedEventChannel<PairingPrompt?>()
        pairingPromptChannel = prompts
        pairingPromptTask?.cancel()
        pairingPromptTask = Task { @MainActor [weak self] in
            for await prompt in prompts.stream {
                self?.pairingPrompt = prompt
            }
        }

        Task { await manager.setOnEvent { event in events.send(event) } }
        Task { await manager.setOnPairingPromptChanged { prompt in prompts.send(prompt) } }
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
        releaseVoice()
        sessionTask?.cancel()
        sessionTask = nil
        // Cancel the consumer, then finish the channel: cancellation is the cooperative signal,
        // finishing is what actually ends the `for await` loop and turns any subsequent `send`
        // from a not-yet-updated `ControlSessionManager` callback into a no-op rather than a stale
        // mutation of the next session's state.
        controlEventTask?.cancel()
        controlEventTask = nil
        controlEventChannel?.finish()
        controlEventChannel = nil
        pairingPromptTask?.cancel()
        pairingPromptTask = nil
        pairingPromptChannel?.finish()
        pairingPromptChannel = nil
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
        // TLS handshake, by the SPKI pin — so `.pairingSucceeded` is never applied here.
        _ = applyEvent(.peerSelected)
        let manager = controlSessionManager
        let identity = localIdentity
        Task { await manager.connectTo(host: peer.host, port: port, local: identity) }
    }

    // MARK: - Phase 2a voice (PROTOCOL §7)

    /// The user pressed Start Voice. A no-op when there is no authenticated session, because the
    /// controller only exists once the trust gate has passed — there is no state to consult, which is
    /// the point: "is voice allowed?" is answered by whether the object exists.
    public func startVoice() {
        guard let voice else { return }
        Task { await voice.start() }
    }

    public func endVoice() {
        guard let voice else { return }
        Task { await voice.stop() }
    }

    public func setMicrophoneMuted(_ muted: Bool) {
        guard let voice else { return }
        Task { await voice.setMicrophoneMuted(muted) }
    }

    /// Creates the voice subsystem for a session that has **just** passed the trust gate, and only then.
    /// Idempotent across a reconnect: `.connected` fires again after `.reconnectSucceeded`, and the
    /// existing controller is the right one to keep — it still holds the open capture device for this
    /// ride segment, which a fresh one would have to reopen.
    private func attachVoice(isLocalLeader: Bool) {
        if let voice {
            // A reconnect. If the user had consented to voice, rebuild the media transport as a fresh
            // negotiation (PROTOCOL §7.8); `start()` is idempotent when voice is already live.
            if voiceDiagnostics.localAudioOpen {
                Task { await voice.start() }
            }
            return
        }
        let manager = controlSessionManager
        Task { @MainActor [weak self] in
            // The relay is actor-isolated on the manager, so it is awaited rather than read: it captures
            // the manager's `activeSocket`/`authenticated`, which is what makes its writer non-nil only
            // past the trust gate (PROTOCOL §7.1).
            let relay = await manager.voiceRelay()
            let controller = VoiceController(
                engine: WebRtcVoiceEngine(),
                audioSession: IosVoiceAudioSession(),
                transport: relay,
                isLocalLeader: isLocalLeader,
                // One audio track per peer (ADR-003). A fixed, non-identifying id: a track id crosses
                // the wire inside the SDP, so it must not carry a device name.
                localTrackId: "ridelink-voice"
            )
            guard let self else { return }
            self.voice = controller
            await controller.attach()
            await controller.setOnDiagnosticsChanged { diagnostics in
                Task { @MainActor in self.voiceDiagnostics = diagnostics }
            }
            await relay.setSink(controller)
            self.logger.info("SessionCoordinator", "voice subsystem attached (offerer=\(isLocalLeader))")
        }
    }

    /// ARCHITECTURE §3 rule 3: only a deliberate end releases the audio session. Called on `BYE` and
    /// from the `ENDING` effect, never on a link blip.
    private func releaseVoice() {
        guard let controller = voice else { return }
        voice = nil
        voiceDiagnostics = VoiceDiagnostics()
        let manager = controlSessionManager
        Task {
            await manager.voiceRelay().setSink(nil)
            await controller.shutdown()
        }
    }

    /// Side effects first, then the one transition `SessionGate` says this event implies.
    private func handleControlEvent(_ event: ControlEvent) {
        applySideEffects(event)
        if let sessionEvent = SessionGate.sessionEvent(for: event, status: state.status) {
            _ = applyEvent(sessionEvent)
        }
        // Only after the FSM has been moved: `beginReconnect` requires `.reconnecting`, which is
        // exactly what the transition above establishes.
        if case .linkLost(.network) = event { beginReconnectIfPossible() }
    }

    private func applySideEffects(_ event: ControlEvent) {
        switch event {
        case .peerTrusted(let remotePeerId):
            // ARCHITECTURE §4.3's silent connect: the stored pin matched, so no code and no prompt
            // — but this, not `.connected`, is what passes the trust gate.
            logger.info("SessionCoordinator", "known peer \(remotePeerId), silent connect")
        case .pairingRequired(let remotePeerId):
            logger.info("SessionCoordinator", "pairing required with \(remotePeerId)")
        case .pairingSucceeded(let peer):
            // `PairingExchange` already wrote the pin, exactly once and only after both users
            // confirmed.
            logger.info("SessionCoordinator", "paired with \(peer.peerId) (\(peer.identitySpkiSha256))")
        case .pairingFailed(let code):
            securityAlert = code
        case .handshakeRefused(let code):
            // pin_mismatch is the one that must never be quietly retried (ADR-012): it means the
            // key behind a familiar peer_id changed, which is either a reinstall or an attack, and
            // only the user can tell those apart.
            securityAlert = code
            logger.warn("SessionCoordinator", "handshake refused: \(code)")
        case .connected(_, _, let isLocalLeader):
            attachVoice(isLocalLeader: isLocalLeader)
        case .linkLost(let reason):
            // PROTOCOL §7.8: media goes, the capture device stays (ARCHITECTURE §6.3/§6.4), and nothing
            // is retried here — §10's control ladder is the app's only reconnect loop.
            if let voice {
                Task { await voice.onControlLinkLost() }
            }
            if reason == .bye { releaseVoice() }
        case .duplicateConnectionClosed, .reconnectBudgetExhausted:
            break
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
            logger.info("SessionCoordinator", "release audio session")
            releaseVoice()
            teardownSession()
        }
    }
}
