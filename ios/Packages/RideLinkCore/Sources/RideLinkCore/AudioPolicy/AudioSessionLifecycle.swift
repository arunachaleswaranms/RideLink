import Foundation

/// Why the intercom's audio session could not be brought up, or why it went away.
///
/// Deliberately **not** one "connection failed" bucket (this phase's brief §41). Each case names a
/// different thing for the user to do about it, and the FR-023 diagnostics screen shows the name.
/// Nothing here ends a trusted control session: an intercom failure is a voice-plane fact
/// (PROTOCOL §7.8), and the TLS session and the persisted pin both survive it.
public enum VoiceFailure: String, Sendable, Equatable, CaseIterable {
    /// `RECORD_AUDIO` / `AVAudioApplication` record permission refused. Ride continues music-only.
    case micPermissionDenied = "MIC_PERMISSION_DENIED"
    /// No audio endpoint at all — nothing to speak into or out of.
    case noAudioEndpoint = "NO_AUDIO_ENDPOINT"
    /// `AudioManager` focus refused, or `AVAudioSession.setActive` threw.
    case audioSessionActivationFailed = "AUDIO_SESSION_ACTIVATION_FAILED"
    /// The platform refused to move the communication route to the chosen endpoint.
    case routeSelectionFailed = "ROUTE_SELECTION_FAILED"
    /// The audio session came up but the capture path did not.
    case captureStartFailed = "CAPTURE_START_FAILED"
    /// The media stack itself failed — `RTCPeerConnection` construction, SDP, or ICE.
    case webRtcFailed = "WEBRTC_FAILED"
    /// The control plane went. Media drops; capture does **not** (ARCHITECTURE §6.3/§6.4).
    case controlLinkLost = "CONTROL_LINK_LOST"
    /// A platform interruption is in force: a call, Siri, another app taking the session.
    case interrupted = "INTERRUPTED"
    /// The media server died and every audio object this process holds is invalid.
    case mediaServicesReset = "MEDIA_SERVICES_RESET"
    /// A first microphone start was attempted while the app was not foreground-visible, and refused.
    /// ARCHITECTURE §6.4: there is no legal way to do this, and the correct response is to tell the user
    /// to bring RideLink to the front — never to retry silently from the background.
    case backgroundStartRefused = "BACKGROUND_START_REFUSED"
    /// The ride foreground service could not be started (Android's `ForegroundServiceStartNotAllowed`).
    case foregroundServiceStartFailed = "FOREGROUND_SERVICE_START_FAILED"
    /// There is no authenticated peer, so voice is not permitted at all (PROTOCOL §7.1).
    case sessionNotAuthenticated = "SESSION_NOT_AUTHENTICATED"
}

/// A route or configuration change **as observable state with a measured duration**, not as a moment when
/// every other field is quietly stale.
///
/// ARCHITECTURE §6.2 puts a configuration switch at roughly 0.5–2 s. That figure is an *expectation from
/// documentation*, and this type exists so the real number can be recorded instead (TEST_PLAN IA-03).
/// `lastDurationUs` is a monotonic measurement of what actually happened on this device; nothing here
/// contains, defaults to, or falls back on the documented range.
public struct RouteTransitionState: Sendable, Equatable {
    public var transitioning: Bool
    public var startedAtMonoUs: Int64?
    /// How long the most recently *settled* transition took. Nil until one has settled.
    public var lastDurationUs: Int64?
    /// How many transitions have been declared settled by a timeout rather than by the platform.
    public var timedOutCount: Int

    public init(
        transitioning: Bool = false,
        startedAtMonoUs: Int64? = nil,
        lastDurationUs: Int64? = nil,
        timedOutCount: Int = 0
    ) {
        self.transitioning = transitioning
        self.startedAtMonoUs = startedAtMonoUs
        self.lastDurationUs = lastDurationUs
        self.timedOutCount = timedOutCount
    }

    public var lastDurationMs: Double? {
        lastDurationUs.map { Double($0) / Self.microsPerMs }
    }

