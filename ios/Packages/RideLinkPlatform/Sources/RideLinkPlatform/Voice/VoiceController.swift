import Foundation
import RideLinkCore

/// FR-023 voice diagnostics, as one value. Contains nothing PROTOCOL §7.7 forbids.
public struct VoiceDiagnostics: Sendable, Equatable {
    public var status: VoiceStatus = .idle
    public var role: VoiceRole?
    /// Redacted to 6 characters, per the ARCHITECTURE §11 rule for ephemeral hex identifiers.
    public var voiceSessionPrefix: String?
    public var micMuted = false
    public var mode: VoiceMode = .continuous
    public var peerReportedState: VoiceWireState = .idle
    public var peerRequestedVoice = false
    public var localAudioOpen = false
    public var engine = VoiceEngineDiagnostics()
    public var route = AudioRouteSnapshot()
    public var queuedCandidates = 0
    public var droppedQueuedCandidates = 0
    /// Counted by reason, so "why did voice not come up" has an answer that is not a guess.
    public var droppedSignals: [VoiceSignalDropReason: Int] = [:]
    /// How many times the media transport has been rebuilt in this control session (§7.8).
    public var rebuildCount = 0
    /// True if any candidate type other than `host` was ever gathered or received. PROTOCOL §7.6
    /// configures an empty ICE server list, so this must stay false — it is surfaced rather than
    /// asserted because a false alarm on a ride is better than a crash.
    public var unexpectedCandidateTypeSeen = false

    public init() {}
}

/// ADR-020: 16 CSPRNG bytes as 32 lowercase hex, fresh per negotiation (PROTOCOL §7.2).
public enum VoiceSessionIdGenerator {
    public static func generate() -> VoiceSessionId {
        var bytes = [UInt8](repeating: 0, count: 16)
        // The same CSPRNG the discovery handle and `conn_tiebreak` use, for the same reason: a
        // predictable generation id would let a peer name a negotiation it was never part of.
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return VoiceSessionId(bytes.map { String(format: "%02x", $0) }.joined())
    }
}

