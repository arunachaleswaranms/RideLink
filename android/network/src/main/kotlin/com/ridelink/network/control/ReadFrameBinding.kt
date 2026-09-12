package com.ridelink.network.control

import com.ridelink.core.model.SessionId

/**
 * One authenticated connection and the generation that owns it (ADR-024 Amendment A7).
 *
 * Immutable on purpose: a frame read from [socket] is authorised by [generation] for as long as
 * that frame exists, and no later session transition may give it a newer one. Created once in
 * [ControlSessionManager.activateAuthenticatedSession] and discarded whole at the session boundary
 * — it is never mutated, so there is no ordering in which a socket could be seen as authenticated
 * under a generation that is not the one its own activation assigned.
 */
internal class AuthenticatedConnection(
    val socket: ControlSocket,
    val generation: Long,
)

/**
 * One inbound frame's permanent authorisation: the connection it was read from, that connection's
 * wire `session_id`, and the authentication generation that owned the connection **at the moment of
 * the read** — null when the connection was not an authenticated session then.
 *
 * **Why this type exists (ADR-024 Amendment A7).** Every consumer of an inbound Phase 5 frame
 * already took the generation as a *value* rather than looking one up: [ControlRelays.deliver],
 * [com.ridelink.network.playback.PlaybackRelay], the Phase 5 sinks (whose own doc says the value is
 * "the authentication generation that was live **when the frame was read off the wire**") and
 * `Phase5FrameQueue`'s loss ledger. But the value they were handed came from `handleFrame` reading
 * the manager's live `authenticationGeneration` field at *dispatch* time — so the contract the whole
 * chain rests on was never met at its origin, and a Session A frame whose read-loop continuation
 * resumed after a reconnect arrived stamped as Session B's authority. This is that origin, fixed.
 *
 * **It lives in its own file rather than inside [ControlSessionManager].** `config/detekt/detekt.yml`
 * records that that class is already at its `TooManyFunctions` and `LargeClass` ceilings and that
 * the answer is to extract rather than raise the numbers again — the discipline Phase 2a followed
 * for `VoiceSignalRelay` and Phase 5 for [ControlRelays]. [of] is the read loop's whole binding step,
 * so it belongs with the type it produces.
 */
internal class ReadFrameBinding private constructor(
    val socket: ControlSocket,
    val sessionId: SessionId,
    val generation: Long?,
) {
    companion object {
        /**
         * The binding a read loop on [socket] captures for a frame it has just read, given the
         * manager's [authenticated] record.
         *
         * Identity comparison against the *record's* socket rather than against the manager's
         * `activeSocket` is deliberate: the record and its generation are created together and
         * discarded together, so a socket either is the authenticated connection under the exact
         * generation its own activation assigned, or is not an authenticated connection at all.
         */
        fun of(
            authenticated: AuthenticatedConnection?,
            socket: ControlSocket,
            sessionId: SessionId,
        ): ReadFrameBinding =
            ReadFrameBinding(
                socket = socket,
                sessionId = sessionId,
                generation = authenticated?.takeIf { it.socket === socket }?.generation,
            )
    }
}