    private static let microsPerMs = 1_000.0
}

/// The pure transition tracker. Monotonic microseconds only, supplied by the caller (CLAUDE.md rule 5).
///
/// **A timeout is failure protection, never the definition of success** (this phase's brief §15). A
/// transition settles because the platform said the route changed; `timeout` exists only so a platform
/// that never says so cannot leave `route_state: transitioning` latched for the rest of a ride, and every
/// use of it is counted so the diagnostics can say it happened.
public enum RouteTransitionTracker {
    /// Default protection window. Generous against ARCHITECTURE §6.2's expected 0.5–2 s.
    public static let defaultTimeoutUs: Int64 = 5_000_000

    public static func begin(_ state: RouteTransitionState, nowMonoUs: Int64) -> RouteTransitionState {
        // Already transitioning: keep the original start instant, so a burst of route callbacks measures
        // one transition rather than resetting the clock and under-reporting it.
        guard !state.transitioning else { return state }
        var next = state
        next.transitioning = true
        next.startedAtMonoUs = nowMonoUs
        return next
    }

    public static func settle(_ state: RouteTransitionState, nowMonoUs: Int64) -> RouteTransitionState {
        guard state.transitioning else { return state }
        let duration = state.startedAtMonoUs.map { max(0, nowMonoUs - $0) } ?? state.lastDurationUs
        return RouteTransitionState(
            transitioning: false,
            startedAtMonoUs: nil,
            lastDurationUs: duration,
            timedOutCount: state.timedOutCount
        )
    }

    /// - Returns: the settled state if `timeoutUs` has elapsed since the transition began, otherwise
    ///   `state` unchanged. The caller polls; this decides.
    public static func timeout(
        _ state: RouteTransitionState,
        nowMonoUs: Int64,
        timeoutUs: Int64 = defaultTimeoutUs
    ) -> RouteTransitionState {
        guard state.transitioning, let started = state.startedAtMonoUs else { return state }
        guard nowMonoUs - started >= timeoutUs else { return state }
        var settled = settle(state, nowMonoUs: nowMonoUs)
        settled.timedOutCount = state.timedOutCount + 1
        return settled
    }
}

/// What the platform audio layer is doing, as a value. Drives both `AndroidVoiceAudioSession` and
/// `IosVoiceAudioSession`.
///
/// `generation` is the same strict-generation philosophy Phase 2a applied to the media stack (ADR-020
/// Amendment A2), applied here to the *audio session*: every platform callback carries the generation it
/// was registered under, and one from an obsolete generation is inert. Without it, a
/// `routeChangeNotification` still in flight from before a media-services reset can overwrite the state
/// of the session that replaced it.
public struct AudioSessionState: Sendable, Equatable {
    public var open: Bool
    public var generation: Int
    public var interrupted: Bool
    /// An interruption ended and the platform said the session **should** be resumed. Distinct from
    /// "ended" alone: iOS reports `.shouldResume` as an option, and an interruption that ends without it
    /// means stay inactive (ARCHITECTURE §6.2, TEST_PLAN IA-06).
    public var awaitingResume: Bool
    public var transition: RouteTransitionState
    public var lastFailure: VoiceFailure?

    public init(
        open: Bool = false,
        generation: Int = 0,
        interrupted: Bool = false,
        awaitingResume: Bool = false,
        transition: RouteTransitionState = RouteTransitionState(),
        lastFailure: VoiceFailure? = nil
    ) {
        self.open = open
        self.generation = generation
        self.interrupted = interrupted
        self.awaitingResume = awaitingResume
        self.transition = transition
        self.lastFailure = lastFailure
    }
}

