package com.ridelink.audio.route

import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioSessionEvent
import com.ridelink.core.audiopolicy.AudioSessionLifecycle
import com.ridelink.core.audiopolicy.AudioSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A structural mirror of [AndroidVoiceAudioSession.close]'s exact call sequence — built from the
 * same two production pieces the real method uses, [TransitionSettlementGate] and the shared
 * `AudioSessionLifecycle` reducer — with every `AudioManager` call replaced by a recorded fake.
 *
 * [AndroidVoiceAudioSession] cannot be constructed in a JVM test: its constructor calls
 * `context.getSystemService(Context.AUDIO_SERVICE)` on a real `Context`, and this module has neither
 * Robolectric nor a mocking library (see `android/audio/build.gradle.kts`). That is exactly the
 * REAL-DEVICE AUDIO GATE PENDING the class doc there names. This fake proves the *ordering policy*
 * `close()` follows — that the listener stays live until settlement, whether settlement is
 * synchronous, asynchronous, or a timeout, and that repeated/idempotent calls do not re-run the
 * platform sequence. It is not, and cannot be, evidence about `AudioManager`'s actual callback timing
 * on a phone.
 */
private class FakeAndroidCloseSession {
    val calls = mutableListOf<String>()
    var lifecycle = AudioSessionState(open = true)
        private set
    private val gate = TransitionSettlementGate(isSettled = { !lifecycle.transition.transitioning })

    /** Simulates `deviceChangedListener` firing — from inside `clearCommunicationDevice()`, or later. */
    fun deliverConfirmingCallback(nowUs: Long) {
        applyEvent(AudioSessionEvent.RouteChanged(lifecycle.generation, AudioRouteChangeReason.CATEGORY_CHANGE, nowUs, settles = true))
    }

    /** Simulates the scheduled failure-protection timeout firing for [generation]. */
    fun deliverTimeout(
        generation: Int = lifecycle.generation,
        nowUs: Long,
        timeoutUs: Long = 0L,
    ) {
        applyEvent(AudioSessionEvent.TransitionTimeoutCheck(generation, nowUs, timeoutUs))
    }

    /**
     * Mirrors [AndroidVoiceAudioSession.close] exactly: `CloseRequested` begins the transition,
     * `requestPlatformRestore()`'s one platform call is the recorded "clearCommunicationDevice" (with
     * [duringClearCommunicationDevice] standing in for a callback the real call could provoke
     * synchronously), `Closed` flips `open` false, then — the fix — the listener stays registered
     * until [TransitionSettlementGate.awaitSettled] actually returns, and only then is it torn down.
     */
    suspend fun close(
        nowUs: Long,
        duringClearCommunicationDevice: () -> Unit = {},
    ) {
        if (!lifecycle.open) {
            calls += "already-closed"
            return
        }
        applyEvent(AudioSessionEvent.CloseRequested(lifecycle.generation, nowUs))
        calls += "clearCommunicationDevice"
        duringClearCommunicationDevice()
        applyEvent(AudioSessionEvent.Closed(lifecycle.generation, nowUs))
        gate.awaitSettled()
        calls += "unregister"
    }

