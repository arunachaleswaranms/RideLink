package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-025 §4's security half, found while sweeping the pre-authentication family for the same
 * defect class: **a retired connection's `PAIR_CONFIRM` could stand in for the remote user's
 * confirmation of a *different* peer's six digits.**
 *
 * PROTOCOL §4.5 requires **both** users to confirm before any pin is written, and
 * `PairingExchange` splits that into `localConfirmed` (this device's user) and `remoteConfirmed`
 * (the peer's `PAIR_CONFIRM`). Of the three pairing frames, `PAIR_REQUEST` and `PAIR_RESULT` both
 * cross-check the advertised `identity_spki_sha256` against the one this exchange was built for —
 * but `onPairConfirm` carries no SPKI to check and has none to check against. It is a bare boolean.
 *
 * Before ADR-025 nothing else bound it to a connection either: `PAIR_*` is in the
 * pre-authentication allowlist, so it never reaches the generation gate, and `handlePairingFrame`
 * reached for `pairing` — whatever exchange happens to be live — with no reference to the socket
 * the frame was read from. The reachable interleaving:
 *
 * 1. a session with a **known** peer B is live, and B's `PAIR_CONFIRM` (or any frame this test can
 *    stand in for one) is read off socket B; its dispatch has not run yet;
 * 2. the link drops — `endConnection` does **not** cancel the read loop (ADR-024 Amendment A7 §A);
 * 3. an **unknown** peer C connects, PROTOCOL §4.5 pairing starts, and the six digits go up;
 * 4. the parked dispatch runs, and `onPairConfirm(true)` marks C's exchange as remotely confirmed;
 * 5. this device's user says yes to C's code — and because both halves now read confirmed, a pin is
 *    written for a peer whose user never confirmed anything.
 *
 * Narrow, and a real weakening of the one gate that makes the SAS comparison mean something. The
 * fix is the same one line the rest of ADR-025 §4 is: the pre-authentication family is answered only
 * for the connection it was read from.
 *
 * The mirror is `RideLinkPlatformTests.RetiredConnectionPairingTests`.
 */
class RetiredConnectionPairingTest {
    /**
     * Against unmodified `326a145` production sources, `trustStore.all()` below contains peer C and
     * a `PairingSucceeded` has been emitted — with C's user never having been asked.
     */
    @Test
    fun `a PAIR_CONFIRM read from a retired connection cannot confirm the successor's pairing`() =
        knownPeerThenUnknownPeer { sut ->
            sut.manager.handleFrame(sut.parkedOnRetiredConnection, pairConfirm(accepted = true))

            // This device's user now says yes to *C's* six digits. That is one half of §4.5's gate;
            // the stale frame above must not have supplied the other.
            sut.manager.confirmPairing(accepted = true)
            delay(FsmSession.SETTLE_MS)

            assertNull(
                sut.session.trustStore.bySpki(sut.unknownPeer.identity.identity.identitySpkiSha256),
                "peer C was never confirmed by its own user and must not be trusted",
            )
            assertEquals(
                emptyList(),
                sut.session.events.filterIsInstance<ControlEvent.PairingSucceeded>(),
                "no pin may be written while only one user has confirmed",
            )
            assertEquals(
                emptyList(),
                sut.session.events.filterIsInstance<ControlEvent.PairingFailed>(),
                "and the exchange was not destroyed either — it is simply still waiting",
            )
            assertNotNull(sut.manager.pairingPrompt.value, "the six digits stay up until the other user answers")
            assertEquals(1, sut.manager.retiredConnectionFrames, "the stale PAIR_CONFIRM was refused and counted")
        }

    /**
     * The half that must keep working: the **live** connection's own `PAIR_CONFIRM` — the real one,
     * produced by peer C's user tapping confirm — still completes PROTOCOL §4.5 and writes the pin.
     * A fix that refused every pairing frame would pass the test above and break the app.
     */
    @Test
    fun `the live connection's own PAIR_CONFIRM still completes pairing`() =
        knownPeerThenUnknownPeer { sut ->
            sut.manager.confirmPairing(accepted = true)
            sut.unknownManager.confirmPairing(accepted = true)

            sut.session.awaitEvent { it is ControlEvent.PairingSucceeded }

            assertEquals(0, sut.manager.retiredConnectionFrames, "nothing on the live connection was refused")
            assertNotNull(
                sut.session.trustStore.bySpki(sut.unknownPeer.identity.identity.identitySpkiSha256),
                "both users confirmed, so the pin is written",
            )
        }

    /**
     * The other direction of the same defect, and the one that needs no second user action to show
     * itself: `PAIR_RESULT` *does* cross-check the advertised SPKI, so a retired peer B's frame
     * reaching peer C's exchange is an `identity_mismatch` — which `failPairing` turns into a closed
     * pairing, a cleared prompt and a `pin`-shaped security alert on a session that was fine.
     *
     * `failPairing` then calls `endConnection(socketB)`, which returns immediately because socket B
     * is not the active one — so C's socket is left open with `pairing` already null: a pairing that
     * can never complete and never fails visibly again.
     */
    @Test
    fun `a PAIR_RESULT read from a retired connection cannot fail the successor's pairing`() =
        knownPeerThenUnknownPeer { sut ->
            sut.manager.handleFrame(sut.parkedOnRetiredConnection, pairResult(sut.retiredPeerSpki))
            delay(FsmSession.SETTLE_MS)

            assertEquals(
                emptyList(),
                sut.session.events.filterIsInstance<ControlEvent.PairingFailed>(),
                "a retired connection's frame cannot end the successor's pairing",
            )
            assertNotNull(sut.manager.pairingPrompt.value, "the six digits are still up")
            assertEquals(1, sut.manager.retiredConnectionFrames, "the stale PAIR_RESULT was refused and counted")
        }

    /**
     * PROTOCOL §4.6's fatal `ERROR` takes the same `failPairing` path whenever an exchange is live —
     * "the other user said no". From a retired connection it is a *different* user, about a
     * *different* code.
     */
    @Test
    fun `a fatal ERROR read from a retired connection cannot fail the successor's pairing`() =
        knownPeerThenUnknownPeer { sut ->
            sut.manager.handleFrame(sut.parkedOnRetiredConnection, fatalError())
            delay(FsmSession.SETTLE_MS)

            assertEquals(
                emptyList(),
                sut.session.events.filterIsInstance<ControlEvent.PairingFailed>(),
                "a retired connection's ERROR is not this pairing's answer",
            )
            assertNotNull(sut.manager.pairingPrompt.value, "the six digits are still up")
            assertEquals(1, sut.manager.retiredConnectionFrames, "the stale ERROR was refused and counted")
        }

    // --- harness --------------------------------------------------------------------------------

    /**
     * One manager, two sessions: first a silent connect with a peer it already trusts (so a
     * `ReadFrameBinding` on a genuinely authenticated connection can be parked), then — after that
     * link has gone — a first-meeting with an **unknown** peer, which is what puts a live
     * `PairingExchange` on the manager for a stale frame to reach.
     *
     * The unknown peer **dials**; this manager only listens. That makes the surviving socket an
     * accepted one, so this device is PROTOCOL §4.5's *acceptor*, whose `onLocalDecision` settles
     * the exchange immediately when both halves read confirmed — which is the step the defect
     * reaches.
     */
    private class Sut(
        val manager: ControlSessionManager,
        val session: FsmSession,
        val parkedOnRetiredConnection: ReadFrameBinding,
        /** The retired connection's own peer identity — what a stale `PAIR_RESULT` would advertise. */
        val retiredPeerSpki: String,
        val unknownPeer: TestPeer,
        val unknownManager: ControlSessionManager,
    )

    private fun knownPeerThenUnknownPeer(body: suspend (Sut) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val c = TestSessions.unpairedPeer("cccccccccccccccc")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val managers = mutableListOf<ControlSessionManager>()
            try {
                val manager = a.manager(scope, MONOTONIC)
                managers.add(manager)
                val session = FsmSession(a, manager)
                session.collectInto(scope)
                val port = manager.startListening(a.local)

                // Session 1: the known peer dials, the pin matches, the trust gate passes silently.
                val knownManager = b.manager(scope, MONOTONIC)
                managers.add(knownManager)
                knownManager.startListening(b.local)
                knownManager.connectTo("127.0.0.1", port, b.local)
                session.awaitEvent { it is ControlEvent.Connected }
                val parked = assertNotNull(manager.currentReadBinding(), "session 1 must have a connection")
                assertEquals(1L, parked.generation, "session 1 is generation 1")

                // The link goes. `endConnection` does not cancel the read loop, which is what leaves
                // a frame already read off socket B still to be dispatched.
                knownManager.shutdown()
                withTimeout(FsmSession.TIMEOUT_MS) {
                    while (manager.currentReadBinding() != null) delay(POLL_MS)
                }

                // Session 2: an unknown peer dials, so PROTOCOL §4.5 pairing starts here.
                val unknownManager = c.manager(scope, MONOTONIC)
                managers.add(unknownManager)
                unknownManager.startListening(c.local)
                unknownManager.connectTo("127.0.0.1", port, c.local)
                session.awaitPairingPrompt()
                session.awaitEvent { it is ControlEvent.PairingRequired }
                assertTrue(manager.currentReadBinding() != null, "session 2 must have a connection")

                body(
                    Sut(
                        manager = manager,
                        session = session,
                        parkedOnRetiredConnection = parked,
                        retiredPeerSpki = b.identity.identity.identitySpkiSha256.value,
                        unknownPeer = c,
                        unknownManager = unknownManager,
                    ),
                )
            } finally {
                managers.forEach { it.shutdown() }
                scope.cancel()
            }
        }

    private fun pairConfirm(accepted: Boolean) =
        FrameReadResult.Frame(
            rawEnvelope(PEER_B, "PAIR_CONFIRM") { confirmBody(accepted) },
            versionOk = true,
        )

    private fun JsonObjectBuilder.confirmBody(accepted: Boolean) {
        put("sas6_accepted", accepted)
    }

    private fun pairResult(advertisedSpki: String) =
        FrameReadResult.Frame(
            rawEnvelope(PEER_B, "PAIR_RESULT") {
                put("accepted", true)
                put("identity_spki_sha256", advertisedSpki)
            },
            versionOk = true,
        )

    private fun fatalError() =
        FrameReadResult.Frame(
            rawEnvelope(PEER_B, "ERROR") {
                put("fatal", true)
                put("code", "pairing_rejected")
                put("message", "no")
            },
            versionOk = true,
        )

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
    }
}