/// What the platform audio layer is told about. Every event carries its generation.
public enum AudioSessionEvent: Sendable, Equatable {
    /// The duplex configuration was applied and capture is open. Confirms the session, but — since
    /// this phase's final hardening pass — does **not** itself begin the route transition; see
    /// `.openRequested`.
    case opened(generation: Int, atMonoUs: Int64)
    /// Mirrors `.opened` for the closing direction; see `.closeRequested`.
    case closed(generation: Int, atMonoUs: Int64)
    /// The platform is *about to be asked* to open the duplex session. Begins the route transition
    /// on its own, before `.opened` ever runs.
    ///
    /// This phase's final hardening pass, Issue 1: the platform call `.opened` used to precede
    /// (`AVAudioSession.setActive` / `AudioManager.setCommunicationDevice`) can itself produce the
    /// confirming callback synchronously, or very shortly after returning. Without a transition
    /// already begun for that callback to settle, `RouteTransitionTracker.settle` silently drops
    /// the confirmation — it only ever acts when `RouteTransitionState.transitioning` is already
    /// true. `.opened` itself no longer begins a transition at all (only this event does), which is
    /// what lets the two be applied safely in sequence whether the confirming callback lands before
    /// or after `.opened` runs: a settle that already happened between the two is never resurrected
    /// into a fresh, spurious `.transitioning` that nothing would ever confirm.
    case openRequested(generation: Int, atMonoUs: Int64)
    /// Mirrors `.openRequested` for the closing direction, against the same race in `.closed`.
    case closeRequested(generation: Int, atMonoUs: Int64)
    /// The platform call `.openRequested` began failed before it could complete — the session was
    /// never actually handed to the platform. Settles the transition immediately, rather than
    /// leaving it to `.transitionTimeoutCheck` to notice five seconds later, and leaves
    /// `AudioSessionState.open` false.
    case openAborted(generation: Int, atMonoUs: Int64, failure: VoiceFailure)
    /// The platform reported a route change. `settles` is true when this callback is the platform
    /// confirming the change **we** asked for, so the transition is over; false for a change originating
    /// outside the app — a device being unplugged mid-ride — which starts a transition of its own.
    case routeChanged(generation: Int, reason: AudioRouteChangeReason, atMonoUs: Int64, settles: Bool)
    case interruptionBegan(generation: Int, atMonoUs: Int64)
    case interruptionEnded(generation: Int, shouldResume: Bool, atMonoUs: Int64)
    /// `AVAudioSession.mediaServicesWereResetNotification`, or its Android equivalent.
    case mediaServicesReset(generation: Int, atMonoUs: Int64)
    /// The caller's protection poll. Never the definition of success — see `RouteTransitionTracker`.
    case transitionTimeoutCheck(generation: Int, atMonoUs: Int64, timeoutUs: Int64)
    case failed(generation: Int, failure: VoiceFailure)

    public var generation: Int {
        switch self {
        case .opened(let generation, _),
             .closed(let generation, _),
             .openRequested(let generation, _),
             .closeRequested(let generation, _),
             .openAborted(let generation, _, _),
             .routeChanged(let generation, _, _, _),
             .interruptionBegan(let generation, _),
             .interruptionEnded(let generation, _, _),
             .mediaServicesReset(let generation, _),
             .transitionTimeoutCheck(let generation, _, _),
             .failed(let generation, _):
            return generation
        }
    }
}

/// What the platform layer must do in response.
public enum AudioSessionAction: Sendable, Equatable {
    /// Publish an `AUDIO_STATE`-shaped snapshot with this route state (PROTOCOL §4.4).
    case publishSnapshot(routeState: RouteState)
    /// Re-activate the platform session after an interruption that said `shouldResume`.
    case reactivate
    /// Every audio object this process holds is invalid: drop them and rebuild from scratch under a
    /// **new** generation, so a callback still in flight from the old one is inert.
    case rebuildAfterReset
    case reportFailure(VoiceFailure)
}

public struct AudioSessionOutcome: Sendable, Equatable {
    public let state: AudioSessionState
    public let actions: [AudioSessionAction]

    public init(state: AudioSessionState, actions: [AudioSessionAction]) {
        self.state = state
        self.actions = actions
    }
}

