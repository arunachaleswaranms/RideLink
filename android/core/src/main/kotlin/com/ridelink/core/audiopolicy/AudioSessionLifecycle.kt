package com.ridelink.core.audiopolicy

/**
 * Why the intercom's audio session could not be brought up, or why it went away.
 *
 * Deliberately **not** one "connection failed" bucket (this phase's brief §41). Each value names a
 * different thing for the user to do about it, and the FR-023 diagnostics screen shows the name.
 * Nothing here ends a trusted control session: an intercom failure is a voice-plane fact
 * (PROTOCOL §7.8), and the TLS session and the persisted pin both survive it.
 */
enum class VoiceFailure {
    /** `RECORD_AUDIO` / `AVAudioApplication` record permission refused. Ride continues music-only. */
    MIC_PERMISSION_DENIED,

    /** No audio endpoint at all — nothing to speak into or out of. */
    NO_AUDIO_ENDPOINT,

    /** `AudioManager` focus refused, or `AVAudioSession.setActive` threw. */
    AUDIO_SESSION_ACTIVATION_FAILED,

    /** The platform refused to move the communication route to the chosen endpoint. */
    ROUTE_SELECTION_FAILED,

    /** The audio session came up but the capture path did not. */
    CAPTURE_START_FAILED,

    /** The media stack itself failed — `PeerConnection` construction, SDP, or ICE. */
    WEBRTC_FAILED,

    /** The control plane went. Media drops; capture does **not** (ARCHITECTURE §6.3/§6.4). */
    CONTROL_LINK_LOST,

    /** A platform interruption is in force: a call, Siri, another app taking the session. */
    INTERRUPTED,

    /** The media server died and every audio object this process holds is invalid. */
    MEDIA_SERVICES_RESET,

    /**
     * A first microphone start was attempted while the app was not foreground-visible, and refused.
     * ARCHITECTURE §6.4: there is no legal way to do this, and the correct response is to tell the
     * user to bring RideLink to the front — never to retry silently from the background.
     */
    BACKGROUND_START_REFUSED,

    /** The ride foreground service could not be started (e.g. `ForegroundServiceStartNotAllowed`). */
    FOREGROUND_SERVICE_START_FAILED,

    /** There is no authenticated peer, so voice is not permitted at all (PROTOCOL §7.1). */
    SESSION_NOT_AUTHENTICATED,
}

/**
 * A route or configuration change **as observable state with a measured duration**, not as a moment
 * when every other field is quietly stale.
 *
 * ARCHITECTURE §6.2 puts a configuration switch at roughly 0.5–2 s. That figure is an *expectation
 * from documentation*, and this type exists so the real number can be recorded instead
 * (TEST_PLAN IA-03). [lastDurationUs] is a monotonic measurement of what actually happened on this
 * device; nothing here contains, defaults to, or falls back on the documented range.
 */
data class RouteTransitionState(
    val transitioning: Boolean = false,
    val startedAtMonoUs: Long? = null,
    /** How long the most recently *settled* transition took. Null until one has settled. */
    val lastDurationUs: Long? = null,
    /** How many transitions have been declared settled by a timeout rather than by the platform. */
    val timedOutCount: Int = 0,
) {
    val lastDurationMs: Double? get() = lastDurationUs?.let { it / MICROS_PER_MS }

    private companion object {
        const val MICROS_PER_MS = 1_000.0
    }
}

/**
 * The pure transition tracker. Monotonic microseconds only, supplied by the caller (CLAUDE.md
 * rule 5).
 *
 * **A timeout is failure protection, never the definition of success** (this phase's brief §15). A
 * transition settles because the platform said the route changed; [timeout] exists only so a
 * platform that never says so cannot leave `route_state: transitioning` latched for the rest of a
 * ride, and every use of it is counted so the diagnostics can say it happened.
 */
object RouteTransitionTracker {
    /** Default protection window. Generous against ARCHITECTURE §6.2's expected 0.5–2 s. */
    const val DEFAULT_TIMEOUT_US = 5_000_000L