    private fun applyEvent(event: AudioSessionEvent) {
        lifecycle = AudioSessionLifecycle.reduce(lifecycle, event).state
        gate.onSettlementObserved()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVoiceAudioSessionCloseOrderingTest {
    @Test
    fun `a synchronous confirming callback settles before unregister runs`() =
        runTest {
            val session = FakeAndroidCloseSession()
            session.close(nowUs = 0L) {
                session.deliverConfirmingCallback(nowUs = 10L)
            }
            assertEquals(listOf("clearCommunicationDevice", "unregister"), session.calls)
            assertEquals(false, session.lifecycle.transition.transitioning)
            assertEquals(0, session.lifecycle.transition.timedOutCount)
        }

    @Test
    fun `an asynchronous confirming callback is still observed before unregister`() =
        runTest {
            val session = FakeAndroidCloseSession()
            val closing = async { session.close(nowUs = 0L) }
            advanceUntilIdle()

            // The confirmation has not arrived yet: unregister must not have run.
            assertEquals(listOf("clearCommunicationDevice"), session.calls)
            assertTrue(session.lifecycle.transition.transitioning)

            session.deliverConfirmingCallback(nowUs = 500_000L)
            advanceUntilIdle()
            closing.await()

            assertEquals(listOf("clearCommunicationDevice", "unregister"), session.calls)
            assertEquals(0, session.lifecycle.transition.timedOutCount)
        }

    @Test
    fun `no callback falls back to the route timeout, and it is counted as a timeout`() =
        runTest {
            val session = FakeAndroidCloseSession()
            val closing = async { session.close(nowUs = 0L) }
            advanceUntilIdle()
            assertEquals(listOf("clearCommunicationDevice"), session.calls)

            session.deliverTimeout(nowUs = 5_000_000L)
            advanceUntilIdle()
            closing.await()

            assertEquals(listOf("clearCommunicationDevice", "unregister"), session.calls)
            assertEquals(1, session.lifecycle.transition.timedOutCount)
        }

    @Test
    fun `an unelapsed timeout check does not settle the transition early`() =
        runTest {
            val session = FakeAndroidCloseSession()
            val closing = async { session.close(nowUs = 0L) }
            advanceUntilIdle()

            session.deliverTimeout(nowUs = 1_000L, timeoutUs = 999_999_999L)
            advanceUntilIdle()
            assertEquals(listOf("clearCommunicationDevice"), session.calls, "an unelapsed timeout must not settle")
            assertTrue(session.lifecycle.transition.transitioning)

            session.deliverConfirmingCallback(nowUs = 2_000L)
            advanceUntilIdle()
            closing.await()
            assertEquals(listOf("clearCommunicationDevice", "unregister"), session.calls)
        }

    @Test
    fun `a timeout for a stale generation is ignored, the real one still settles`() =
        runTest {
            val session = FakeAndroidCloseSession()
            val closing = async { session.close(nowUs = 0L) }
            advanceUntilIdle()

            val staleGeneration = session.lifecycle.generation + 1
            session.deliverTimeout(generation = staleGeneration, nowUs = 5_000_000L)
            advanceUntilIdle()
            assertEquals(listOf("clearCommunicationDevice"), session.calls, "a mismatched-generation timeout must not settle")
            assertTrue(session.lifecycle.transition.transitioning)
            assertEquals(0, session.lifecycle.transition.timedOutCount)

            session.deliverTimeout(nowUs = 5_000_001L)
            advanceUntilIdle()
            closing.await()
            assertEquals(listOf("clearCommunicationDevice", "unregister"), session.calls)
            assertEquals(1, session.lifecycle.transition.timedOutCount)
        }

    @Test
    fun `repeated close is idempotent — a concurrent second call does not repeat the platform sequence`() =
        runTest {
            val session = FakeAndroidCloseSession()
            val first = async { session.close(nowUs = 0L) }
            advanceUntilIdle()

            // `open` already flipped false before the await, so a second call sees it and returns.
            val second = async { session.close(nowUs = 1L) }
            advanceUntilIdle()
            assertEquals(listOf("clearCommunicationDevice", "already-closed"), session.calls)

            session.deliverConfirmingCallback(nowUs = 2L)
            advanceUntilIdle()
            first.await()
            second.await()

            assertEquals(listOf("clearCommunicationDevice", "already-closed", "unregister"), session.calls)
        }

    @Test
    fun `close on an already-closed session is immediate, no platform calls at all`() =
        runTest {
            val session = FakeAndroidCloseSession()
            session.close(nowUs = 0L) { session.deliverConfirmingCallback(nowUs = 1L) }
            session.calls.clear()

            session.close(nowUs = 2L)
            assertEquals(listOf("already-closed"), session.calls)
        }
}
