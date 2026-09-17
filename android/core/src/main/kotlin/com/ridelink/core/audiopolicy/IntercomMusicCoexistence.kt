package com.ridelink.core.audiopolicy

/**
 * The one pure decision table for temporary intercom/music effects.
 *
 * It owns neither player nor capture resources. It decides only the temporary coexistence layer:
 * a multiplicative music gain, or a pause that is explicitly distinguished from the user's own
 * pause. Platform drivers execute [CoexistenceAction]s through the one existing music coordinator.
 */
data class CoexistenceState(
    val generation: Long = 0,
    val active: Boolean = false,
    val policy: IntercomPolicy = IntercomPolicy.DEFAULT,
    val voiceAvailable: Boolean = false,
    val localTransmitting: Boolean = false,
    val peerTransmitting: Boolean = false,
    val musicAvailable: Boolean = true,
    val trackToken: String? = null,
    val musicPlaying: Boolean = false,
    val trackEnded: Boolean = false,
    val userPaused: Boolean = false,
    /** The user's durable/base app volume. Coexistence never mutates this value. */
    val baseVolumePermille: Int = FULL_GAIN_PERMILLE,
    /** The last coexistence target, not a replacement for [baseVolumePermille]. */
    val targetVolumePermille: Int = FULL_GAIN_PERMILLE,
    val pausedByVoice: Boolean = false,
    val pausedTrackToken: String? = null,
    val routeState: RouteState = RouteState.STABLE,
    val interrupted: Boolean = false,
    val routeTransitionTimedOut: Boolean = false,
    val syncAvailable: Boolean = true,
    val fallback: CoexistenceFallback = CoexistenceFallback.NONE,
    val staleInputCount: Int = 0,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(baseVolumePermille in MIN_GAIN_PERMILLE..FULL_GAIN_PERMILLE) { "base volume must be 0...1000" }
        require(targetVolumePermille in MIN_GAIN_PERMILLE..FULL_GAIN_PERMILLE) { "target volume must be 0...1000" }
    }

    val voiceActive: Boolean
        get() = active && policy.intercomEnabled && voiceAvailable && !interrupted && (localTransmitting || peerTransmitting)

    companion object {
        const val MIN_GAIN_PERMILLE = 0
        const val FULL_GAIN_PERMILLE = 1_000
    }
}

enum class CoexistenceFallback {
    NONE,
    VOICE_UNAVAILABLE,
    MUSIC_UNAVAILABLE,
    ROUTE_TRANSITION_TIMEOUT,
    INTERRUPTED,
    SYNC_UNAVAILABLE,
}

sealed class CoexistenceInput {
    data class LifetimeStarted(
        val generation: Long,
        val policy: IntercomPolicy,
    ) : CoexistenceInput()

    data class LifetimeEnded(
        val generation: Long,
    ) : CoexistenceInput()

    data class PolicySelected(
        val generation: Long,
        val policy: IntercomPolicy,
    ) : CoexistenceInput()

    data class VoiceChanged(
        val generation: Long,
        val available: Boolean,
        val localTransmitting: Boolean,
        val peerTransmitting: Boolean,
    ) : CoexistenceInput()

    data class MusicChanged(
        val generation: Long,
        val available: Boolean,
        val trackToken: String?,
        val playing: Boolean,
        val ended: Boolean,
    ) : CoexistenceInput()

    data class UserPlaybackIntent(
        val generation: Long,
        val playing: Boolean,
    ) : CoexistenceInput()

    data class BaseVolumeChanged(
        val generation: Long,
        val volumePermille: Int,
    ) : CoexistenceInput() {
        init {
            require(volumePermille in CoexistenceState.MIN_GAIN_PERMILLE..CoexistenceState.FULL_GAIN_PERMILLE) {
                "base volume must be 0...1000"
            }
        }
    }

    data class RouteChanged(
        val generation: Long,
        val routeState: RouteState,
        val interrupted: Boolean,
        val transitionTimedOut: Boolean = false,
    ) : CoexistenceInput()