/// Owns one voice session's WebRTC lifecycle, and nothing else.
///
/// **What it owns:** the peer connection's creation and disposal, the local capture/audio-session
/// handle, the negotiation state (via the pure `VoiceNegotiation` table), the bounded trickle-ICE
/// queue, mute, and the FR-023 voice diagnostics.
///
/// **What it deliberately does not own:** RideLink trust, the global session FSM, discovery, peer
/// identity persistence, music, and navigation. `SessionCoordinator` remains the single owner of
/// session state (CLAUDE.md rule 8) — it constructs this controller only for an authenticated session,
/// tells it when the control link goes, and tears it down on `ENDING`.
///
/// **Every decision is in the pure table, not here.** This actor is a driver: it turns inputs into
/// `VoiceInput`, applies the `VoiceAction`s that come back, and reports. That division is the direct
/// lesson of ADR-019 and of STATUS §4 problem 20 — the Phase 1b security bug lived in a `switch` that
/// no test suite could construct. `VoiceNegotiation` is exhausted by shared vectors on both platforms;
/// what is left here is effects.
///
/// One controller per two-person session, enforced by there being exactly one construction site
/// (`SessionCoordinator`) and by `start()` being idempotent through the table.
public actor VoiceController: VoiceSignalSink {
    private var state: VoiceNegotiationState
    private var pending = PendingCandidates()
    private var dropCounts: [VoiceSignalDropReason: Int] = [:]
    private var rebuildCount = 0
    private var unexpectedCandidateSeen = false
    private var startedGeneration: VoiceSessionId?

    private let engine: any VoiceEngine
    private let audioSession: any VoiceAudioSession
    private let transport: any VoiceSignalTransport
    private let localTrackId: String
    private let audioProcessing: AudioProcessingConfig
    private let newVoiceSessionId: @Sendable () -> VoiceSessionId

    private var diagnostics = VoiceDiagnostics()
    private var onDiagnosticsChanged: (@Sendable (VoiceDiagnostics) -> Void)?

    /// Where every input actually lives; bounded by lane rather than sitting in one unbounded queue.
    /// `mailbox.offer` is called from `submit` and from the engine's own event sink, both of which run
    /// outside actor isolation, so the storage itself has to be a plain `Sendable` box with its own
    /// lock -- exactly the reason `OrderedEventChannel`, which this replaces, needed no lock of its
    /// own either: it is a `let` on the actor, safely reachable from a `nonisolated` context.
    private let mailbox = VoiceInputMailboxBox()

    /// The only thing that crosses into actor isolation on every input now: a wake-up, not the input
    /// itself (that lives in `mailbox`). `mailbox`'s own lane priorities are what make it safe for
    /// several producers to ring this doorbell concurrently, and `ConflatedSignal`'s at-most-one-
    /// pending-wake-up buffering is what stops the doorbell itself becoming a second, unbounded queue
    /// sitting behind the now-bounded mailbox -- see `ConflatedSignal`'s own doc comment for why this
    /// is a distinct type from `OrderedEventChannel` (used elsewhere on this actor's sibling,
    /// `SessionCoordinator`, for events whose relative order is itself security-sensitive) rather than
    /// a second use of it.
    private let doorbell = ConflatedSignal()
    private var consumerTask: Task<Void, Never>?
    private var diagnosticsPollTask: Task<Void, Never>?

    public init(
        engine: any VoiceEngine,
        audioSession: any VoiceAudioSession,
        transport: any VoiceSignalTransport,
        isLocalLeader: Bool,
        localTrackId: String,
        audioProcessing: AudioProcessingConfig = AudioProcessingConfig(),
        newVoiceSessionId: @escaping @Sendable () -> VoiceSessionId = { VoiceSessionIdGenerator.generate() }
    ) {
        self.engine = engine
        self.audioSession = audioSession
        self.transport = transport
        self.localTrackId = localTrackId
        self.audioProcessing = audioProcessing
        self.newVoiceSessionId = newVoiceSessionId
        self.state = VoiceNegotiationState(role: VoiceRole.forLeadership(isLocalLeader: isLocalLeader))
        self.diagnostics.role = state.role
    }

    /// Starts the single consumer and attaches the engine and route sinks. Separate from `init` because
    /// an actor cannot hand `self` to an escaping closure during initialisation.
    public func attach() async {
        let box = mailbox
        let bell = doorbell
        await engine.setEventSink { event in
            // Reduced to a table input at the boundary, so nothing WebRTC-shaped reaches the mailbox
            // -- and, on this platform, so nothing non-`Sendable` has to cross an isolation domain.
            box.offer(Self.inputFor(event), doorbell: bell)
        }
        await audioSession.setRouteSink { [weak self] snapshot in
            Task { await self?.publishRoute(snapshot) }
        }
        consumerTask = Task { [weak self] in
            for await _ in bell.stream {
                guard let self else { return }
                await self.drainMailbox()
            }
        }
    }

    public func setOnDiagnosticsChanged(_ handler: @escaping @Sendable (VoiceDiagnostics) -> Void) {
        onDiagnosticsChanged = handler
        handler(diagnostics)
    }

    public func currentDiagnostics() -> VoiceDiagnostics { diagnostics }

    // MARK: - the four things the app asks for

    /// The user pressed Start Voice, or a control reconnect is rebuilding voice (PROTOCOL §7.8).
    public func start() {
        mailbox.offer(.startRequested(freshVoiceSessionId: newVoiceSessionId()), doorbell: doorbell)
    }

    /// The user pressed End Voice, or the session is entering `ENDING`.
    public func stop() {
        mailbox.offer(.stopRequested, doorbell: doorbell)
    }

    public func setMicrophoneMuted(_ muted: Bool) {
        mailbox.offer(.muteRequested(muted: muted), doorbell: doorbell)
    }

    /// The control plane was lost. Media goes; capture stays open for the ride segment
    /// (ARCHITECTURE §6.3/§6.4). Nothing is retried here — PROTOCOL §10's ladder is the only reconnect
    /// loop in the app, and a second one competing with it is the bug the §2e hardening pass fixed for
    /// the control plane.
    public func onControlLinkLost() {
        mailbox.offer(.controlLinkLost, doorbell: doorbell)
    }

    /// A `VOICE_*` frame that has **already** passed the ADR-019 trust gate. There is no other entry
    /// point: an unauthenticated peer's frame is dropped by `ControlSessionManager` before it can reach
    /// this method (PROTOCOL §7.1).
    ///
    /// `nonisolated` and non-async so the control read loop is never blocked by it, even under a flood
    /// of frames from an authenticated peer -- `mailbox.offer` only ever touches an in-memory,
    /// lock-guarded deque/dictionary, never suspends, and never grows without bound.
    public nonisolated func submit(_ signal: VoiceSignal) {
        mailbox.offer(.signalReceived(signal: signal, freshVoiceSessionId: newVoiceSessionId()), doorbell: doorbell)
    }

    /// Releases every task this controller owns. After this, no callback can mutate anything.
    public func shutdown() async {
        await apply(.stopRequested)
        diagnosticsPollTask?.cancel()
        diagnosticsPollTask = nil
        consumerTask?.cancel()
        consumerTask = nil
        doorbell.finish()
        mailbox.clear()
        pending.reset()
    }

    // MARK: - the mailbox

    /// Drains `mailbox` to empty, in `VoiceMailboxLane` priority order.
    ///
    /// A `.criticalOverflow` cannot simply be swallowed -- a lost `VOICE_OFFER` or `VOICE_ANSWER`
    /// would wedge a negotiation with no error anywhere, the same failure mode the old unbounded
    /// channel existed to avoid. `VoiceInputMailboxBox.offer` already responded to that by forcing
    /// `.controlLinkLost` through the always-accepting teardown lane before this method ever runs --
    /// which mirrors an actual control-link blip rather than inventing a new failure path: it drops
    /// the media transport and keeps this user's local capture and the TLS control session both
    /// untouched (ARCHITECTURE §6.3/§6.4).
    private func drainMailbox() async {
        while let next = mailbox.poll() {
            await apply(next)
        }
    }

    // MARK: - the driver

    private func apply(_ input: VoiceInput) async {
        let outcome = VoiceNegotiation.reduce(state: state, input: input)
        state = outcome.state
        for action in outcome.actions {
            await perform(action)
        }
        publishDiagnostics()
    }

    // swiftlint:disable:next cyclomatic_complexity
    private func perform(_ action: VoiceAction) async {
        switch action {
        case .startLocalAudio:
            await startLocalAudio()
        case .createOffer(let id):
            await startEngine(id) { await self.engine.createOffer() }
        case .createAnswer(let id):
            await startEngine(id) { await self.engine.createAnswer() }
        case .applyRemoteOffer(let id, let sdp):
            await startEngine(id) { await self.engine.applyRemoteDescription(kind: .offer, sdp: sdp) }
        case .applyRemoteAnswer(_, let sdp):
            _ = await engine.applyRemoteDescription(kind: .answer, sdp: sdp)
        case .sendOffer(let id, let sdp):
            _ = await transport.send(.offer(voiceSessionId: id, sdp: sdp))
        case .sendAnswer(let id, let sdp):
            _ = await transport.send(.answer(voiceSessionId: id, sdp: sdp))
        case .sendVoiceState(let id, let wire, let micMuted, let mode):
            _ = await transport.send(.state(voiceSessionId: id, state: wire, micMuted: micMuted, mode: mode))
        case .sendCandidate(let id, let candidate, let mid, let index):
            // PROTOCOL §7.6 inspects the `typ` of every candidate this side **gathers** as well as
            // every one it receives. The gathering direction is the one that would reveal a STUN
            // server had been contacted, so missing it would miss the case the check is for.
            noteCandidateType(candidate)
            _ = await transport.send(
                .iceCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
            )
        case .applyRemoteCandidate(_, let candidate, let mid, let index):
            noteCandidateType(candidate)
            _ = await engine.addRemoteCandidate(candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
        case .queueRemoteCandidate(let id, let candidate, let mid, let index):
            noteCandidateType(candidate)
            pending.offer(
                RemoteCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
            )
        case .drainQueuedCandidates:
            await drainCandidates()
        case .setMicrophoneMuted(let muted):
            await engine.setMicrophoneMuted(muted)
        case .stopMediaTransport:
            await stopMediaTransport()
        case .releaseLocalAudio:
            // Order: media factory and capture device first, then the platform audio session.
            // Releasing the session while WebRTC still holds the audio unit leaves the route in a
            // state neither side owns.
            await engine.release()
            await audioSession.close()
        case .recordDroppedSignal(let reason):
            dropCounts[reason, default: 0] += 1
        case .surfacePeerVoiceRequest:
            break // published through diagnostics.peerRequestedVoice
        }
    }

    /// Opens the capture device and the audio session. A failure here is **not** silent and is not a
    /// crash: the negotiation continues without a local microphone, which is FR-025's graceful
    /// degradation, and the diagnostics show `localAudioOpen = false` so the UI can say why.
    private func startLocalAudio() async {
        if case .failure = await audioSession.open() {
            dropCounts[.unexpectedForStatus, default: 0] += 1
        }
        publishRoute(await audioSession.route())
    }

    /// Every SDP action needs a peer connection, and the negotiation table does not model "engine
    /// started" — that is an effect, not a decision. Starting it here, idempotently and keyed on the
    /// generation, is what keeps the table free of a field that would only ever mirror this actor.
    private func startEngine(
        _ voiceSessionId: VoiceSessionId,
        _ then: () async -> Result<Void, VoiceEngineError>
    ) async {
        if startedGeneration != voiceSessionId {
            if case .failure = await engine.start(
                config: VoiceEngineConfig(
                    voiceSessionId: voiceSessionId,
                    localTrackId: localTrackId,
                    audioProcessing: audioProcessing
                )
            ) {
                return
            }
            startedGeneration = voiceSessionId
            if diagnosticsPollTask == nil { startDiagnosticsPolling() }
        }
        _ = await then()
    }

    private func stopMediaTransport() async {
        if startedGeneration != nil { rebuildCount += 1 }
        startedGeneration = nil
        await engine.stop()
        // The queue belongs to a generation. Clearing it here — rather than relying on `drain`'s
        // generation filter alone — means a candidate from a torn-down negotiation is not merely
        // unusable, it is gone.
        pending.clear()
        await publishEngineDiagnostics()
    }

    private func drainCandidates() async {
        guard let id = state.voiceSessionId else { return }
        for candidate in pending.drain(voiceSessionId: id) {
            _ = await engine.addRemoteCandidate(
                candidate: candidate.candidate,
                sdpMid: candidate.sdpMid,
                sdpMlineIndex: candidate.sdpMlineIndex
            )
        }
    }

    private func noteCandidateType(_ candidateLine: String) {
        // The **type** only. PROTOCOL §7.7 gives an address and port no log path at all, and a value
        // that is never extracted cannot be leaked by a later careless log call.
        if IceCandidateType.fromCandidateLine(candidateLine).impliesNonLocalDependency {
            unexpectedCandidateSeen = true
        }
    }

    /// Maps the media stack's callbacks onto table inputs. Every one carries its `voice_session_id`,
    /// which is the generation guard applied to callbacks rather than to the wire (PROTOCOL §7.8) — a
    /// delegate call from a peer connection this controller has already closed names the old generation
    /// and the table drops it.
    private static func inputFor(_ event: VoiceEngineEvent) -> VoiceInput {
        switch event {
        case .offerCreated(let id, let sdp):
            return .localOfferCreated(voiceSessionId: id, sdp: sdp)
        case .answerCreated(let id, let sdp):
            return .localAnswerCreated(voiceSessionId: id, sdp: sdp)
        case .localCandidateGathered(let id, let candidate, let mid, let index):
            return .localCandidateGathered(
                voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index
            )
        case .remoteTrackChanged(let id, let present):
            return .remoteTrackChanged(voiceSessionId: id, present: present)
        case .transportStateChanged(let id, let transportState):
            return .mediaConnectivityChanged(
                voiceSessionId: id,
                connected: transportState == .connected,
                failed: transportState == .failed
            )
        case .failed(let id, _):
            return .mediaConnectivityChanged(voiceSessionId: id, connected: false, failed: true)
        }
    }

    // MARK: - diagnostics

    private func startDiagnosticsPolling() {
        diagnosticsPollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self else { return }
                await self.refreshEngineDiagnostics()
            }
        }
    }

    private func refreshEngineDiagnostics() async {
        await engine.refreshDiagnostics()
        await publishEngineDiagnostics()
    }

    private func publishEngineDiagnostics() async {
        diagnostics.engine = await engine.diagnostics()
        onDiagnosticsChanged?(diagnostics)
    }

    private func publishRoute(_ snapshot: AudioRouteSnapshot) {
        diagnostics.route = snapshot
        onDiagnosticsChanged?(diagnostics)
    }

    private func publishDiagnostics() {
        diagnostics.status = state.status
        diagnostics.role = state.role
        diagnostics.voiceSessionPrefix = state.voiceSessionId?.description
        diagnostics.micMuted = state.micMuted
        diagnostics.mode = state.mode
        diagnostics.peerReportedState = state.peerReportedState
        diagnostics.peerRequestedVoice =
            state.heldRemoteOffer != nil || (state.peerVoiceEnabled && !state.localAudioOpen)
        diagnostics.localAudioOpen = state.localAudioOpen
        diagnostics.queuedCandidates = pending.count
        diagnostics.droppedQueuedCandidates = pending.droppedCount
        let mailboxOverflows = mailbox.overflowCount
        var droppedSignals = dropCounts
        if mailboxOverflows > 0 { droppedSignals[.inputMailboxOverflow] = mailboxOverflows }
        diagnostics.droppedSignals = droppedSignals
        diagnostics.rebuildCount = rebuildCount
        diagnostics.unexpectedCandidateTypeSeen = unexpectedCandidateSeen
        onDiagnosticsChanged?(diagnostics)
    }
}

