import Foundation
import RideLinkCore

/// FR-023 voice diagnostics, as one value. Contains nothing PROTOCOL §7.7 forbids.
public struct VoiceDiagnostics: Sendable, Equatable {
    public var status: VoiceStatus = .idle
    public var role: VoiceRole?
    /// Redacted to 6 characters, per the ARCHITECTURE §11 rule for ephemeral hex identifiers.
    public var voiceSessionPrefix: String?
    /// What `VOICE_STATE.mic_muted` reports: this peer is transmitting silence (PROTOCOL §7.4). Under
    /// PTT it is `true` whenever the button is not held, which is correct on the wire and is why the UI
    /// shows `userMuted` separately — "not talking right now" and "muted" are different things to a user.
    public var micMuted = false
    public var mode: VoiceMode = .continuous
    /// ARCHITECTURE §6.3's policy object, as selected. Never five code paths — see `IntercomPolicy`.
    public var policy: IntercomPolicy = .default
    /// `AUDIO_STATE.intercom_mode` (PROTOCOL §4.4). Four values, unlike `mode`'s three (ADR-021 §3).
    public var intercomMode: IntercomMode = IntercomPolicy.default.intercomWireMode
    /// Whether outbound audio is flowing **right now**. The gate's whole output.
    public var transmitting = false
    /// The PTT control's current position, for the UI to reflect back at the user.
    public var pttHeld = false
    /// The user's own Mute toggle, as distinct from `micMuted`. Survives a policy change.
    public var userMuted = false
    /// False for as long as no microphone-driven input level exists on this platform, which is
    /// **currently always** — see `TransmissionGate.vox` and ADR-021 §6. Surfaced rather than hidden,
    /// because selecting Mode B while this is false means the VOX gate can never open and the user is
    /// entitled to be told that rather than to discover it by silence.
    public var voxLevelSourceAvailable = false
    /// Software setup timing (PROTOCOL §7.8 / TEST_PLAN V-01). **Not latency** — see
    /// `VoiceSetupTimeline`'s own doc. Mouth-to-ear latency is A-09/V-11 and requires hardware.
    public var setup = VoiceSetupTimeline()
    /// The last named reason the intercom could not run. Never a generic "connection failed" (§41).
    public var lastFailure: VoiceFailure?
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
    private var setupTimeline = VoiceSetupTimeline()
    private var lastFailure: VoiceFailure?

    /// The intercom transmission gate's state (ARCHITECTURE §6.3, ADR-021). Actor-isolated and mutated
    /// only by `applyIntercom`, on the single consumer.
    ///
    /// Starts at `IntercomPolicy.default` — Mode C, by architecture rather than by measurement. The owner
    /// (`SessionCoordinator`) calls `selectPolicy` immediately after `attach()` with whatever the user
    /// has actually chosen, so there is one source of that choice rather than an init parameter and a
    /// setter that could disagree.
    private var transmission = TransmissionState(policy: .default)

