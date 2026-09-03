package com.ridelink.network.control

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.protocol.AudioStateMessageTypes
import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.ProtocolVersion
import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceSignalCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-015: 16 CSPRNG bytes as 32 lowercase hex characters, generated once per app process per
 * discovery session, stable across every connection opened or accepted in that session. A
 * distinct value from the device's durable `peer_id` and from the mDNS discovery handle — reusing
 * one random value for two jobs is the exact mistake ADR-015 warns against.
 *
 * (Phase 1a's `ProvisionalIdentity` — a per-process random `peer_id` and a zero-filled sentinel
 * `identity_spki_sha256` — is gone. Both are real now: the `peer_id` is persisted by
 * `data.trustedpeers.LocalPeerIdStore`, and the SPKI hash comes from the Keystore identity key.)
 */
object ConnTiebreakGenerator {
    private const val BYTES = 16
    private val random = SecureRandom()

    fun generate(): ConnTiebreak {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return ConnTiebreak(bytes.joinToString("") { "%02x".format(it) })
    }
}

/** Per-connection monotonic `seq` counter, starting at 1 per session (PROTOCOL §2). */
class SeqCounter {
    private val next = AtomicLong(1)

    fun nextSeq(): Long = next.getAndIncrement()
}

fun newMsgId(): String = UUID.randomUUID().toString()

/** Builds the fixed envelope + type-specific payload for every Phase 1a session message. */
object ControlMessages {
    // PROTOCOL §4.1 HELLO has 9 wire fields plus the local peer_id; one param each is clearer
    // than a wrapper object for something this small and this stable.
    @Suppress("LongParameterList")
    fun hello(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        displayName: String,
        platform: String,
        osVersion: String,
        appVersion: String,
        sessionIdProposal: SessionId,
        connTiebreak: ConnTiebreak,
        identitySpkiSha256: SpkiHash,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = "HELLO",
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload =
                buildJsonObject {
                    put("peer_id", localPeerId.value)
                    put("display_name", displayName)
                    put("platform", platform)
                    put("os_version", osVersion)
                    put("app_version", appVersion)
                    putJsonArray("protocol_versions") { add(JsonPrimitive(ProtocolVersion.CURRENT)) }
                    put("session_id_proposal", sessionIdProposal.value)
                    // Advisory only (PROTOCOL §4.1): cross-checked against the TLS certificate,
                    // and a mismatch is ERROR/identity_mismatch. Trust never derives from it.
                    put("identity_spki_sha256", identitySpkiSha256.value)
                    put("conn_tiebreak", connTiebreak.value)
                },
        )

    fun helloAck(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        acceptedSessionId: SessionId,
        connTiebreak: ConnTiebreak,
        leaderPeerId: PeerId,
        identitySpkiSha256: SpkiHash,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = "HELLO_ACK",
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload =
                buildJsonObject {
                    put("peer_id", localPeerId.value)
                    put("accepted_session_id", acceptedSessionId.value)
                    put("protocol_version", ProtocolVersion.CURRENT)
                    put("identity_spki_sha256", identitySpkiSha256.value)
                    put("conn_tiebreak", connTiebreak.value)
                    put("leader_peer_id", leaderPeerId.value)
                },
        )

    /**
     * PROTOCOL §4.5. Sent by the **initiator** of the surviving connection only, so exactly one
     * pairing exchange runs per first meeting (§4.2).
     *
     * Carries no code and no key material: the six digits are derived independently on each side
     * from the TLS exporter and compared by the two humans. Putting the SAS on the wire would
     * destroy the entire point of the check — a man-in-the-middle would simply forward it.
     */
    fun pairRequest(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        displayName: String,
        platform: String,
        identitySpkiSha256: SpkiHash,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = "PAIR_REQUEST",
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload =
                buildJsonObject {
                    put("display_name", displayName)
                    put("platform", platform)
                    put("identity_spki_sha256", identitySpkiSha256.value)
                },
        )