    fun begin(
        state: RouteTransitionState,
        nowMonoUs: Long,
    ): RouteTransitionState =
        // Already transitioning: keep the original start instant, so a burst of route callbacks
        // measures one transition rather than resetting the clock and under-reporting it.
        if (state.transitioning) state else state.copy(transitioning = true, startedAtMonoUs = nowMonoUs)

    fun settle(
        state: RouteTransitionState,
        nowMonoUs: Long,
    ): RouteTransitionState {
        if (!state.transitioning) return state
        val started = state.startedAtMonoUs
        return RouteTransitionState(
            transitioning = false,
            startedAtMonoUs = null,
            lastDurationUs = if (started == null) state.lastDurationUs else (nowMonoUs - started).coerceAtLeast(0),
            timedOutCount = state.timedOutCount,
        )
    }

    /**
     * @return the settled state if [timeoutUs] has elapsed since the transition began, otherwise
     *   [state] unchanged. The caller polls; this decides.
     */
    fun timeout(
        state: RouteTransitionState,
        nowMonoUs: Long,
        timeoutUs: Long = DEFAULT_TIMEOUT_US,
    ): RouteTransitionState {
        val started = state.startedAtMonoUs
        val elapsed = state.transitioning && started != null && nowMonoUs - started >= timeoutUs
        return if (elapsed) settle(state, nowMonoUs).copy(timedOutCount = state.timedOutCount + 1) else state
    }
}

/**
 * What the platform audio layer is doing, as a value. Drives both `AndroidVoiceAudioSession` and
 * `IosVoiceAudioSession`.
 *
 * [generation] is the same strict-generation philosophy Phase 2a applied to the media stack
 * (ADR-020 Amendment A2), applied here to the *audio session*: every platform callback carries the
 * generation it was registered under, and one from an obsolete generation is inert. Without it, a
 * `routeChangeNotification` still in flight from before a media-services reset can overwrite the
 * state of the session that replaced it.
 */
data class AudioSessionState(
    val open: Boolean = false,
    val generation: Int = 0,
    val interrupted: Boolean = false,
    /**
     * An interruption ended and the platform said the session **should** be resumed. Distinct from
     * "ended" alone: iOS reports `.shouldResume` as an option, and an interruption that ends without
     * it means stay inactive (ARCHITECTURE §6.2, TEST_PLAN IA-06).
     */
    val awaitingResume: Boolean = false,
    val transition: RouteTransitionState = RouteTransitionState(),
    val lastFailure: VoiceFailure? = null,
)

/** What the platform audio layer is told about. Every event carries its [generation]. */
sealed class AudioSessionEvent {
    abstract val generation: Int

    /** The duplex configuration was applied and capture is open. Begins a route transition. */
    data class Opened(
        override val generation: Int,
        val atMonoUs: Long,
    ) : AudioSessionEvent()

    /** The session was returned to the music-only configuration. Begins a route transition. */
    data class Closed(
        override val generation: Int,
        val atMonoUs: Long,
    ) : AudioSessionEvent()

    /**
     * The platform reported a route change. This both *begins* a transition (the route is moving)
     * and, for the reason the platform gives, is the signal that a transition we started has
     * settled — which is why [settles] exists rather than a timer.
     */
    data class RouteChanged(
        override val generation: Int,
        val reason: AudioRouteChangeReason,
        val atMonoUs: Long,
        /**
         * True when this callback is the platform confirming the change **we** asked for, so the
         * transition is over. False for a change originating outside the app — a device being
         * unplugged mid-ride — which starts a transition of its own.
         */
        val settles: Boolean,
    ) : AudioSessionEvent()

    data class InterruptionBegan(
        override val generation: Int,
        val atMonoUs: Long,
    ) : AudioSessionEvent()

    data class InterruptionEnded(
        override val generation: Int,
        val shouldResume: Boolean,
        val atMonoUs: Long,
    ) : AudioSessionEvent()

    /** `AVAudioSession.mediaServicesWereResetNotification`, or its Android equivalent. */
    data class MediaServicesReset(
        override val generation: Int,
        val atMonoUs: Long,
    ) : AudioSessionEvent()