    private let engine: any VoiceEngine
    private let audioSession: any VoiceAudioSession
    private let transport: any VoiceSignalTransport
    private let localTrackId: String
    private let audioProcessing: AudioProcessingConfig
    private let newVoiceSessionId: @Sendable () -> VoiceSessionId
    /// Monotonic microseconds, for `VoiceSetupTimeline` and nothing else. A parameter rather than a clock
    /// read here, so the timings are deterministic in a test and CLAUDE.md rule 5 holds.
    private let monotonicNowUs: @Sendable () -> Int64

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
        monotonicNowUs: @escaping @Sendable () -> Int64 = { 0 },
        newVoiceSessionId: @escaping @Sendable () -> VoiceSessionId = { VoiceSessionIdGenerator.generate() }
    ) {
        self.engine = engine
        self.audioSession = audioSession
        self.transport = transport
        self.localTrackId = localTrackId
        self.audioProcessing = audioProcessing
        self.monotonicNowUs = monotonicNowUs
        self.newVoiceSessionId = newVoiceSessionId
        self.state = VoiceNegotiationState(role: VoiceRole.forLeadership(isLocalLeader: isLocalLeader))
        self.diagnostics.role = state.role
    }

    /// Starts the single consumer and attaches the engine and route sinks. Separate from `init` because
    /// an actor cannot hand `self` to an escaping closure during initialisation.
    public func attach() async {
        let box = mailbox
        let bell = doorbell
        await engine.setEventSink { [weak self] event in
            // Reduced to a table input at the boundary, so nothing WebRTC-shaped reaches the mailbox
            // -- and, on this platform, so nothing non-`Sendable` has to cross an isolation domain.
            box.offer(Self.inputFor(event), doorbell: bell)
            // The setup marks and the named failure are actor state, so they are recorded through a hop
            // rather than in the callback. Ordering does not matter for either: `VoiceSetupTimer` is
            // first-write-wins per milestone, and a failure name is an absolute value.
            Task { await self?.noteEngineEvent(event) }
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
        // A fresh negotiation is a fresh measurement (V-01's setup figure is per generation, not a
        // lifetime average), and the mark is taken here rather than in the consumer so it times the
        // user's tap rather than when the queue got round to it.
        setupTimeline = VoiceSetupTimer.restart(atMonoUs: monotonicNowUs())
        mailbox.offer(.startRequested(freshVoiceSessionId: newVoiceSessionId()), doorbell: doorbell)
    }

    /// The user pressed End Voice, or the session is entering `ENDING`.
    public func stop() {
        mailbox.offer(.stopRequested, doorbell: doorbell)
    }

    /// The user's own Mute toggle. It goes through the intercom gate rather than straight to the
    /// negotiation table, because mute is one of five inputs that decide whether audio leaves — the
    /// others being the policy, the PTT button, the capture path and any platform interruption — and
    /// having two paths to `setMicrophoneMuted` is how they would come to disagree (ADR-021 §4).
    public func setMicrophoneMuted(_ muted: Bool) {
        mailbox.offerIntercom(.userMuted(muted), doorbell: doorbell)
    }

    /// ARCHITECTURE §6.3's five modes, selected as one policy object. Takes effect immediately and is
    /// announced to the peer as `VOICE_STATE.mode` and `AUDIO_STATE.intercom_mode` when either changes.
    ///
    /// Selecting a policy **never touches the capture device.** That is the whole point of the mode
    /// model: `micAlwaysOpen == false` means outbound speech is gated, not that the microphone is
    /// reopened per utterance.
    ///
    /// `nonisolated`, like `submit`, so the UI reaches the bounded mailbox directly rather than through a
    /// `Task` per event — which would only preserve the order the events were *created* in, not the order
    /// they run in (STATUS §2h's lesson, and this phase's brief §39).
    public nonisolated func selectPolicy(_ policy: IntercomPolicy) {
        mailbox.offerIntercom(.policySelected(policy), doorbell: doorbell)
    }

    /// The PTT control's current position — `true` on press, `false` on release, on touch-cancel, and
    /// when the app is backgrounded (`onAppBackgrounded`).
    ///
    /// **This gates the outbound WebRTC track and nothing else.** It does not open, close, reopen or
    /// reconfigure the capture device, the audio session or the peer connection, and it does not change
    /// `voice_session_id`. `VoiceControllerIntercomTests` counts the capture operations across 50 presses
    /// and asserts they are zero; TEST_PLAN A-10 is the same assertion against real hardware.
    public nonisolated func setPushToTalkHeld(_ held: Bool) {
        mailbox.offerIntercom(.pttHeld(held), doorbell: doorbell)
    }

    /// The app left the foreground while a PTT press may still have been outstanding.
    ///
    /// This phase's brief §25: backgrounding while held must not leave transmission stuck on. It is the
    /// same absolute assignment a release is, deliberately — one code path, so the two cannot diverge.
    /// Nothing about capture changes: the ride segment continues, and on Android ARCHITECTURE §6.4 gives
    /// no second chance to reopen a microphone once the screen is locked.
    public nonisolated func onAppBackgrounded() {
        mailbox.offerIntercom(.pttHeld(false), doorbell: doorbell)
    }

    /// The control plane was lost. Media goes; capture stays open for the ride segment
    /// (ARCHITECTURE §6.3/§6.4). Nothing is retried here — PROTOCOL §10's ladder is the only reconnect
    /// loop in the app, and a second one competing with it is the bug the §2e hardening pass fixed for
    /// the control plane.
    public func onControlLinkLost() {
        lastFailure = .controlLinkLost
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
    /// Drains **both** mailboxes to empty on each wake, intercom commands first.
    ///
    /// Intercom first because an intercom command's whole output is one or two `VoiceInput`s, which then
    /// need draining in the same pass — otherwise a PTT press would sit until the next doorbell ring.
    /// The loop re-checks both, so the pass ends only when neither has anything left.
    private func drainMailbox() async {
        while true {
            if let command = mailbox.pollIntercom() {
                await applyIntercom(command)
            } else if let next = mailbox.poll() {
                await apply(next)
            } else {
                return
            }
        }
    }

    /// Applies one intercom command through the pure `IntercomTransmission` table and performs what comes
    /// back.
    ///
    /// The resulting effects are turned into ordinary `VoiceInput`s — `.muteRequested` and
    /// `.modeSelected` — rather than reaching the engine directly, so every effect on the media plane
    /// still goes through `VoiceNegotiation`'s generation guard and through the one bounded queue. There
    /// is deliberately no second path to `engine.setMicrophoneMuted`.
    private func applyIntercom(_ input: IntercomInput) async {
        let outcome = IntercomTransmission.reduce(state: transmission, input: input)
        transmission = outcome.state
        for action in outcome.actions {
            switch action {
            // The gate's absolute value is what reaches the negotiation table (below), not this action,
            // so there is nothing to do on the transition itself.
            case .setTransmitting:
                break
            case .announceVoiceMode(let mode):
                await apply(.modeSelected(mode: mode))
            // The coordinator publishes `AUDIO_STATE` from a diagnostics change, and a policy change is
            // one, so there is nothing further to do here.
            case .publishAudioState:
                break
            }
        }
        // **The gate is the single source of `VOICE_STATE.mic_muted`** (PROTOCOL §7.4: "transmitting
        // silence"), and the driver takes its **absolute** value rather than the `.setTransmitting` diff.
        //
        // The diff is right for the table — it is what `protocol/vectors/intercom/` pins, and a restated
        // unchanged value would be noise there. It is the wrong thing for a driver, because it cannot
        // correct a value that was never established: with a gated policy, capture opening leaves
        // `transmitting` false on both sides of the transition, so no diff is emitted, while
        // `VoiceNegotiationState.micMuted` still holds its `false` default and the wire would claim this
        // side is transmitting. `VoiceNegotiation.mute` is itself idempotent, so offering the absolute
        // value on every intercom input costs nothing and closes that gap.
        // Applied **directly**, not offered.
        //
        // Both are produced by this consumer, on this consumer, so they cannot flood — the mailbox exists
        // to bound *external* producers (the read loop, a WebRTC callback, the UI), and routing these
        // through it would only reintroduce its lane priorities: `.muteRequested` and `.modeSelected` are
        // coalesced-lane inputs, so a `.startRequested` already waiting in the critical lane would be
        // reduced **before** them and would put the previous policy's mode and a stale `mic_muted` on the
        // wire. Applying in place is what makes the first `VOICE_STATE` after a policy change carry that
        // policy.
        await apply(.muteRequested(muted: outcome.state.micMutedForWire))
        publishDiagnostics()
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
            mark(.remoteDescription)
            await startEngine(id) { await self.engine.applyRemoteDescription(kind: .offer, sdp: sdp) }
        case .applyRemoteAnswer(_, let sdp):
            mark(.remoteDescription)
            _ = await engine.applyRemoteDescription(kind: .answer, sdp: sdp)
        case .sendOffer(let id, let sdp):
            mark(.localDescription)
            _ = await transport.send(.offer(voiceSessionId: id, sdp: sdp))
        case .sendAnswer(let id, let sdp):
            mark(.localDescription)
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
            // And the gate closes with it, so a later reopen cannot resume a stale press.
            mailbox.offerIntercom(.captureOpen(false), doorbell: doorbell)
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
        switch await audioSession.open() {
        case .success:
            mark(.captureOpen)
            lastFailure = nil
        case .failure(let error):
            // FR-025 graceful degradation, with a **named** reason rather than a generic one (this
            // phase's brief §41): the negotiation continues without a local microphone, the control
            // session is untouched, and the UI can say which of permission, activation, route selection
            // or capture actually refused.
            lastFailure = error.failure
            dropCounts[.unexpectedForStatus, default: 0] += 1
        }
        // The gate needs to know whether there is a capture path before it can ever transmit
        // (ARCHITECTURE §6.4): a PTT press must never be what opens one.
        mailbox.offerIntercom(.captureOpen(await audioSession.isOpen()), doorbell: doorbell)
        publishRoute(await audioSession.route())
    }

    /// Records one `VoiceSetupMark`, first-write-wins within the current negotiation (`VoiceSetupTimer`).
    private func mark(_ mark: VoiceSetupMark) {
        setupTimeline = VoiceSetupTimer.mark(setupTimeline, mark, atMonoUs: monotonicNowUs())
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
            // **A new peer connection is a new track, and its enabled state must come from the gate.**
            // Both engines enable the local track when they build it, which is right for full duplex and
            // wrong for every gated policy: under PTT a rebuild would go live before the first press.
            // Pushing the gate's current value here — on every engine start, including a reconnect
            // rebuild — is what makes the track's state a consequence of the policy rather than of a
            // constructor default. Idempotent: the engine just sets a boolean.
            await engine.setMicrophoneMuted(transmission.micMutedForWire)
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
    /// Records the timing milestones and the named failure an engine event implies.
    ///
    /// Separate from `inputFor` because that is `static` — it must be, to be callable from a `Sendable`
    /// closure without capturing the actor — and these are actor state.
    private func noteEngineEvent(_ event: VoiceEngineEvent) {
        switch event {
        case .offerCreated, .answerCreated:
            mark(.localDescription)
        case .remoteTrackChanged(_, let present):
            if present { mark(.remoteTrack) }
        case .transportStateChanged(_, let transportState):
            if transportState == .connected { mark(.mediaConnected) }
            if transportState == .failed { lastFailure = .webRtcFailed }
        case .failed:
            lastFailure = .webRtcFailed
        case .localCandidateGathered:
            break
        }
        // Published here as well as from `apply`, because this runs on its own hop: the mailbox input
        // the same event produced may already have been applied and published by the time these marks
        // and this failure name are recorded, and a diagnostic nobody publishes is a diagnostic nobody
        // sees.
        publishDiagnostics()
    }

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
        // An interruption is a *route* fact (ADR-016), and it is one of the two overrides that can only
        // ever stop transmission. Routed through the gate rather than acted on here, so there is one
        // place that decides whether audio leaves.
        mailbox.offerIntercom(.interrupted(snapshot.interrupted), doorbell: doorbell)
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
        // The gate's own view of the capture path, not the session object's, so this field can never
        // disagree with `transmitting` — which is derived from the same value. It is "consent AND a real
        // capture path": `VoiceNegotiationState.localAudioOpen` records that the user consented for this
        // ride segment, which stays true even when the platform refused the microphone (FR-025 graceful
        // degradation), so consent alone would render as "mic: open" on a device that has none.
        diagnostics.localAudioOpen = state.localAudioOpen && transmission.captureOpen
        diagnostics.queuedCandidates = pending.count
        diagnostics.droppedQueuedCandidates = pending.droppedCount
        let mailboxOverflows = mailbox.overflowCount
        var droppedSignals = dropCounts
        if mailboxOverflows > 0 { droppedSignals[.inputMailboxOverflow] = mailboxOverflows }
        diagnostics.droppedSignals = droppedSignals
        diagnostics.rebuildCount = rebuildCount
        diagnostics.unexpectedCandidateTypeSeen = unexpectedCandidateSeen
        diagnostics.policy = transmission.policy
        diagnostics.intercomMode = transmission.policy.intercomWireMode
        diagnostics.transmitting = transmission.transmitting
        diagnostics.pttHeld = transmission.pttHeld
        diagnostics.userMuted = transmission.userMuted
        // False until a microphone-driven level exists on this platform, which is currently always —
        // see the field's own doc and ADR-021 §6.
        diagnostics.voxLevelSourceAvailable = false
        diagnostics.setup = setupTimeline
        diagnostics.lastFailure = lastFailure
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

    /// The intercom commands' own mailbox, behind the **same** lock — bounded by construction at one slot
    /// per `IntercomCommandKind`, so no burst of PTT edges, mute taps or policy switches can grow it
    /// (this phase's brief §38). Sharing the lock and the doorbell with `mailbox` is what keeps a press
    /// and its release in order without a `Task` per event (§39).
    private var intercom = IntercomCommandMailbox()

    /// Offers an intercom command and rings `doorbell`. Never suspends and never blocks its caller:
    /// there is no capacity check to fail, because there is nothing to overflow.
    func offerIntercom(_ input: IntercomInput, doorbell: ConflatedSignal) {
        lock.lock()
        intercom.offer(input)
        lock.unlock()
        doorbell.signal()
    }

    func pollIntercom() -> IntercomInput? {
        lock.lock()
        defer { lock.unlock() }
        return intercom.poll()
    }

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
        intercom.clear()
    }
}