    data class SyncAvailabilityChanged(
        val generation: Long,
        val available: Boolean,
    ) : CoexistenceInput()
}

sealed class CoexistenceAction {
    /** Ramp to an effective volume. The user's [CoexistenceState.baseVolumePermille] is untouched. */
    data class RampMusicVolume(
        val targetPermille: Int,
        val durationMs: Long = RAMP_DURATION_MS,
    ) : CoexistenceAction()

    /** A local temporary suppression, never an authoritative Phase 5 PAUSE command. */
    data class PauseMusicForVoice(
        val trackToken: String,
    ) : CoexistenceAction()

    /** Resume only the exact track [PauseMusicForVoice] suppressed. */
    data class ResumeMusicAfterVoice(
        val trackToken: String,
    ) : CoexistenceAction()

    companion object {
        /** FR-016 / ARCHITECTURE §6.1–6.2's deterministic 150–250 ms envelope. */
        const val RAMP_DURATION_MS = 200L
    }
}

data class CoexistenceOutcome(
    val state: CoexistenceState,
    val actions: List<CoexistenceAction>,
)

/** Pure `(state, input) -> (state, actions)` owner of all coexistence policy decisions. */
object IntercomMusicCoexistence {
    fun reduce(
        state: CoexistenceState,
        input: CoexistenceInput,
    ): CoexistenceOutcome {
        if (input !is CoexistenceInput.LifetimeStarted && generationOf(input) != state.generation) {
            return CoexistenceOutcome(state.copy(staleInputCount = state.staleInputCount + 1), emptyList())
        }

        val before = state
        val applied = apply(state, input)
        return reconcile(before, applied, forceGain = forcesGainReassertion(before, input))
    }

    // One exhaustive branch per input is the decision table; splitting it would duplicate dispatch.
    @Suppress("CyclomaticComplexMethod")
    private fun apply(
        state: CoexistenceState,
        input: CoexistenceInput,
    ): CoexistenceState =
        when (input) {
            is CoexistenceInput.LifetimeStarted -> {
                if (input.generation <= state.generation) {
                    state.copy(staleInputCount = state.staleInputCount + 1)
                } else {
                    state.copy(
                        generation = input.generation,
                        active = true,
                        policy = input.policy,
                        voiceAvailable = false,
                        localTransmitting = false,
                        peerTransmitting = false,
                        interrupted = false,
                        routeTransitionTimedOut = false,
                        fallback = CoexistenceFallback.NONE,
                    )
                }
            }
            is CoexistenceInput.LifetimeEnded ->
                state.copy(
                    active = false,
                    voiceAvailable = false,
                    localTransmitting = false,
                    peerTransmitting = false,
                    interrupted = false,
                    routeTransitionTimedOut = false,
                    fallback = CoexistenceFallback.NONE,
                )
            is CoexistenceInput.PolicySelected ->
                state.copy(policy = input.policy)
            is CoexistenceInput.VoiceChanged ->
                state.copy(
                    voiceAvailable = input.available,
                    localTransmitting = input.localTransmitting,
                    peerTransmitting = input.peerTransmitting,
                )
            is CoexistenceInput.MusicChanged -> {
                val trackChanged = state.trackToken != input.trackToken
                state.copy(
                    musicAvailable = input.available,
                    trackToken = input.trackToken,
                    musicPlaying = input.playing,
                    trackEnded = input.ended,
                    userPaused = if (trackChanged) false else state.userPaused,
                    pausedByVoice = if (trackChanged || input.ended || !input.available) false else state.pausedByVoice,
                    pausedTrackToken = if (trackChanged || input.ended || !input.available) null else state.pausedTrackToken,
                )
            }
            is CoexistenceInput.UserPlaybackIntent ->
                state.copy(
                    userPaused = !input.playing,
                    pausedByVoice = if (input.playing) state.pausedByVoice else false,
                    pausedTrackToken = if (input.playing) state.pausedTrackToken else null,
                )
            is CoexistenceInput.BaseVolumeChanged -> state.copy(baseVolumePermille = input.volumePermille)
            is CoexistenceInput.RouteChanged ->
                state.copy(
                    routeState = input.routeState,
                    interrupted = input.interrupted,
                    routeTransitionTimedOut = input.transitionTimedOut,
                )
            is CoexistenceInput.SyncAvailabilityChanged ->
                state.copy(syncAvailable = input.available)
        }

