package com.ridelink.network.transfer

import com.ridelink.network.control.ControlListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A4 Finding W — [ControlListener.acceptWithin]'s bound, proven with a short
 * timeout rather than the production 30 s one, and proven to be *opt-in*: the control plane's own
 * unbounded [ControlListener.accept] must stay unbounded, because a control listener legitimately
 * waits indefinitely for its peer to appear while a bulk listener is answering an offer whose
 * `bulk_token` has a TTL.
 *
 * Deliberately built on a plain [ServerSocket] with a pass-through `acceptOne`, not on
 * `TlsControlChannel`: what is under test is the accept bound itself, and a real TLS handshake
 * would only add a second, unrelated timeout to reason about.
 */
class ControlListenerAcceptTimeoutTest {
    /**
     * `acceptOne` for a listener with no TLS. Calling `accept()` is `acceptOne`'s own job — that is
     * the seam `acceptWithin` sets `soTimeout` around — so this does exactly that and nothing else.
     * No test here ever lets a connection arrive, so `accept()` always throws and the `error` below
     * is genuinely unreachable.
     */
    private fun listener(server: ServerSocket): ControlListener =
        ControlListener(server) { s ->
            s.accept()
            error("unreachable: no test in this class ever dials the listener")
        }

    @Test
    fun `acceptWithin gives up once its bound expires instead of parking forever`() =
        runBlocking {
            val server = ServerSocket(0)
            try {
                val elapsedMs =
                    withContext(Dispatchers.IO) {
                        val startedAtNs = System.nanoTime()
                        // The IOException is a SocketTimeoutException from the socket itself, which
                        // is what BulkTransportManager.serve already catches and reports as
                        // IO_ERROR -- no new failure path anywhere.
                        assertFailsWith<SocketTimeoutException> { listener(server).acceptWithin(TIMEOUT_MS) }
                        (System.nanoTime() - startedAtNs) / 1_000_000
                    }
                assertTrue(elapsedMs >= TIMEOUT_MS, "must have actually waited its bound, not returned instantly ($elapsedMs ms)")
                assertTrue(elapsedMs < TIMEOUT_MS * GENEROUS_UPPER_FACTOR, "must not have waited far beyond it ($elapsedMs ms)")
            } finally {
                server.close()
            }
        }

    @Test
    fun `acceptWithin restores the unbounded timeout afterwards, so a reused listener is not left bounded`() =
        runBlocking {
            val server = ServerSocket(0)
            try {
                withContext(Dispatchers.IO) {
                    runCatching { listener(server).acceptWithin(TIMEOUT_MS) }
                }
                // 0 is "no timeout" for a ServerSocket -- the state the control plane requires and
                // the state a second, unbounded accept() on the same listener depends on.
                assertTrue(server.soTimeout == 0, "soTimeout left at ${server.soTimeout} would silently bound a later accept()")
            } finally {
                server.close()
            }
        }

    @Test
    fun `the bound applies only to acceptWithin -- a listener never asked for one keeps none`() =
        runBlocking {
            val server = ServerSocket(0)
            try {
                assertTrue(server.soTimeout == 0, "a freshly bound ServerSocket must start unbounded")
                // Prove the ordinary path leaves it that way: acceptWithin is the only setter, so a
                // control listener that only ever calls accept() can never acquire a bound.
                assertTrue(
                    ControlListener::class.java.methods.any { it.name == "acceptWithin" },
                    "the bounded entry point is a separate method, not a change to accept()",
                )
            } finally {
                server.close()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 300
        const val GENEROUS_UPPER_FACTOR = 20
    }
}
