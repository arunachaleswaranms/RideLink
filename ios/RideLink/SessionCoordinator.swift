import Foundation
import Observation
import RideLinkCore
import RideLinkPlatform
#if canImport(AVFAudio)
import AVFAudio
#endif
#if canImport(UIKit)
import UIKit
#endif

/// Adapts a closure to `AudioStateSink`, whose `submit` is called from the control read loop and must
/// therefore not block. The hop onto the main actor is the only work it does.
private struct PeerAudioStateSink: AudioStateSink {
    let onMessage: @Sendable (AudioStateMessage) -> Void

    func submit(_ message: AudioStateMessage) {
        onMessage(message)
    }
}

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

    /// ARCHITECTURE §6.3's selected policy. Owned here rather than in the voice controller because it
    /// outlives any one voice session: a user's choice of gate is a property of the ride, and
    /// `AUDIO_STATE.intercom_mode` has to be reportable before the intercom has ever been started.
    ///
    /// **Mode C by default, by architecture rather than by measurement** — see `IntercomPolicy`.
    public private(set) var intercomPolicy: IntercomPolicy = .default

    /// The peer's latest `AUDIO_STATE` (PROTOCOL §4.4), after the revision rule has been applied.
    public private(set) var peerAudioState: AudioStateMessage?

    /// Why the last Start Intercom was refused, by name. FR-025: the ride is not over, the session is not
    /// over, and the user is told which of permission, endpoint, background or authentication was the
    /// problem rather than "connection failed" (this phase's brief §41).
    public private(set) var lastIntercomRefusal: VoiceFailure?

    private var audioStatePublisher = AudioStatePublisher()
    private var peerAudioStateInbox = AudioStateInbox()

    /// Whether the app is foreground-active. The only honest source for
    /// `RideStartRequest.appForegroundVisible`, and the reason the scene phase is reported in rather
    /// than looked up here.
    private var appForegroundVisible = true

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

    /// Ordered delivery for `VoiceController`'s diagnostics, mirroring `controlEventChannel` above and
    /// for the identical reason (STATUS §2h). `VoiceController.setOnDiagnosticsChanged`'s callback used
    /// to be wrapped in a fresh `Task { @MainActor in ... }` per call — which preserves only the order
    /// those tasks were *created* in, not the order they run in. Since `AUDIO_STATE.revision` is derived
    /// from the diagnostics sequence (`publishAudioState` below), an out-of-order delivery could make a
    /// stale route or transmission snapshot the one the peer sees as authoritative. Recreated per
    /// `attachVoice` and finished in `releaseVoice`, so a diagnostics callback still in flight from a
    /// torn-down controller lands as a no-op `send` rather than mutating the session that replaced it.
    private var voiceDiagnosticsChannel: OrderedEventChannel<VoiceDiagnostics>?
    private var voiceDiagnosticsTask: Task<Void, Never>?

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
        // PROTOCOL §4.4's revision is per sender per **session**, so a new discovery session restarts the
        // numbering — and the peer's held state goes with it, since it belonged to the old one.
        audioStatePublisher.resetForNewSession()
        peerAudioStateInbox.reset()
        peerAudioState = nil
        lastIntercomRefusal = nil

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

    /// **The readiness gate, as a pure decision** (ARCHITECTURE §6.4, `RideStartPolicy`).
    ///
    /// No side effects. iOS has no equivalent of Android's microphone foreground-service rule — a
    /// background-audio app keeps its session — but the *policy* is shared on purpose: it is the same
    /// decision, expressed once, so a permission or endpoint refusal is named identically on both
    /// phones and neither platform can quietly grow a different answer.
    @discardableResult
    public func evaluateIntercomStart() -> RideStartDecision {
        let decision = RideStartPolicy.decide(
            RideStartRequest(
                appForegroundVisible: appForegroundVisible,
                // Read from the platform rather than assumed: a denial is FR-025 graceful degradation,
                // and the request itself is what triggers the system prompt when undetermined.
                micPermissionGranted: Self.microphonePermissionPlausible(),
                // iOS has no notification permission in this path: the lock-screen surface is the
                // now-playing controls, which Phase 3 adds with the player.
                notificationsPermissionGranted: true,
                // "Is voice allowed?" is answered by whether the controller exists — it is built only
                // for a session that has passed the ADR-019 trust gate (PROTOCOL §7.1).
                sessionAuthenticated: voice != nil,
                audioEndpointPresent: Self.audioEndpointPresent(),
                captureAlreadyOpen: voiceDiagnostics.localAudioOpen,
                intercomEnabled: intercomPolicy.intercomEnabled
            )
        )
        if case .refused(let failure) = decision {
            lastIntercomRefusal = failure
            logger.warn("SessionCoordinator", "intercom start refused: \(failure.rawValue)")
        } else {
            lastIntercomRefusal = nil
        }
        return decision
    }

    /// The user pressed Start Intercom. A no-op when there is no authenticated session, because the
    /// controller only exists once the trust gate has passed — there is no state to consult, which is
    /// the point: "is voice allowed?" is answered by whether the object exists.
    public func startIntercom() {
        guard case .allowed = evaluateIntercomStart(), let voice else { return }
        Task { await voice.start() }
    }

    public func endIntercom() {
        guard let voice else { return }
        Task { await voice.stop() }
    }

    public func setMicrophoneMuted(_ muted: Bool) {
        guard let voice else { return }
        Task { await voice.setMicrophoneMuted(muted) }
    }

    /// The PTT control's current position. Gates the outbound WebRTC track and **nothing else** — no
    /// capture reopen, no peer-connection rebuild, no new `voice_session_id` (ADR-021 §4).
    ///
    /// Called synchronously, with no `Task`: `setPushToTalkHeld` is `nonisolated` on the controller and
    /// offers straight into its bounded mailbox, so a press and its release keep their order (this
    /// phase's brief §39).
    public func setPushToTalkHeld(_ held: Bool) {
        voice?.setPushToTalkHeld(held)
    }

    /// Selects one of ARCHITECTURE §6.3's five modes. Announced to the peer on both planes.
    public func selectIntercomPolicy(_ policy: IntercomPolicy) {
        intercomPolicy = policy
        voice?.selectPolicy(policy)
        // With no controller there is no diagnostics change to ride on, so the mode change is published
        // here — `AUDIO_STATE.intercom_mode` is meaningful before the intercom has ever started (Mode E
        // is exactly that case).
        publishAudioState(force: false)
    }

    /// The app's scene phase changed.
    ///
    /// Leaving the foreground releases the PTT gate — this phase's brief §25: a press still outstanding
    /// must not leave transmission stuck on. Capture is deliberately untouched: the ride segment
    /// continues, and the whole reason the gate and the device are separate things is that a link blip
    /// or a lock screen must not close a microphone.
    public func setAppForegroundVisible(_ visible: Bool) {
        appForegroundVisible = visible
        if !visible { voice?.onAppBackgrounded() }
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
            controller.selectPolicy(self.intercomPolicy)

            // Exactly one consumer, draining in a single `for await` loop — see `OrderedEventChannel`'s
            // doc comment for why a `Task` per event cannot make the ordering guarantee `AUDIO_STATE`'s
            // revision rule depends on. `send` itself is a plain synchronous call, so it is safe to
            // invoke directly from the controller actor's `onDiagnosticsChanged` callback.
            let diagnosticsChannel = OrderedEventChannel<VoiceDiagnostics>()
            self.voiceDiagnosticsChannel = diagnosticsChannel
            self.voiceDiagnosticsTask?.cancel()
            self.voiceDiagnosticsTask = Task { @MainActor [weak self] in
                for await diagnostics in diagnosticsChannel.stream {
                    guard let self else { return }
                    self.voiceDiagnostics = diagnostics
                    // Every observable audio change publishes, and the publisher itself decides whether
                    // there is anything new to say — which is what makes `revision` mean "the state
                    // changed" rather than "a callback fired".
                    self.publishAudioState(force: false)
                }
            }
            await controller.setOnDiagnosticsChanged { diagnostics in
                diagnosticsChannel.send(diagnostics)
            }
            await relay.setSink(controller)
            let audioRelay = await manager.audioStateRelay()
            await audioRelay.setSink(PeerAudioStateSink { message in
                Task { @MainActor in self.acceptPeerAudioState(message) }
            })
            self.logger.info("SessionCoordinator", "voice subsystem attached (offerer=\(isLocalLeader))")
        }
    }

    /// ARCHITECTURE §3 rule 3: only a deliberate end releases the audio session. Called on `BYE` and
    /// from the `ENDING` effect, never on a link blip.
    private func releaseVoice() {
        guard let controller = voice else { return }
        voice = nil
        voiceDiagnostics = VoiceDiagnostics()
        // Cancel the consumer, then finish the channel — cancellation is the cooperative signal,
        // finishing is what actually ends the `for await` loop and turns any later `send` from a
        // not-yet-torn-down controller callback into a no-op rather than a stale mutation of whatever
        // session replaces this one.
        voiceDiagnosticsTask?.cancel()
        voiceDiagnosticsTask = nil
        voiceDiagnosticsChannel?.finish()
        voiceDiagnosticsChannel = nil
        let manager = controlSessionManager
        Task {
            await manager.voiceRelay().setSink(nil)
            await manager.audioStateRelay().setSink(nil)
            await controller.shutdown()
        }
    }

    /// PROTOCOL §4.4's revision rule lives in the shared `AudioStateInbox`: anything not strictly greater
    /// than what we hold is dropped, so reordering cannot resurrect a stale route and a retransmit
    /// changes nothing.
    private func acceptPeerAudioState(_ message: AudioStateMessage) {
        if peerAudioStateInbox.accept(message) { peerAudioState = message }
    }

    /// Sends `AUDIO_STATE` if there is anything new to say (PROTOCOL §4.4).
    ///
    /// The `revision` is `AudioStatePublisher`'s — strictly increasing, per sender per session, and
    /// **not** reset by a reconnect or a voice rebuild, so a peer can always tell a newer route from an
    /// older one. `force` is for the two moments §4.4 names explicitly: reaching `CONNECTED`, and ride
    /// start.
    ///
    /// The route comes from the voice controller's diagnostics when one exists and is the default unknown
    /// snapshot otherwise, which is the honest answer before the intercom has been started: the platform
    /// has told us nothing about a route we have not asked for.
    private func publishAudioState(force: Bool) {
        let route = voice == nil ? AudioRouteSnapshot() : voiceDiagnostics.route
        let mode = intercomPolicy.intercomWireMode
        let message: AudioStateMessage?
        if force {
            message = audioStatePublisher.forceNext(snapshot: route, intercomMode: mode)
        } else {
            message = audioStatePublisher.next(snapshot: route, intercomMode: mode)
        }
        guard let message else { return }
        let manager = controlSessionManager
        Task { _ = await manager.audioStateRelay().send(message) }
    }

    /// Whether the platform has not refused the microphone. `.undetermined` counts as plausible: the
    /// request itself is what triggers the system prompt, and refusing before asking would make the first
    /// Start Intercom fail for a user who would have said yes.
    private static func microphonePermissionPlausible() -> Bool {
        #if canImport(AVFAudio)
        return AVAudioApplication.shared.recordPermission != .denied
        #else
        return true
        #endif
    }

    /// Whether there is any audio route at all. On iOS the session always reports *something* once
    /// configured, so this is about the case where it reports nothing — which is a named refusal rather
    /// than a silent start with nowhere to speak (this phase's brief §41).
    private static func audioEndpointPresent() -> Bool {
        #if canImport(AVFAudio) && os(iOS)
        return !AVAudioSession.sharedInstance().currentRoute.outputs.isEmpty
        #else
        return true
        #endif
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
            // PROTOCOL §4.4 names `CONNECTED` as one of the two moments an `AUDIO_STATE` is sent
            // regardless of whether anything changed: a peer that has just connected has never seen any
            // of our state, so "nothing changed" is not a reason to stay silent.
            publishAudioState(force: true)
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
