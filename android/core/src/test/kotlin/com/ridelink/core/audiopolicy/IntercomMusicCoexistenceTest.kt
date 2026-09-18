package com.ridelink.core.audiopolicy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntercomMusicCoexistenceTest {
    private fun started(
        policy: IntercomPolicy,
        baseVolumePermille: Int = 1_000,
    ): CoexistenceState {
        var state = CoexistenceState(baseVolumePermille = baseVolumePermille, targetVolumePermille = baseVolumePermille)
        state = reduce(state, CoexistenceInput.LifetimeStarted(GENERATION, policy)).state
        state =
            reduce(
                state,
                CoexistenceInput.MusicChanged(GENERATION, available = true, trackToken = TRACK, playing = true, ended = false),
            ).state
        return state
    }

    @Test
    fun `mode A ducks to 25 percent and restores exactly`() {
        var state = started(IntercomPolicy.MODE_A)
        val down = voice(state, local = true)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(250)), down.actions)
        state = down.state
        val up = voice(state, local = false)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(1_000)), up.actions)
    }

    @Test
    fun `mode C multiplies rather than overwrites the user volume`() {
        var state = started(IntercomPolicy.MODE_C, baseVolumePermille = 800)
        val down = voice(state, local = true)
        assertEquals(800, down.state.baseVolumePermille)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(280)), down.actions)
        state = down.state
        val up = voice(state, local = false)
        assertEquals(800, up.state.baseVolumePermille)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(800)), up.actions)
    }

    @Test
    fun `duplicate activity is idempotent and a rapid reversal converges to base`() {
        var state = started(IntercomPolicy.MODE_C)
        val down = voice(state, local = true)
        state = down.state
        assertTrue(voice(state, local = true).actions.isEmpty())
        val reverse = voice(state, local = false)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(1_000)), reverse.actions)
        assertEquals(1_000, reverse.state.targetVolumePermille)
    }

    @Test
    fun `mode D never resumes a user pause or an ended or replacement track`() {
        var state = started(IntercomPolicy.MODE_D)
        val paused = voice(state, local = true)
        assertEquals(listOf(CoexistenceAction.PauseMusicForVoice(TRACK)), paused.actions)
        state = paused.state

        state = reduce(state, CoexistenceInput.UserPlaybackIntent(GENERATION, playing = false)).state
        assertTrue(voice(state, local = false).actions.isEmpty(), "speech end must not override user pause")

        state = started(IntercomPolicy.MODE_D)
        state = voice(state, local = true).state
        state =
            reduce(
                state,
                CoexistenceInput.MusicChanged(GENERATION, available = true, trackToken = TRACK, playing = false, ended = true),
            ).state
        assertTrue(voice(state, local = false).actions.isEmpty(), "an ended track must not be resurrected")

        state = started(IntercomPolicy.MODE_D)
        state = voice(state, local = true).state
        val replacement =
            reduce(
                state,
                CoexistenceInput.MusicChanged(GENERATION, available = true, trackToken = "replacement", playing = true, ended = false),
            )
        assertEquals(
            listOf(CoexistenceAction.PauseMusicForVoice("replacement"), CoexistenceAction.RampMusicVolume(1_000)),
            replacement.actions,
            "the live speech policy may suppress the replacement, but only under its own identity",
        )
        assertEquals(
            listOf(CoexistenceAction.ResumeMusicAfterVoice("replacement")),
            voice(replacement.state, local = false).actions,
            "speech end may resume the replacement, never the superseded track",
        )
    }

    @Test
    fun `mode D resumes only its own exact temporary pause`() {
        var state = started(IntercomPolicy.MODE_D)
        state = voice(state, local = true).state
        state =
            reduce(
                state,
                CoexistenceInput.MusicChanged(GENERATION, available = true, trackToken = TRACK, playing = false, ended = false),
            ).state
        val resumed = voice(state, local = false)
        assertEquals(listOf(CoexistenceAction.ResumeMusicAfterVoice(TRACK)), resumed.actions)
        assertFalse(resumed.state.pausedByVoice)
    }

    @Test
    fun `mode and availability changes clear stale duck and pause`() {
        var state = started(IntercomPolicy.MODE_C)
        state = voice(state, local = true).state
        val modeE = reduce(state, CoexistenceInput.PolicySelected(GENERATION, IntercomPolicy.MODE_E))
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(1_000)), modeE.actions)

        state = started(IntercomPolicy.MODE_D)
        state = voice(state, local = true).state
        val unavailable = voice(state, local = false, available = false)
        assertEquals(listOf(CoexistenceAction.ResumeMusicAfterVoice(TRACK)), unavailable.actions)
        assertEquals(CoexistenceFallback.VOICE_UNAVAILABLE, unavailable.state.fallback)
    }

    @Test
    fun `continuous Modes A and D never duck or pause merely because a track is enabled with no speech signal`() {
        // Phase 6 review blocker 1: `localSpeechActive`/`peerSpeechActive` both false and
        // `speechActivityAvailable` false is exactly what a continuous (gate = none) policy reports —
        // the outbound track being permanently enabled is not evidence of speech, so this must never
        // duck or pause, and the fallback must say why rather than pretend nothing is wrong.
        var modeA = started(IntercomPolicy.MODE_A)
        val noSignalA = voice(modeA, local = false, speechActivityAvailable = false)
        assertTrue(noSignalA.actions.isEmpty(), "Mode A must not duck with no honest speech signal")
        assertEquals(1_000, noSignalA.state.targetVolumePermille)
        assertEquals(CoexistenceFallback.SPEECH_ACTIVITY_UNAVAILABLE, noSignalA.state.fallback)
        modeA = noSignalA.state

        var modeD = started(IntercomPolicy.MODE_D)
        val noSignalD = voice(modeD, local = false, speechActivityAvailable = false)
        assertTrue(noSignalD.actions.isEmpty(), "Mode D must not pause with no honest speech signal")
        assertFalse(noSignalD.state.pausedByVoice)
        assertEquals(CoexistenceFallback.SPEECH_ACTIVITY_UNAVAILABLE, noSignalD.state.fallback)
        modeD = noSignalD.state

        // And once a genuine signal arrives (PTT held, or a real VOX open), the same track carries on
        // ducking/pausing normally — the fallback is not a permanent state.
        val signalA = voice(modeA, local = true, speechActivityAvailable = true)
        assertEquals(listOf(CoexistenceAction.RampMusicVolume(250)), signalA.actions)
        assertEquals(CoexistenceFallback.NONE, signalA.state.fallback)

        val signalD = voice(modeD, local = true, speechActivityAvailable = true)
        assertEquals(listOf(CoexistenceAction.PauseMusicForVoice(TRACK)), signalD.actions)
        assertEquals(CoexistenceFallback.NONE, signalD.state.fallback)
    }

    @Test
    fun `a stale predecessor cannot alter the successor`() {
        var state = started(IntercomPolicy.MODE_C)
        state = voice(state, local = true).state
        state = reduce(state, CoexistenceInput.LifetimeStarted(GENERATION + 1, IntercomPolicy.MODE_C)).state
        val stale =
            reduce(
                state,
                CoexistenceInput.VoiceChanged(
                    GENERATION,
                    available = true,
                    localSpeechActive = false,
                    peerSpeechActive = false,
                    speechActivityAvailable = true,
                ),
            )
        assertTrue(stale.actions.isEmpty())
        assertEquals(GENERATION + 1, stale.state.generation)
        assertEquals(1, stale.state.staleInputCount)
    }

    @Test
    fun `a successor reconciles a predecessor Mode D pause before rejecting stale input`() {
        var state = started(IntercomPolicy.MODE_D)
        state = voice(state, local = true).state

        val successor = reduce(state, CoexistenceInput.LifetimeStarted(GENERATION + 1, IntercomPolicy.MODE_C))

        assertEquals(
            listOf(
                CoexistenceAction.ResumeMusicAfterVoice(TRACK),
                CoexistenceAction.RampMusicVolume(1_000),
            ),
            successor.actions,
        )
        assertFalse(successor.state.pausedByVoice)
        assertFalse(successor.state.localSpeechActive)
        assertFalse(successor.state.peerSpeechActive)
    }

    @Test
    fun `teardown restores ducking and a temporary pause without reviving user state`() {
        var ducked = started(IntercomPolicy.MODE_C)
        ducked = voice(ducked, local = true).state
        assertEquals(
            listOf(CoexistenceAction.RampMusicVolume(1_000)),
            reduce(ducked, CoexistenceInput.LifetimeEnded(GENERATION)).actions,
        )

        var paused = started(IntercomPolicy.MODE_D)
        paused = voice(paused, local = true).state
        paused =
            reduce(
                paused,
                CoexistenceInput.MusicChanged(GENERATION, available = true, trackToken = TRACK, playing = false, ended = false),
            ).state
        assertEquals(
            listOf(CoexistenceAction.ResumeMusicAfterVoice(TRACK)),
            reduce(paused, CoexistenceInput.LifetimeEnded(GENERATION)).actions,
        )
    }

    private fun voice(
        state: CoexistenceState,
        local: Boolean,
        available: Boolean = true,
        speechActivityAvailable: Boolean = true,
    ): CoexistenceOutcome =
        reduce(
            state,
            CoexistenceInput.VoiceChanged(
                GENERATION,
                available,
                localSpeechActive = local,
                peerSpeechActive = false,
                speechActivityAvailable = speechActivityAvailable,
            ),
        )

    private fun reduce(
        state: CoexistenceState,
        input: CoexistenceInput,
    ) = IntercomMusicCoexistence.reduce(state, input)

    private companion object {
        const val GENERATION = 1L
        const val TRACK = "track-a"
    }
}