    private fun reconcile(
        before: CoexistenceState,
        applied: CoexistenceState,
        forceGain: Boolean,
    ): CoexistenceOutcome {
        var next = applied
        val actions = mutableListOf<CoexistenceAction>()

        val shouldPause = next.voiceActive && next.policy.onSpeech == OnSpeech.Pause
        if (shouldPause) {
            val token = next.trackToken
            if (token != null && canPause(next)) {
                actions += CoexistenceAction.PauseMusicForVoice(token)
                next = next.copy(pausedByVoice = true, pausedTrackToken = token)
            }
        } else if (next.pausedByVoice) {
            val token = next.pausedTrackToken
            if (token != null && canResume(next, token)) {
                actions += CoexistenceAction.ResumeMusicAfterVoice(token)
            }
            next = next.copy(pausedByVoice = false, pausedTrackToken = null)
        }

        val target = targetVolume(next)
        if ((forceGain || target != before.targetVolumePermille) && next.musicAvailable) {
            actions += CoexistenceAction.RampMusicVolume(target)
        }
        next = next.copy(targetVolumePermille = target, fallback = fallback(next))
        return CoexistenceOutcome(next, actions)
    }

    private fun canPause(state: CoexistenceState): Boolean {
        val playable = state.musicAvailable && state.musicPlaying
        val unsuppressed = !state.userPaused && !state.pausedByVoice
        return playable && unsuppressed
    }

    private fun canResume(
        state: CoexistenceState,
        token: String,
    ): Boolean {
        val sameTrack = token == state.trackToken
        val playable = state.musicAvailable && !state.userPaused
        return sameTrack && playable && !state.trackEnded
    }

    private fun targetVolume(state: CoexistenceState): Int {
        val duckPercent =
            if (state.voiceActive) {
                (state.policy.onSpeech as? OnSpeech.Duck)?.toPercent ?: FULL_PERCENT
            } else {
                FULL_PERCENT
            }
        return (state.baseVolumePermille * duckPercent) / FULL_PERCENT
    }

    private fun fallback(state: CoexistenceState): CoexistenceFallback =
        when {
            !state.active -> CoexistenceFallback.NONE
            state.interrupted -> CoexistenceFallback.INTERRUPTED
            state.routeTransitionTimedOut -> CoexistenceFallback.ROUTE_TRANSITION_TIMEOUT
            state.policy.intercomEnabled && !state.voiceAvailable -> CoexistenceFallback.VOICE_UNAVAILABLE
            !state.musicAvailable -> CoexistenceFallback.MUSIC_UNAVAILABLE
            !state.syncAvailable -> CoexistenceFallback.SYNC_UNAVAILABLE
            else -> CoexistenceFallback.NONE
        }

    private fun forcesGainReassertion(
        state: CoexistenceState,
        input: CoexistenceInput,
    ): Boolean =
        when (input) {
            is CoexistenceInput.LifetimeStarted -> input.generation > state.generation
            is CoexistenceInput.MusicChanged -> input.trackToken != state.trackToken && input.trackToken != null
            else -> false
        }

    private fun generationOf(input: CoexistenceInput): Long =
        when (input) {
            is CoexistenceInput.LifetimeStarted -> input.generation
            is CoexistenceInput.LifetimeEnded -> input.generation
            is CoexistenceInput.PolicySelected -> input.generation
            is CoexistenceInput.VoiceChanged -> input.generation
            is CoexistenceInput.MusicChanged -> input.generation
            is CoexistenceInput.UserPlaybackIntent -> input.generation
            is CoexistenceInput.BaseVolumeChanged -> input.generation
            is CoexistenceInput.RouteChanged -> input.generation
            is CoexistenceInput.SyncAvailabilityChanged -> input.generation
        }

    private const val FULL_PERCENT = 100
}
