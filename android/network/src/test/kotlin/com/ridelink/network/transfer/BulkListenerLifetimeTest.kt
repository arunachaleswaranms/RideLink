package com.ridelink.network.transfer

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A5 Finding B — a listener bound under a lifetime that has already ended must
 * never be published.
 *
 * `ensureListening()` suspends inside `bind()`. `close()` does not, and cannot take the same
 * coroutine `Mutex`, so before A5 the sequence
 *
 * ```
 * ensureListening() -> enters bind() -> suspends           (listener still null)
 * close()           -> sees listener == null -> returns    ("session torn down")
 * bind() resumes    -> listener = <the new listener>       (old session republishes)
 * ```
 *
 * left an *old* session's listener accepting connections after that session's teardown had already
 * completed — a direct violation of ADR-023 §1 ("the listener never outlives the session that
 * opened it"). The fix is a lifetime counter bumped **before** anything is closed and re-checked at
 * the publication point.
 *
 * Driven by a [ControlChannel] test double whose `bind()` suspends exactly where the race needs it,
 * rather than by hoping a real TLS bind is slow — the reason [BulkTransportManager] takes the
 * interface. The double is not a transport at all: `connect` is unreachable, and it never carries a
 * byte. The Swift mirror is `RideLinkPlatformTests.TransferManagerTests`'
 * `testABindThatCompletesAfterCloseNeverPublishesItsListener`.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BulkListenerLifetimeTest {
    /**
     * `bind()` announces that it has started, waits to be released, and only then binds a real
     * [ServerSocket] and hands back a [ControlListener] over it. Holding the real socket lets a test
     * assert mechanically that the manager *closed* what it refused to publish, rather than leaking
     * it.
     */
    private class GatedBindChannel : ControlChannel {
        override val transportLabel = "test-gated-bind"

        /** Never dials, never carries a byte — this exists only to make `bind()` suspend on demand. */
        override val isSecure = true

        val bindEntered = CompletableDeferred<Unit>()
        val releaseBind = CompletableDeferred<Unit>()
        val bound = mutableListOf<ServerSocket>()

        override suspend fun bind(): ControlListener {
            bindEntered.complete(Unit)
            releaseBind.await()
            val server = ServerSocket(0)
            bound.add(server)
            // Blocks in a real accept() exactly as the production `acceptOne` does; no test here
            // ever dials, so the error below is genuinely unreachable.
            return ControlListener(server) { s ->
                s.accept()
                error("unreachable: no test in this class ever dials a bulk listener")
            }
        }

        override suspend fun connect(
            host: String,
            port: Int,
        ): ControlSocket = error("no test in this class ever dials a bulk listener")
    }

    private fun manager(channel: ControlChannel) = BulkTransportManager(channel, { System.nanoTime() / 1000 })

    @Test
    fun `a bind that completes after close never publishes its listener`() =
        runBlocking {
            val channel = GatedBindChannel()
            val manager = manager(channel)
            try {
                coroutineScope {
                    // runCatching inside the child: a failing `async` would otherwise cancel this
                    // whole `coroutineScope` before the assertions below could run.
                    val listening = async(Dispatchers.IO) { runCatching { manager.ensureListening() } }
                    withTimeout(TIMEOUT_MS) { channel.bindEntered.await() }
                    assertEquals(null, manager.listenerPortForTesting, "nothing is published while bind is still in flight")

                    // The session boundary lands inside the suspended bind, exactly as it can in
                    // production: close() cannot take listenerMutex, so it runs to completion here.
                    manager.close()

                    channel.releaseBind.complete(Unit)
                    // The resumed bind must fail rather than publish into the lifetime close() ended.
                    val result = withTimeout(TIMEOUT_MS) { listening.await() }
                    assertTrue(
                        result.exceptionOrNull() is IOException,
                        "the resumed bind must fail, not return a port -- got $result",
                    )
                }

                assertEquals(1, channel.bound.size)
                assertTrue(channel.bound[0].isClosed, "the listener the abandoned bind produced must have been closed, not leaked")
                assertEquals(null, manager.listenerPortForTesting, "the stale listener must never have been published")

                // No permanent wedge: the next lifetime binds and publishes normally.
                channel.releaseBind.complete(Unit) // already complete; the next bind proceeds straight through
                val freshPort = withTimeout(TIMEOUT_MS) { manager.ensureListening() }
                assertEquals(2, channel.bound.size)
                assertEquals(freshPort, manager.listenerPortForTesting)
                assertNotEquals(0, freshPort)
            } finally {
                manager.close()
            }
        }

    /**
     * The *other* thing that ends a listener lifetime: A5's cancellation of a pending accept. This
     * asserts the lifetime really ends and the next transfer gets a genuinely new listener.
     *
     * It deliberately does **not** claim to reproduce a bind-versus-cancel publication race, because
     * that race is unreachable by construction and saying otherwise would be a test that looks
     * stronger than it is: `cancelActive` can only reach its `WaitingForAccept` branch while a
     * listener is published, and `ensureListening` only suspends in `bind()` when none is. The
     * epoch bump inside that branch is belt-and-braces on a shared teardown path; the publication
     * rule itself is proven by the `close()` case above, which shares
     * `endListenerLifetime`/`ensureListening` with it.
     */
    @Test
    fun `cancelling a pending accept ends the listener lifetime and the next transfer gets a fresh one`() =
        runBlocking {
            val channel = GatedBindChannel()
            val manager = manager(channel)
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B61")
            try {
                // A first, ordinary lifetime, so there is a listener for `serve` to park on.
                channel.releaseBind.complete(Unit)
                manager.ensureListening()
                manager.issueToken(transferId, 1L)

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            manager.serve(transferId, SpkiHash("sha256:" + "aa".repeat(32)), { 1L }, 1L) { null }
                        }
                    withTimeout(TIMEOUT_MS) {
                        while (manager.pendingAcceptTransferIdForTesting != transferId) delay(POLL_MS)
                    }
                    manager.cancelActive(transferId)
                    assertEquals(BulkServeOutcome.IO_ERROR, withTimeout(TIMEOUT_MS) { serveResult.await() })
                }
                assertEquals(null, manager.listenerPortForTesting, "cancelling the pending accept ends the listener's lifetime")

                val freshPort = withTimeout(TIMEOUT_MS) { manager.ensureListening() }
                assertEquals(freshPort, manager.listenerPortForTesting)
                assertNotEquals(channel.bound[0].localPort, freshPort, "the next transfer must get a genuinely new listener")
            } finally {
                manager.close()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
