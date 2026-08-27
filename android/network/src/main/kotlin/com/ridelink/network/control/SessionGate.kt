package com.ridelink.network.control

import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.sessionfsm.LinkLossReason as FsmLinkLossReason

/**
 * The RideLink **trust gate**: the complete, pure translation from a control-plane
 * [ControlEvent] to the [SessionEvent] the session FSM should be driven with, given the status the
 * session is currently in.
 *
 * It exists as its own object for one reason. The security property Phase 1b turns on —
 *
 * > for an unknown peer there is no execution path that reaches `CONNECTED` before SAS
 * > confirmation and trust persistence
 *
 * — is a property of *this table* plus `SessionFsm`, and both are pure. Keeping the table here
 * rather than inline in a platform `SessionCoordinator` is what lets a laptop unit test exhaust it
 * on both platforms, instead of the invariant only being observable on two phones on a ride.
 * `RideLinkPlatform.SessionGate` is the mirror; the two must agree case for case.
 *
 * It is a translation table and nothing else: it holds no state, reads no clock and performs no
 * side effect. `SessionCoordinator` remains the single owner of the session state (CLAUDE.md rule
 * 8) — it owns the [com.ridelink.core.sessionfsm.FsmState], applies the returned event, and does
 * the side effects (persisting trust, raising a security alert, starting a reconnect) itself.
 *
 * The one rule the table encodes above all others:
 *
 * > [ControlEvent.Connected] never produces [SessionEvent.PairingSucceeded].
 *
 * `PAIRING -> CONNECTING` is reachable **only** from [ControlEvent.PeerTrusted] (the stored pin
 * matched) or [ControlEvent.PairingSucceeded] (both users confirmed and the pin was written).
 */
object SessionGate {
    /**
     * @return the FSM event this control event implies from [status], or null when it implies no
     *   transition at all. Null is a legitimate answer, not a failure: [ControlEvent.PairingRequired]
     *   deliberately keeps the session exactly where it is, in `PAIRING`, waiting for two humans.
     */
    fun sessionEventFor(
        event: ControlEvent,
        status: SessionStatus,
    ): SessionEvent? =
        when (event) {
            // The connection has already passed the trust gate by the time this arrives (see
            // ControlEvent.Connected's contract), so all that is left is to finish the walk to
            // CONNECTED from wherever the session is. Never PairingSucceeded — that would make a
            // TLS socket to an unknown peer indistinguishable from a confirmed pairing.
            is ControlEvent.Connected -> connectionAuthenticated(status)

            // The two — and only two — ways out of PAIRING and into CONNECTING.
            is ControlEvent.PeerTrusted -> pairingPassed(status)
            is ControlEvent.PairingSucceeded -> pairingPassed(status)

            // A six-digit code is on screen. The session stays in PAIRING until two humans answer.
            is ControlEvent.PairingRequired -> null

            is ControlEvent.PairingFailed -> pairingEnded(status)

            // Including `pin_mismatch`, which ADR-012 forbids resolving by re-pairing: it leaves
            // PAIRING for DISCOVERING and the coordinator raises a security alert alongside.
            is ControlEvent.HandshakeRefused -> pairingEnded(status)

            is ControlEvent.LinkLost -> linkLost(event.reason, status)

            // ARCHITECTURE §3 rule 6: passed to the FSM so the no-op is *recorded* as one, rather
            // than dropped here and invisible in the transition log.
            ControlEvent.DuplicateConnectionClosed -> SessionEvent.DuplicateConnectionClosed

            ControlEvent.ReconnectBudgetExhausted -> SessionEvent.ReconnectBudgetExhausted
        }

    private fun connectionAuthenticated(status: SessionStatus): SessionEvent? =
        when (status) {
            SessionStatus.CONNECTING -> SessionEvent.ConnectionEstablished
            SessionStatus.RECONNECTING -> SessionEvent.ReconnectSucceeded
            else -> null
        }

    /**
     * A link that dies mid-pairing is a pairing that did not happen. Neither `LinkLost` event is
     * legal in `PAIRING`, so without these two rows the session would sit there with no prompt and
     * no way forward — which is why the reason is folded into `PairingRejectedOrTimeout` instead.
     */
    private fun linkLost(
        reason: LinkLossReason,
        status: SessionStatus,
    ): SessionEvent? {
        if (status == SessionStatus.PAIRING) {
            return if (reason == LinkLossReason.NETWORK || reason == LinkLossReason.BYE) {
                SessionEvent.PairingRejectedOrTimeout
            } else {
                null
            }
        }
        return when (reason) {
            LinkLossReason.NETWORK ->
                if (status == SessionStatus.CONNECTING) {
                    SessionEvent.ConnectionFailed
                } else {
                    SessionEvent.LinkLost(FsmLinkLossReason.NETWORK)
                }
            LinkLossReason.BYE -> SessionEvent.LinkLost(FsmLinkLossReason.BYE)
            // ADR-015: closing a duplicate is not a fault, and a deliberate local end has already
            // been accounted for by whatever asked for it.
            LinkLossReason.DUPLICATE_CONNECTION, LinkLossReason.USER_ENDED -> null
        }
    }

    private fun pairingPassed(status: SessionStatus): SessionEvent? =
        if (status == SessionStatus.PAIRING) SessionEvent.PairingSucceeded else null

    private fun pairingEnded(status: SessionStatus): SessionEvent? =
        if (status == SessionStatus.PAIRING) SessionEvent.PairingRejectedOrTimeout else null
}
