package com.ridelink.network.control

import com.ridelink.core.sync.ClockSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * STATUS §4 problem 137: the timeout bounds the whole round trip, not only the wait for the `PONG`.
     * [writeLock] stands in for a connection whose write lock a stalled write holds; a `PING` queued
     * behind it used to wait for as long as that write did.
     */
    @Test
    fun `the timeout bounds the write too, so a PING queued behind a stalled write gives up on time and leaves no waiter`() =
        runBlocking {
            val waiters = PingWaiters()
            val writeLock = Mutex(locked = true)
            val roundTrip = async { runCatching { waiters.awaitRoundTrip(t1 = 100, timeoutMs = 50) { writeLock.withLock {} } } }
            val outcome = withTimeoutOrNull(2_000) { roundTrip.await() }
            roundTrip.cancel()
            assertTrue(outcome?.exceptionOrNull() is TimeoutCancellationException, "a PING behind a stalled write must time out: $outcome")
            assertEquals(0, waiters.size, "a timed-out PING must not leave a waiter behind")
        }

    @Test
    fun `cancelling a round trip whose write is waiting for the lock leaves no waiter`() =
        runBlocking {
            val waiters = PingWaiters()
            val writeLock = Mutex(locked = true)
            val waiting = CompletableDeferred<Unit>()
            val roundTrip =
                async {
                    waiters.awaitRoundTrip(t1 = 100, timeoutMs = 60_000) {
                        waiting.complete(Unit)
                        writeLock.withLock {}
                    }
                }
            waiting.await()
            assertEquals(1, waiters.size)
            roundTrip.cancel()
            roundTrip.join()
            assertEquals(0, waiters.size, "cancellation must not orphan the waiter")
            waiters.complete(sample(100)) // a late PONG: a no-op, not a crash
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