/// A thread-safe wrapper around the pure `VoiceInputMailbox`, since `submit` and the engine's own
/// event sink both call in from outside actor isolation -- the same reason `OrderedEventChannel` needs
/// none of its own locking either, just from the other direction (there, delivery itself is lock-free;
/// here, the mailbox's bounding logic needs a lock because `VoiceInputMailbox` is a plain, non-atomic
/// value type).
private final class VoiceInputMailboxBox: @unchecked Sendable {
    private let lock = NSLock()
    private var mailbox = VoiceInputMailbox()

    /// Offers `input`, forces a safe degrade on a critical-lane or terminal-peer-state-lane overflow,
    /// and always rings `doorbell` -- mirrors `VoiceController.offer` on Android exactly. Never
    /// suspends and never blocks its caller for any meaningful time: every critical section here is an
    /// in-memory deque/dictionary operation.
    func offer(_ input: VoiceInput, doorbell: ConflatedSignal) {
        lock.lock()
        let outcome = mailbox.offer(input)
        if outcome == .criticalOverflow || outcome == .terminalOverflow {
            // A well-formed, authenticated input could not be held. Forcing a link-loss-style
            // degrade -- media stops, local capture and the TLS control session both survive -- is
            // the same safe response an actual control-link blip already produces, applied one layer
            // earlier. The teardown lane always accepts.
            _ = mailbox.offer(.controlLinkLost)
        }
        lock.unlock()
        doorbell.signal()
    }

    func poll() -> VoiceInput? {
        lock.lock()
        defer { lock.unlock() }
        return mailbox.poll()
    }

    var overflowCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return mailbox.overflowCount
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        mailbox.clear()
    }
}