/// The platform-audio lifecycle, as a pure `(state, event) -> (state, actions)` reducer, shared by both
/// platforms.
///
/// It exists because neither `AVAudioSession` nor `AudioManager` can be executed off-device
/// (docs/STATUS.md §4 problem 23), so **every decision either of them would make is moved here**, where a
/// laptop test on both platforms can exhaust it: the `stable -> transitioning -> stable` sequence with
/// monotonic revisions, `shouldResume` versus not, a media-services reset invalidating the old
/// generation, and a stale callback from a superseded generation doing nothing at all.
///
/// What is left in the platform classes is API calls. That division is the direct lesson of ADR-019 and of
/// docs/STATUS.md §4 problem 20.
public enum AudioSessionLifecycle {
    public static func reduce(state: AudioSessionState, event: AudioSessionEvent) -> AudioSessionOutcome {
        // The strict generation guard, applied to the audio session rather than the media stack (ADR-020
        // Amendment A2's rule, ADR-021 §5). A callback naming any generation other than the current one is
        // inert — including a real one that used to be current.
        guard event.generation == state.generation else {
            return AudioSessionOutcome(state: state, actions: [])
        }
        switch event {
        case .opened:
            return opened(state)
        case .closed:
            return closed(state)
        case .openRequested(_, let atMonoUs), .closeRequested(_, let atMonoUs):
            return beginTransition(state, nowMonoUs: atMonoUs)
        case .openAborted(_, let atMonoUs, let failure):
            return openAborted(state, nowMonoUs: atMonoUs, failure: failure)
        case .routeChanged(_, _, let atMonoUs, let settles):
            return routeChanged(state, nowMonoUs: atMonoUs, settles: settles)
        case .interruptionBegan:
            return interruptionBegan(state)
        case .interruptionEnded(_, let shouldResume, _):
            return interruptionEnded(state, shouldResume: shouldResume)
        case .mediaServicesReset(_, let atMonoUs):
            return mediaServicesReset(state, nowMonoUs: atMonoUs)
        case .transitionTimeoutCheck(_, let atMonoUs, let timeoutUs):
            return timeoutCheck(state, nowMonoUs: atMonoUs, timeoutUs: timeoutUs)
        case .failed(_, let failure):
            var next = state
            next.lastFailure = failure
            return AudioSessionOutcome(
                state: next,
                actions: [.reportFailure(failure), .publishSnapshot(routeState: routeState(of: state))]
            )
        }
    }

