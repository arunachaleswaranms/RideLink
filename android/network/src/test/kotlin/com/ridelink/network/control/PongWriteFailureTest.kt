package com.ridelink.network.control

import com.ridelink.core.model.PeerId
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
import kotlinx.serialization.json.put
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 123, found on two physical phones (Phase 9B-A): **a `PONG` that cannot be
 * written crashed the app.**
 *
 * `handlePing` answered every `PING` with an unguarded `socket.writeFrame`, and it runs on the read
 * loop, which production launches on the application scope (`SupervisorJob() + Dispatchers.Main`, no
 * exception handler). On the phones, the peer's screen turned off mid-pairing, Android destroyed the
 * peer app's sockets, and the reset arrived between this side reading a `PING` and writing its
 * answer. `sun.net.ConnectionResetException` escaped the read loop and killed the process.
 *
 * The fix does not stop at not crashing. A write can fail while reads stall, so the connection whose
 * `PONG` failed is retired at once through the one existing path, `endConnection(socket, NETWORK)`.
 * That path acts only on the still-active socket, which is what these tests hold it to.
 *
 * The write must fail on a connection that is still **live** when the `PING` is dispatched — the
 * phones' ordering. Closing the socket would end the connection first, and ADR-025 then refuses the
 * `PING` before any write is attempted. So only the live socket's output stream is replaced. Reads,
 * and the connection's liveness up to the failure, are untouched. iOS mirrors the rule
 * (`ControlSessionManager.swift`'s `PING` case).
 */
class PongWriteFailureTest {
    /** Against the unguarded `handlePing`, `handleFrame` throws `IOException("Connection reset")`. */
    @Test
    fun `a PONG that cannot be written retires its own connection once, and the link recovers`() =
        withTrustedPair { pair ->
            val first = pair.connectAndAwait(expectedConnections = 1)
            val attempts = first.socket.failWrites()

            pair.manager.handleFrame(first, ping())

            assertTrue(attempts.get() > 0, "the PONG write was really attempted")
            assertNull(pair.manager.currentReadBinding(), "the connection whose write failed is retired")
            pair.session.awaitStatus(SessionStatus.RECONNECTING)
            assertEquals(
                listOf(ControlEvent.LinkLost(LinkLossReason.NETWORK, retiredAuthGeneration = first.generation)),
                pair.linkLosses(),
                "one network loss, naming the generation that ended",
            )

            // The read loop now sees its socket closed and calls endConnection again: a no-op.
            delay(FsmSession.SETTLE_MS)
            assertEquals(1, pair.linkLosses().size, "no duplicate teardown")

            // Recovery is the ordinary one: the peer dials again, the pin matches, RECONNECTING returns.
            val second = pair.connectAndAwait(expectedConnections = 2)
            pair.session.awaitStatus(SessionStatus.CONNECTED)
            val before = assertNotNull(first.generation)
            assertTrue(assertNotNull(second.generation) > before, "a new authenticated lifetime")
            assertEquals(1, pair.linkLosses().size)
        }

    /**
     * The ownership half: a write authorised on connection 1 that fails only after connection 2 has
     * authenticated must not retire connection 2.
     */
    @Test
    fun `a stale PONG write failure never retires the successor connection`() =
        withTrustedPair { pair ->
            val first = pair.connectAndAwait(expectedConnections = 1)
            val release = CountDownLatch(1)
            val entered = CompletableDeferred<Unit>()
            first.socket.parkThenFailWrites(entered, release)
            val parkedPong = pair.scope.async { pair.manager.handleFrame(first, ping()) }
            withTimeout(FsmSession.TIMEOUT_MS) { entered.await() }

            // Connection 1 dies by itself (no BYE), and the peer's next dial authenticates as 2.
            pair.dropDiallerConnection()
            pair.session.awaitStatus(SessionStatus.RECONNECTING)
            val second = pair.connectAndAwait(expectedConnections = 2)
            pair.session.awaitStatus(SessionStatus.CONNECTED)

            release.countDown()
            withTimeout(FsmSession.TIMEOUT_MS) { parkedPong.await() } // no exception escapes either

            assertSame(second.socket, pair.manager.currentReadBinding()?.socket, "the successor is untouched")
            assertEquals(SessionStatus.CONNECTED, pair.session.status)
            assertEquals(
                listOf(ControlEvent.LinkLost(LinkLossReason.NETWORK, retiredAuthGeneration = first.generation)),
                pair.linkLosses(),
                "only connection 1's own loss, reported once",
            )
        }

    private class Pair(
        val scope: CoroutineScope,
        val manager: ControlSessionManager,
        val session: FsmSession,
        val port: Int,
        val dialler: ControlSessionManager,
        val diallerPeer: TestPeer,
    ) {
        fun linkLosses() = session.events.filterIsInstance<ControlEvent.LinkLost>()

        suspend fun connectAndAwait(expectedConnections: Int): ReadFrameBinding {
            withTimeout(FsmSession.TIMEOUT_MS) { while (dialler.currentReadBinding() != null) delay(POLL_MS) }
            dialler.connectTo("127.0.0.1", port, diallerPeer.local)
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (session.countOf { it is ControlEvent.Connected } < expectedConnections) delay(POLL_MS)
            }
            return assertNotNull(manager.currentReadBinding(), "connection $expectedConnections must be live")
        }

        /** Closes the dialler's end of the live connection without a `BYE`: a network loss. */
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
                // IDLE -> DISCOVERING -> PAIRING, as a dial would leave it; the trusted pin opens CONNECTING.
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

    /** Fails every write the way a reset connection does, and counts the attempts. */
    private fun ControlSocket.failWrites(): AtomicInteger {
        val attempts = AtomicInteger(0)
        replaceOutput(
            object : OutputStream() {
                override fun write(b: Int) {
                    attempts.incrementAndGet()
                    throw IOException("Connection reset")
                }
            },
        )
        return attempts
    }

    /** The first write signals [entered], waits for [release], then fails as a reset connection does. */
    private fun ControlSocket.parkThenFailWrites(
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) {
        replaceOutput(
            object : OutputStream() {
                override fun write(b: Int) {
                    entered.complete(Unit)
                    release.await(PARK_LIMIT_S, TimeUnit.SECONDS)
                    throw IOException("Connection reset")
                }
            },
        )
    }

    /**
     * Replaces the socket's private output stream, leaving its input — and so the read loop and the
     * connection's liveness — alone.
     */
    private fun ControlSocket.replaceOutput(stream: OutputStream) {
        val field = ControlSocket::class.java.getDeclaredField("output")
        field.isAccessible = true
        field.set(this, DataOutputStream(stream))
    }

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
        const val PARK_LIMIT_S = 30L
    }
}
