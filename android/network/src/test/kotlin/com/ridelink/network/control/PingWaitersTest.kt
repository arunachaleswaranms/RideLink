package com.ridelink.network.control

import com.ridelink.core.sync.ClockSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PingWaiters] was extracted from `ControlSessionManager` without a change in behaviour (PR #27
 * review). These pin that behaviour at its own seam. The end-to-end cases — a real `PONG` resolving a
 * real round trip, a write failure, teardown — stay in `PingRaceAndReconnectTest`,
 * `PongWriteFailureTest` and `TeardownTest`, which drive it through the manager.
 */
class PingWaitersTest {
    private fun sample(t1: Long) = ClockSync.Sample(t1, t1 + 10, t1 + 20, t1 + 30)

    @Test
    fun `the waiter is registered before the write, so a PONG during the write is not lost`() =
        runBlocking {
            val waiters = PingWaiters()
            val result = waiters.awaitRoundTrip(t1 = 100, timeoutMs = 1_000) { waiters.complete(sample(100)) }
            assertEquals(sample(100), result)
            assertEquals(0, waiters.size)
        }

    @Test
    fun `a write failure removes the waiter and rethrows`() =
        runBlocking {
            val waiters = PingWaiters()
            assertFailsWith<IOException> {
                waiters.awaitRoundTrip(t1 = 100, timeoutMs = 1_000) { throw IOException("socket closed") }
            }
            assertEquals(0, waiters.size, "a failed write must not leave an entry behind")
        }

    @Test
    fun `a timeout removes the waiter, and a PONG after it finds nothing to complete`() =
        runBlocking {
            val waiters = PingWaiters()
            assertFailsWith<TimeoutCancellationException> {
                waiters.awaitRoundTrip(t1 = 100, timeoutMs = 10) {}
            }
            assertEquals(0, waiters.size)
            waiters.complete(sample(100)) // late: a no-op, not a crash
            assertEquals(0, waiters.size)
        }

    @Test
    fun `a PONG for a t1 nobody is waiting on completes nothing`() =
        runBlocking {
            val waiters = PingWaiters()
            val written = CompletableDeferred<Unit>()
            val pending = async { waiters.awaitRoundTrip(t1 = 100, timeoutMs = 1_000) { written.complete(Unit) } }
            written.await()
            waiters.complete(sample(999))
            assertEquals(1, waiters.size, "the outstanding round trip is untouched")
            waiters.complete(sample(100))
            assertEquals(sample(100), pending.await())
        }

    @Test
    fun `teardown fails every outstanding waiter and leaves none`() =
        runBlocking {
            val waiters = PingWaiters()
            val written = CompletableDeferred<Unit>()
            val first = async { runCatching { waiters.awaitRoundTrip(t1 = 1, timeoutMs = 5_000) {} } }
            val second =
                async { runCatching { waiters.awaitRoundTrip(t1 = 2, timeoutMs = 5_000) { written.complete(Unit) } } }
            written.await()
            assertEquals(2, waiters.size)
            waiters.failAll()
            assertTrue(first.await().exceptionOrNull() is IOException)
            assertTrue(second.await().exceptionOrNull() is IOException)
            assertEquals(0, waiters.size)
        }
}
