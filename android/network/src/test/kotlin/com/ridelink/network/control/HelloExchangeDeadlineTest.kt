package com.ridelink.network.control

import com.ridelink.network.security.TestTlsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * A peer that completes TLS and then says nothing used to park the `HELLO`/`HELLO_ACK` read
 * forever: TLS's own timeout is cleared once the handshake finishes, and the keepalive that detects
 * silence only starts after promotion. Both directions over real TLS 1.3 with real identities —
 * the silent side is a genuine TLS endpoint that simply never speaks the control protocol.
 */
class HelloExchangeDeadlineTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    private fun manager(
        peer: TestPeer,
        scope: CoroutineScope,
    ) = ControlSessionManager(
        scope = scope,
        monotonicNowUs = monotonicNowUs,
        localPeerId = peer.peerId,
        channel = peer.channel(),
        trustedPeers = peer.trustedPeers,
        nowEpochSeconds = { TestTlsSupport.NOW_EPOCH_SECONDS },
        helloExchangeTimeoutMs = DEADLINE_MS,
    )

    @Test
    fun `an inbound peer that never sends HELLO is disconnected, and the listener still serves a real peer`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (sutPeer, fakePeer) = TestSessions.pairedPeers("9999999999999999", "1111111111111111", "SUT", "fake")
                val sut = manager(sutPeer, scope)
                val port = sut.startListening(sutPeer.local)

                val silent = fakePeer.channel().connect("127.0.0.1", port)
                // The SUT must close it; nothing else will ever arrive on this socket. `withTimeout`
                // cannot interrupt a blocking socket read — the very fact the fix rests on — so the
                // test bounds itself the same way, and records whether it had to.
                val closedByTest = AtomicBoolean(false)
                val bound =
                    scope.launch {
                        delay(TEST_BOUND_MS)
                        closedByTest.set(true)
                        silent.close()
                    }
                assertEquals(FrameReadResult.ConnectionClosed, silent.readFrame())
                bound.cancel()
                assertFalse(closedByTest.get(), "the SUT never closed a peer that sent no HELLO")
                silent.close()

                val real = fakePeer.channel().connect("127.0.0.1", port)
                val outcome =
                    ControlHandshake.performAsInitiator(
                        real,
                        fakePeer.peerId,
                        SeqCounter(),
                        monotonicNowUs,
                        fakePeer.local,
                        fakePeer.trustedPeers,
                    )
                assertIs<HandshakeOutcome.Success>(outcome)
                withTimeout(TEST_BOUND_MS) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }
                real.close()
                sut.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a dialled peer that never answers HELLO ends the attempt instead of parking it`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (sutPeer, fakePeer) = TestSessions.pairedPeers("9999999999999999", "1111111111111111", "SUT", "fake")
                val listener = fakePeer.channel().bind()
                // Accepts TLS and then holds the socket open without ever reading or writing.
                val held = scope.async { listener.accept() }
                val sut = manager(sutPeer, scope)
                val lost = scope.async { sut.events.first { it is ControlEvent.LinkLost } }
                scope.launch { sut.connectTo("127.0.0.1", listener.localPort, sutPeer.local) }

                assertIs<ControlEvent.LinkLost>(withTimeout(TEST_BOUND_MS) { lost.await() })
                held.await().close()
                listener.close()
                sut.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        // Also bounds the well-behaved peer's HELLO in the first test, so it leaves a loaded CI
        // runner room for a cold first HELLO/HELLO_ACK; still far below TEST_BOUND_MS.
        const val DEADLINE_MS = 1_500L

        // Far above the deadline, far below "forever": the pre-fix code never finishes at all.
        const val TEST_BOUND_MS = 10_000L
    }
}
