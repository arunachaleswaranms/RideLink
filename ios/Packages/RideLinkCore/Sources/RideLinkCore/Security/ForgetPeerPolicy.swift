import Foundation

/// When the user may *Forget peer* (ADR-012 Amendment A1, STATUS §4 problem 126), as a pure decision
/// both the coordinator (which enforces it) and the UI (which disables the control) read.
///
/// **Only in `.idle`.** That is the one state in which no control plane belongs to the current session:
/// nothing is listening, dialling, reconnecting, pairing or authenticated. In every other state a
/// connection may exist, or be about to, whose trust verdict was read from the store a moment earlier —
/// and forgetting the peer of a *live* session would need a second way of ending it, which ADR-026
/// (CLAUDE.md rule 21) forbids. A user who wants to forget the phone they are connected to ends the
/// session with the existing control first; `ENDING -> IDLE` is reached only after the single teardown
/// owner has shut the control plane down.
///
/// The policy is not the whole defence. `ControlSessionManager` re-checks the pin, serialised with the
/// forget, at the instant a trusted connection would pass the trust gate — so even a handshake still in
/// flight from a retiring session cannot authenticate on a forgotten pin.
///
/// `com.ridelink.core.security.ForgetPeerPolicy` is the mirror.
public enum ForgetPeerPolicy {
    public static func permits(_ status: SessionStatus) -> Bool { status == .idle }
}

/// What `SessionCoordinator.forgetPeer` did (STATUS §4 problem 126). Mirrors Android's `ForgetPeerResult`.
public enum ForgetPeerResult: Sendable, Equatable, CaseIterable {
    /// The pin is gone; the next connection to that phone pairs afresh with a six-digit code.
    case forgotten
    /// A session is not `.idle`. Nothing changed; the user ends the session first.
    case refusedSessionActive
    /// The store could not make the removal durable. Nothing changed; the phone is still trusted.
    case failed
}
