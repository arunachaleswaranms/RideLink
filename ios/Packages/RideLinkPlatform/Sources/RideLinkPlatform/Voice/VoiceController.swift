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
    /// Whether the peer's last accepted `VOICE_STATE` says its outbound track is carrying speech.
    public var peerTransmitting = false
    /// This device's honest `SpeechActivity` (`TransmissionState.speechActivity`) — deliberately a
    /// separate field from `transmitting`, which answers "may audio leave" and nothing about whether
    /// anyone is actually talking (Phase 6 review blocker 1; ADR-027 Amendment A1).
    public var localSpeechActivity: SpeechActivity = .unavailable
    /// The peer's honest `SpeechActivity`, from `peerSpeechActivity(mode:transmittingOnWire:)` —
    /// never `mic_muted` alone.
    public var peerSpeechActivity: SpeechActivity = .unavailable
    /// The authenticated control lifetime `VoiceNegotiationState.negotiationControlGeneration` owned
    /// this negotiation under when this snapshot was produced — `nil` when nothing owns it. Carried
    /// so a consumer that outlives a reconnect (`SessionCoordinator`'s coexistence forwarding) can
    /// refuse a snapshot whose provenance predates the control lifetime it is being asked to
    /// represent, rather than relabelling it under whatever generation happens to be current at the
    /// point the snapshot is consumed (Phase 6 review blocker 2; ADR-027 Amendment A1). Stamped once
    /// here, at production time, and never re-derived downstream.
    public var controlGeneration: Int64?
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
    /// A coexistence projection updated only after the generation-bound reducer accepts a signal.
    private var peerTransmitting = false
    /// The peer's own honest `SpeechActivity`, derived from their last accepted `VOICE_STATE.mode`
    /// and `peerTransmitting` together — never from `peerTransmitting` alone, which for a continuous
    /// peer (Mode A/D) means nothing about speech (Phase 6 review blocker 1).
    private var peerSpeechActivityValue: SpeechActivity = .unavailable

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
    private var attachmentTask: Task<Void, Never>?
    private var shutdownTask: Task<Void, Never>?
    private var isShuttingDown = false

    /// Ordered route delivery (this phase's final hardening pass, Issue 4). `audioSession.setRouteSink`'s
    /// callback is synchronous and non-isolated, so it cannot call directly into this actor — the
    /// previous shape spawned an unstructured `Task` per snapshot, which (like every other `Task`-per-
    /// event pattern this codebase has already fixed, e.g. STATUS §2h) only preserves the order the
    /// tasks were *created* in, not the order they *run* in. One channel, one consumer, exactly the
    /// pattern `SessionCoordinator.voiceDiagnosticsChannel` already establishes for this actor's own
    /// diagnostics.
    private var routeChannel: OrderedEventChannel<AudioRouteSnapshot>?
    private var routeConsumerTask: Task<Void, Never>?

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
        guard !isShuttingDown else { return }
        if attachmentTask == nil {
            attachmentTask = Task { await self.attachInputs() }
        }
        guard let attachmentTask else { return }
        await withTaskCancellationHandler {
            await attachmentTask.value
        } onCancel: {
            attachmentTask.cancel()
        }
    }

    private func attachInputs() async {
        guard !isShuttingDown, !Task.isCancelled else { return }
        let box = mailbox
        let bell = doorbell
        await engine.setEventSink { event in
            // Reduced to a table input at the boundary, so nothing WebRTC-shaped reaches the mailbox
            // -- and, on this platform, so nothing non-`Sendable` has to cross an isolation domain.
            //
            // This phase's final hardening pass, Issue 5: the setup marks and named failure this
            // event can imply used to be recorded via a second `Task` per event
            // (`noteEngineEvent`), racing independently against the mailbox's own ordered
            // processing of the very same event. Deriving them from the `VoiceInput` `apply` is
            // about to reduce anyway -- inside `apply`, before the reducer runs -- makes them
            // exactly as ordered and exactly as generation-consistent as the table transition
            // itself, with no second hop and nothing left to race.
            box.offer(Self.inputFor(event), doorbell: bell)
        }
        // Ordered route delivery (Issue 4): `setRouteSink`'s callback is synchronous and
        // non-isolated, so it cannot call into this actor directly, and a `Task` per snapshot only
        // preserves creation order, not run order -- see `routeChannel`'s own doc. One channel, one
        // consumer, created fresh here so a stale sink from a torn-down controller can only ever
        // write into a channel `shutdown()` has already finished.
        guard !isShuttingDown, !Task.isCancelled else { return }
        let route = OrderedEventChannel<AudioRouteSnapshot>()
        routeChannel = route
        routeConsumerTask = Task { [weak self] in
            for await snapshot in route.stream {
                guard !Task.isCancelled, let self else { return }
                await self.publishRoute(snapshot)
            }
        }
        await audioSession.setRouteSink { snapshot in
            route.send(snapshot)
        }
        guard !isShuttingDown, !Task.isCancelled else { return }
        consumerTask = Task { [weak self] in
            for await _ in bell.stream {
                guard !Task.isCancelled, let self else { return }
                await self.drainMailbox()
            }
        }
    }

    public func setOnDiagnosticsChanged(_ handler: @escaping @Sendable (VoiceDiagnostics) -> Void) {
        guard !isShuttingDown else { return }
        onDiagnosticsChanged = handler
        handler(diagnostics)
    }

    public func currentDiagnostics() -> VoiceDiagnostics { diagnostics }

    // MARK: - the four things the app asks for

    /// The user pressed Start Voice, or a control reconnect is rebuilding voice (PROTOCOL §7.8).
    ///
    /// - Parameter controlGeneration: **the authenticated control lifetime this start is authorised
    ///   by**, which becomes the owner of any negotiation it establishes (STATUS §4 problem 61). The
    ///   caller supplies it — `SessionCoordinator` passes `.connected`'s `authGeneration` for the
    ///   reconnect rebuild and `liveAuthenticatedGeneration()` for a user's tap — because this
    ///   controller is deliberately retained across a reconnect and has no live generation of its own
    ///   to read.
    ///
    ///   Nil when no lifetime is authenticated, which a user reaches by pressing Start in the gap
    ///   between one link dying and the ladder restoring the next: the press then records consent and
    ///   opens capture but starts no negotiation, and `attachVoice` rebuilds it under the successor.
    public func start(controlGeneration: Int64?) {
        guard !isShuttingDown else { return }
        // A fresh negotiation is a fresh measurement (V-01's setup figure is per generation, not a
        // lifetime average), and the mark is taken here rather than in the consumer so it times the
        // user's tap rather than when the queue got round to it.
        setupTimeline = VoiceSetupTimer.restart(atMonoUs: monotonicNowUs())
        mailbox.offer(
            .startRequested(freshVoiceSessionId: newVoiceSessionId(), controlGeneration: controlGeneration),
            doorbell: doorbell)
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
    /// - Parameter retiredControlGeneration: **which** authentication generation ended, from
    ///   `.linkLost` (STATUS §4 problem 60). Nil when the connection never authenticated, and
    ///   therefore never admitted any semantic voice work to own. This controller is deliberately
    ///   retained across a control reconnect, so "the link is gone" and "*whose* link is gone" are
    ///   different questions and only the second one can safely decide what queued peer work is
    ///   discarded — see `VoiceInputMailbox.offer`.
    public func onControlLinkLost(retiredControlGeneration: Int64?) {
        guard !isShuttingDown else { return }
        lastFailure = .controlLinkLost
        mailbox.offer(.controlLinkLost(retiredControlGeneration: retiredControlGeneration), doorbell: doorbell)
    }

    /// Explicit successor authority from Connected, consumed by the reducer (ADR-020 A11).
    public func controlAuthenticated(controlGeneration: Int64) {
        guard !isShuttingDown else { return }
        mailbox.offer(
            .controlAuthenticated(
                controlGeneration: controlGeneration,
                freshVoiceSessionId: newVoiceSessionId()
            ),
            doorbell: doorbell
        )
    }

    /// A `VOICE_*` frame that has **already** passed the ADR-019 trust gate. There is no other entry
    /// point: an unauthenticated peer's frame is dropped by `ControlSessionManager` before it can reach
    /// this method (PROTOCOL §7.1).
    ///
    /// `nonisolated` and non-async so the control read loop is never blocked by it, even under a flood
    /// of frames from an authenticated peer -- `mailbox.offer` only ever touches an in-memory,
    /// lock-guarded deque/dictionary, never suspends, and never grows without bound.
    /// `controlGeneration` is carried into the input unchanged and is **never** re-derived here:
    /// reading a live generation to label a frame that has already been read is exactly ADR-024
    /// Amendment A7's defect, and a `VoiceController` that outlives a reconnect has no live generation
    /// of its own to read in any case.
    public nonisolated func submit(_ signal: VoiceSignal, controlGeneration: Int64) {
        guard mailbox.isAccepting else { return }
        mailbox.offer(
            .signalReceived(
                signal: signal,
                controlGeneration: controlGeneration,
                freshVoiceSessionId: newVoiceSessionId()
            ),
            doorbell: doorbell
        )
    }

    /// Terminal and idempotent: close admission, cancel and join owned work, then clean up once.
    /// A cancelled transport/engine await can still resume, so no task handle is discarded early.
    public func shutdown() async {
        if let shutdownTask {
            await shutdownTask.value
            return
        }
        isShuttingDown = true
        mailbox.close()
        doorbell.finish()
        routeChannel?.finish()
        attachmentTask?.cancel()
        consumerTask?.cancel()
        diagnosticsPollTask?.cancel()
        routeConsumerTask?.cancel()
        let task = Task { await self.finishShutdown() }
        shutdownTask = task
        await task.value
    }

    private func finishShutdown() async {
        await attachmentTask?.value
        attachmentTask = nil
        await consumerTask?.value
        consumerTask = nil
        await diagnosticsPollTask?.value
        diagnosticsPollTask = nil
        await routeConsumerTask?.value
        routeConsumerTask = nil
        routeChannel = nil
        // An already-reduced Stop finishes its effects before the join above; this Stop is then
        // idempotent. Other interrupted inputs leave their consent/resource ownership for cleanup.
        await apply(.stopRequested)
        transmission = IntercomTransmission.reduce(state: transmission, input: .captureOpen(false)).state
        pending.reset()
        publishDiagnostics()
        onDiagnosticsChanged = nil
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
        while !isShuttingDown && !Task.isCancelled {
            if let command = mailbox.pollIntercom() {
                await applyIntercom(command)
            } else if let next = mailbox.poll() {
                await apply(next)
            } else {
                // A wake that applied nothing is still a wake that may have something to report: an
                // input the mailbox **refused** rings this doorbell and then leaves the queues empty,
                // so without this the retired-lifetime refusal count could only ever surface on the
                // back of some *later* applied input (STATUS §4 problem 60). A counter that is only
                // observable by accident is not surfaced. Safe here and nowhere else: this is the
                // single consumer, so the state `publishDiagnostics` reads is not being mutated
                // underneath it.
                publishDiagnostics()
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
        guard !isShuttingDown else { return }
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
        let completesCleanup: Bool
        if case .stopRequested = input { completesCleanup = true } else { completesCleanup = false }
        guard !isShuttingDown || completesCleanup else { return }
        // Recorded here -- before the reducer runs, on the same ordered call the mailbox consumer
        // already applies this exact input through -- rather than from a second `Task` per engine
        // event (Issue 5). Ordering could otherwise corrupt a setup mark across a generation
        // boundary: a stale mark racing a fresh `VoiceSetupTimer.restart()` would land as that
        // restart's own first-write-wins entry, reporting a V-01 setup time that was actually the
        // previous negotiation's. Applying in the same call `apply` itself is ordered by makes that
        // structurally impossible rather than merely unlikely.
        noteFromInput(input)
        let outcome = VoiceNegotiation.reduce(state: state, input: input)
        state = outcome.state
        let rejected = outcome.actions.contains {
            if case .recordDroppedSignal = $0 { return true }
            return false
        }
        if case .signalReceived(let signal, _, _) = input,
           case .state(_, let wireState, let micMuted, let peerMode) = signal,
           !rejected {
            let onWire =
                state.peerVoiceEnabled &&
                !micMuted &&
                wireState != .idle &&
                wireState != .closed &&
                wireState != .failed
            peerTransmitting = onWire
            // The peer's own reported mode, not a guess: `VOICE_STATE.mode` is the wire vocabulary
            // PROTOCOL §7.4 already carries for exactly this (this peer's policy, not a negotiated
            // value). A continuous peer's un-muted track is not evidence of speech (blocker 1).
            peerSpeechActivityValue = peerSpeechActivity(mode: peerMode, transmittingOnWire: onWire)
        } else if !state.peerVoiceEnabled {
            peerTransmitting = false
            peerSpeechActivityValue = .unavailable
        }
        for action in outcome.actions {
            guard !isShuttingDown || completesCleanup else { break }
            await perform(action)
        }
        publishDiagnostics()
    }

    /// The setup-timing marks and named failure an engine-originated `VoiceInput` implies --
    /// derived from the already-mapped input (see `inputFor`) rather than from the raw
    /// `VoiceEngineEvent`, so no second copy of this mapping exists. A no-op for every input that
    /// is not one of these three shapes.
    private func noteFromInput(_ input: VoiceInput) {
        switch input {
        case .localOfferCreated, .localAnswerCreated:
            mark(.localDescription)
        case .remoteTrackChanged(_, let present):
            if present { mark(.remoteTrack) }
        case .mediaConnectivityChanged(_, let connected, let failed):
            if connected { mark(.mediaConnected) }
            if failed { lastFailure = .webRtcFailed }
        default:
            break
        }
    }

    // swiftlint:disable:next cyclomatic_complexity
    /// STATUS §4 problems 56, 57 and 59. The `Bool` from `VoiceSignalTransport.send` used to be
    /// discarded for every action in `perform` -- and for an offer or an answer that silently loses a
    /// **negotiation**, not just a frame.
    ///
    /// `VoiceSignalRelay.send` returns false whenever there is no authenticated writer -- which is
    /// exactly the window between a link loss and the §10 ladder reconnecting. A Start pressed in that
    /// window created an offer nothing could carry, and the table still advanced to `.negotiating`.
    /// `VoiceNegotiation.start` is idempotent against a live negotiation on purpose (two Start presses
    /// must make one offer), so `SessionCoordinator.attachVoice`'s reconnect rebuild then did nothing
    /// at all, the peer's own `negotiating` intent hit the same idempotence coming back, and voice
    /// stayed wedged for the rest of the ride segment with no error anywhere.
    ///
    /// The response resets the table to `.idle` and drops the media transport while **keeping this
    /// user's capture device open** (ARCHITECTURE §6.3/§6.4), which is precisely the state a reconnect
    /// rebuild needs to find.
    ///
    /// **It is `.negotiationSendFailed`, never `.controlLinkLost`** -- problem 57. The first
    /// implementation of this fix reused the link-loss input because the table's *reaction* is the
    /// same, but the two are not the same *event*, and `.controlLinkLost` carries two lifetime-boundary
    /// powers a send failure has no right to. It owns every `.signalReceived` queued below it
    /// (problem 50), and it occupies the single `.teardown` slot. Because `VoiceSignalRelay.send` is
    /// three `await`s deep before a byte moves -- `authenticatedWriter()`, `activeSessionId()`, then the
    /// writer -- and every one of them releases this actor, this `Bool` can arrive long after
    /// PROTOCOL §10's ladder has authenticated a **successor** generation whose own `VOICE_OFFER` is
    /// already queued (`submit` is `nonisolated` and needs none of this actor's time to enqueue one).
    /// Injecting a lifetime boundary there discarded the successor's offer and wedged voice for the
    /// ride segment, and injecting it over a pending `.stopRequested` erased a capture release
    /// `SessionCoordinator.retireSession` waits on with no timeout.
    ///
    /// `voiceSessionId` is the generation the lost frame belonged to, so the reducer can refuse to act
    /// on any other. Nil is not "unknown" -- it is an answerer's intent-to-talk, which names none.
    ///
    /// Deliberately **not** applied to `.sendCandidate`, nor to any `.sendVoiceState` other than that
    /// intent (problem 59): trickle ICE is designed to lose candidates, and every other state update
    /// either names a generation or is genuinely superseded by the next one. Neither strands a
    /// negotiation, and tearing media down for one would turn a recoverable blip into a rebuild.
    private func degradeIfUnsent(_ sent: Bool, voiceSessionId: VoiceSessionId?) {
        guard !sent, !isShuttingDown else { return }
        lastFailure = .controlLinkLost
        mailbox.offer(.negotiationSendFailed(voiceSessionId: voiceSessionId), doorbell: doorbell)
    }

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
        case .sendOffer(let id, let sdp, let owner):
            mark(.localDescription)
            // `owner` -- the lifetime the reducing transition captured -- and never a live read here
            // or in the transport (ADR-020 Amendment A9).
            degradeIfUnsent(
                await transport.send(.offer(voiceSessionId: id, sdp: sdp), controlGeneration: owner),
                voiceSessionId: id
            )
        case .sendAnswer(let id, let sdp, let owner):
            mark(.localDescription)
            degradeIfUnsent(
                await transport.send(.answer(voiceSessionId: id, sdp: sdp), controlGeneration: owner),
                voiceSessionId: id
            )
        case .sendVoiceState(let id, let wire, let micMuted, let mode, let owner):
            let sent = await transport.send(
                .state(voiceSessionId: id, state: wire, micMuted: micMuted, mode: mode),
                controlGeneration: owner
            )
            // STATUS §4 problem 59. One `VOICE_STATE` is not "carried by the next one": an answerer's
            // intent-to-talk. It names no generation because the offerer has not made one yet (§7.3),
            // it is the **only** wire effect an answerer's `start()` produces, and the table is already
            // `.negotiating` by the time it is attempted -- so losing it wedges exactly as a lost offer
            // does, and `attachVoice`'s rebuild finds a live negotiation and does nothing. Every other
            // `VOICE_STATE` (a mute, a mode, a connectivity transition, a `closed`) either names a
            // generation or is genuinely superseded by the next one, and is deliberately left alone.
            if id == nil, wire == .negotiating { degradeIfUnsent(sent, voiceSessionId: nil) }
        case .sendCandidate(let id, let candidate, let mid, let index, let owner):
            // PROTOCOL §7.6 inspects the `typ` of every candidate this side **gathers** as well as
            // every one it receives. The gathering direction is the one that would reveal a STUN
            // server had been contacted, so missing it would miss the case the check is for.
            noteCandidateType(candidate)
            _ = await transport.send(
                .iceCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index),
                controlGeneration: owner
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
        guard !isShuttingDown else { return }
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
            guard !isShuttingDown else { return }
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
        guard !isShuttingDown else { return }
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
        await publishEngineDiagnostics(allowDuringShutdown: true)
    }

    private func drainCandidates() async {
        guard let id = state.voiceSessionId else { return }
        for candidate in pending.drain(voiceSessionId: id) {
            guard !isShuttingDown else { return }
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
    ///
    /// `static` so it is callable from the `Sendable` closure `attach()` hands `engine.setEventSink`
    /// without capturing the actor. What an event implies for `VoiceSetupTimeline`/`lastFailure` is
    /// derived from the `VoiceInput` this produces, inside `apply` (`noteFromInput`) — not here, and
    /// not from a second, actor-hopping `Task` (this phase's final hardening pass, Issue 5).

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
        guard !isShuttingDown else { return }
        diagnosticsPollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, let self else { return }
                await self.refreshEngineDiagnostics()
            }
        }
    }

    private func refreshEngineDiagnostics() async {
        guard !isShuttingDown else { return }
        await engine.refreshDiagnostics()
        guard !isShuttingDown else { return }
        await publishEngineDiagnostics()
    }

    private func publishEngineDiagnostics(allowDuringShutdown: Bool = false) async {
        let snapshot = await engine.diagnostics()
        guard !isShuttingDown || allowDuringShutdown else { return }
        diagnostics.engine = snapshot
        onDiagnosticsChanged?(diagnostics)
    }

    private func publishRoute(_ snapshot: AudioRouteSnapshot) {
        guard !isShuttingDown else { return }
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
        // Discarded **and** refused: the mailbox keeps the two apart because they are the same fact
        // caught at its two different instants, and the diagnostics screen has one reason for "a peer
        // signal its own control lifetime had already outlived" (STATUS §4 problem 60).
        let retiredSignalDiscards = mailbox.discardedRetiredSignalCount + mailbox.refusedRetiredSignalCount
        // Both of these are counted by the mailbox, one layer earlier than every reason the table
        // itself produces -- so they are merged in here rather than living in `dropCounts`.
        var droppedSignals = dropCounts
        if mailboxOverflows > 0 { droppedSignals[.inputMailboxOverflow] = mailboxOverflows }
        if retiredSignalDiscards > 0 { droppedSignals[.retiredControlLifetime] = retiredSignalDiscards }
        diagnostics.droppedSignals = droppedSignals
        diagnostics.rebuildCount = rebuildCount
        diagnostics.unexpectedCandidateTypeSeen = unexpectedCandidateSeen
        diagnostics.policy = transmission.policy
        diagnostics.intercomMode = transmission.policy.intercomWireMode
        diagnostics.transmitting = transmission.transmitting
        diagnostics.peerTransmitting = peerTransmitting
        diagnostics.localSpeechActivity = transmission.speechActivity
        diagnostics.peerSpeechActivity = peerSpeechActivityValue
        // The negotiation's own owner (ADR-020 rule 23), stamped now rather than left for a
        // downstream consumer to re-derive from whatever is live when it happens to look (blocker
        // 2): `nil` here truthfully means "nothing owns this snapshot yet."
        diagnostics.controlGeneration = state.negotiationControlGeneration
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
    private var accepting = true

    var isAccepting: Bool {
        lock.lock()
        defer { lock.unlock() }
        return accepting
    }

    /// The intercom commands' own mailbox, behind the **same** lock — bounded by construction at one slot
    /// per `IntercomCommandKind`, so no burst of PTT edges, mute taps or policy switches can grow it
    /// (this phase's brief §38). Sharing the lock and the doorbell with `mailbox` is what keeps a press
    /// and its release in order without a `Task` per event (§39).
    private var intercom = IntercomCommandMailbox()

    /// Offers an intercom command and rings `doorbell`. Never suspends and never blocks its caller:
    /// there is no capacity check to fail, because there is nothing to overflow.
    func offerIntercom(_ input: IntercomInput, doorbell: ConflatedSignal) {
        lock.lock()
        guard accepting else { lock.unlock(); return }
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
        guard accepting else { lock.unlock(); return }
        let outcome = mailbox.offer(input)
        if outcome == .criticalOverflow || outcome == .terminalOverflow {
            // A well-formed, authenticated input could not be held. Forcing a link-loss-style
            // degrade -- media stops, local capture and the TLS control session both survive -- is
            // the same safe response an actual control-link blip already produces, applied one layer
            // earlier. The teardown lane always accepts.
            // `retiredControlGeneration: nil` -- an overflow is a local fact about *this* device's
            // bounded queue, not a control-lifetime boundary, so it retires nothing and owns nobody's
            // queued work (STATUS §4 problem 60). The degrade the reducer performs is identical.
            _ = mailbox.offer(.controlLinkLost(retiredControlGeneration: nil))
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

    var discardedRetiredSignalCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return mailbox.discardedRetiredSignalCount
    }

    /// See `VoiceInputMailbox.refusedRetiredSignalCount` (STATUS §4 problem 60).
    var refusedRetiredSignalCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return mailbox.refusedRetiredSignalCount
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        accepting = false
        mailbox.clear()
        intercom.clear()
    }
}
