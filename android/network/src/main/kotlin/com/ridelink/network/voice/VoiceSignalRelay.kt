package com.ridelink.network.voice

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceSignalCodec
import com.ridelink.core.protocol.VoiceSignalRejection
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.network.control.ControlMessages
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes one already-built frame to the surviving **authenticated** control connection.
 *
 * A named type rather than an inline lambda so the suspend signature is unambiguous at both ends, and
 * so the thing being handed across the seam has a name that says what it is allowed to do.
 */
internal fun interface AuthenticatedFrameWriter {
    suspend fun write(envelope: com.ridelink.core.protocol.Envelope)
}

/**
 * The `VOICE_*` half of the control plane: decode inbound frames, encode outbound ones, and count
 * what was refused.
 *
 * **Why this is a separate type.** `ControlSessionManager` was already the largest class in the
 * codebase and `docs/STATUS.md` §4 problem 18 predicted it would get worse — Phase 2a is exactly the
 * change that would have made it worse. detekt's `LargeClass` fired on the first attempt to add the
 * voice wiring inline, and the answer was to extract rather than to raise the threshold. Everything
 * here is genuinely separable: none of it touches the session, the handshake, pairing, reconnect or
 * the clock.
 *
 * **What it deliberately does not decide.** Whether a frame is *allowed* is not this type's business
 * and cannot be: PROTOCOL §7.1's gate is `ControlSessionManager`'s pre-authentication frame
 * allowlist, which drops every `VOICE_*` type before the read loop's dispatch ever reaches
 * [deliver]. What this type adds is the *encoding*, the *bounds*, and the counters — so that a
 * refused frame is a visible fact rather than an absence.
 *
 * The one guard it does enforce is on the way out: [send] refuses unless the caller's socket supplier
 * yields an **authenticated** connection, so a `VoiceController` wired up by mistake before the trust
 * gate still could not put an SDP on a socket.
 */
class VoiceSignalRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    /**
     * Yields a writer for the surviving connection **only while it is authenticated**, and null
     * otherwise. A supplier rather than a socket because the connection comes and goes and this type
     * must never hold one across a teardown.
     */
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
) : VoiceSignalTransport {
    @Volatile
    var sink: VoiceSignalSink? = null

    private val rejections = ConcurrentHashMap<VoiceSignalRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    val rejectionCounts: Map<VoiceSignalRejection, Int> get() = rejections.toMap()

    override suspend fun send(signal: VoiceSignal): Boolean {
        val write = authenticatedWriter() ?: return false
        return runCatching {
            write.write(
                ControlMessages.voiceSignal(
                    localPeerId = localPeerId,
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    signal = signal,
                ),
            )
        }.isSuccess
    }

    /**
     * PROTOCOL §7.4: parse, bounds-check, hand over — and on any failure, **drop the frame and keep
     * the connection**. The framing was intact; only this message's shape was wrong. An
     * attacker-supplied SDP must not be able to end a ride's control plane, and the bounds are
     * checked before the string reaches the media stack, so it cannot make the reader allocate
     * either.
     *
     * Called only from the read loop's authenticated dispatch.
     */
    fun deliver(
        type: String,
        payload: JsonObject,
    ) {
        when (val result = VoiceSignalCodec.parse(type, payload)) {
            is VoiceSignalCodec.Result.Parsed -> sink?.submit(result.signal)
            is VoiceSignalCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /**
     * A `VOICE_*` frame arrived on a connection that had not passed the trust gate. Counted rather
     * than merely dropped: PROTOCOL §7.1's whole point is that voice is inert before authentication,
     * and "it never happened" and "it happened and was refused" are different facts on a diagnostics
     * screen — and only the second one tells you something tried.
     */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    fun reset() {
        sink = null
        rejections.clear()
        droppedPreAuthentication = 0
    }
}