    /** The caller's protection poll. Never the definition of success — see [RouteTransitionTracker]. */
    data class TransitionTimeoutCheck(
        override val generation: Int,
        val atMonoUs: Long,
        val timeoutUs: Long = RouteTransitionTracker.DEFAULT_TIMEOUT_US,
    ) : AudioSessionEvent()

    data class Failed(
        override val generation: Int,
        val failure: VoiceFailure,
    ) : AudioSessionEvent()
}

/** What the platform layer must do in response. */
sealed class AudioSessionAction {
    /** Publish an `AUDIO_STATE`-shaped snapshot with this route state (PROTOCOL §4.4). */
    data class PublishSnapshot(
        val routeState: RouteState,
    ) : AudioSessionAction()

    /** Re-activate the platform session after an interruption that said `shouldResume`. */
    object Reactivate : AudioSessionAction()

    /**
     * Every audio object this process holds is invalid: drop them and rebuild from scratch under a
     * **new** generation, so a callback still in flight from the old one is inert.
     */
    object RebuildAfterReset : AudioSessionAction()

    data class ReportFailure(
        val failure: VoiceFailure,
    ) : AudioSessionAction()
}

data class AudioSessionOutcome(
    val state: AudioSessionState,
    val actions: List<AudioSessionAction>,
)

/**
 * The platform-audio lifecycle, as a pure `(state, event) -> (state, actions)` reducer, shared by
 * both platforms.
 *
 * It exists because neither `AVAudioSession` nor `AudioManager` can be executed off-device
 * (docs/STATUS.md §4 problem 23), so **every decision either of them would make is moved here**,
 * where a laptop test on both platforms can exhaust it: the `stable -> transitioning -> stable`
 * sequence with monotonic revisions, `shouldResume` versus not, a media-services reset invalidating
 * the old generation, and a stale callback from a superseded generation doing nothing at all.
 *
 * What is left in the platform classes is API calls. That division is the direct lesson of ADR-019
 * and of docs/STATUS.md §4 problem 20.
 */
object AudioSessionLifecycle {
    fun reduce(
        state: AudioSessionState,
        event: AudioSessionEvent,
    ): AudioSessionOutcome {
        // The strict generation guard, applied to the audio session rather than the media stack
        // (ADR-020 Amendment A2's rule, ADR-021 §5). A callback naming any generation other than the
        // current one is inert — including a real one that used to be current.
        if (event.generation != state.generation) return AudioSessionOutcome(state, emptyList())
        return when (event) {
            is AudioSessionEvent.Opened -> opened(state, event.atMonoUs)
            is AudioSessionEvent.Closed -> closed(state, event.atMonoUs)
            is AudioSessionEvent.RouteChanged -> routeChanged(state, event)
            is AudioSessionEvent.InterruptionBegan -> interruptionBegan(state)
            is AudioSessionEvent.InterruptionEnded -> interruptionEnded(state, event.shouldResume)
            is AudioSessionEvent.MediaServicesReset -> mediaServicesReset(state, event.atMonoUs)
            is AudioSessionEvent.TransitionTimeoutCheck -> timeoutCheck(state, event)
            is AudioSessionEvent.Failed ->
                AudioSessionOutcome(
                    state.copy(lastFailure = event.failure),
                    listOf(AudioSessionAction.ReportFailure(event.failure), AudioSessionAction.PublishSnapshot(routeStateOf(state))),
                )
        }
    }

