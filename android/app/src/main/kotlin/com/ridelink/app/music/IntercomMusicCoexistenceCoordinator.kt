package com.ridelink.app.music

import androidx.annotation.MainThread
import com.ridelink.core.audiopolicy.CoexistenceAction
import com.ridelink.core.audiopolicy.CoexistenceFallback
import com.ridelink.core.audiopolicy.CoexistenceInput
import com.ridelink.core.audiopolicy.CoexistenceState
import com.ridelink.core.audiopolicy.IntercomMusicCoexistence
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

interface CoexistenceEventSink {
    fun onMusicChanged(state: PlayerState)

    fun onPlaybackIntent(playing: Boolean)

    fun onBaseVolumeChanged(volumePermille: Int)
}

/** Narrow player boundary used by the coexistence owner and its deterministic tests. */
interface MusicCoexistencePort {
    val coexistencePlayerState: StateFlow<PlayerState>
    val coexistenceBaseVolumePermille: StateFlow<Int>

    suspend fun beginCoexistenceLifetime(generation: Long)

    suspend fun applyCoexistenceGain(
        generation: Long,
        volumePermille: Int,
    ): Boolean

    suspend fun pauseForVoice(
        generation: Long,
        trackToken: String,
    ): Boolean

    suspend fun resumeAfterVoice(
        generation: Long,
        trackToken: String,
    ): Boolean

    var coexistenceEvents: CoexistenceEventSink?
}

fun interface GainRampSleeper {
    suspend fun sleep(stepDurationMs: Long)
}

data class CoexistenceDiagnostics(
    val generation: Long = 0,
    val targetVolumePermille: Int = CoexistenceState.FULL_GAIN_PERMILLE,
    val appliedVolumePermille: Int = CoexistenceState.FULL_GAIN_PERMILLE,
    val pausedByVoice: Boolean = false,
    val routeState: RouteState = RouteState.STABLE,
    val fallback: CoexistenceFallback = CoexistenceFallback.NONE,
    val rampRevision: Long = 0,
    val rampCancellationCount: Int = 0,
    val pauseCount: Int = 0,
    val resumeCount: Int = 0,
    val staleInputCount: Int = 0,
)

/**
 * The sole driver of Phase 6 music effects. [IntercomMusicCoexistence] makes every decision; this
 * class serialises its effects and owns the cancellable 200 ms gain ramp.
 */
