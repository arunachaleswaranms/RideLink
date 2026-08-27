package com.ridelink.network.control

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.PeerTrust
import com.ridelink.core.security.PinDecision
import com.ridelink.core.security.TrustedPeerStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Smaller `peer_id` leads (ADR-010), independent of who initiated the connection (ADR-015 A2). */
fun computeLeaderId(
    a: PeerId,
    b: PeerId,
): PeerId = if (a.value < b.value) a else b

/**
 * Placeholder session-id generation. `SessionId` (PROTOCOL §2) is documented as a ULID for
 * sortability; nothing yet parses it as one, so a random UUID string is a deliberate "smallest
 * coherent increment" stand-in (CLAUDE.md) rather than a protocol deviation. A real
 * Crockford-base32 ULID generator is a low-risk follow-up, not a phase blocker.
 */
fun freshSessionId(): SessionId = SessionId(UUID.randomUUID().toString())

sealed class HandshakeOutcome {
    data class Success(
        val remotePeerId: PeerId,
        val remoteConnTiebreak: ConnTiebreak,
        val sessionId: SessionId,
        val leaderPeerId: PeerId,
        /** Computed from the peer's TLS certificate — the only trustworthy identity input (ADR-012). */
        val peerIdentitySpkiSha256: SpkiHash,
        /**
         * Whether this peer is already trusted, needs pairing, or must be refused. Carried rather
         * than acted on here, because PROTOCOL §4.5 says pairing runs **only on the surviving
         * connection** — so the decision is made once, at handshake time, and applied after
         * duplicate-connection resolution.
         */
        val pinDecision: PinDecision,
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
    /** This device's own `identity_spki_sha256` (ADR-012). Advisory on the wire; the certificate is authoritative. */
    val identitySpkiSha256: SpkiHash,
)

/**
 * PROTOCOL §4.1 HELLO/HELLO_ACK, plus the SPKI pin check that decides whether this peer is
 * trusted, unknown, or refused.
 *
 * **Every field read here comes off the wire**, so nothing in this file may throw on a malformed
 * value: a peer that sends `"peer_id": 7` or omits `conn_tiebreak` must get a clean
 * `malformed_frame` and a closed socket, not an exception that kills the coroutine running the
 * read loop. That is the same class of bug the Phase 1a hardening pass fixed for PING/PONG
 * (STATUS §2e fix 6); HELLO was not covered then because Phase 1a had no security-bearing field
 * in it to get wrong.
 *
 * **Ordering.** PROTOCOL §4.1's diagram draws the pin check before HELLO, while its normative
 * table defines the pin as "the stored pin **for that `peer_id`**" — which HELLO is what carries.
 * Both are honoured: the certificate's own structural validity is checked before HELLO is sent
 * (so an expired or unverifiable certificate never gets a session's worth of device metadata out
 * of us), and the pin comparison happens once `peer_id` is known.
 */
object ControlHandshake {
    @Suppress("ReturnCount")
    suspend fun performAsInitiator(
        socket: ControlSocket,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: () -> Long,
        local: LocalHandshakeIdentity,
        trustedPeers: TrustedPeerStore,
    ): HandshakeOutcome {
        val security = socket.security ?: return HandshakeOutcome.Rejected(ERROR_CODE_INTERNAL)
        if (!security.peerCertificateStructurallyValid) {
            return HandshakeOutcome.Rejected(ERROR_CODE_CERTIFICATE_INVALID)
        }

        val proposal = freshSessionId()
        val sent =
            socket.writeOrClosed(
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
                    identitySpkiSha256 = local.identitySpkiSha256,
                ),
            )
        if (!sent) return HandshakeOutcome.ConnectionClosed

        val frame = socket.readFrame()
        if (frame !is FrameReadResult.Frame || frame.envelope.type != "HELLO_ACK") return mapFailure(frame)
        val payload = frame.envelope.payload

        val remotePeerId = payload.peerId("peer_id") ?: return malformed()
        val remoteConnTiebreak = payload.connTiebreak("conn_tiebreak") ?: return malformed()
        val acceptedSessionId = payload.text("accepted_session_id")?.let(::SessionId) ?: return malformed()
        val claimedLeader = payload.peerId("leader_peer_id") ?: return malformed()

        val computedLeader = computeLeaderId(localPeerId, remotePeerId)
        if (computedLeader != claimedLeader) return HandshakeOutcome.Rejected(ERROR_CODE_LEADER_MISMATCH)

        return finish(payload, security, remotePeerId, remoteConnTiebreak, acceptedSessionId, computedLeader, trustedPeers)
    }

