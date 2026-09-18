package com.ridelink.app.music

import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IntercomMusicCoexistenceCoordinatorTest {
    @Test
    fun `fifty PTT cycles produce fifty exact duck and restore ramps without replacing the player lifetime`() =
        runTest {
            val port = FakeMusicPort(playingState("track"))
            val coordinator = IntercomMusicCoexistenceCoordinator(this, port, GainRampSleeper { })
            val generation = coordinator.beginLifetime(IntercomPolicy.MODE_C)
            runCurrent()

            repeat(50) {
                coordinator.updateVoice(generation, true, true, false, true, RouteState.STABLE, false, false)
                advanceUntilIdle()
                assertEquals(350, port.gains.last())
                coordinator.updateVoice(generation, true, false, false, true, RouteState.STABLE, false, false)
                advanceUntilIdle()
                assertEquals(1_000, port.gains.last())
            }

            assertEquals(listOf(generation), port.lifetimes)
            assertEquals(1_000, coordinator.diagnostics.value.appliedVolumePermille)
            assertEquals(1_000, port.gains.last())
            assertTrue(port.pauseCalls.isEmpty())
            assertTrue(port.resumeCalls.isEmpty())
        }

    @Test
    fun `a blocked predecessor ramp cannot mutate a successor player lifetime`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val port = FakeMusicPort(playingState("track"))
            val coordinator = IntercomMusicCoexistenceCoordinator(this, port, GainRampSleeper { gate.await() })
            val first = coordinator.beginLifetime(IntercomPolicy.MODE_C)
            runCurrent()
            coordinator.updateVoice(first, true, true, false, true, RouteState.STABLE, false, false)
            runCurrent()

            val second = coordinator.beginLifetime(IntercomPolicy.MODE_A)
            coordinator.updateVoice(second, true, false, false, true, RouteState.STABLE, false, false)
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(first, second), port.lifetimes)
            assertFalse(port.appliedGenerations.dropWhile { it == first }.contains(first))
            assertEquals(1_000, port.gains.last())
        }

    @Test
    fun `terminal lifetime wait joins an in-progress restore ramp`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val port = FakeMusicPort(playingState("track"))
            val coordinator = IntercomMusicCoexistenceCoordinator(this, port, GainRampSleeper { gate.await() })
            val generation = coordinator.beginLifetime(IntercomPolicy.MODE_C)
            runCurrent()
            coordinator.updateVoice(generation, true, true, false, true, RouteState.STABLE, false, false)
            coordinator.endLifetime(generation)

            var returned = false
            val waiter =
                launch {
                    coordinator.awaitLifetimeEnded()
                    returned = true
                }
            runCurrent()
            assertFalse(returned, "teardown must not return while its restore ramp is suspended")

            gate.complete(Unit)
            waiter.join()
            assertTrue(returned)
            assertEquals(1_000, port.gains.last())
        }

    @Test
    fun `Mode D resumes only its exact track and never overrides a user pause`() =
        runTest {
            val port = FakeMusicPort(playingState("first"))
            val coordinator = IntercomMusicCoexistenceCoordinator(this, port, GainRampSleeper { })
            val generation = coordinator.beginLifetime(IntercomPolicy.MODE_D)
            coordinator.updateVoice(generation, true, true, false, true, RouteState.STABLE, false, false)
            advanceUntilIdle()
            assertEquals(1, port.pauseCalls.size)

            coordinator.onPlaybackIntent(false)
            coordinator.updateVoice(generation, true, false, false, true, RouteState.STABLE, false, false)
            advanceUntilIdle()
            assertTrue(port.resumeCalls.isEmpty())

            port.emit(playingState("second"))
            coordinator.updateVoice(generation, true, true, false, true, RouteState.STABLE, false, false)
            advanceUntilIdle()
            val replacedTrack = port.pauseCalls.last()
            port.emit(playingState("third"))
            coordinator.updateVoice(generation, true, false, false, true, RouteState.STABLE, false, false)
            advanceUntilIdle()
            assertFalse(port.resumeCalls.contains(replacedTrack))
        }

    private class FakeMusicPort(
        initial: PlayerState,
    ) : MusicCoexistencePort {
        private val state = MutableStateFlow(initial)
        private val volume = MutableStateFlow(1_000)
        override val coexistencePlayerState: StateFlow<PlayerState> = state
        override val coexistenceBaseVolumePermille: StateFlow<Int> = volume
        override var coexistenceEvents: CoexistenceEventSink? = null
        val lifetimes = mutableListOf<Long>()
        val gains = mutableListOf<Int>()
        val appliedGenerations = mutableListOf<Long>()
        val pauseCalls = mutableListOf<String>()
        val resumeCalls = mutableListOf<String>()
        private var generation = 0L

        override suspend fun beginCoexistenceLifetime(generation: Long) {
            this.generation = generation
            lifetimes += generation
        }

        override suspend fun applyCoexistenceGain(
            generation: Long,
            volumePermille: Int,
        ): Boolean {
            if (generation != this.generation) return false
            appliedGenerations += generation
            gains += volumePermille
            return true
        }

        override suspend fun pauseForVoice(
            generation: Long,
            trackToken: String,
        ): Boolean {
            if (generation != this.generation || state.value.localEntryId?.value != trackToken) return false
            pauseCalls += trackToken
            state.value = state.value.copy(playing = false)
            return true
        }

        override suspend fun resumeAfterVoice(
            generation: Long,
            trackToken: String,
        ): Boolean {
            if (generation != this.generation || state.value.localEntryId?.value != trackToken) return false
            resumeCalls += trackToken
            state.value = state.value.copy(playing = true)
            return true
        }

        fun emit(next: PlayerState) {
            state.value = next
            coexistenceEvents?.onMusicChanged(next)
        }
    }

    private companion object {
        private val tokens =
            mapOf(
                "track" to "00000000-0000-0000-0000-000000000001",
                "first" to "00000000-0000-0000-0000-000000000002",
                "second" to "00000000-0000-0000-0000-000000000003",
                "third" to "00000000-0000-0000-0000-000000000004",
            )

        fun playingState(token: String) =
            PlayerState(
                localEntryId = LocalEntryId(requireNotNull(tokens[token])),
                durationMs = 10_000,
                playing = true,
            )
    }
}