    /**
     * PROTOCOL §4.5: `{ sas6_accepted: true }` — a **boolean**, never the digits. The sender is
     * asserting "my user looked at my screen and said the two codes match", which is a claim about
     * a human, not a value that could be forwarded.
     */
    fun pairConfirm(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        accepted: Boolean,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = "PAIR_CONFIRM",
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload = buildJsonObject { put("sas6_accepted", accepted) },
        )

    /** PROTOCOL §4.5, the acceptor's verdict. On `accepted`, both sides persist the trusted-peer record. */
    fun pairResult(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        accepted: Boolean,
        identitySpkiSha256: SpkiHash,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = "PAIR_RESULT",
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload =
                buildJsonObject {
                    put("accepted", accepted)
                    put("peer_id", localPeerId.value)
                    put("identity_spki_sha256", identitySpkiSha256.value)
                },
        )

    /**
     * PROTOCOL §7.4 — the four `VOICE_*` frames, encoded from one already-validated [VoiceSignal].
     *
     * There is deliberately no per-message builder here. The bounds and the field shapes live in
     * [VoiceSignalCodec] (in `core`, shared with iOS and pinned by `protocol/vectors/voice-signal/`),
     * so encoding is a mechanical transcription of a value that has already been checked. A second
     * hand-written builder per message type is a second place for the two platforms to disagree.
     */
    fun voiceSignal(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        signal: VoiceSignal,
    ): Envelope {
        val type =
            when (signal) {
                is VoiceSignal.Offer -> VoiceMessageTypes.OFFER
                is VoiceSignal.Answer -> VoiceMessageTypes.ANSWER
                is VoiceSignal.IceCandidate -> VoiceMessageTypes.ICE
                is VoiceSignal.State -> VoiceMessageTypes.STATE
            }
        val payload =
            buildJsonObject {
                when (signal) {
                    is VoiceSignal.Offer -> {
                        put(VoiceSignalCodec.FIELD_VOICE_SESSION_ID, signal.voiceSessionId.value)
                        put(VoiceSignalCodec.FIELD_SDP, signal.sdp)
                    }
                    is VoiceSignal.Answer -> {
                        put(VoiceSignalCodec.FIELD_VOICE_SESSION_ID, signal.voiceSessionId.value)
                        put(VoiceSignalCodec.FIELD_SDP, signal.sdp)
                    }
                    is VoiceSignal.IceCandidate -> {
                        put(VoiceSignalCodec.FIELD_VOICE_SESSION_ID, signal.voiceSessionId.value)
                        put(VoiceSignalCodec.FIELD_CANDIDATE, signal.candidate)
                        // Explicit JSON null rather than an absent key: PROTOCOL §7.4 makes
                        // `sdp_mid` nullable, and a null is the sender saying "identified by index
                        // alone" rather than the sender having forgotten the field.
                        val mid = signal.sdpMid
                        if (mid == null) {
                            put(VoiceSignalCodec.FIELD_SDP_MID, JsonNull)
                        } else {
                            put(VoiceSignalCodec.FIELD_SDP_MID, mid)
                        }
                        put(VoiceSignalCodec.FIELD_SDP_MLINE_INDEX, signal.sdpMlineIndex)
                    }
                    is VoiceSignal.State -> {
                        val id = signal.voiceSessionId
                        if (id == null) {
                            put(VoiceSignalCodec.FIELD_VOICE_SESSION_ID, JsonNull)
                        } else {
                            put(VoiceSignalCodec.FIELD_VOICE_SESSION_ID, id.value)
                        }
                        put(VoiceSignalCodec.FIELD_STATE, signal.state.wire)
                        put(VoiceSignalCodec.FIELD_MIC_MUTED, signal.micMuted)
                        put(VoiceSignalCodec.FIELD_MODE, signal.mode.wire)
                    }
                }
            }
        return envelope(localPeerId, type, sessionId, seq, sentAtMonoUs, payload)
    }

