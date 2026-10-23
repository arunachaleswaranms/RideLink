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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * STATUS §4 problem 137 — a stalled control-plane write must not stop the session noticing that its
 * peer has gone. PROTOCOL §1: a `PING` every 2 s, the link lost after 6 s without a `PONG`.
 *
 * `PongWriteFailureTest > a stale PONG write failure never retires the successor connection` timed
 * out under CPU load. Its premise is a `PONG` write parked on connection 1 while the peer goes away.
 * Under load, the peer's own clock-burst `PING` could reach this side in that window. The read loop
 * answers a `PING` **inline**, so its `PONG` waited on the write lock held by the parked write, and
 * the read loop never read the close. The keepalive should then have caught the silence, and could
 * not, for three separate reasons. Each case below pins one of them, and fails if its part of the fix
 * is reverted:
 *
 * 1. **The keepalive's `PING` waited for the write lock outside its timeout.** Fixed by bounding the
 *    whole round trip ([PingWaiters.awaitRoundTrip]).
 * 2. **The keepalive awaited each `PING` inline**, so a `PING` that is *itself* the stalled write —
 *    inside a blocking call, which ignores cancellation — stopped the loss decision for good. Fixed
 *    by never waiting for a `PING` before deciding.
 * 3. **Retiring the connection waited for the stalled write**: Conscrypt's `close()` drains a TLS
 *    `close_notify` through the socket write lock, so a write blocked in the kernel blocked the
 *    close, and the loss after it was never reported (`BlockingWriteSemanticsTest`). Fixed by
 *    [ControlSocket.abort] on a network loss.
 *
 * Cases 1 and 2 park writes in a fake stream that, like a kernel write, ignores cancellation. Case 3
 * uses a real kernel-blocked TLS write to a peer that has stopped reading and has not closed.
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
                pair.assertReconnectingWithin(KEEPALIVE_BOUND_MS, "a write is stalled and the read loop waits behind it")
            } finally {
                release.countDown()
            }
        }

    @Test
    fun `a peer that goes away is noticed within the keepalive bound even while the keepalive's own PING is the stalled write`() =
        withTrustedPair { pair ->
            val first = pair.connectAndAwait()
            // Past the authentication clock burst (11 PINGs, 50 ms apart), so the next PING this side
            // writes is the keepalive's, at most 2 s away, and the next burst is 10 s away.
            delay(AFTER_CLOCK_BURST_MS)
            val release = CountDownLatch(1)
            val entered = CompletableDeferred<Unit>()
            try {
                first.socket.parkPingWrites(entered, release)
                withTimeout(FsmSession.TIMEOUT_MS) { entered.await() }

                // The peer's PING reaches this side's read loop, whose PONG now waits behind the PING.
                assertTrue(pair.dialler.writeRawFrame(ControlMessages.ping(PEER_B, SessionId("s"), 1, 1, 1)))
                delay(SETTLE_MS)

                pair.dropDiallerConnection()
                pair.assertReconnectingWithin(KEEPALIVE_BOUND_MS, "the keepalive's own PING is stalled inside its write")
            } finally {
                release.countDown()
            }
        }

    @Test
    fun `a peer that stops reading without closing is noticed within the keepalive bound while a real write is blocked in the kernel`() =
        withTrustedPair(diallerClock = FROZEN) { pair ->
            pair.connectAndAwait()
            // The peer stops answering and stops reading, and does not close: a phone whose Wi-Fi
            // vanished. Its writes park, its read loop then parks answering this side's PING, and its
            // frozen clock keeps its own keepalive from ever closing the connection for it.
            val diallerSide = assertNotNull(pair.dialler.currentReadBinding(), "the dialler's connection must be live")
            val release = CountDownLatch(1)
            val parked = CompletableDeferred<Unit>()
            val silencedAt = System.nanoTime()
            try {
                diallerSide.socket.parkWrites(parked, release)
                assertTrue(pair.manager.writeRawFrame(ControlMessages.ping(PEER_A, SessionId("s"), 1, 1, 1)))
                withTimeout(FsmSession.TIMEOUT_MS) { parked.await() }

                // This side then writes more than both kernels' buffers hold, so one write blocks.
                val written = AtomicInteger()
                pair.scope.launch {
                    while (pair.manager.writeRawFrame(bigFrame())) written.incrementAndGet()
                }
                withTimeout(FILL_BOUND_MS) {
                    var last = -1
                    while (written.get() != last) {
                        last = written.get()
                        delay(STALL_SAMPLE_MS)
                    }
                }
                assertTrue(pair.session.status == SessionStatus.CONNECTED, "the write must block before the loss bound")

                val remainingMs = KEEPALIVE_BOUND_MS - (System.nanoTime() - silencedAt) / NANOS_PER_MS
                pair.assertReconnectingWithin(remainingMs, "a write is blocked in the kernel and the peer has stopped reading")
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

        suspend fun assertReconnectingWithin(
            boundMs: Long,
            why: String,
        ) {
            val noticed =
                withTimeoutOrNull(boundMs.coerceAtLeast(0)) {
                    while (session.status != SessionStatus.RECONNECTING) delay(POLL_MS)
                }
            if (noticed == null) {
                fail("problem 137: the peer went silent and the session is still ${session.status} after the keepalive bound: $why")
            }
        }
    }

    private fun withTrustedPair(
        diallerClock: () -> Long = MONOTONIC,
        body: suspend (Pair) -> Unit,
    ) = runBlocking {
        val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = a.manager(scope, MONOTONIC)
        val dialler = b.manager(scope, diallerClock)
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

    private fun bigFrame() = rawEnvelope(PEER_A, "PAD") { put("pad", "x".repeat(PAD_BYTES)) }

    /** The first write signals [entered], then blocks until [release], then fails as a reset does. */
    private fun ControlSocket.parkWrites(
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) = replaceOutput(
        object : OutputStream() {
            override fun write(b: Int) {
                entered.complete(Unit)
                release.await(PARK_LIMIT_S, TimeUnit.SECONDS)
                throw IOException("Connection reset")
            }
        },
    )

    /**
     * Every frame passes through unchanged except a `PING`, which signals [entered], blocks until
     * [release] and fails as a reset does. The length prefix arrives as its own write, so it is held
     * until the frame it belongs to says whether to pass or park.
     */
    private fun ControlSocket.parkPingWrites(
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) {
        val real = outputField().get(this) as OutputStream
        replaceOutput(
            object : OutputStream() {
                private var prefix: ByteArray? = null

                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    val held = prefix
                    if (held == null) {
                        prefix = b.copyOfRange(off, off + len)
                        return
                    }
                    prefix = null
                    if (String(b, off, len, Charsets.UTF_8).contains("\"type\":\"PING\"")) {
                        entered.complete(Unit)
                        release.await(PARK_LIMIT_S, TimeUnit.SECONDS)
                        throw IOException("Connection reset")
                    }
                    real.write(held)
                    real.write(b, off, len)
                }

                override fun flush() = real.flush()
            },
        )
    }

    private fun ControlSocket.replaceOutput(stream: OutputStream) = outputField().set(this, DataOutputStream(stream))

    private fun outputField() = ControlSocket::class.java.getDeclaredField("output").apply { isAccessible = true }

    private companion object {
        val PEER_A = PeerId("aaaaaaaaaaaaaaaa")
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }

        /** A clock that never advances, so this peer's own keepalive never declares a loss. */
        val FROZEN: () -> Long = { 1_000_000L }
        const val POLL_MS = 10L
        const val SETTLE_MS = 500L
        const val PARK_LIMIT_S = 60L
        const val AFTER_CLOCK_BURST_MS = 1_500L
        const val PAD_BYTES = 200 * 1024
        const val FILL_BOUND_MS = 6_000L
        const val STALL_SAMPLE_MS = 500L
        const val NANOS_PER_MS = 1_000_000L

        /** PROTOCOL §1's 6 s keepalive loss threshold, plus three of its 2 s intervals of slack. */
        const val KEEPALIVE_BOUND_MS = 9_000L
    }
}
