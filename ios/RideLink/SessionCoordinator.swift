import Foundation
import GRDB
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
    public private(set) var coexistenceDiagnostics = CoexistenceDiagnostics()

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

    /// This device's `AUDIO_STATE` sender lifetime (PROTOCOL §4.4, ADR-021 Amendment A7). The epoch is
    /// minted here and re-minted by `startDiscovery()`; nothing else may move it, because the epoch's only
    /// meaning is "the `revision` counter below restarted".
    private var audioStatePublisher = AudioStatePublisher(epoch: AudioStateEpochGenerator.generate())
    private var peerAudioStateInbox = AudioStateInbox()

    /// Whether the app is foreground-active. The only honest source for
    /// `RideStartRequest.appForegroundVisible`, and the reason the scene phase is reported in rather
    /// than looked up here.
    private var appForegroundVisible = true

    private let discovery = BonjourDiscovery()
    private let trustedPeers: any TrustedPeerStore
    private let controlSessionManager: ControlSessionManager
    private let localIdentity: LocalHandshakeIdentity
    /// This device's durable `peer_id` — ADR-010's election input, and the `issued_by` on every
    /// Phase 5 command this device stamps.
    private let localPeerId: PeerId
    private let deviceIdentity: DeviceIdentity
    private let monotonicNowUs: @Sendable () -> Int64
    private let logger: StructuredLogger

    private var connectAttempted = false
    private var lastPeerHost: String?
    private var lastPeerPort: UInt16?

    /// **Every unstructured `Task` one discovery session starts**, so a teardown can cancel all of
    /// them and then *await* all of them — the Swift mirror of Android's one `SupervisorJob`.
    ///
    /// An unstructured `Task` has no parent to cancel, which is why the set is tracked explicitly;
    /// `SyncPlaybackCoordinator.sessionChainNodes` is the same idiom one layer down (ADR-024
    /// Amendment A3). Two things it does that a bare cancel cannot:
    ///
    /// - **awaiting `task.value` is completion**, and cancellation is only a request. Nothing here is
    ///   allowed to assume a cancelled continuation has stopped, because several of them park in
    ///   `await`s on the `ControlSessionManager` actor that ignore cancellation entirely.
    /// - **the dictionary *is* the ownership token.** A retirement empties it synchronously, so a
    ///   continuation that has suspended can ask `ownsSessionWork(id)` afterwards and get "no" — on
    ///   the main actor, atomically with whatever statement follows. No separate generation counter
    ///   is invented, because this answers the same question and cannot drift from it.
    private var sessionWork: [Int64: Task<Void, Never>] = [:]
    private var nextSessionWorkId: Int64 = 0

    /// True from the moment a discovery session asks the control plane to start until its teardown
    /// has shut it down again. Deliberately not inferred from anything else: a teardown must know
    /// whether to call `ControlSessionManager.shutdown()` at all, and shutting down a manager that
    /// was never started would publish `.ended` over a cold `IDLE` app.
    private var controlPlaneStarted = false

    /// The single owner of teardown ordering and completion — see `SessionTeardownOwner`.
    private let teardown = SessionTeardownOwner()

    /// Ordered delivery for `ControlEvent`/`PairingPrompt` (see `OrderedEventChannel`): each gets
    /// its own channel plus exactly one long-lived consumer, replacing a
    /// `Task { @MainActor in ... }` per event, which only preserved *creation* order, not the
    /// *execution* order the trust gate depends on. Both are recreated per `beginDiscoverySession`
    /// and torn down in `retireSession`, so a stale event from a torn-down session cannot mutate the
    /// next one: the old channel is finished synchronously (further sends become no-ops) and its
    /// consumer is one of the `sessionWork` tasks the teardown cancels *and awaits*.
    private var controlEventChannel: OrderedEventChannel<ControlEvent>?
    private var pairingPromptChannel: OrderedEventChannel<PairingPrompt?>?

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
    /// `attachVoice` and finished in `retireSession`, so a diagnostics callback still in flight from a
    /// torn-down controller lands as a no-op `send` rather than mutating the session that replaced it.
    private var voiceDiagnosticsChannel: OrderedEventChannel<VoiceDiagnostics>?
    private let audioSessionCoordinator: IosAudioSessionCoordinator
    private var coexistence: IntercomMusicCoexistenceCoordinator?
    private var coexistenceGeneration: Int64?
    /// The authenticated control lifetime `attachVoice` was last called under. Compared — never used
    /// to relabel — against `VoiceDiagnostics.controlGeneration` in `updateCoexistence`, so a
    /// predecessor's diagnostics snapshot cannot be forwarded to coexistence under a successor's
    /// generation merely because it happens to be the one live when the snapshot is consumed (Phase 6
    /// review blocker 2; ADR-027 Amendment A1).
    private var voiceControlGeneration: Int64?
    /// Test-visible count of `VoiceDiagnostics` snapshots `updateCoexistence` refused as stale.
    public private(set) var staleVoiceDiagnosticsCount = 0

    /// Assembles the security wiring, and nothing else does: the Keychain identity (ADR-017), the
    /// one production `ControlChannel` — TLS 1.3 — and the trusted-peer store the SPKI pin is
    /// checked against (ADR-012).
    ///
    /// Throws if the device identity cannot be created. That is deliberately fatal to the session
    /// rather than degraded: without an identity there is no certificate, no pin and no channel
    /// binding, and PROTOCOL §1 admits no plaintext alternative to fall back to.
    public init(audioSessionCoordinator: IosAudioSessionCoordinator = IosAudioSessionCoordinator()) throws {
        self.audioSessionCoordinator = audioSessionCoordinator
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
        deviceIdentity = identity
        self.monotonicNowUs = monotonicNowUs

        let channel = TlsControlChannel(identity: identity)
        SecureTransportPolicy.requireSecure(channel)

        trustedPeers = FileTrustedPeerStore(url: directory.appendingPathComponent("trusted_peers.json"))
        let localPeerId = LocalPeerIdStore(url: directory.appendingPathComponent("peer_id")).loadOrCreate()
        self.localPeerId = localPeerId

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

    /// Phase 4's shared-library stack (ADR-023), non-nil once the composition root
    /// (`RideLinkApp.init`) calls [attachSharedLibrary] — it needs `MusicCoordinator`'s
    /// `LibraryRepository` to exist first, and construction can fail (a cache-directory create can
    /// throw), so it is not built inside this class's own `init`.
    ///
    /// **Why this coordinator forwards events instead of `SharedLibraryCoordinator` subscribing to
    /// its own.** `onEvent`/`setOnEvent` on `ControlSessionManager` is a single mutable callback
    /// slot (unlike Android's multi-collector `SharedFlow`) — a second `setOnEvent` call would
    /// silently replace this class's own handler. `SessionCoordinator` already owns the one
    /// subscription, so it forwards the two events `SharedLibraryCoordinator` needs
    /// (`.connected`/`.linkLost`) explicitly, in [applySideEffects].
    public private(set) var sharedLibrary: SharedLibraryCoordinator?

    /// Phase 5's synchronisation plane (ADR-004, ADR-024), forwarded the same two events as
    /// [sharedLibrary] and for the same reason this file's comment above already gives:
    /// `onEvent` is a single mutable callback slot, and this class owns the one subscription.
    public private(set) var syncPlayback: SyncPlaybackCoordinator?

    /// Builds and attaches [sharedLibrary]. A no-op if already attached. `SharedLibraryCoordinator`
    /// gets its own `TlsControlChannel` for the bulk plane — a second, independent listener
    /// (ADR-015) — but the **same** [deviceIdentity] as the control connection, which is what the
    /// SPKI pin check on both ends depends on.
    public func attachSharedLibrary(
        libraryRepository: LibraryRepository,
        libraryDatabaseQueue: DatabaseQueue,
        libraryIndexer: LibraryIndexer,
        activeCacheHash: @escaping @MainActor () -> ContentHash? = { nil }
    ) {
        guard sharedLibrary == nil else { return }
        sharedLibrary = SharedLibraryCoordinator(
            controlSessionManager: controlSessionManager,
            bulkTransport: TransferManager(channel: TlsControlChannel(identity: deviceIdentity), monotonicNowUs: monotonicNowUs),
            libraryRepository: libraryRepository,
            libraryDatabaseQueue: libraryDatabaseQueue,
            libraryIndexer: libraryIndexer,
            monotonicNowUs: monotonicNowUs,
            activeCacheHash: activeCacheHash
        )
    }

    /// Builds and attaches [syncPlayback]. A no-op if already attached. It owns no player and no
    /// queue: every audible effect goes through the **one** `MusicCoordinator` the caller supplies as
    /// `player`, and every content lookup through Phase 3's library and Phase 4's verified cache.
    public func attachSyncPlayback(
        player: any SyncPlayerPort,
        content: any SyncContentPort,
        nextQueueItemId: @escaping @Sendable () -> String
    ) -> SyncPlaybackCoordinator? {
        if let existing = syncPlayback { return existing }
        let coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: monotonicNowUs,
            localPeerId: localPeerId,
            session: ControlSessionSyncPort(manager: controlSessionManager),
            player: player,
            content: content,
            sleeper: MonotonicDeadlineSleeper(monotonicNowUs: monotonicNowUs),
            routeState: SessionRouteStatePort(session: self),
            nextQueueItemId: nextQueueItemId
        )
        syncPlayback = coordinator
        Task { await coordinator.start() }
        return coordinator
    }

    /// The user's answer on the pairing screen. Both peers must answer before any pin is written.
    public func confirmPairing(accepted: Bool) {
        let manager = controlSessionManager
        // Session-owned: a confirm whose `Task` had not yet run when its session was retired must not
        // supply the *successor's* PROTOCOL §4.5 gate — the local mirror of the retired-connection
        // `PAIR_CONFIRM` ADR-025 closed on the wire.
        launchInSession { _ in await manager.confirmPairing(accepted: accepted) }
    }

    public func forgetPeer(_ peer: TrustedPeer) {
        trustedPeers.forget(peer.peerId)
        securityAlert = nil
    }

    public func dismissSecurityAlert() {
        securityAlert = nil
    }

    /// ARCHITECTURE §3's `IDLE -> DISCOVERING`. Rejected from every other state by `SessionFsm`,
    /// which is the point: this is the *only* way a first session starts.
    public func startDiscovery() {
        beginDiscoverySession(.startDiscovery, retireHere: true)
    }

    /// ARCHITECTURE §3's `DISCONNECTED -> DISCOVERING`, and the second half of `docs/STATUS.md` §4
    /// problem 53: `SessionFsm` has always had this transition and **nothing ever emitted
    /// `.retryRequested`**, so a rider whose reconnect budget ran out had no way back to discovery
    /// short of force-quitting the app.
    ///
    /// **Deliberately a user action, never an automatic one.** PROTOCOL §10's ladder is the app's one
    /// reconnect loop and it has a 120 s budget on purpose; re-entering discovery by itself once that
    /// budget is spent would be an unbounded background loop wearing the radio for a peer that may
    /// simply be switched off. `DISCONNECTED` is ARCHITECTURE §3's "awaiting user", and this is the
    /// user.
    ///
    /// The retirement is not done *here*: `.retryRequested` is one of ARCHITECTURE §3 rule 3's two
    /// deliberate ends, so `SessionFsm` emits `.releaseAudioAndStopForegroundService` for it and
    /// `runEffect` has already retired the session by the time this resumes — which is why it passes
    /// `retireHere: false`. One owner, reached two ways, never two owners.
    public func retryDiscovery() {
        beginDiscoverySession(.retryRequested, retireHere: false)
    }

    /// The user ending the ride from **this** phone (ARCHITECTURE §3's `.userEnded`), as opposed to
    /// the peer's `BYE` that `SessionGate` already turns into the same `ENDING`.
    ///
    /// Legal from `CONNECTED`, `RIDE_ACTIVE`, `RECONNECTING` and `DISCONNECTED`; rejected and logged
    /// anywhere else. Everything after the transition is `ENDING`'s one effect (see `runEffect`).
    public func endSession() {
        _ = applyEvent(.userEnded)
    }

    private func beginDiscoverySession(_ event: SessionEvent, retireHere: Bool) {
        guard applyEvent(event) else { return }
        // `.startDiscovery` carries no FSM effect, so the (from a cold `IDLE`, empty) retirement
        // happens here; `.retryRequested` carries the deliberate-end effect and `runEffect` has
        // already done it, synchronously, inside the `applyEvent` above. Either way the successor
        // waits on the one owner's latest task rather than on a reference of its own.
        if retireHere { retireSession(.discoveryRestart) }
        let previousSession = teardown.pending
        connectAttempted = false
        discoveredPeers = []
        discoveryCount = 0
        // PROTOCOL §4.4's revision is per sender per **session**, so a new discovery session restarts the
        // numbering — and the peer's held state goes with it, since it belonged to the old one.
        //
        // ADR-021 Amendment A7: the restart is *announced*. A fresh epoch is what tells the peer that the
        // numbers it is about to see belong to a new counter, so its held floor — which it keeps across a
        // reconnect on purpose — does not silently refuse every one of them. Minting it in the same
        // statement that resets the counter is the invariant: the two cannot drift apart.
        audioStatePublisher.resetForNewSession(epoch: AudioStateEpochGenerator.generate())
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
        launchInSession { [weak self] _ in
            for await event in events.stream {
                await self?.handleControlEvent(event)
            }
        }

        let prompts = OrderedEventChannel<PairingPrompt?>()
        pairingPromptChannel = prompts
        launchInSession { [weak self] _ in
            for await prompt in prompts.stream {
                self?.pairingPrompt = prompt
            }
        }

        controlPlaneStarted = true
        launchInSession { [weak self] id in
            // **Nothing shared is touched until the previous session is terminal.** The control
            // manager, the Bonjour advertiser and the browser are one instance each for the whole
            // process, so a teardown still in flight would otherwise un-latch what this session has
            // just latched — `shutdown()` setting `isShutDown` and closing the listener that
            // `startListening` below had already bound is the worst of them, and it leaves the new
            // session permanently unable to accept a connection.
            await previousSession?.value
            guard let self, self.ownsSessionWork(id) else { return }
            await manager.setOnEvent { event in events.send(event) }
            await manager.setOnPairingPromptChanged { prompt in prompts.send(prompt) }
            await manager.setOnDiagnosticsChanged { [weak self] diagnostics in
                Task { @MainActor in
                    guard let self, self.ownsSessionWork(id) else { return }
                    self.controlDiagnostics = diagnostics
                }
            }
            guard self.ownsSessionWork(id), (try? await manager.startListening(local: identity)) != nil else { return }
            guard self.ownsSessionWork(id) else { return }
            if let listener = await manager.underlyingListener() {
                discoverySession.startAdvertising(on: listener) { [weak self] advertiseState in
                    Task { @MainActor in
                        guard let self, self.ownsSessionWork(id) else { return }
                        self.logger.debug("SessionCoordinator", "advertise: \(advertiseState)")
                    }
                }
            }
            discoverySession.startBrowsing { [weak self] event in
                Task { @MainActor in
                    guard let self, self.ownsSessionWork(id) else { return }
                    self.handleDiscoveryEvent(event)
                }
            }
        }
    }

    public func cancelDiscovery() {
        guard applyEvent(.cancelDiscovery) else { return }
        retireSession(.discoveryRestart)
        discoveredPeers = []
    }

    /// Why a session is being retired: the two facts that differ between the paths, and nothing else.
    /// The teardown *steps* are identical in all three — that is the point of there being one owner.
    private enum SessionEnd {
        /// `ENDING`: a peer `BYE`, the user ending the ride, or an acknowledged fatal error.
        case ending
        /// `DISCONNECTED -> DISCOVERING`: ARCHITECTURE §3 rule 3's *other* deliberate end.
        case userRetry
        /// Stop Discovery, and the (normally empty) retirement a cold Start Discovery performs.
        case discoveryRestart

        /// Only `ENDING` has an `IDLE` to reach, so only `ENDING` has a `.teardownComplete` to emit.
        var signalsTeardownComplete: Bool { self == .ending }
    }

    /// Reserves an identity for one unit of session-owned work. Monotonic, so a retired unit's own
    /// cleanup can never remove one the *next* session created.
    private func claimSessionWorkId() -> Int64 {
        nextSessionWorkId += 1
        return nextSessionWorkId
    }

    /// Whether the session that authorised this unit of work is still the current one. See
    /// `sessionWork`: the registry is the token, and this read is synchronous on the main actor, so
    /// it is atomic with the statement that follows it.
    private func ownsSessionWork(_ id: Int64) -> Bool { sessionWork[id] != nil }

    @discardableResult
    private func launchInSession(_ body: @escaping @MainActor (_ id: Int64) async -> Void) -> Task<Void, Never> {
        let id = claimSessionWorkId()
        let task = Task { @MainActor [weak self] in
            await body(id)
            self?.sessionWork[id] = nil
        }
        sessionWork[id] = task
        return task
    }

    /// **Retires the current session and returns the task that completes when it is terminal.**
    ///
    /// Everything above the `teardown.retire` call runs **synchronously**, on the caller's stack,
    /// before this returns: after it, no field this coordinator holds belongs to the session being
    /// retired, so nothing the asynchronous half does can reach a *successor's* voice controller,
    /// channels, work registry or diagnostics. Each captured reference is the ownership token for its
    /// own object — no counter is invented, because the reference itself already answers "whose?"
    /// exactly (ADR-024 Amendment A3's lesson, at the session layer).
    ///
    /// The asynchronous half then, in order: cancels and **awaits** every continuation this session
    /// started; releases the voice controller and detaches its two relay sinks; and shuts the control
    /// plane down. Only when all three have returned may `.teardownComplete` be emitted, and
    /// `SessionTeardownOwner` is what makes "only when" mean something — a successor awaits the same
    /// task.
    @discardableResult
    private func retireSession(_ end: SessionEnd) -> Task<Void, Never> {
        let endingVoice = voice
        let endingCoexistence = coexistence
        if let generation = coexistenceGeneration { endingCoexistence?.endLifetime(generation) }
        coexistenceGeneration = nil
        voiceControlGeneration = nil
        if endingVoice != nil {
            voice = nil
            voiceDiagnostics = VoiceDiagnostics()
        }
        // Cancel the consumers and finish the channels here, synchronously: cancellation is only the
        // cooperative signal, and finishing is what turns a later `send` from a not-yet-torn-down
        // callback into a no-op rather than a mutation of whatever session replaces this one.
        voiceDiagnosticsChannel?.finish()
        voiceDiagnosticsChannel = nil
        controlEventChannel?.finish()
        controlEventChannel = nil
        pairingPromptChannel?.finish()
        pairingPromptChannel = nil
        let endingWork = sessionWork
        sessionWork.removeAll()
        let endingControlPlane = controlPlaneStarted
        controlPlaneStarted = false
        // Synchronous on `BonjourDiscovery`, and deliberately before the suspension below: the
        // browser and the advertiser are one instance for the whole process, so stopping them after
        // a successor had started them would stop the successor's.
        discovery.stopBrowsing()
        discovery.stopAdvertising()
        let manager = controlSessionManager

        return teardown.retire { [weak self] in
            // Ending a lifetime can enqueue an exact-volume restore or a Mode D resume. It belongs
            // to this session just as much as voice shutdown does, so terminal teardown awaits it
            // before a successor may start.
            await endingCoexistence?.awaitLifetimeEnded()
            for task in endingWork.values { task.cancel() }
            for task in endingWork.values { await task.value }
            // Re-read *after* every continuation of this session is terminal, which is the one point
            // at which re-reading is correct rather than the ADR-025 defect: a retired `attachVoice`
            // may legitimately have finished installing its controller between the synchronous
            // capture above and its cancellation taking effect, and at this instant nothing can write
            // the field — the session that could is over, and the successor that will cannot have
            // started, because it is awaiting this very task.
            let controller = endingVoice ?? self?.voice
            self?.voice = nil
            if controller != nil {
                await manager.voiceRelay().setSink(nil)
                await manager.audioStateRelay().setSink(nil)
            }
            await controller?.shutdown()
            if endingControlPlane { await manager.shutdown() }
            if end.signalsTeardownComplete {
                // The event name is now literally true: every effect above has returned, and the only
                // references to any of them were captured synchronously above, so no continuation of
                // this session exists to mutate whatever starts next.
                _ = self?.applyEvent(.teardownComplete)
            }
        }
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
        launchInSession { _ in await manager.connectTo(host: peer.host, port: port, local: identity) }
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
        // The live generation, read **now**, for an input that happens now: a local press carries no
        // frame whose provenance could be preserved instead, and "which lifetime is authenticated at
        // the moment the user taps" is exactly the lifetime that will carry its offer. Nil in the gap
        // between two links, which records consent and starts no negotiation — see
        // `VoiceInput.startRequested`'s `controlGeneration` (STATUS §4 problem 61).
        let generation = controlSessionManager.liveAuthenticatedGeneration()
        // **Session-owned, not unstructured** (ADR-026 rule 21, STATUS §4 problem 67). `start` is
        // actor-isolated on the controller — unlike `setPushToTalkHeld`, it stamps `VoiceSetupTimeline`
        // — so this press cannot reach the bounded mailbox without a hop, and a bare `Task` here is a
        // continuation this session started that nothing cancels and nothing joins. `retireSession`
        // could then emit `.teardownComplete` with a press still in flight against the controller it is
        // about to shut down. `launchInSession` is the registry that makes cancellation *and* joining
        // the session's, and it is the only thing that changes: the hop itself stays, because it is
        // what the actor requires, and STATUS §4 problem 66 is about what the table does with a press
        // that arrives late rather than about preventing one.
        launchInSession { _ in await voice.start(controlGeneration: generation) }
    }

    public func endIntercom() {
        guard let voice else { return }
        launchInSession { _ in await voice.stop() }
    }

    public func setMicrophoneMuted(_ muted: Bool) {
        guard let voice else { return }
        launchInSession { _ in await voice.setMicrophoneMuted(muted) }
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
        if let generation = coexistenceGeneration { coexistence?.selectPolicy(policy, generation: generation) }
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
    private func attachVoice(isLocalLeader: Bool, authGeneration: Int64) {
        // Read once, here, and compared (never used to relabel) in `updateCoexistence` — see
        // `voiceControlGeneration`'s own doc.
        voiceControlGeneration = authGeneration
        if let voice {
            coexistenceGeneration = coexistence?.beginLifetime(policy: intercomPolicy)
            // Deliberately **not** seeded from `voiceDiagnostics` here: that projection can still be
            // a predecessor's, and `updateCoexistence`'s provenance check would refuse it anyway.
            // This generation starts neutral; the persistent diagnostics channel below applies its
            // own genuine diagnostics once they arrive (Phase 6 review blocker 2; ADR-027 Amendment
            // A1).
            // One authenticated event supplies successor authority and the §7.8 rebuild opportunity.
            // The reducer also sees a gap press delivered after this task; diagnostics decide neither.
            launchInSession { _ in await voice.controlAuthenticated(controlGeneration: authGeneration) }
            return
        }
        let manager = controlSessionManager
        let audioSessionCoordinator = audioSessionCoordinator
        launchInSession { [weak self] id in
            // The relay is actor-isolated on the manager, so it is awaited rather than read: it captures
            // the manager's `activeSocket`/`authenticated`, which is what makes its writer non-nil only
            // past the trust gate (PROTOCOL §7.1).
            let relay = await manager.voiceRelay()
            let controller = VoiceController(
                engine: WebRtcVoiceEngine(),
                audioSession: IosVoiceAudioSession(audioSessionCoordinator: audioSessionCoordinator),
                transport: relay,
                isLocalLeader: isLocalLeader,
                // One audio track per peer (ADR-003). A fixed, non-identifying id: a track id crosses
                // the wire inside the SDP, so it must not carry a device name.
                localTrackId: "ridelink-voice"
            )
            // Every `await` below is a point at which this session can be retired, and every statement
            // below *installs* something — a controller, a diagnostics consumer, two relay sinks. A
            // retired install would hand the successor this session's voice subsystem, which is why
            // ownership is re-proved before each one rather than once at the top.
            guard let self, self.ownsSessionWork(id) else { return }
            self.voice = controller
            await controller.attach()
            guard self.ownsSessionWork(id) else { return }
            controller.selectPolicy(self.intercomPolicy)
            // The lifetime this controller was born under, as an input like every other
            // control-lifetime fact the table holds (ADR-020 Amendment A11). A first-ever press
            // before this point reads the live generation itself; this is for the press that
            // arrives after a boundary, whose own tap-time read can only ever be honest about the
            // gap it was pressed in.
            await controller.controlAuthenticated(controlGeneration: authGeneration)
            guard self.ownsSessionWork(id) else { return }
            self.coexistenceGeneration = self.coexistence?.beginLifetime(policy: self.intercomPolicy)

            // Exactly one consumer, draining in a single `for await` loop — see `OrderedEventChannel`'s
            // doc comment for why a `Task` per event cannot make the ordering guarantee `AUDIO_STATE`'s
            // revision rule depends on. `send` itself is a plain synchronous call, so it is safe to
            // invoke directly from the controller actor's `onDiagnosticsChanged` callback.
            let diagnosticsChannel = OrderedEventChannel<VoiceDiagnostics>()
            self.voiceDiagnosticsChannel = diagnosticsChannel
            self.launchInSession { [weak self] _ in
                for await diagnostics in diagnosticsChannel.stream {
                    guard let self else { return }
                    self.voiceDiagnostics = diagnostics
                    if let generation = self.coexistenceGeneration {
                        self.updateCoexistence(generation: generation, diagnostics: diagnostics)
                    }
                    // Every observable audio change publishes, and the publisher itself decides whether
                    // there is anything new to say — which is what makes `revision` mean "the state
                    // changed" rather than "a callback fired".
                    self.publishAudioState(force: false)
                }
            }
            await controller.setOnDiagnosticsChanged { diagnostics in
                diagnosticsChannel.send(diagnostics)
            }
            guard self.ownsSessionWork(id) else { return }
            await relay.setSink(controller)
            let audioRelay = await manager.audioStateRelay()
            guard self.ownsSessionWork(id) else { return }
            await audioRelay.setSink(PeerAudioStateSink { [weak self] message in
                Task { @MainActor in self?.acceptPeerAudioState(message) }
            })
            self.logger.info("SessionCoordinator", "voice subsystem attached (offerer=\(isLocalLeader))")
        }
    }

    public func attachCoexistence(music: any MusicCoexistencePort) {
        guard coexistence == nil else { return }
        coexistence = IntercomMusicCoexistenceCoordinator(music: music)
        coexistence?.setDiagnosticsObserver { [weak self] diagnostics in
            self?.coexistenceDiagnostics = diagnostics
        }
    }

    /// Phase 6 fallback projection; it changes no Phase 5 authority and starts no retry.
    public func updateSyncAvailability(_ available: Bool) {
        coexistence?.updateSyncAvailability(available)
    }

    private func updateCoexistence(generation: Int64, diagnostics: VoiceDiagnostics) {
        // Provenance gate (Phase 6 review blocker 2; ADR-027 Amendment A1). `diagnostics` is stamped,
        // at production time inside `VoiceController.publishDiagnostics`, with the control lifetime
        // that owned `VoiceNegotiationState` when it was computed (ADR-020 rule 23's own
        // `negotiationControlGeneration`) — never re-derived here. A snapshot whose provenance does
        // not match the control lifetime this coexistence generation was begun under is a
        // predecessor's (or not-yet-owned) state and is refused rather than relabelled as this
        // generation's: `VoiceController` is retained across a reconnect and keeps publishing to the
        // one diagnostics channel `attachVoice` set up on first construction, so this check — not
        // which reconnect branch happened to run — is what stops a stale duck, pause or resume
        // crossing a control-lifetime boundary.
        guard diagnostics.controlGeneration == voiceControlGeneration else {
            staleVoiceDiagnosticsCount += 1
            return
        }
        coexistence?.updateVoice(
            generation: generation,
            available: diagnostics.status != .failed,
            localSpeechActive: diagnostics.localSpeechActivity == .active,
            peerSpeechActive: diagnostics.peerSpeechActivity == .active,
            speechActivityAvailable: diagnostics.localSpeechActivity != .unavailable || diagnostics.peerSpeechActivity != .unavailable,
            routeState: diagnostics.route.routeState,
            interrupted: diagnostics.route.interrupted,
            transitionTimedOut: diagnostics.route.lastTransitionTimedOut
        )
        coexistenceDiagnostics = coexistence?.diagnostics ?? CoexistenceDiagnostics()
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
        // Session-owned, so the send is one of the continuations a teardown awaits rather than one it
        // merely outlives. The epoch fence below still stands on its own — it is what covers a publish
        // raised outside any session at all, e.g. a mode change from `IDLE`.
        launchInSession { [weak self] _ in
            // ADR-021 Amendment A7 §4, which is ADR-024 Amendment A3/A5's rule applied outbound:
            // **authorised to build is not authorised to send.** The revision above was committed
            // synchronously, but this `Task` is a suspension point, and `startDiscovery()` can land in
            // that gap — retiring this message's whole sender lifetime. Sending it anyway would announce
            // a dead epoch on the successor's connection, and a peer that adopted it would then treat the
            // *live* lifetime's first frame as yet another new epoch.
            //
            // The check is deliberately on the epoch and not on the control session: a reconnect does
            // **not** end this lifetime, and §4.4 requires our current audio state to reach the peer on
            // the new connection.
            guard let self, message.revisionEpoch == self.audioStatePublisher.currentEpoch else { return }
            _ = await manager.audioStateRelay().send(message)
        }
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
    private func handleControlEvent(_ event: ControlEvent) async {
        await applySideEffects(event)
        if let sessionEvent = SessionGate.sessionEvent(for: event, status: state.status) {
            _ = applyEvent(sessionEvent)
        }
        // Only after the FSM has been moved: `beginReconnect` requires `.reconnecting`, which is
        // exactly what the transition above establishes.
        if case .linkLost(.network, _) = event { beginReconnectIfPossible() }
    }

    /// `async` (ADR-023 Amendment A3) purely to `await` the two `sharedLibrary` calls below to
    /// completion before this returns — see `SharedLibraryCoordinator.onSessionBoundary`'s doc
    /// comment for why the old session's transport teardown must fully finish before a new session's
    /// `Connected`/`LinkLost` side effects (or the FSM transition [handleControlEvent] applies right
    /// after this call returns) can proceed.
    private func applySideEffects(_ event: ControlEvent) async {
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
        case .connected(_, _, let isLocalLeader, let authGeneration):
            attachVoice(isLocalLeader: isLocalLeader, authGeneration: authGeneration)
            // PROTOCOL §4.4 names `CONNECTED` as one of the two moments an `AUDIO_STATE` is sent
            // regardless of whether anything changed: a peer that has just connected has never seen any
            // of our state, so "nothing changed" is not a reason to stay silent.
            publishAudioState(force: true)
            await sharedLibrary?.handleConnected()
            await syncPlayback?.handleConnected(isLocalLeader: isLocalLeader)
        case .linkLost(_, let retiredAuthGeneration):
            // PROTOCOL §7.8: media goes, the capture device stays (ARCHITECTURE §6.3/§6.4), and nothing
            // is retried here — §10's control ladder is the app's only reconnect loop.
            if let voice {
                let controller = voice
                // The generation the event names, never a live read (STATUS §4 problem 60). This
                // consumer is asynchronous with respect to `endConnection` and the call below is
                // deferred once more into a `Task`, while an inbound promotion can authenticate a
                // successor without passing through either — so by the time it runs, the controller's
                // mailbox may already hold the *successor's* admitted frames. Naming the retired
                // lifetime is what stops this discarding them.
                launchInSession { _ in
                    await controller.onControlLinkLost(retiredControlGeneration: retiredAuthGeneration)
                }
            }
            // A `BYE`'s own release is **not** started here. `handleControlEvent` applies the FSM
            // transition this same event implies right after this method returns, and `BYE` always
            // drives CONNECTED/RIDE_ACTIVE/RECONNECTING to `ENDING`, whose effect is the **one** owner
            // of the release -> teardown order (`retireSession`). The eager release that used to be
            // here was a second owner, and once `ENDING -> IDLE` became reachable it was a second
            // owner whose fire-and-forget tail could clear the *successor's* sinks.
            await sharedLibrary?.handleLinkLost()
            await syncPlayback?.handleLinkLost()
        case .duplicateConnectionClosed, .reconnectBudgetExhausted:
            break
        }
    }

    private func beginReconnectIfPossible() {
        guard let host = lastPeerHost, let port = lastPeerPort, state.status == .reconnecting else { return }
        let manager = controlSessionManager
        let identity = localIdentity
        launchInSession { _ in await manager.beginReconnect(local: identity, host: host, port: port) }
    }

    @discardableResult
    private func applyEvent(_ event: SessionEvent) -> Bool {
        switch SessionFsm.transition(state, event) {
        case .transitioned(let newState, let effects):
            state = newState
            effects.forEach { runEffect($0, newState: newState) }
            return true
        case .rejected:
            logger.warn("SessionCoordinator", "rejected \(event) from \(state.status)")
            return false
        case .ignored(_, _, let reason):
            logger.debug("SessionCoordinator", "ignored \(event) from \(state.status): \(reason)")
            return false
        }
    }

    /// - Parameter newState: the state this transition produced. Passed rather than re-read: which
    ///   deliberate end a `.releaseAudioAndStopForegroundService` belongs to is a property of *this*
    ///   transition, and asking a mutable field for it later is the shape ADR-024 Amendment A7 and
    ///   ADR-025 are both about.
    private func runEffect(_ effect: Effect, newState: FsmState) {
        switch effect {
        case .logTransition(let from, let to, let trigger):
            logger.info("SessionCoordinator", "\(from.status) -> \(to.status) (\(trigger))")
        case .releaseAudioAndStopForegroundService:
            // iOS has no microphone foreground service — a background-audio app keeps its session —
            // so the effect's second half is Android's alone. The first half, and the ordering around
            // it, is shared: `retireSession` is the one owner, and `.teardownComplete` is emitted as
            // the last statement of the same task that performs the teardown (`docs/STATUS.md` §4
            // problem 53).
            logger.info("SessionCoordinator", "release audio session")
            retireSession(newState.status == .ending ? .ending : .userRetry)
        }
    }
}