    /**
     * PROTOCOL §4.4 — `AUDIO_STATE`, the effective runtime audio state.
     *
     * The payload is built by [audioStatePayload] from [com.ridelink.core.protocol.AudioStateCodec]'s
     * own field list rather than spelled out here, for the same reason [voiceSignal] defers to
     * `VoiceSignalCodec`: the shape lives in `core`, shared with iOS and pinned by
     * `protocol/vectors/audio-state/`, so a second hand-written builder would be a second place for
     * the two platforms to disagree.
     */
    fun audioState(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        message: AudioStateMessage,
    ): Envelope =
        envelope(
            localPeerId = localPeerId,
            type = AudioStateMessageTypes.AUDIO_STATE,
            sessionId = sessionId,
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            payload = audioStatePayload(message),
        )

    fun ping(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        t1MonoUs: Long,
    ): Envelope = envelope(localPeerId, "PING", sessionId, seq, sentAtMonoUs, buildJsonObject { put("t1_mono_us", t1MonoUs) })

    fun pong(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        t1MonoUs: Long,
        t2MonoUs: Long,
        t3MonoUs: Long,
    ): Envelope =
        envelope(
            localPeerId,
            "PONG",
            sessionId,
            seq,
            sentAtMonoUs,
            buildJsonObject {
                put("t1_mono_us", t1MonoUs)
                put("t2_mono_us", t2MonoUs)
                put("t3_mono_us", t3MonoUs)
            },
        )

    fun ack(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        ackedMsgId: String,
    ): Envelope = envelope(localPeerId, "ACK", sessionId, seq, sentAtMonoUs, buildJsonObject { put("acked_msg_id", ackedMsgId) })

    fun error(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        code: String,
        message: String,
        fatal: Boolean,
    ): Envelope =
        envelope(
            localPeerId,
            "ERROR",
            sessionId,
            seq,
            sentAtMonoUs,
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("fatal", fatal)
            },
        )

    fun bye(
        localPeerId: PeerId,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        reason: String,
    ): Envelope = envelope(localPeerId, "BYE", sessionId, seq, sentAtMonoUs, buildJsonObject { put("reason", reason) })

    private fun envelope(
        localPeerId: PeerId,
        type: String,
        sessionId: SessionId,
        seq: Long,
        sentAtMonoUs: Long,
        payload: JsonObject,
    ): Envelope =
        Envelope(
            v = ProtocolVersion.CURRENT,
            type = type,
            sessionId = sessionId.value,
            senderId = localPeerId.value,
            msgId = newMsgId(),
            seq = seq,
            sentAtMonoUs = sentAtMonoUs,
            requiresAck = false,
            payload = payload,
        )
}

/** `duplicate_connection` BYE reason (PROTOCOL §4.2 / ADR-015). */
const val BYE_REASON_DUPLICATE_CONNECTION = "duplicate_connection"
const val BYE_REASON_USER_ENDED = "user_ended"
const val BYE_REASON_SHUTDOWN = "shutdown"
const val ERROR_CODE_SESSION_ALREADY_ACTIVE = "session_already_active"
const val ERROR_CODE_LEADER_MISMATCH = "leader_mismatch"
const val ERROR_CODE_VERSION_MISMATCH = "version_mismatch"
const val ERROR_CODE_FRAME_TOO_LARGE = "frame_too_large"
const val ERROR_CODE_MALFORMED_FRAME = "malformed_frame"
const val ERROR_CODE_PIN_MISMATCH = "pin_mismatch"
const val ERROR_CODE_IDENTITY_MISMATCH = "identity_mismatch"
const val ERROR_CODE_CERTIFICATE_INVALID = "certificate_invalid"
const val ERROR_CODE_UNTRUSTED_PEER = "untrusted_peer"
const val ERROR_CODE_PAIRING_REJECTED = "pairing_rejected"
const val ERROR_CODE_PAIRING_RATE_LIMITED = "pairing_rate_limited"
const val ERROR_CODE_INTERNAL = "internal"