class IntercomMusicCoexistenceCoordinator(
    private val scope: CoroutineScope,
    private val music: MusicCoexistencePort,
    private val sleeper: GainRampSleeper = GainRampSleeper { delay(it) },
) : CoexistenceEventSink {
    private var state = CoexistenceState()
    private var nextGeneration = 0L
    private var effectTail: Job? = null
    private var rampJob: Job? = null
    private var rampRevision = 0L
    private var appliedVolumePermille = CoexistenceState.FULL_GAIN_PERMILLE
    private var rampCancellationCount = 0
    private var pauseCount = 0
    private var resumeCount = 0

    private val _diagnostics = MutableStateFlow(CoexistenceDiagnostics())
    val diagnostics: StateFlow<CoexistenceDiagnostics> = _diagnostics.asStateFlow()

    init {
        music.coexistenceEvents = this
        onBaseVolumeChanged(music.coexistenceBaseVolumePermille.value)
        onMusicChanged(music.coexistencePlayerState.value)
    }

    @MainThread
    fun beginLifetime(policy: IntercomPolicy): Long {
        val generation = ++nextGeneration
        val outcome = IntercomMusicCoexistence.reduce(state, CoexistenceInput.LifetimeStarted(generation, policy))
        state = outcome.state

        // A successor never waits for predecessor work. Cancellation is backed by the player's own
        // generation check, so an uncooperative old continuation is still inert when it resumes.
        effectTail?.cancel()
        rampJob?.cancel()
        effectTail =
            scope.launch {
                music.beginCoexistenceLifetime(generation)
                perform(generation, outcome.actions)
            }
        publishDiagnostics()
        return generation
    }

    @MainThread
    fun endLifetime(generation: Long) {
        submit(CoexistenceInput.LifetimeEnded(generation))
    }

    /** Terminal lifecycle seam: restoration and any cancelled ramp are complete on return. */
    suspend fun awaitLifetimeEnded() {
        awaitEffectsSettled()
    }

    @MainThread
    fun selectPolicy(
        generation: Long,
        policy: IntercomPolicy,
    ) {
        submit(CoexistenceInput.PolicySelected(generation, policy))
    }

    @MainThread
    fun updateVoice(
        generation: Long,
        available: Boolean,
        localTransmitting: Boolean,
        peerTransmitting: Boolean,
        routeState: RouteState,
        interrupted: Boolean,
        transitionTimedOut: Boolean,
    ) {
        submit(CoexistenceInput.VoiceChanged(generation, available, localTransmitting, peerTransmitting))
        submit(CoexistenceInput.RouteChanged(generation, routeState, interrupted, transitionTimedOut))
    }

    @MainThread
    fun updateSyncAvailability(available: Boolean) {
        submit(CoexistenceInput.SyncAvailabilityChanged(state.generation, available))
    }

    /** Observable completion seam for deterministic lifecycle tests; production never needs a sleep. */
    suspend fun awaitEffectsSettled() {
        effectTail?.join()
        rampJob?.join()
    }

    @MainThread
    override fun onMusicChanged(state: PlayerState) {
        submit(
            CoexistenceInput.MusicChanged(
                generation = this.state.generation,
                available = state.error == null,
                trackToken = state.localEntryId?.value,
                playing = state.playing,
                ended = state.ended,
            ),
        )
    }

    @MainThread
    override fun onPlaybackIntent(playing: Boolean) {
        submit(CoexistenceInput.UserPlaybackIntent(state.generation, playing))
    }

    @MainThread
    override fun onBaseVolumeChanged(volumePermille: Int) {
        submit(CoexistenceInput.BaseVolumeChanged(state.generation, volumePermille))
    }

    @MainThread
    private fun submit(input: CoexistenceInput) {
        val outcome = IntercomMusicCoexistence.reduce(state, input)
        state = outcome.state
        if (outcome.actions.isNotEmpty()) enqueueEffects(state.generation, outcome.actions)
        publishDiagnostics()
    }

    private fun enqueueEffects(
        generation: Long,
        actions: List<CoexistenceAction>,
    ) {
        val previous = effectTail
        effectTail =
            scope.launch {
                previous?.join()
                perform(generation, actions)
            }
    }

    private suspend fun perform(
        generation: Long,
        actions: List<CoexistenceAction>,
    ) {
        for (action in actions) {
            when (action) {
                is CoexistenceAction.RampMusicVolume -> startRamp(generation, action)
                is CoexistenceAction.PauseMusicForVoice -> {
                    if (music.pauseForVoice(generation, action.trackToken)) pauseCount += 1
                }
                is CoexistenceAction.ResumeMusicAfterVoice -> {
                    if (music.resumeAfterVoice(generation, action.trackToken)) resumeCount += 1
                }
            }
        }
        publishDiagnostics()
    }

    private fun startRamp(
        generation: Long,
        action: CoexistenceAction.RampMusicVolume,
    ) {
        if (rampJob?.isActive == true) rampCancellationCount += 1
        rampJob?.cancel()
        val revision = ++rampRevision
        val start = appliedVolumePermille
        val steps = RAMP_STEPS
        val stepDurationMs = action.durationMs / steps
        rampJob =
            scope.launch {
                for (step in 1..steps) {
                    sleeper.sleep(stepDurationMs)
                    if (revision != rampRevision || generation != state.generation) return@launch
                    val fraction = step.toDouble() / steps
                    val value = (start + ((action.targetPermille - start) * fraction)).roundToInt()
                    if (!music.applyCoexistenceGain(generation, value)) return@launch
                    appliedVolumePermille = value
                    publishDiagnostics()
                }
            }
        publishDiagnostics()
    }

    private fun publishDiagnostics() {
        _diagnostics.value =
            CoexistenceDiagnostics(
                generation = state.generation,
                targetVolumePermille = state.targetVolumePermille,
                appliedVolumePermille = appliedVolumePermille,
                pausedByVoice = state.pausedByVoice,
                routeState = state.routeState,
                fallback = state.fallback,
                rampRevision = rampRevision,
                rampCancellationCount = rampCancellationCount,
                pauseCount = pauseCount,
                resumeCount = resumeCount,
                staleInputCount = state.staleInputCount,
            )
    }

    private companion object {
        const val RAMP_STEPS = 10
    }
}
