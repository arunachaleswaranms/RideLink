package com.ridelink.network.control

import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 124, measured end to end on real managers over real loopback TLS 1.3. **A peer
 * that dials this device while its session is in `DISCOVERING` — not `PAIRING` — must still reach
 * `CONNECTED` once the trust gate passes.**
 *
 * That is the shape the phones were in. Device A's own `PAIRING` had ended (a link loss), and it went
 * on advertising. The peer then dialled again, and the manager accepted and paired on that
 * connection. Before ADR-019 Amendment A2, `SessionGate` dropped the verdict in `DISCOVERING`: the
 * session stayed there over an authenticated control connection.
 *
 * [FsmSession] applies the real `SessionGate` and `SessionFsm` to the real manager's events; the
 * coordinator-level mirror is `SessionCoordinatorDiscoveringTrustGateTest` in `:app`.
 */
class InboundTrustWhileDiscoveringTest {
    @Test
    fun `an unknown peer that dials into DISCOVERING pairs and reaches CONNECTED`() =
        runBlocking {
            val a = TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa")
            val c = TestSessions.unpairedPeer("cccccccccccccccc")
            withListeningSession(a) { scope, session, port ->
                val dialler = c.manager(scope, MONOTONIC)
                dialler.startListening(c.local)
                dialler.connectTo("127.0.0.1", port, c.local)

                session.awaitPairingPrompt()
                withTimeout(FsmSession.TIMEOUT_MS) { while (dialler.pairingPrompt.value == null) delay(POLL_MS) }
                assertEquals(SessionStatus.DISCOVERING, session.status, "a code on screen is not a transition")

                session.manager.confirmPairing(accepted = true)
                dialler.confirmPairing(accepted = true)
                session.awaitEvent { it is ControlEvent.Connected }

                assertNotNull(session.trustStore.bySpki(c.identity.identity.identitySpkiSha256), "both users confirmed")
                assertEquals(SessionStatus.CONNECTED, session.status)
                assertFalse(SessionStatus.PAIRING in session.visitedStatuses, "the walk really started in DISCOVERING")
                dialler.shutdown()
            }
        }

    @Test
    fun `a trusted peer that dials into DISCOVERING connects silently`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            withListeningSession(a) { scope, session, port ->
                val dialler = b.manager(scope, MONOTONIC)
                dialler.startListening(b.local)
                dialler.connectTo("127.0.0.1", port, b.local)

                session.awaitEvent { it is ControlEvent.Connected }

                assertEquals(SessionStatus.CONNECTED, session.status)
                assertTrue(session.events.none { it is ControlEvent.PairingRequired }, "the pin matched; no code")
                dialler.shutdown()
            }
        }

    /** Rule 15, unchanged: an unknown peer that only one user confirmed opens nothing. */
    @Test
    fun `an unknown peer in DISCOVERING confirmed by one user only stays out of CONNECTING`() =
        runBlocking {
            val a = TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa")
            val c = TestSessions.unpairedPeer("cccccccccccccccc")
            withListeningSession(a) { scope, session, port ->
                val dialler = c.manager(scope, MONOTONIC)
                dialler.startListening(c.local)
                dialler.connectTo("127.0.0.1", port, c.local)
                session.awaitPairingPrompt()

                session.manager.confirmPairing(accepted = true)
                delay(FsmSession.SETTLE_MS)

                assertEquals(SessionStatus.DISCOVERING, session.status)
                assertTrue(session.events.none { it is ControlEvent.Connected || it is ControlEvent.PairingSucceeded })
                dialler.shutdown()
            }
        }

    /** A listening manager whose session has done `StartDiscovery` and nothing else: no peer selected. */
    private suspend fun withListeningSession(
        peer: TestPeer,
        body: suspend (CoroutineScope, FsmSession, Int) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = peer.manager(scope, MONOTONIC)
        try {
            val session = FsmSession(peer, manager)
            session.collectInto(scope)
            assertTrue(session.apply(SessionEvent.StartDiscovery))
            val port = manager.startListening(peer.local)
            body(scope, session, port)
        } finally {
            manager.shutdown()
            scope.cancel()
        }
    }

    private companion object {
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
    }
}
