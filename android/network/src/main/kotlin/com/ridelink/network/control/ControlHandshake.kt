package com.ridelink.network.control

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Smaller `peer_id` leads (ADR-010), independent of who initiated the connection (ADR-015 A2). */
fun computeLeaderId(
    a: PeerId,
    b: PeerId,
): PeerId = if (a.value < b.value) a else b

/**
 * Placeholder session-id generation. `SessionId` (PROTOCOL §2) is documented as a ULID for
 * sortability; nothing in Phase 1a parses it as one, so a random UUID string is a deliberate
 * "smallest coherent increment" stand-in (CLAUDE.md) rather than a protocol deviation. A real
 * Crockford-base32 ULID generator is a low-risk follow-up, not a Phase 1a blocker.
 */
fun freshSessionId(): SessionId = SessionId(UUID.randomUUID().toString())

sealed class HandshakeOutcome {
    data class Success(
        val remotePeerId: PeerId,
        val remoteConnTiebreak: ConnTiebreak,
        val sessionId: SessionId,
        val leaderPeerId: PeerId,
    ) : HandshakeOutcome()

    data class Rejected(
        val errorCode: String,
    ) : HandshakeOutcome()

    object ConnectionClosed : HandshakeOutcome()
}

data class LocalHandshakeIdentity(
    val displayName: String,
    val platform: String,
    val osVersion: String,
    val appVersion: String,
    val connTiebreak: ConnTiebreak,
)

/**
 * PROTOCOL §4.1 HELLO/HELLO_ACK exchange over [PlainControlTransportPhase1a]. No TLS, no SPKI
 * pin check, no pairing (Phase 1b) — see [ProvisionalIdentity].
 */
object ControlHandshake {
    @Suppress("ReturnCount")
    suspend fun performAsInitiator(
        socket: ControlSocket,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: () -> Long,
        local: LocalHandshakeIdentity,
    ): HandshakeOutcome {
        val proposal = freshSessionId()
        socket.writeFrame(
            ControlMessages.hello(
                localPeerId = localPeerId,
                sessionId = proposal,
                seq = seqCounter.nextSeq(),
                sentAtMonoUs = monotonicNowUs(),
                displayName = local.displayName,
                platform = local.platform,
                osVersion = local.osVersion,
                appVersion = local.appVersion,
                sessionIdProposal = proposal,
                connTiebreak = local.connTiebreak,
            ),
        )

        val frame = socket.readFrame()
        if (frame !is FrameReadResult.Frame || frame.envelope.type != "HELLO_ACK") return mapFailure(frame)
        val payload = frame.envelope.payload

        val remotePeerId = PeerId(payload["peer_id"]!!.jsonPrimitive.content)
        val remoteConnTiebreak = ConnTiebreak(payload["conn_tiebreak"]!!.jsonPrimitive.content)
        val acceptedSessionId = SessionId(payload["accepted_session_id"]!!.jsonPrimitive.content)
        val claimedLeader = PeerId(payload["leader_peer_id"]!!.jsonPrimitive.content)

        val computedLeader = computeLeaderId(localPeerId, remotePeerId)
        if (computedLeader != claimedLeader) return HandshakeOutcome.Rejected(ERROR_CODE_LEADER_MISMATCH)

        return HandshakeOutcome.Success(remotePeerId, remoteConnTiebreak, acceptedSessionId, computedLeader)
    }

    suspend fun performAsAcceptor(
        socket: ControlSocket,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: () -> Long,
        local: LocalHandshakeIdentity,
    ): HandshakeOutcome {
        val frame = socket.readFrame()
        if (frame !is FrameReadResult.Frame || frame.envelope.type != "HELLO") return mapFailure(frame)
        val payload = frame.envelope.payload

        val remotePeerId = PeerId(payload["peer_id"]!!.jsonPrimitive.content)
        val remoteConnTiebreak = ConnTiebreak(payload["conn_tiebreak"]!!.jsonPrimitive.content)
        val initiatorProposal = SessionId(payload["session_id_proposal"]!!.jsonPrimitive.content)

        val leader = computeLeaderId(localPeerId, remotePeerId)
        val acceptedSessionId = if (leader == localPeerId) freshSessionId() else initiatorProposal

        socket.writeFrame(
            ControlMessages.helloAck(
                localPeerId = localPeerId,
                sessionId = acceptedSessionId,
                seq = seqCounter.nextSeq(),
                sentAtMonoUs = monotonicNowUs(),
                acceptedSessionId = acceptedSessionId,
                connTiebreak = local.connTiebreak,
                leaderPeerId = leader,
            ),
        )

        return HandshakeOutcome.Success(remotePeerId, remoteConnTiebreak, acceptedSessionId, leader)
    }

    private fun mapFailure(frame: FrameReadResult): HandshakeOutcome =
        when (frame) {
            is FrameReadResult.ConnectionClosed -> HandshakeOutcome.ConnectionClosed
            is FrameReadResult.FrameTooLarge -> HandshakeOutcome.Rejected(ERROR_CODE_FRAME_TOO_LARGE)
            is FrameReadResult.Malformed -> HandshakeOutcome.Rejected(frame.errorCode)
            is FrameReadResult.Frame -> HandshakeOutcome.Rejected("unexpected_message_type")
        }
}
