package com.ridelink.network.control

import com.ridelink.network.security.TestTlsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * STATUS §4 problem 110, reproduced first on the physical OnePlus Nord 5 (Phase 9A): one inbound
 * connection whose TLS handshake failed ended the control accept loop for the rest of the session.
 * The listener stayed bound and advertised, so later peers connected at the TCP level and then
 * waited for a TLS answer that never came, while the UI kept saying "Finding your peer…".
 *
 * A failed candidate is that candidate's failure, never the listener's. Real TLS 1.3 with real
 * identities; the failing candidates are the two cheapest things any host on the Wi-Fi can do.
 */
class InboundHandshakeFailureTest {
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
    )

    @Test
    fun `failed inbound handshakes do not stop the listener from serving a real peer`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (sutPeer, fakePeer) = TestSessions.pairedPeers("9999999999999999", "1111111111111111", "SUT", "fake")
                val sut = manager(sutPeer, scope)
                val port = sut.startListening(sutPeer.local)

                repeat(FAILED_CANDIDATES) {
                    sendNotTls(port) // bytes that are not a ClientHello: the server handshake throws
                    connectAndHangUp(port) // EOF in the middle of the server handshake
                }

                val real =
                    runCatching { fakePeer.channel().connect("127.0.0.1", port) }.getOrElse { failure ->
                        fail("the listener stopped accepting after a failed inbound handshake: $failure")
                    }
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

    /** Writes garbage and waits for the server to give up on it (EOF), so the next candidate is
     *  only accepted after this one has definitely failed. */
    private suspend fun sendNotTls(port: Int) =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = TEST_BOUND_MS.toInt()
                socket.getOutputStream().apply {
                    write("GET / HTTP/1.1\r\nHost: ridelink\r\n\r\n".toByteArray())
                    flush()
                }
                try {
                    while (socket.getInputStream().read() != -1) Unit
                } catch (timeout: SocketTimeoutException) {
                    fail("a failing candidate was never closed: the listener stopped accepting, or left the socket open: $timeout")
                }
            }
        }

    private suspend fun connectAndHangUp(port: Int) =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", port).close()
        }

    private companion object {
        const val FAILED_CANDIDATES = 3

        // Far above a real handshake on loopback; the pre-fix listener never answers at all.
        const val TEST_BOUND_MS = 10_000L
    }
}
