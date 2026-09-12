import Foundation
import RideLinkCore

/// One authenticated connection and the generation that owns it (ADR-024 Amendment A7).
///
/// Immutable on purpose: a frame read from `connection` is authorised by `generation` for as long
/// as that frame exists, and no later session transition may give it a newer one. Created once in
/// `ControlSessionManager.activateAuthenticatedSession()` and discarded whole at the session
/// boundary — it is never mutated, so there is no ordering in which a connection could be seen as
/// authenticated under a generation that is not the one its own activation assigned.
///
/// Mirrors Android's `AuthenticatedConnection`.
struct AuthenticatedConnection: Sendable {
    let connection: ControlConnection
    let generation: Int64
}

/// One inbound frame's permanent authorisation: the connection it was read from, that connection's
/// wire `session_id`, and the authentication generation that owned the connection **at the moment
/// of the read** — nil when the connection was not an authenticated session then.
///
/// **Why this type exists (ADR-024 Amendment A7).** Every consumer of an inbound Phase 5 frame
/// already took the generation as a *value* rather than looking one up: `PlaybackRelay`, the Phase 5
/// sinks (whose own doc says the value is "the authentication generation that was live **when the
/// frame was read off the wire**") and `Phase5FrameQueue`'s loss ledger. But the value they were
/// handed came from `handleFrame` reading the manager's live `authenticationGeneration` at
/// *dispatch* time — so the contract the whole chain rests on was never met at its origin, and a
/// Session A frame whose read-loop task resumed after a reconnect arrived stamped as Session B's
/// authority. This is that origin, fixed.
///
/// It lives in its own file for the same reason Android's does: `ControlSessionManager` is the
/// largest type in either codebase, and `of` is the read loop's whole binding step, so it belongs
/// with the type it produces.
///
/// Mirrors Android's `ReadFrameBinding`.
struct ReadFrameBinding: Sendable {
    let connection: ControlConnection
    let sessionId: SessionId
    let generation: Int64?

    private init(connection: ControlConnection, sessionId: SessionId, generation: Int64?) {
        self.connection = connection
        self.sessionId = sessionId
        self.generation = generation
    }

    /// The binding a read loop on `connection` captures for a frame it has just read, given the
    /// manager's `authenticated` record.
    ///
    /// Identity comparison against the *record's* connection rather than against the manager's
    /// `activeSocket` is deliberate: the record and its generation are created together and
    /// discarded together, so a connection either is the authenticated connection under the exact
    /// generation its own activation assigned, or is not an authenticated connection at all.
    static func of(
        authenticated: AuthenticatedConnection?,
        connection: ControlConnection,
        sessionId: SessionId
    ) -> ReadFrameBinding {
        let generation: Int64?
        if let authenticated, authenticated.connection === connection {
            generation = authenticated.generation
        } else {
            generation = nil
        }
        return ReadFrameBinding(connection: connection, sessionId: sessionId, generation: generation)
    }
}
