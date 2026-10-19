package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.security.TestTlsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 126 / ADR-012 Amendment A1, over real managers and real loopback TLS 1.3:
 * **forgetting a phone can never be bypassed** — not by the next connection, and not by a handshake
 * that read the pin a moment before the forget landed.
 *
 * The handshake reads the trust store during `HELLO` and the connection is promoted only after
 * duplicate resolution's 300 ms grace period, so before this amendment a forget that landed in
 * between was silently overruled: the stale `Trusted` verdict passed the trust gate. [ForgetOnLookup]
 * reproduces that interleaving deterministically — the user's forget runs, through the real
 * [TrustRevocationGate.forget], the instant the handshake's own lookup has returned the
 * pin. Every `Trusted`-path assertion below fails against the unamended `promote`.
 */
class ForgetPeerTrustGateTest {
    @Test
    fun `after both phones forget each other the next connection asks for the code and connects only after both confirm`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            withSessions(a, a.trustedPeers, b) { _, sessionA, sessionB ->
                sessionA.manager.trustGate.forget(b.peerId)
                sessionB.manager.trustGate.forget(a.peerId)
                assertNull(a.trustedPeers.byPeerId(b.peerId))
                assertNull(b.trustedPeers.byPeerId(a.peerId))

                sessionB.manager.connectTo("127.0.0.1", sessionA.port, b.local)

                val promptA = sessionA.awaitPairingPrompt()
                val promptB = sessionB.awaitPairingPrompt()
                assertEquals(promptA.sas6, promptB.sas6, "a fresh first meeting, bound to this TLS session")
                delay(FsmSession.SETTLE_MS)
                assertNeverAuthenticated(sessionA)
                assertNeverAuthenticated(sessionB)

                sessionA.manager.confirmPairing(accepted = true)
                sessionB.manager.confirmPairing(accepted = true)
                sessionA.awaitEvent { it is ControlEvent.Connected }
                sessionB.awaitEvent { it is ControlEvent.Connected }

                assertEquals(listOf("PairingRequired", "PairingSucceeded", "Connected"), sessionA.session.gateEvents())
                assertEquals(b.identity.identity.identitySpkiSha256, assertNotNull(a.trustedPeers.byPeerId(b.peerId)).identitySpkiSha256)
                assertEquals(a.identity.identity.identitySpkiSha256, assertNotNull(b.trustedPeers.byPeerId(a.peerId)).identitySpkiSha256)
            }
        }

    @Test
    fun `a forget that lands after the handshake read the pin sends the connection to the code, not to CONNECTED`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaab", "bbbbbbbbbbbbbbba")
            // B has forgotten A already; A's user forgets B while B's dial is mid-handshake.
            b.trustedPeers.forget(a.peerId)
            val racing = ForgetOnLookup(a.trustedPeers)
            withSessions(a, racing, b) { _, sessionA, sessionB ->
                racing.onLookup = { peerId -> sessionA.manager.trustGate.forget(peerId) }

                sessionB.manager.connectTo("127.0.0.1", sessionA.port, b.local)

                sessionA.awaitPairingPrompt()
                sessionB.awaitPairingPrompt()
                assertTrue(racing.fired, "the forget really landed after the handshake had read the pin")
                delay(FsmSession.SETTLE_MS)
                assertNeverAuthenticated(sessionA)
                assertNull(a.trustedPeers.byPeerId(b.peerId))

                // The code is a real first meeting: confirmed on both screens, it pairs afresh.
                sessionA.manager.confirmPairing(accepted = true)
                sessionB.manager.confirmPairing(accepted = true)
                sessionA.awaitEvent { it is ControlEvent.Connected }
                assertEquals(listOf("PairingRequired", "PairingSucceeded", "Connected"), sessionA.session.gateEvents())
            }
        }

    /**
     * Forgetting is local. The phone that still holds a pin passes its *own* trust gate silently;
     * this one shows the code and, because the other side never confirms one, never authenticates.
     * That is why the documented procedure forgets on both phones (TEST_PLAN I-02/I-16).
     */
    @Test
    fun `a phone that forgot never authenticates on the old pin even when the other phone still trusts it`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaac", "bbbbbbbbbbbbbbbc")
            val racing = ForgetOnLookup(a.trustedPeers)
            withSessions(a, racing, b) { _, sessionA, sessionB ->
                racing.onLookup = { peerId -> sessionA.manager.trustGate.forget(peerId) }

                sessionB.manager.connectTo("127.0.0.1", sessionA.port, b.local)

                sessionA.awaitPairingPrompt()
                sessionB.awaitEvent { it is ControlEvent.Connected }
                delay(FsmSession.SETTLE_MS)
                assertTrue(racing.fired)
                assertNeverAuthenticated(sessionA)
                assertNull(a.trustedPeers.byPeerId(b.peerId), "the forget is not undone by the peer's trust")
            }
        }

    /** The re-check's third verdict: a pin *replaced* in the window is refused, never re-paired. */
    @Test
    fun `a pin replaced while the handshake was in flight is refused as pin_mismatch`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaad", "bbbbbbbbbbbbbbbd")
            val replacement = SpkiHash("sha256:" + "ee".repeat(32))
            val racing = ForgetOnLookup(a.trustedPeers)
            withSessions(a, racing, b) { _, sessionA, sessionB ->
                racing.onLookup = { peerId ->
                    sessionA.manager.trustGate.forget(peerId)
                    a.trustedPeers.remember(TrustedPeer(peerId, replacement, "other", 1, 1))
                }

                sessionB.manager.connectTo("127.0.0.1", sessionA.port, b.local)

                sessionA.awaitEvent { it is ControlEvent.PairingFailed }
                val failure = sessionA.events.filterIsInstance<ControlEvent.PairingFailed>().single()
                assertEquals(ERROR_CODE_PIN_MISMATCH, failure.code)
                assertNull(sessionA.manager.pairingPrompt.value, "a pin mismatch is never resolved by pairing")
                assertNeverAuthenticated(sessionA)
                assertEquals(replacement, assertNotNull(a.trustedPeers.byPeerId(b.peerId)).identitySpkiSha256)
                assertTrue(sessionB.events.none { it is ControlEvent.PairingSucceeded })
            }
        }

    private fun assertNeverAuthenticated(session: FsmSession) {
        assertTrue(session.events.none { it is ControlEvent.PeerTrusted || it is ControlEvent.Connected }, "${session.events}")
        assertFalse(session.hasReached(SessionStatus.CONNECTING), "${session.visitedStatuses}")
        assertFalse(session.hasReached(SessionStatus.CONNECTED))
        assertNull(session.manager.liveAuthenticatedGeneration)
    }

    private fun FsmSession.gateEvents(): List<String> =
        events.mapNotNull {
            when (it) {
                is ControlEvent.PeerTrusted -> "PeerTrusted"
                is ControlEvent.PairingRequired -> "PairingRequired"
                is ControlEvent.PairingSucceeded -> "PairingSucceeded"
                is ControlEvent.Connected -> "Connected"
                else -> null
            }
        }

    private class Listening(
        val session: FsmSession,
        val port: Int,
    ) {
        val manager get() = session.manager

        suspend fun awaitPairingPrompt() = session.awaitPairingPrompt()

        suspend fun awaitEvent(predicate: (ControlEvent) -> Boolean) = session.awaitEvent(predicate)

        val events get() = session.events
    }

    /**
     * A listens with [aStore] behind its manager and only `StartDiscovery` applied — the state a phone
     * is in when the other one dials it. B is a second real manager, also listening, that the test
     * makes dial A.
     */
    private suspend fun withSessions(
        a: TestPeer,
        aStore: TrustedPeerStore,
        b: TestPeer,
        body: suspend (CoroutineScope, Listening, FsmSession) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val managerA =
            ControlSessionManager(
                scope = scope,
                monotonicNowUs = MONOTONIC,
                localPeerId = a.peerId,
                channel = a.channel(),
                trustedPeers = aStore,
                nowEpochSeconds = { TestTlsSupport.NOW_EPOCH_SECONDS },
            )
        val managerB = b.manager(scope, MONOTONIC)
        try {
            val sessionA = FsmSession(a, managerA)
            val sessionB = FsmSession(b, managerB)
            sessionA.collectInto(scope)
            sessionB.collectInto(scope)
            assertTrue(sessionA.apply(SessionEvent.StartDiscovery))
            assertTrue(sessionB.apply(SessionEvent.StartDiscovery))
            assertTrue(sessionB.apply(SessionEvent.PeerSelected))
            val port = managerA.startListening(a.local)
            managerB.startListening(b.local)
            body(scope, Listening(sessionA, port), sessionB)
        } finally {
            withTimeout(FsmSession.TIMEOUT_MS) {
                managerA.shutdown()
                managerB.shutdown()
            }
            scope.cancel()
        }
    }

    private fun assertNeverAuthenticated(session: Listening) = assertNeverAuthenticated(session.session)

    /**
     * Delegates to [delegate], and the first time a lookup returns a pin — which is the handshake's own
     * `PeerTrust.decide` read — runs [onLookup] before handing the pin back. That is a user's forget
     * landing after the verdict was read and before the connection is promoted, without a sleep.
     */
    private class ForgetOnLookup(
        private val delegate: TrustedPeerStore,
    ) : TrustedPeerStore by delegate {
        @Volatile
        var onLookup: (PeerId) -> Unit = {}
        private val armed = AtomicBoolean(true)
        private val lookups = AtomicInteger(0)

        val fired: Boolean get() = !armed.get()

        override fun byPeerId(peerId: PeerId): TrustedPeer? {
            lookups.incrementAndGet()
            val found = delegate.byPeerId(peerId)
            if (found != null && armed.compareAndSet(true, false)) onLookup(peerId)
            return found
        }
    }

    private companion object {
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
    }
}
