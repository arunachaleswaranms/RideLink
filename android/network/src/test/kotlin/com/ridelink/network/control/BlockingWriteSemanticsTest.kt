package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 137 — what a **kernel-blocked** control write actually does, measured on a real
 * Conscrypt TLS 1.3 socket rather than assumed. The fix depends on each of these facts:
 *
 * 1. A writer **waiting for [ControlSocket]'s write lock** is a suspended coroutine, so a timeout or a
 *    cancel ends it on time. This is the keepalive's position behind someone else's stalled write.
 * 2. A writer **inside** the blocking `OutputStream.write` is not suspended. Cancelling it, or a
 *    `withTimeout` around it, only requests cancellation: `withContext` cannot return until the
 *    blocking call does. A coroutine timeout does not end blocking socket I/O.
 * 3. **[ControlSocket.close] does not end it either, and itself blocks.** Conscrypt's engine socket
 *    drains a TLS `close_notify` through the same socket write lock the stalled writer holds, so
 *    `close()` waits for that writer — for as long as the peer stays silent.
 * 4. **[ControlSocket.abort]** — shutting down the TCP send direction first — needs no write lock: the
 *    stalled write fails with an [IOException] at once, and an already-waiting `close()` returns.
 *
 * Measured on both ends, because the two are different objects: the dialled socket is Conscrypt
 * layered over a plain TCP socket, the accepted one is not. The peer never reads, so large frames
 * fill both kernels' buffers and one write blocks for as long as the peer stays silent — a
 * `MANIFEST_PAGE` to a peer that has gone quiet.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BlockingWriteSemanticsTest {
    @Test
    fun `on the accepted socket, a stalled write ignores cancellation and close, and abort releases both`() =
        withTlsPair { scope, accepted, _ -> assertStalledWriteSemantics(scope, accepted, "accepted") }

    @Test
    fun `on the dialled socket, a stalled write ignores cancellation and close, and abort releases both`() =
        withTlsPair { scope, _, dialled -> assertStalledWriteSemantics(scope, dialled, "dialled") }

    private suspend fun assertStalledWriteSemantics(
        scope: CoroutineScope,
        writerSide: ControlSocket,
        label: String,
    ) {
        println("problem 137 evidence [$label]: TLS socket class = ${sslSocketClassOf(writerSide)}")

        // The peer never reads. Fill until one write stops completing.
        val completed = AtomicInteger()
        val writerFailure = AtomicReference<Throwable?>()
        val writer =
            scope.launch {
                try {
                    while (true) {
                        writerSide.writeFrame(bigFrame())
                        completed.incrementAndGet()
                    }
                } catch (
                    // Recorded and asserted below. Rethrown, it would reach Android's uncaught handler.
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    writerFailure.set(t)
                }
            }
        awaitNoProgress(completed)
        println("problem 137 evidence [$label]: ${completed.get()} × ${PAD_BYTES / 1024} KiB frames before a write blocked")
        assertTrue(writer.isActive, "the writer must be blocked inside a write, not finished")

        // (1) A writer waiting for the lock is cancellable: the timeout ends it on time.
        val lockWaitStart = System.nanoTime()
        val lockWaiter = withTimeoutOrNull(LOCK_WAIT_BOUND_MS) { writerSide.writeFrame(smallFrame()) }
        val lockWaitMs = elapsedMs(lockWaitStart)
        println("problem 137 evidence [$label]: a lock waiter timed out after $lockWaitMs ms (bound $LOCK_WAIT_BOUND_MS ms)")
        assertNull(lockWaiter)
        assertTrue(lockWaitMs < LOCK_WAIT_BOUND_MS + SLACK_MS, "a lock waiter must honour its timeout")

        // (2) A writer inside the blocking write is not: cancelling it changes nothing.
        writer.cancel()
        delay(OBSERVE_MS)
        println("problem 137 evidence [$label]: $OBSERVE_MS ms after cancel(), the blocked writer completed = ${writer.isCompleted}")
        assertFalse(writer.isCompleted, "cancellation must not be assumed to end a blocking socket write")

        // (3) close() does not end it, and does not return: it waits for the stalled writer.
        val closeReturned = CountDownLatch(1)
        val closeStart = System.nanoTime()
        thread(name = "problem-137-close", isDaemon = true) {
            writerSide.close()
            closeReturned.countDown()
        }
        val closeReturnedAlone = closeReturned.await(OBSERVE_MS, TimeUnit.MILLISECONDS)
        println(
            "problem 137 evidence [$label]: $OBSERVE_MS ms after close(), close() returned = $closeReturnedAlone, " +
                "the blocked writer completed = ${writer.isCompleted}",
        )
        assertFalse(writer.isCompleted, "close() alone does not release a writer stalled in the kernel")

        // (4) abort() releases the writer, and with it the close() that was waiting.
        val abortStart = System.nanoTime()
        writerSide.abort()
        val abortMs = elapsedMs(abortStart)
        val joined = withTimeoutOrNull(RELEASE_BOUND_MS) { writer.join() }
        val writerReleasedMs = elapsedMs(abortStart)
        val closeReleased = closeReturned.await(RELEASE_BOUND_MS, TimeUnit.MILLISECONDS)
        val closeReleasedMs = elapsedMs(closeStart)
        val cause = generateSequence(writerFailure.get()) { it.cause }.firstOrNull { it is IOException }
        println(
            "problem 137 evidence [$label]: abort() returned in $abortMs ms; the blocked writer ended $writerReleasedMs ms " +
                "after it (I/O cause: ${cause?.javaClass?.simpleName}: ${cause?.message}); the waiting close() returned = " +
                "$closeReleased, $closeReleasedMs ms after it was called",
        )
        assertTrue(abortMs < RELEASE_BOUND_MS, "abort() must not wait for a writer stalled in the kernel")
        assertEquals(Unit, joined, "abort() must release a writer stalled in the kernel")
        assertTrue(cause != null, "the released write must fail as I/O, not succeed")
        assertTrue(closeReleased, "abort() must also release a close() that was waiting for the writer")
    }

    private fun withTlsPair(body: suspend (scope: CoroutineScope, accepted: ControlSocket, dialled: ControlSocket) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val listener = a.channel().bind()
            try {
                val acceptedJob = scope.async { listener.accept() }
                val dialled = b.channel().connect("127.0.0.1", listener.localPort)
                val accepted = withTimeout(HANDSHAKE_MS) { acceptedJob.await() }
                try {
                    body(scope, accepted, dialled)
                } finally {
                    accepted.abort()
                    dialled.abort()
                }
            } finally {
                listener.close()
                scope.cancel()
            }
        }

    private suspend fun awaitNoProgress(completed: AtomicInteger) {
        withTimeout(FILL_BOUND_MS) {
            var last = -1
            while (true) {
                delay(STALL_SAMPLE_MS)
                val now = completed.get()
                if (now == last) return@withTimeout
                last = now
            }
        }
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / NANOS_PER_MS

    private fun sslSocketClassOf(socket: ControlSocket): String {
        val field = ControlSocket::class.java.getDeclaredField("socket")
        field.isAccessible = true
        return field.get(socket).javaClass.name
    }

    private fun bigFrame() = rawEnvelope(PEER, "PAD") { put("pad", "x".repeat(PAD_BYTES)) }

    private fun smallFrame() = rawEnvelope(PEER, "PING") { put("t1_mono_us", 1L) }

    private companion object {
        val PEER = PeerId("aaaaaaaaaaaaaaaa")
        const val PAD_BYTES = 200 * 1024
        const val HANDSHAKE_MS = 10_000L
        const val FILL_BOUND_MS = 30_000L
        const val STALL_SAMPLE_MS = 1_000L
        const val LOCK_WAIT_BOUND_MS = 500L
        const val OBSERVE_MS = 2_000L
        const val RELEASE_BOUND_MS = 2_000L
        const val SLACK_MS = 500L
        const val NANOS_PER_MS = 1_000_000L
    }
}
