import Foundation
import RideLinkCore

/// The RideLink **trust gate**: the complete, pure translation from a control-plane `ControlEvent`
/// to the `SessionEvent` the session FSM should be driven with, given the status the session is
/// currently in.
///
/// It exists as its own type for one reason. The security property Phase 1b turns on —
///
/// > for an unknown peer there is no execution path that reaches `CONNECTED` before SAS
/// > confirmation and trust persistence
///
/// — is a property of *this table* plus `SessionFsm`, and both are pure. Keeping the table here
/// rather than inline in the app's `SessionCoordinator` is what lets a laptop unit test exhaust it
/// on both platforms, instead of the invariant only being observable on two phones on a ride.
/// `com.ridelink.network.control.SessionGate` is the mirror; the two must agree case for case.
///
/// It is a translation table and nothing else: it holds no state, reads no clock and performs no
/// side effect. `SessionCoordinator` remains the single owner of the session state (CLAUDE.md rule
/// 8) — it owns the `FsmState`, applies the returned event, and does the side effects (persisting
/// trust, raising a security alert, starting a reconnect) itself.
///
/// The one rule the table encodes above all others:
///
/// > `.connected` never produces `.pairingSucceeded`.
///
/// `PAIRING -> CONNECTING` is reachable **only** from `.peerTrusted` (the stored pin matched) or
/// `.pairingSucceeded` (both users confirmed and the pin was written).
public enum SessionGate {
    /// - Returns: the FSM event this control event implies from `status`, or nil when it implies no
    ///   transition at all. Nil is a legitimate answer, not a failure: `.pairingRequired`
    ///   deliberately keeps the session exactly where it is, in `.pairing`, waiting for two humans.
    public static func sessionEvent(for event: ControlEvent, status: SessionStatus) -> SessionEvent? {
        switch event {
        // The connection has already passed the trust gate by the time this arrives (see
        // `ControlEvent.connected`'s contract), so all that is left is to finish the walk to
        // `.connected` from wherever the session is. Never `.pairingSucceeded` — that would make a
        // TLS socket to an unknown peer indistinguishable from a confirmed pairing.
        case .connected:
            return connectionAuthenticated(status)

        // The two — and only two — ways out of `.pairing` and into `.connecting`.
        case .peerTrusted, .pairingSucceeded:
            return status == .pairing ? .pairingSucceeded : nil

        // A six-digit code is on screen. The session stays in `.pairing` until two humans answer.
        case .pairingRequired:
            return nil

        // `.handshakeRefused` includes `pin_mismatch`, which ADR-012 forbids resolving by
        // re-pairing: it leaves `.pairing` for `.discovering` and the coordinator raises a security
        // alert alongside.
        case .pairingFailed, .handshakeRefused:
            return status == .pairing ? .pairingRejectedOrTimeout : nil

        case .linkLost(let reason):
            return linkLost(reason: reason, status: status)

        // ARCHITECTURE §3 rule 6: passed to the FSM so the no-op is *recorded* as one, rather than
        // dropped here and invisible in the transition log.
        case .duplicateConnectionClosed:
            return .duplicateConnectionClosed

        case .reconnectBudgetExhausted:
            return .reconnectBudgetExhausted
        }
    }

    private static func connectionAuthenticated(_ status: SessionStatus) -> SessionEvent? {
        switch status {
        case .connecting: return .connectionEstablished
        case .reconnecting: return .reconnectSucceeded
        default: return nil
        }
    }

    /// A link that dies mid-pairing is a pairing that did not happen. Neither `.linkLost` event is
    /// legal in `.pairing`, so without these two rows the session would sit there with no prompt
    /// and no way forward — which is why the reason is folded into `.pairingRejectedOrTimeout`.
    private static func linkLost(reason: RideLinkPlatform.LinkLossReason, status: SessionStatus) -> SessionEvent? {
        if status == .pairing {
            switch reason {
            case .network, .bye: return .pairingRejectedOrTimeout
            case .duplicateConnection, .userEnded: return nil
            }
        }
        switch reason {
        case .network:
            return status == .connecting ? .connectionFailed : .linkLost(reason: .network)
        case .bye:
            return .linkLost(reason: .bye)
        // ADR-015: closing a duplicate is not a fault, and a deliberate local end has already been
        // accounted for by whatever asked for it.
        case .duplicateConnection, .userEnded:
            return nil
        }
    }
}