    /// Confirms the platform actually took the session; **does not** begin the transition itself any
    /// more. That is `.openRequested`'s job, applied *before* the platform call that produces this
    /// event (this phase's final hardening pass, Issue 1) — beginning it again here as well would
    /// resurrect a transition a synchronous confirming callback landing between the two events may
    /// already have settled, publishing a spurious `.transitioning` that nothing will ever confirm,
    /// stuck until the failure-protection timeout. `routeState(of:)` reports whatever the transition
    /// actually is right now, whether still transitioning (the ordinary case) or already settled
    /// (the race this event exists to survive).
    private static func opened(_ state: AudioSessionState) -> AudioSessionOutcome {
        var next = state
        next.open = true
        next.lastFailure = nil
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: routeState(of: state))])
    }

    /// Mirrors `opened(_:)` for the closing direction — `.closeRequested` begins it.
    private static func closed(_ state: AudioSessionState) -> AudioSessionOutcome {
        var next = state
        next.open = false
        next.interrupted = false
        next.awaitingResume = false
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: routeState(of: state))])
    }

    /// `.openRequested` and `.closeRequested` both do exactly this and nothing else: begin the
    /// transition, before the platform call either request precedes has a chance to confirm it out
    /// from under a not-yet-begun state (this phase's hardening pass, Issue 1). Neither touches
    /// `AudioSessionState.open` — that stays `.opened`'s and `.closed`'s job, applied right after
    /// the platform call actually runs.
    private static func beginTransition(_ state: AudioSessionState, nowMonoUs: Int64) -> AudioSessionOutcome {
        var next = state
        next.transition = RouteTransitionTracker.begin(state.transition, nowMonoUs: nowMonoUs)
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: .transitioning)])
    }

    private static func openAborted(
        _ state: AudioSessionState,
        nowMonoUs: Int64,
        failure: VoiceFailure
    ) -> AudioSessionOutcome {
        var next = state
        next.open = false
        next.transition = RouteTransitionTracker.settle(state.transition, nowMonoUs: nowMonoUs)
        next.lastFailure = failure
        return AudioSessionOutcome(
            state: next,
            actions: [.reportFailure(failure), .publishSnapshot(routeState: .stable)]
        )
    }

    private static func routeChanged(
        _ state: AudioSessionState,
        nowMonoUs: Int64,
        settles: Bool
    ) -> AudioSessionOutcome {
        var next = state
        if settles {
            next.transition = RouteTransitionTracker.settle(state.transition, nowMonoUs: nowMonoUs)
            return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: .stable)])
        }
        // A change we did not ask for — a device unplugged, a new one appearing. It is the *start* of a
        // transition, and the settling callback for it arrives separately.
        next.transition = RouteTransitionTracker.begin(state.transition, nowMonoUs: nowMonoUs)
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: .transitioning)])
    }

    private static func interruptionBegan(_ state: AudioSessionState) -> AudioSessionOutcome {
        // Not a duplicate session and not a rebuild: an interruption is a *route* fact (ADR-016), and
        // conflating it with a media failure would make "a call came in" indistinguishable from "the peer
        // connection died" (PROTOCOL §7.4).
        var next = state
        next.interrupted = true
        next.awaitingResume = false
        next.lastFailure = .interrupted
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: routeState(of: state))])
    }

    private static func interruptionEnded(_ state: AudioSessionState, shouldResume: Bool) -> AudioSessionOutcome {
        var next = state
        if shouldResume {
            next.interrupted = false
            next.awaitingResume = false
            next.lastFailure = nil
            return AudioSessionOutcome(
                state: next,
                actions: [.reactivate, .publishSnapshot(routeState: routeState(of: state))]
            )
        }
        // ARCHITECTURE §6.2 / TEST_PLAN IA-06: an interruption that ends without `shouldResume` leaves the
        // session inactive. Reactivating anyway is how an app ends up fighting the platform for a route the
        // user gave to something else.
        next.interrupted = true
        next.awaitingResume = true
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: routeState(of: state))])
    }

    private static func mediaServicesReset(_ state: AudioSessionState, nowMonoUs: Int64) -> AudioSessionOutcome {
        // The generation moves here, which is the whole point: every callback already registered against
        // the old one becomes inert by comparison rather than by hoping the timing lines up.
        //
        // The transition is timed from when the app *learned* of the reset, which is the only instant it
        // observed: the media server died at some earlier moment nothing measured, and pretending
        // otherwise would fabricate the IA-03 number this type exists to record.
        let next = AudioSessionState(
            open: false,
            generation: state.generation + 1,
            interrupted: false,
            awaitingResume: false,
            transition: RouteTransitionTracker.begin(RouteTransitionState(), nowMonoUs: nowMonoUs),
            lastFailure: .mediaServicesReset
        )
        return AudioSessionOutcome(
            state: next,
            actions: [
                .rebuildAfterReset,
                .reportFailure(.mediaServicesReset),
                .publishSnapshot(routeState: .transitioning),
            ]
        )
    }

    private static func timeoutCheck(
        _ state: AudioSessionState,
        nowMonoUs: Int64,
        timeoutUs: Int64
    ) -> AudioSessionOutcome {
        let transition = RouteTransitionTracker.timeout(state.transition, nowMonoUs: nowMonoUs, timeoutUs: timeoutUs)
        guard transition != state.transition else { return AudioSessionOutcome(state: state, actions: []) }
        var next = state
        next.transition = transition
        return AudioSessionOutcome(state: next, actions: [.publishSnapshot(routeState: .stable)])
    }

    private static func routeState(of state: AudioSessionState) -> RouteState {
        state.transition.transitioning ? .transitioning : .stable
    }
}
