package com.ridelink.audio.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TransitionSettlementGate] — the suspend/resume plumbing this phase's route-close hardening pass
 * extracted so it, at least, is provable off-device. It holds no Android type and makes no decision
 * of its own, so what is proven here is the ordering policy: [TransitionSettlementGate.awaitSettled]
 * resumes on settlement and never before it, and a settlement event that nothing is waiting on, or
 * that arrives twice, does not misfire. See [AndroidVoiceAudioSessionCloseOrderingTest] for the same
 * proof against the exact call sequence [AndroidVoiceAudioSession.close] uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitionSettlementGateTest {
    @Test
    fun `already settled means awaitSettled never suspends`() =
        runTest {
            val gate = TransitionSettlementGate(isSettled = { true })
            gate.awaitSettled() // must return without a coroutine ever needing to resume it
        }

    @Test
    fun `awaitSettled suspends until onSettlementObserved reports settled`() =
        runTest {
            var settled = false
            val gate = TransitionSettlementGate(isSettled = { settled })
            var resumed = false

            val waiter =
                async {
                    gate.awaitSettled()
                    resumed = true
                }
            advanceUntilIdle()
            assertFalse(resumed, "must not resume before settlement is observed")

            settled = true
            gate.onSettlementObserved()
            advanceUntilIdle()

            assertTrue(resumed, "must resume once settlement is observed")
            waiter.await()
        }

    @Test
    fun `onSettlementObserved while not yet settled does not resume the waiter`() =
        runTest {
            var settled = false
            val gate = TransitionSettlementGate(isSettled = { settled })
            val waiter = async { gate.awaitSettled() }
            advanceUntilIdle()

            gate.onSettlementObserved() // isSettled() still false — must be a no-op
            advanceUntilIdle()
            assertFalse(waiter.isCompleted)

            settled = true
            gate.onSettlementObserved()
            advanceUntilIdle()
            assertTrue(waiter.isCompleted)
        }

    @Test
    fun `onSettlementObserved with no pending waiter does nothing`() {
        val gate = TransitionSettlementGate(isSettled = { true })
        gate.onSettlementObserved() // no awaitSettled call in flight — must not throw
    }

    @Test
    fun `a repeated onSettlementObserved after resume does not misfire`() =
        runTest {
            var settled = false
            val gate = TransitionSettlementGate(isSettled = { settled })
            val waiter = async { gate.awaitSettled() }
            advanceUntilIdle()

            settled = true
            gate.onSettlementObserved()
            gate.onSettlementObserved() // the pending signal is already cleared — must not double-complete
            advanceUntilIdle()

            waiter.await()
        }

    @Test
    fun `each awaitSettled call gets its own signal, independent of a previous cycle`() =
        runTest {
            var settled = false
            val gate = TransitionSettlementGate(isSettled = { settled })

            val first = async { gate.awaitSettled() }
            advanceUntilIdle()
            settled = true
            gate.onSettlementObserved()
            first.await()

            settled = false
            var secondResumed = false
            val second =
                async {
                    gate.awaitSettled()
                    secondResumed = true
                }
            advanceUntilIdle()
            assertFalse(secondResumed, "the first cycle's completion must not leak into the second")

            settled = true
            gate.onSettlementObserved()
            advanceUntilIdle()
            assertTrue(secondResumed)
            second.await()
        }
}
