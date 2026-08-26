package com.ridelink.network.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOCOL §10: `0.5, 1, 2, 4, 8, 8, 8 … s`, ±20% jitter, 120 s budget. Pure — no
 * [Thread.sleep] anywhere (CLAUDE.md rule 12).
 */
class ReconnectPolicyTest {
    @Test
    fun `ladder matches PROTOCOL section 10 exactly`() {
        assertEquals(500, ReconnectPolicy.baseDelayMs(0))
        assertEquals(1000, ReconnectPolicy.baseDelayMs(1))
        assertEquals(2000, ReconnectPolicy.baseDelayMs(2))
        assertEquals(4000, ReconnectPolicy.baseDelayMs(3))
        assertEquals(8000, ReconnectPolicy.baseDelayMs(4))
        assertEquals(8000, ReconnectPolicy.baseDelayMs(5))
        assertEquals(8000, ReconnectPolicy.baseDelayMs(50))
    }

    @Test
    fun `jitter stays within plus-minus 20 percent of the base delay`() {
        for (attempt in 0..6) {
            val base = ReconnectPolicy.baseDelayMs(attempt)
            val min = ReconnectPolicy.jitteredDelayMs(attempt, -1.0)
            val max = ReconnectPolicy.jitteredDelayMs(attempt, 1.0)
            assertEquals((base * 0.8).toLong(), min)
            assertEquals((base * 1.2).toLong(), max)
        }
    }

    @Test
    fun `jittered delay is never negative and never busy-loops at zero`() {
        val delay = ReconnectPolicy.jitteredDelayMs(0, -1.0)
        assertTrue(delay > 0, "even worst-case negative jitter on the smallest rung must stay positive: $delay")
    }

    @Test
    fun `controller never busy-loops -- every attempt is preceded by a nonzero recorded delay`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                val recordedDelays = mutableListOf<Long>()
                val controller =
                    ReconnectController(scope, Random(42)) { ms -> recordedDelays.add(ms) } // no real delay(): pure recorder

                var attempts = 0
                withTimeout(5_000) {
                    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                    controller.start(
                        onAttempt = {
                            attempts += 1
                            (attempts >= 3).also { if (it) done.complete(Unit) }
                        },
                        onExhausted = { done.complete(Unit) },
                    )
                    done.await()
                }

                assertEquals(3, attempts)
                assertEquals(3, controller.reconnectCount)
                assertTrue(recordedDelays.all { it > 0 }, "every attempt must be preceded by a positive delay: $recordedDelays")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `budget exhaustion fires after roughly 120s of ladder delays with no success`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                var totalDelayed = 0L
                val controller =
                    ReconnectController(scope, Random(7)) { ms -> totalDelayed += ms } // recorder, not a real delay

                val exhausted = kotlinx.coroutines.CompletableDeferred<Unit>()
                withTimeout(5_000) {
                    controller.start(onAttempt = { false }, onExhausted = { exhausted.complete(Unit) })
                    exhausted.await()
                }

                assertTrue(totalDelayed >= ReconnectPolicy.MAX_TOTAL_BUDGET_MS, "must not give up before the 120s budget: $totalDelayed")
            } finally {
                scope.cancel()
            }
        }
}
