package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * STATUS §4 problem 137 — **reproduction; expected to FAIL on current code.**
 *
 * `PongWriteFailureTest > a stale PONG write failure never retires the successor connection` times
 * out under CPU load. Its premise is a `PONG` write parked on connection 1 while the peer goes away.
 * Under load, the peer's own clock-burst `PING` (11 samples, 50 ms apart, right after authentication)
 * can reach this side in that window. The read loop answers a `PING` **inline**, so its `PONG` waits
 * on the connection's write lock held by the parked write. The read loop therefore never reads the
 * close, and nothing reports the loss.
 *
 * This makes that interleaving deterministic, by having the peer send one `PING` after the park and
 * before it goes away, and asserts what production should do: notice that the peer has gone within
 * PROTOCOL §1's keepalive bound (6 s of silence), stalled write or not. It waits 6 s + 3 s.
 *
 * It fails today for a second reason too, and that one is the production defect rather than the
 * test's timing. The keepalive loop is what should catch a peer that has gone silent. But its own
 * `PING` write ([ControlSessionManager]'s `sendPingAndAwait`) also waits on that write lock, outside
 * its timeout, so the keepalive is stuck behind the same stalled write.
 */
class WriteStallLivenessTest {
    @Test
    fun `a peer that goes away is noticed within the keepalive bound even while a write is stalled`() =
        withTrustedPair { pair ->
            val first = pair.connectAndAwait()
            val release = CountDownLatch(1)
            val entered = CompletableDeferred<Unit>()
            try {
                first.socket.parkWrites(entered, release)
                // A write stalls on connection 1 — here this side's PONG, as in PongWriteFailureTest.
                pair.scope.async { pair.manager.handleFrame(first, ping()) }
                withTimeout(FsmSession.TIMEOUT_MS) { entered.await() }

                // The peer's PING reaches this side's read loop while that write is stalled.
                assertTrue(pair.dialler.writeRawFrame(ControlMessages.ping(PEER_B, SessionId("s"), 1, 1, 1)))
                delay(SETTLE_MS)

                // Then the peer goes away.
                pair.dropDiallerConnection()
                val noticed =
                    withTimeoutOrNull(KEEPALIVE_BOUND_MS) {
                        while (pair.session.status != SessionStatus.RECONNECTING) delay(POLL_MS)
                    }
                if (noticed == null) {
                    fail(
                        "problem 137: the peer went away ${KEEPALIVE_BOUND_MS / 1_000} s ago and the session is still " +
                            "${pair.session.status}: the read loop is waiting to write a PONG behind the stalled " +
                            "write, and the keepalive's own PING waits on the same lock",
                    )
                }
            } finally {
                release.countDown()
            }
        }

    private class Pair(
        val scope: CoroutineScope,
        val manager: ControlSessionManager,
        val session: FsmSession,
        val port: Int,
        val dialler: ControlSessionManager,
        val diallerPeer: TestPeer,
    ) {
        suspend fun connectAndAwait(): ReadFrameBinding {
            dialler.connectTo("127.0.0.1", port, diallerPeer.local)
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (session.countOf { it is com.ridelink.network.control.ControlEvent.Connected } < 1) delay(POLL_MS)
            }
            return assertNotNull(manager.currentReadBinding(), "connection 1 must be live")
        }

        fun dropDiallerConnection() {
            assertNotNull(dialler.currentReadBinding(), "the dialler's connection must be live").socket.close()
        }
    }

    private fun withTrustedPair(body: suspend (Pair) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = a.manager(scope, MONOTONIC)
            val dialler = b.manager(scope, MONOTONIC)
            try {
                val session = FsmSession(a, manager)
                session.collectInto(scope)
                val port = manager.startListening(a.local)
                dialler.startListening(b.local)
                assertTrue(session.apply(SessionEvent.StartDiscovery) && session.apply(SessionEvent.PeerSelected))
                body(Pair(scope, manager, session, port, dialler, b))
            } finally {
                dialler.shutdown()
                manager.shutdown()
                scope.cancel()
            }
        }

    private fun ping() =
        FrameReadResult.Frame(
            rawEnvelope(PEER_B, "PING") { put("t1_mono_us", 1L) },
            versionOk = true,
        )

    /** The first write signals [entered], then blocks until [release], then fails as a reset does. */
    private fun ControlSocket.parkWrites(
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) {
        val field = ControlSocket::class.java.getDeclaredField("output")
        field.isAccessible = true
        field.set(
            this,
            DataOutputStream(
                object : OutputStream() {
                    override fun write(b: Int) {
                        entered.complete(Unit)
                        release.await(PARK_LIMIT_S, TimeUnit.SECONDS)
                        throw IOException("Connection reset")
                    }
                },
            ),
        )
    }

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
        const val SETTLE_MS = 500L
        const val PARK_LIMIT_S = 60L

        /** PROTOCOL §1's 6 s keepalive loss threshold, plus three of its 2 s intervals of slack. */
        const val KEEPALIVE_BOUND_MS = 9_000L
    }
}
