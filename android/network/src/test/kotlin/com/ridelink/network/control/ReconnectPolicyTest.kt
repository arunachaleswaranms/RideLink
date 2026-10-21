package com.ridelink.network.control

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // MARK: - STATUS §4 problem 125: a promotion ends the run, whichever connection it promoted

    @Test
    fun `a promotion while the ladder is parked ends the run, and the next run has a full budget`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                val parked = Channel<Unit>(Channel.UNLIMITED)
                val release = Channel<Unit>(Channel.UNLIMITED)
                var free = false
                val controller =
                    ReconnectController(scope, Random(11)) {
                        if (!free) {
                            parked.send(Unit)
                            release.receive()
                        }
                    }
                var attempts = 0
                val firstExhausted = CompletableDeferred<Unit>()
                controller.start(onAttempt = {
                    attempts += 1
                    false
                }, onExhausted = { firstExhausted.complete(Unit) })
                withTimeout(5_000) { parked.receive() }

                controller.connectionPromoted() // e.g. the peer dialled in
                free = true
                release.send(Unit)
                delay(SETTLE_MS)
                assertEquals(0, attempts, "the ended run made no attempt")
                assertFalse(firstExhausted.isCompleted, "and reported no exhaustion")

                var secondAttempts = 0
                val secondExhausted = CompletableDeferred<Unit>()
                controller.start(onAttempt = {
                    secondAttempts += 1
                    false
                }, onExhausted = { secondExhausted.complete(Unit) })
                withTimeout(5_000) { secondExhausted.await() }
                assertTrue(secondAttempts >= MIN_FULL_BUDGET_ATTEMPTS, "the next link loss gets a full budget: $secondAttempts")
                assertFalse(firstExhausted.isCompleted)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a promotion during an attempt retires the run without cancelling the attempt`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                val controller = ReconnectController(scope, Random(12)) { }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var attempts = 0
                var attemptRanToCompletion = false
                val exhausted = CompletableDeferred<Unit>()
                controller.start(
                    onAttempt = {
                        attempts += 1
                        entered.complete(Unit)
                        release.await()
                        attemptRanToCompletion = true
                        false // e.g. this dial lost to the peer's, which was promoted meanwhile
                    },
                    onExhausted = { exhausted.complete(Unit) },
                )
                withTimeout(5_000) { entered.await() }
                controller.connectionPromoted()
                release.complete(Unit)
                delay(SETTLE_MS)

                assertTrue(attemptRanToCompletion, "an in-flight attempt owns its socket and is never cancelled")
                assertEquals(1, attempts, "no attempt after the promotion")
                assertFalse(exhausted.isCompleted, "a retired run never reports exhaustion")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a promotion performed by the ladder's own attempt does not cancel that attempt`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                lateinit var controller: ReconnectController
                controller = ReconnectController(scope, Random(13)) { }
                val done = CompletableDeferred<Boolean>()
                var attempts = 0
                controller.start(
                    onAttempt = {
                        attempts += 1
                        controller.connectionPromoted() // promote() runs on this very coroutine
                        delay(1) // a suspension point: it would throw here if the run had cancelled itself
                        done.complete(true)
                        true
                    },
                    onExhausted = { done.complete(false) },
                )
                assertTrue(withTimeout(5_000) { done.await() }, "the promoting attempt completes")
                delay(SETTLE_MS)
                assertEquals(1, attempts)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `an exhaustion commits once, and never after a promotion ended its run`() =
        runBlocking {
            val scope = CoroutineScope(Job())
            try {
                val controller = ReconnectController(scope, Random(14)) { }

                val first = CompletableDeferred<ReconnectRun>()
                controller.start(onAttempt = { false }, onExhausted = { run -> first.complete(run) })
                val decided = withTimeout(5_000) { first.await() }
                assertTrue(controller.commitExhaustion(decided), "the deciding run still owns the budget")
                assertFalse(controller.commitExhaustion(decided), "and commits exactly once")

                controller.connectionPromoted() // refreshes the budget for the next run
                val second = CompletableDeferred<ReconnectRun>()
                controller.start(onAttempt = { false }, onExhausted = { run -> second.complete(run) })
                val stale = withTimeout(5_000) { second.await() }
                controller.connectionPromoted() // a promotion lands between the decision and its commit
                assertFalse(controller.commitExhaustion(stale), "a promotion that ended the run first wins")
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        const val SETTLE_MS = 200L

        /** 7.5 s on the first four rungs, then at most 9.6 s each: a 120 s budget holds at least 16. */
        const val MIN_FULL_BUDGET_ATTEMPTS = 16
    }
}