    private fun opened(
        state: AudioSessionState,
        nowMonoUs: Long,
    ): AudioSessionOutcome {
        // ARCHITECTURE §6.2: switching configuration is an audible route change, so it is announced
        // as `transitioning` and superseded by `stable` when the platform confirms it — never
        // published as a fait accompli, and never settled by a sleep.
        val transition = RouteTransitionTracker.begin(state.transition, nowMonoUs)
        return AudioSessionOutcome(
            state.copy(open = true, transition = transition, lastFailure = null),
            listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)),
        )
    }

    private fun closed(
        state: AudioSessionState,
        nowMonoUs: Long,
    ): AudioSessionOutcome {
        val transition = RouteTransitionTracker.begin(state.transition, nowMonoUs)
        return AudioSessionOutcome(
            state.copy(open = false, interrupted = false, awaitingResume = false, transition = transition),
            listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)),
        )
    }

    private fun routeChanged(
        state: AudioSessionState,
        event: AudioSessionEvent.RouteChanged,
    ): AudioSessionOutcome =
        if (event.settles) {
            val transition = RouteTransitionTracker.settle(state.transition, event.atMonoUs)
            AudioSessionOutcome(
                state.copy(transition = transition),
                listOf(AudioSessionAction.PublishSnapshot(RouteState.STABLE)),
            )
        } else {
            // A change we did not ask for — a device unplugged, a new one appearing. It is the
            // *start* of a transition, and the settling callback for it arrives separately.
            val transition = RouteTransitionTracker.begin(state.transition, event.atMonoUs)
            AudioSessionOutcome(
                state.copy(transition = transition),
                listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)),
            )
        }

    private fun interruptionBegan(state: AudioSessionState): AudioSessionOutcome =
        // Not a duplicate session and not a rebuild: an interruption is a *route* fact (ADR-016),
        // and conflating it with a media failure would make "a call came in" indistinguishable from
        // "the peer connection died" (PROTOCOL §7.4).
        AudioSessionOutcome(
            state.copy(interrupted = true, awaitingResume = false, lastFailure = VoiceFailure.INTERRUPTED),
            listOf(AudioSessionAction.PublishSnapshot(routeStateOf(state))),
        )

    private fun interruptionEnded(
        state: AudioSessionState,
        shouldResume: Boolean,
    ): AudioSessionOutcome =
        if (shouldResume) {
            AudioSessionOutcome(
                state.copy(interrupted = false, awaitingResume = false, lastFailure = null),
                listOf(AudioSessionAction.Reactivate, AudioSessionAction.PublishSnapshot(routeStateOf(state))),
            )
        } else {
            // ARCHITECTURE §6.2 / TEST_PLAN IA-06: an interruption that ends without `shouldResume`
            // leaves the session inactive. Reactivating anyway is how an app ends up fighting the
            // platform for a route the user gave to something else.
            AudioSessionOutcome(
                state.copy(interrupted = true, awaitingResume = true),
                listOf(AudioSessionAction.PublishSnapshot(routeStateOf(state))),
            )
        }

    private fun mediaServicesReset(
        state: AudioSessionState,
        nowMonoUs: Long,
    ): AudioSessionOutcome =
        // The generation moves here, which is the whole point: every callback already registered
        // against the old one becomes inert by comparison rather than by hoping the timing lines up.
        AudioSessionOutcome(
            AudioSessionState(
                open = false,
                generation = state.generation + 1,
                interrupted = false,
                awaitingResume = false,
                // Timed from when the app *learned* of the reset, which is the only instant it
                // observed: the media server died at some earlier moment nothing measured, and
                // pretending otherwise would fabricate the IA-03 number this type exists to record.
                transition = RouteTransitionTracker.begin(RouteTransitionState(), nowMonoUs),
                lastFailure = VoiceFailure.MEDIA_SERVICES_RESET,
            ),
            listOf(
                AudioSessionAction.RebuildAfterReset,
                AudioSessionAction.ReportFailure(VoiceFailure.MEDIA_SERVICES_RESET),
                AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING),
            ),
        )

    private fun timeoutCheck(
        state: AudioSessionState,
        event: AudioSessionEvent.TransitionTimeoutCheck,
    ): AudioSessionOutcome {
        val transition = RouteTransitionTracker.timeout(state.transition, event.atMonoUs, event.timeoutUs)
        if (transition == state.transition) return AudioSessionOutcome(state, emptyList())
        return AudioSessionOutcome(
            state.copy(transition = transition),
            listOf(AudioSessionAction.PublishSnapshot(RouteState.STABLE)),
        )
    }

    private fun routeStateOf(state: AudioSessionState): RouteState =
        if (state.transition.transitioning) RouteState.TRANSITIONING else RouteState.STABLE
}
