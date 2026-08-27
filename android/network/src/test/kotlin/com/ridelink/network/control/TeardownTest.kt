package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This session's brief §9/§10: on shutdown, every task the session owns must actually stop —
 * reconnect, the active socket, pending PING waiters — and a fresh session started afterward on
 * the same (reused) [ControlSessionManager] instance must work cleanly, with nothing left over
 * from the torn-down one.
 */
class TeardownTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    private fun localIdentity(name: String) =
        LocalHandshakeIdentity(
            displayName = name,
            platform = "android",
            osVersion = "test",
            appVersion = "test",
            connTiebreak = ConnTiebreakGenerator.generate(),
        )

    @Test
    fun `shutdown stops the reconnect ladder -- no further attempts afterward`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peer = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("1010101010101010"))
                val deadListener = ControlListener.bind()
                val deadPort = deadListener.localPort
                deadListener.close()

                peer.beginReconnect(localIdentity("A"), "127.0.0.1", deadPort)
                delay(200) // let the ladder get going
                peer.shutdown()

                val countAtShutdown = peer.reconnectCount
                delay(2_000)
                assertEquals(countAtShutdown, peer.reconnectCount, "no reconnect attempt may run after shutdown")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `shutdown closes the active socket -- the peer observes connection closed`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sut = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("2020202020202020"))
                val port = sut.startListening(localIdentity("SUT"))

                val fakePeerId = PeerId("3030303030303030")
                val fake = ControlSocket.connect("127.0.0.1", port)
                val outcome = ControlHandshake.performAsInitiator(fake, fakePeerId, SeqCounter(), monotonicNowUs, localIdentity("fake"))
                check(outcome is HandshakeOutcome.Success)

                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                sut.shutdown()

                // The SUT's own keepalive/clock-sync loop may still be sending its own PING/PONG
                // traffic on this socket in the instant before shutdown's cancellation takes
                // effect; skip over those and look for the actual teardown signal — BYE followed
                // by (or coinciding with) the socket closing.
                var closedCleanly = false
                withTimeout(5_000) {
                    while (!closedCleanly) {
                        when (val result = fake.readFrame()) {
                            is FrameReadResult.ConnectionClosed -> closedCleanly = true
                            is FrameReadResult.Frame -> if (result.envelope.type == "BYE") closedCleanly = true
                            else -> Unit
                        }
                    }
                }
                assert(closedCleanly)

                fake.close()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new session on the same reused manager starts cleanly after shutdown`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sut = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("4040404040404040"))
                val peerB1 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("5050505050505050"))

                val portB1 = peerB1.startListening(localIdentity("B1"))
                val portSut1 = sut.startListening(localIdentity("SUT"))
                sut.connectTo("127.0.0.1", portB1, localIdentity("SUT"))
                peerB1.connectTo("127.0.0.1", portSut1, localIdentity("B1"))
                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                sut.shutdown()
                peerB1.shutdown()
                assertEquals(ControlState.ENDED, sut.diagnostics.value.controlState)

                // Reuse the SAME sut instance for a fresh session, matching how
                // SessionCoordinator holds one ControlSessionManager for the app's lifetime.
                val peerB2 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("6060606060606060"))
                val portB2 = peerB2.startListening(localIdentity("B2"))
                val portSut2 = sut.startListening(localIdentity("SUT"))
                sut.connectTo("127.0.0.1", portB2, localIdentity("SUT"))
                peerB2.connectTo("127.0.0.1", portSut2, localIdentity("B2"))

                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }
                assertEquals(ControlState.CONNECTED, sut.diagnostics.value.controlState)

                sut.shutdown()
                peerB2.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `shutdown mid clock burst leaves no stale pending ping to collide with the next session`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peerA1 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("7070707070707070"))
                val peerB1 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("8080808080808080"))
                val portA1 = peerA1.startListening(localIdentity("A"))
                val portB1 = peerB1.startListening(localIdentity("B"))
                peerA1.connectTo("127.0.0.1", portB1, localIdentity("A"))
                peerB1.connectTo("127.0.0.1", portA1, localIdentity("B"))
                withTimeout(5_000) { peerA1.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                // Tear down immediately, almost certainly while the first 11-sample clock burst
                // is still in flight.
                peerA1.shutdown()
                peerB1.shutdown()

                // A fresh pair, reusing the same clock (so t1 values can collide with the old
                // session's), must still converge cleanly.
                val peerA2 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("7070707070707070"))
                val peerB2 = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("8080808080808080"))
                val portA2 = peerA2.startListening(localIdentity("A"))
                val portB2 = peerB2.startListening(localIdentity("B"))
                peerA2.connectTo("127.0.0.1", portB2, localIdentity("A"))
                peerB2.connectTo("127.0.0.1", portA2, localIdentity("B"))
                withTimeout(5_000) { peerA2.diagnostics.first { it.controlState == ControlState.CONNECTED } }
                withTimeout(2_500) { peerA2.diagnostics.first { it.clockOffsetUs != null } }

                peerA2.shutdown()
                peerB2.shutdown()
            } finally {
                scope.cancel()
            }
        }
}