    @Suppress("ReturnCount")
    suspend fun performAsAcceptor(
        socket: ControlSocket,
        localPeerId: PeerId,
        seqCounter: SeqCounter,
        monotonicNowUs: () -> Long,
        local: LocalHandshakeIdentity,
        trustedPeers: TrustedPeerStore,
    ): HandshakeOutcome {
        val security = socket.security ?: return HandshakeOutcome.Rejected(ERROR_CODE_INTERNAL)
        if (!security.peerCertificateStructurallyValid) {
            return HandshakeOutcome.Rejected(ERROR_CODE_CERTIFICATE_INVALID)
        }

        val frame = socket.readFrame()
        if (frame !is FrameReadResult.Frame || frame.envelope.type != "HELLO") return mapFailure(frame)
        val payload = frame.envelope.payload

        val remotePeerId = payload.peerId("peer_id") ?: return malformed()
        val remoteConnTiebreak = payload.connTiebreak("conn_tiebreak") ?: return malformed()
        val initiatorProposal = payload.text("session_id_proposal")?.let(::SessionId) ?: return malformed()

        val leader = computeLeaderId(localPeerId, remotePeerId)
        val acceptedSessionId = if (leader == localPeerId) freshSessionId() else initiatorProposal

        val sent =
            socket.writeOrClosed(
                ControlMessages.helloAck(
                    localPeerId = localPeerId,
                    sessionId = acceptedSessionId,
                    seq = seqCounter.nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    acceptedSessionId = acceptedSessionId,
                    connTiebreak = local.connTiebreak,
                    leaderPeerId = leader,
                    identitySpkiSha256 = local.identitySpkiSha256,
                ),
            )
        if (!sent) return HandshakeOutcome.ConnectionClosed

        return finish(payload, security, remotePeerId, remoteConnTiebreak, acceptedSessionId, leader, trustedPeers)
    }

    // One handshake's worth of already-validated fields, and one return per outcome.
    @Suppress("LongParameterList", "ReturnCount")
    private fun finish(
        payload: JsonObject,
        security: ChannelSecurity,
        remotePeerId: PeerId,
        remoteConnTiebreak: ConnTiebreak,
        sessionId: SessionId,
        leaderPeerId: PeerId,
        trustedPeers: TrustedPeerStore,
    ): HandshakeOutcome {
        // Absent is tolerated (PROTOCOL §2 rule 1: unknown/missing fields are not fatal) and simply
        // means "nothing to cross-check". Present-but-malformed is not: a peer that sends a
        // wrongly-shaped identity field is either broken or probing, and either way the session is
        // not worth having.
        val advertised = payload["identity_spki_sha256"]
        if (advertised != null && payload.spkiHash("identity_spki_sha256") == null) return malformed()

        val decision =
            PeerTrust.decide(
                storedPin = trustedPeers.byPeerId(remotePeerId)?.identitySpkiSha256,
                presentedSpki = security.peerIdentitySpkiSha256,
                helloAdvertisedSpki = payload.spkiHash("identity_spki_sha256"),
                certificateStructurallyValid = security.peerCertificateStructurallyValid,
            )
        if (decision is PinDecision.Refused) return HandshakeOutcome.Rejected(decision.code)

        return HandshakeOutcome.Success(
            remotePeerId = remotePeerId,
            remoteConnTiebreak = remoteConnTiebreak,
            sessionId = sessionId,
            leaderPeerId = leaderPeerId,
            peerIdentitySpkiSha256 = security.peerIdentitySpkiSha256,
            pinDecision = decision,
        )
    }

    /**
     * A handshake write can fail for a reason that is not this side's fault and is not an error:
     * the peer refused the certificate and closed, or the session was torn down mid-handshake. An
     * `IOException` escaping here would leave the socket unclosed and produce no outcome at all,
     * so a failed write is reported the same way a failed read already is — as
     * [HandshakeOutcome.ConnectionClosed], which the caller closes and moves on from.
     */
    @Suppress("SwallowedException") // the reason is exactly "the peer went away", which is the return value
    private suspend fun ControlSocket.writeOrClosed(envelope: com.ridelink.core.protocol.Envelope): Boolean =
        try {
            writeFrame(envelope)
            true
        } catch (io: java.io.IOException) {
            false
        }

    private fun malformed(): HandshakeOutcome = HandshakeOutcome.Rejected(ERROR_CODE_MALFORMED_FRAME)

    private fun mapFailure(frame: FrameReadResult): HandshakeOutcome =
        when (frame) {
            is FrameReadResult.ConnectionClosed -> HandshakeOutcome.ConnectionClosed
            is FrameReadResult.FrameTooLarge -> HandshakeOutcome.Rejected(ERROR_CODE_FRAME_TOO_LARGE)
            is FrameReadResult.Malformed -> HandshakeOutcome.Rejected(frame.errorCode)
            is FrameReadResult.Frame -> HandshakeOutcome.Rejected("unexpected_message_type")
        }

    /**
     * Wire-safe field readers. Each returns null — never throws — for a missing field, a field of
     * the wrong JSON type, a quoted number, or a value that fails its identifier's format check.
     * The value-class constructors (`PeerId`, `ConnTiebreak`) `require` their format because that
     * is right for values *we* produce; these wrappers are how the same types are built from
     * values a **peer** chose.
     */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.peerId(key: String): PeerId? = text(key)?.takeIf { PEER_ID_FORMAT.matches(it) }?.let(::PeerId)

    private fun JsonObject.connTiebreak(key: String): ConnTiebreak? =
        text(key)?.takeIf { CONN_TIEBREAK_FORMAT.matches(it) }?.let(::ConnTiebreak)

    private fun JsonObject.spkiHash(key: String): SpkiHash? = text(key)?.let(SpkiHash::parse)

    private val PEER_ID_FORMAT = Regex("^[0-9a-f]{16}$")
    private val CONN_TIEBREAK_FORMAT = Regex("^[0-9a-f]{32}$")
}
