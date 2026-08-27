package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.sessionfsm.FsmResult
import com.ridelink.core.sessionfsm.FsmState
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionFsm
import com.ridelink.core.sessionfsm.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.ridelink.core.sessionfsm.LinkLossReason as FsmLinkLossReason

/**
 * [SessionGate] exhausted from every session status, because it is one half of the Phase 1b
 * security invariant and the half that is cheapest to get wrong by accident.
 *
 * `RideLinkPlatformTests.SessionGateTests` is the mirror of this file and asserts the same rows.
 */
class SessionGateTest {
    private val remote = PeerId("bbbbbbbbbbbbbbbb")
    private val allStatuses = SessionStatus.entries

    private val connected = ControlEvent.Connected(remote, SessionId("s"), isLocalLeader = true)
    private val peerTrusted = ControlEvent.PeerTrusted(remote)
    private val pairingRequired = ControlEvent.PairingRequired(remote)
    private val pairingSucceeded =
        ControlEvent.PairingSucceeded(
            TrustedPeer(remote, SpkiHash("sha256:" + "ab".repeat(32)), "B", 1, 1),
        )

    @Test
    fun `Connected never produces PairingSucceeded, from any status`() {
        // The whole bug in one assertion. `Connected` used to be read as "pairing must have
        // worked", which let a TLS socket to an unknown peer walk PAIRING -> CONNECTING -> CONNECTED.
        for (status in allStatuses) {
            val event = SessionGate.sessionEventFor(connected, status)
            assertEquals(
                event != SessionEvent.PairingSucceeded,
                true,
                "Connected must never imply pairing success (status $status)",
            )
        }
    }

    @Test
    fun `Connected only finishes a walk that is already past the trust gate`() {
        assertEquals(SessionEvent.ConnectionEstablished, SessionGate.sessionEventFor(connected, SessionStatus.CONNECTING))
        assertEquals(SessionEvent.ReconnectSucceeded, SessionGate.sessionEventFor(connected, SessionStatus.RECONNECTING))
        for (status in allStatuses - SessionStatus.CONNECTING - SessionStatus.RECONNECTING) {
            assertNull(SessionGate.sessionEventFor(connected, status), "Connected from $status implies no transition")
        }
    }

    @Test
    fun `PAIRING is left for CONNECTING by the trust gate and by nothing else`() {
        val leavesPairing =
            listOf<ControlEvent>(connected, peerTrusted, pairingRequired, pairingSucceeded)
                .filter { SessionGate.sessionEventFor(it, SessionStatus.PAIRING) == SessionEvent.PairingSucceeded }
        assertEquals(listOf(peerTrusted, pairingSucceeded), leavesPairing)
    }

    @Test
    fun `PairingRequired holds the session exactly where it is`() {
        for (status in allStatuses) {
            assertNull(SessionGate.sessionEventFor(pairingRequired, status), "a code on screen is not a transition")
        }
    }

    @Test
    fun `the trust gate only opens from PAIRING`() {
        for (event in listOf(peerTrusted, pairingSucceeded)) {
            for (status in allStatuses - SessionStatus.PAIRING) {
                assertNull(SessionGate.sessionEventFor(event, status), "$event from $status")
            }
        }
    }

    @Test
    fun `a pairing that ends without a pin leaves PAIRING and only PAIRING`() {
        val endings =
            listOf(
                ControlEvent.PairingFailed(ERROR_CODE_PAIRING_REJECTED),
                ControlEvent.HandshakeRefused(ERROR_CODE_PIN_MISMATCH),
            )
        for (event in endings) {
            assertEquals(SessionEvent.PairingRejectedOrTimeout, SessionGate.sessionEventFor(event, SessionStatus.PAIRING))
            for (status in allStatuses - SessionStatus.PAIRING) {
                assertNull(SessionGate.sessionEventFor(event, status), "$event from $status")
            }
        }
    }

    @Test
    fun `a link that dies mid-pairing cannot wedge the session in PAIRING`() {
        // Neither LinkLost event is legal in PAIRING, so without these two rows the FSM would sit
        // there with no prompt and no way forward.
        for (reason in listOf(LinkLossReason.NETWORK, LinkLossReason.BYE)) {
            assertEquals(
                SessionEvent.PairingRejectedOrTimeout,
                SessionGate.sessionEventFor(ControlEvent.LinkLost(reason), SessionStatus.PAIRING),
            )
        }
        for (reason in listOf(LinkLossReason.NETWORK, LinkLossReason.BYE)) {
            val event = SessionGate.sessionEventFor(ControlEvent.LinkLost(reason), SessionStatus.PAIRING)!!
            assertEquals(FsmState(SessionStatus.DISCOVERING), transitionedState(SessionStatus.PAIRING, event))
        }
    }

    @Test
    fun `link loss maps to the FSM's own vocabulary everywhere else`() {
        assertEquals(
            SessionEvent.ConnectionFailed,
            SessionGate.sessionEventFor(ControlEvent.LinkLost(LinkLossReason.NETWORK), SessionStatus.CONNECTING),
        )
        assertEquals(
            SessionEvent.LinkLost(FsmLinkLossReason.NETWORK),
            SessionGate.sessionEventFor(ControlEvent.LinkLost(LinkLossReason.NETWORK), SessionStatus.CONNECTED),
        )
        assertEquals(
            SessionEvent.LinkLost(FsmLinkLossReason.BYE),
            SessionGate.sessionEventFor(ControlEvent.LinkLost(LinkLossReason.BYE), SessionStatus.CONNECTED),
        )
    }

    @Test
    fun `a duplicate or deliberate close is never a transition`() {
        // ADR-015 / ARCHITECTURE §3 rule 6.
        for (reason in listOf(LinkLossReason.DUPLICATE_CONNECTION, LinkLossReason.USER_ENDED)) {
            for (status in allStatuses) {
                assertNull(SessionGate.sessionEventFor(ControlEvent.LinkLost(reason), status), "$reason from $status")
            }
        }
        // Passed through so the FSM records the no-op rather than it vanishing here.
        assertEquals(
            SessionEvent.DuplicateConnectionClosed,
            SessionGate.sessionEventFor(ControlEvent.DuplicateConnectionClosed, SessionStatus.CONNECTED),
        )
    }

    @Test
    fun `an unknown peer cannot reach CONNECTED without PairingSucceeded, replayed against the real FSM`() {
        // The invariant stated as a walk: feed the FSM every control event an unknown peer can
        // produce before pairing settles, in any order, and CONNECTED must stay unreachable.
        val beforePairing =
            listOf(
                connected,
                pairingRequired,
                connected,
                ControlEvent.DuplicateConnectionClosed,
                pairingRequired,
                connected,
            )
        var state = FsmState(SessionStatus.PAIRING)
        for (event in beforePairing) {
            SessionGate.sessionEventFor(event, state.status)?.let { fsmEvent ->
                val result = SessionFsm.transition(state, fsmEvent)
                if (result is FsmResult.Transitioned) state = result.newState
            }
            assertEquals(SessionStatus.PAIRING, state.status, "left PAIRING on $event")
        }
    }

    private fun transitionedState(
        from: SessionStatus,
        event: SessionEvent,
    ): FsmState = (SessionFsm.transition(FsmState(from), event) as FsmResult.Transitioned).newState
}
