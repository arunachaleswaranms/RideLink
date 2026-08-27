package com.ridelink.network.control

import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.security.TestTlsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Phase 1b security invariant, end to end over real TLS:
 *
 * > For an **unknown** peer there is no execution path that reaches `CONNECTED` before SAS
 * > confirmation on both sides and trust persistence.
 *
 * Two real [ControlSessionManager]s stand in for the two phones — real P-256 identities, a real
 * TLS 1.3 handshake, real mutual authentication, a real exporter-derived six-digit code — and each
 * one's [ControlEvent] stream drives a real [SessionGate] and a real
 * [com.ridelink.core.sessionfsm.SessionFsm], which is exactly the wiring `SessionCoordinator` has.
 *
 * The bug these exist to keep dead: `ControlEvent.Connected` used to be emitted the moment
 * duplicate resolution picked a survivor, and `SessionCoordinator` read it as implicit pairing
 * success. An unknown peer therefore reached `CONNECTED` *before* the six digits were even
 * displayed, and the SAS screen appeared over an already-connected session. See
 * `docs/STATUS.md` §4.
 *
 * Both peers dial each other in every test, which is the ordinary case (ARCHITECTURE §4.1: both
 * advertise and both browse) and also PROTOCOL §4.2's simultaneous-connect race — so every one of
 * these tests is additionally a duplicate-resolution test.
 */
class PairingSessionIntegrationTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    // ---------------------------------------------------------------- unknown peer

    @Test
    fun `an unknown peer holds the session in PAIRING with one code and no Connected`() =
        twoPhones("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb") { a, b ->
            val promptA = a.awaitPairingPrompt()
            val promptB = b.awaitPairingPrompt()

            // Both screens show the same six digits — the whole point of the exporter binding.
            assertEquals(promptA.sas6, promptB.sas6, "a man-in-the-middle is what two different codes look like")
            assertEquals(6, promptA.sas6.length)
            assertTrue(promptA.sas6.all(Char::isDigit))

            // PROTOCOL §4.2: one prompt per device even though two connections were dialled.
            assertEquals(1, a.countOf { it is ControlEvent.PairingRequired }, "exactly one SAS prompt per device")
            assertEquals(1, b.countOf { it is ControlEvent.PairingRequired })

            assertNoConnected(a, b)
            assertEquals(SessionStatus.PAIRING, a.status)
            assertEquals(SessionStatus.PAIRING, b.status)
            assertNoTrust(a, b)
        }

    @Test
    fun `StartRide is refused while the session is still pairing`() =
        twoPhones("0000000000000001", "0000000000000002") { a, _ ->
            a.awaitPairingPrompt()
            assertFalse(a.apply(SessionEvent.StartRide), "a ride cannot start over an unauthenticated peer")
            assertEquals(SessionStatus.PAIRING, a.status)
        }

    @Test
    fun `this user confirming alone pairs nothing`() =
        twoPhones("1111111111111111", "2222222222222222") { a, b ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()

            a.manager.confirmPairing(accepted = true)
            delay(FsmSession.SETTLE_MS)

            assertEquals(SessionStatus.PAIRING, a.status, "one screen's yes is not a pairing")
            assertEquals(SessionStatus.PAIRING, b.status)
            assertNoConnected(a, b)
            assertNoTrust(a, b)
            assertNotNull(a.manager.pairingPrompt.value, "the code stays up until the other user answers")
        }

    @Test
    fun `the peer confirming alone pairs nothing`() =
        twoPhones("3333333333333333", "4444444444444444") { a, b ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()

            b.manager.confirmPairing(accepted = true)
            delay(FsmSession.SETTLE_MS)

            assertEquals(SessionStatus.PAIRING, a.status, "the remote user cannot pair on this user's behalf")
            assertEquals(SessionStatus.PAIRING, b.status)
            assertNoConnected(a, b)
            assertNoTrust(a, b)
        }

    @Test
    fun `both users confirming pairs once, clears the code and only then reaches CONNECTED`() =
        twoPhones("5555555555555555", "6666666666666666") { a, b, dials ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.manager.confirmPairing(accepted = true)
            b.manager.confirmPairing(accepted = true)

            a.awaitStatus(SessionStatus.CONNECTED)
            b.awaitStatus(SessionStatus.CONNECTED)

            for (session in listOf(a, b)) {
                // The order is the invariant. PairingRequired, then the pin written, then — and
                // only then — a connection the FSM may treat as authenticated.
                assertEquals(
                    listOf("PairingRequired", "PairingSucceeded", "Connected"),
                    session.events.mapNotNull(::gateName),
                    "trust-gate event order",
                )
                assertEquals(
                    listOf(
                        SessionStatus.IDLE,
                        SessionStatus.DISCOVERING,
                        SessionStatus.PAIRING,
                        SessionStatus.CONNECTING,
                        SessionStatus.CONNECTED,
                    ),
                    session.visitedStatuses,
                    "PAIRING and CONNECTING are distinct states and neither is skipped",
                )
                assertNull(session.manager.pairingPrompt.value, "the six digits are dropped the moment pairing settles")
                assertEquals(0, session.manager.reconnectCount)
            }

            // Exactly one trusted-peer record per side, pinning the other's real SPKI.
            assertEquals(
                b.peer.identity.identity.identitySpkiSha256,
                assertNotNull(a.trustStore.byPeerId(b.peer.peerId)).identitySpkiSha256,
            )
            assertEquals(
                a.peer.identity.identity.identitySpkiSha256,
                assertNotNull(b.trustStore.byPeerId(a.peer.peerId)).identitySpkiSha256,
            )
            assertEquals(1, a.trustStore.all().size, "the losing candidate must not have written a pin of its own")
            assertEquals(1, b.trustStore.all().size)

            // PROTOCOL §4.5.1: the code was bound to one exporter, so pairing succeeding must
            // continue on that same socket rather than dial a fresh one.
            assertEquals(listOf(1, 1), dials(), "pairing must not open a second TLS connection")
        }

    // ---------------------------------------------------------------- refusal

    @Test
    fun `this user rejecting never connects and writes no pin`() =
        twoPhones("7777777777777777", "8888888888888888") { a, b ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()

            a.manager.confirmPairing(accepted = false)

            a.awaitEvent { it is ControlEvent.PairingFailed }
            b.awaitEvent { it is ControlEvent.PairingFailed }

            assertPairingRefused(a, b, expectedCode = ERROR_CODE_PAIRING_REJECTED)
        }

    @Test
    fun `the peer rejecting never connects and writes no pin`() =
        twoPhones("9999999999999999", "aaaaaaaabbbbbbbb") { a, b ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()

            b.manager.confirmPairing(accepted = false)

            a.awaitEvent { it is ControlEvent.PairingFailed }
            b.awaitEvent { it is ControlEvent.PairingFailed }

            assertPairingRefused(a, b, expectedCode = ERROR_CODE_PAIRING_REJECTED)
        }

    // ---------------------------------------------------------------- known peer

    @Test
    fun `a peer whose pin matches connects silently and fast`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("cccccccccccccccc", "dddddddddddddddd")
            withPhones(a, b) { sessionA, sessionB, _ ->
                sessionA.awaitStatus(SessionStatus.CONNECTED)
                sessionB.awaitStatus(SessionStatus.CONNECTED)

                for (session in listOf(sessionA, sessionB)) {
                    assertEquals(
                        listOf("PeerTrusted", "Connected"),
                        session.events.mapNotNull(::gateName),
                        "a known peer passes the trust gate on the pin alone",
                    )
                    assertNull(session.manager.pairingPrompt.value, "a known peer is never asked for a code")
                    assertEquals(0, session.countOf { it is ControlEvent.PairingRequired })
                }
            }
        }

    @Test
    fun `a certificate re-issued around the same key stays trusted and asks for no code`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("eeeeeeeeeeeeeeee", "ffffffffffffffff")
            // B's certificate is regenerated around B's existing key: new serial, new validity
            // window, new self-signature, same SPKI (ADR-012 / PROTOCOL §4.5.3).
            val reissuedB = TestPeer(b.peerId, TestTlsSupport.reissue(b.identity), b.trustedPeers, "B")
            withPhones(a, reissuedB) { sessionA, sessionB, _ ->
                sessionA.awaitStatus(SessionStatus.CONNECTED)
                sessionB.awaitStatus(SessionStatus.CONNECTED)
                assertEquals(0, sessionA.countOf { it is ControlEvent.PairingRequired }, "the pin is the key, not the certificate")
            }
        }

    @Test
    fun `forgetting a trusted peer makes the next connection ask for a code again`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("0123456789abcdef", "fedcba9876543210")
            a.trustedPeers.forget(b.peerId)
            b.trustedPeers.forget(a.peerId)
            withPhones(a, b) { sessionA, sessionB, _ ->
                sessionA.awaitPairingPrompt()
                sessionB.awaitPairingPrompt()
                assertNoConnected(sessionA, sessionB)
                assertEquals(SessionStatus.PAIRING, sessionA.status)
            }
        }

    // ---------------------------------------------------------------- pin mismatch

    @Test
    fun `a peer_id wearing a different key is refused and never connects`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("1234123412341234", "5678567856785678")
            // Same peer_id, different identity keypair — an unknown peer wearing a familiar name.
            val impostor = TestPeer(b.peerId, TestTlsSupport.freshIdentity(), InMemoryTrustedPeerStore(), "B")
            withPhones(a, impostor) { sessionA, _, _ ->
                sessionA.awaitEvent { it is ControlEvent.HandshakeRefused }

                val refusal = sessionA.events.filterIsInstance<ControlEvent.HandshakeRefused>().first()
                assertEquals(ERROR_CODE_PIN_MISMATCH, refusal.code)
                assertNull(sessionA.manager.pairingPrompt.value, "a pin mismatch is never resolved by re-pairing")
                assertEquals(0, sessionA.countOf { it is ControlEvent.PairingRequired })
                assertFalse(sessionA.hasReached(SessionStatus.CONNECTED))
                assertEquals(
                    b.identity.identity.identitySpkiSha256,
                    assertNotNull(sessionA.trustStore.byPeerId(b.peerId)).identitySpkiSha256,
                    "the stored pin must be untouched",
                )
            }
        }

    // ---------------------------------------------------------------- helpers

    private fun assertNoConnected(vararg sessions: FsmSession) {
        for (session in sessions) {
            assertEquals(
                0,
                session.countOf { it is ControlEvent.Connected },
                "Connected before the trust gate passed: ${session.events}",
            )
            assertFalse(session.hasReached(SessionStatus.CONNECTED), "CONNECTED reached before pairing: ${session.visitedStatuses}")
            assertFalse(session.hasReached(SessionStatus.CONNECTING))
        }
    }

    private fun assertNoTrust(vararg sessions: FsmSession) {
        for (session in sessions) {
            assertTrue(session.trustStore.all().isEmpty(), "a pin was written without both confirmations")
        }
    }

    private fun assertPairingRefused(
        a: FsmSession,
        b: FsmSession,
        expectedCode: String,
    ) {
        for (session in listOf(a, b)) {
            val failure = session.events.filterIsInstance<ControlEvent.PairingFailed>().first()
            assertEquals(expectedCode, failure.code)
            assertNull(session.manager.pairingPrompt.value, "the six digits are dropped on failure too")
            assertFalse(session.hasReached(SessionStatus.CONNECTED))
            // PROTOCOL §4.5: back where PAIRING came from, and never onward.
            assertEquals(SessionStatus.DISCOVERING, session.status)
            assertEquals(0, session.manager.reconnectCount, "a refused pairing must not become a reconnect")
        }
        assertNoTrust(a, b)
    }

    /** `Connected`/`PeerTrusted`/`PairingRequired`/`PairingSucceeded` only — the trust-gate events. */
    private fun gateName(event: ControlEvent): String? =
        when (event) {
            is ControlEvent.Connected -> "Connected"
            is ControlEvent.PeerTrusted -> "PeerTrusted"
            is ControlEvent.PairingRequired -> "PairingRequired"
            is ControlEvent.PairingSucceeded -> "PairingSucceeded"
            else -> null
        }

    private fun twoPhones(
        aPeerId: String,
        bPeerId: String,
        body: suspend (FsmSession, FsmSession, () -> List<Int>) -> Unit,
    ) = runBlocking {
        val a = TestSessions.unpairedPeer(aPeerId, "A")
        val b = TestSessions.unpairedPeer(bPeerId, "B")
        withPhones(a, b, body)
    }

    private fun twoPhones(
        aPeerId: String,
        bPeerId: String,
        body: suspend (FsmSession, FsmSession) -> Unit,
    ) = twoPhones(aPeerId, bPeerId) { a, b, _ -> body(a, b) }

    /**
     * Stands both phones up, walks each one's FSM as far as `PAIRING` exactly as
     * `SessionCoordinator.startDiscovery`/`maybeConnect` do, then has both dial each other at once.
     */
    private suspend fun withPhones(
        a: TestPeer,
        b: TestPeer,
        body: suspend (FsmSession, FsmSession, () -> List<Int>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val channelA = CountingControlChannel(a.channel())
            val channelB = CountingControlChannel(b.channel())
            val sessionA = FsmSession(a, a.manager(scope, monotonicNowUs, channelA))
            val sessionB = FsmSession(b, b.manager(scope, monotonicNowUs, channelB))
            sessionA.collectInto(scope)
            sessionB.collectInto(scope)

            val portA = sessionA.manager.startListening(a.local)
            val portB = sessionB.manager.startListening(b.local)

            for (session in listOf(sessionA, sessionB)) {
                session.apply(SessionEvent.StartDiscovery)
                session.apply(SessionEvent.PeerSelected)
            }

            // `connectTo` launches into each manager's own scope and returns at once, so calling
            // both here is PROTOCOL §4.2's simultaneous-connect race, not a sequence.
            sessionA.manager.connectTo("127.0.0.1", portB, a.local)
            sessionB.manager.connectTo("127.0.0.1", portA, b.local)

            body(sessionA, sessionB) { listOf(channelA.dials, channelB.dials) }

            sessionA.manager.shutdown()
            sessionB.manager.shutdown()
        } finally {
            scope.cancel()
        }
    }
}
