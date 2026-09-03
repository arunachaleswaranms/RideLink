package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.AudioStateCodec
import com.ridelink.core.protocol.AudioStateInbox
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.protocol.AudioStateRejection
import com.ridelink.network.voice.AuthenticatedFrameWriter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Where an `AUDIO_STATE` frame that has passed the ADR-019 trust gate is delivered. */
fun interface AudioStateSink {
    /**
     * Must be **non-suspending and never block**: it is called from the control read loop. There is
     * no queue behind it because there is nothing to queue — [AudioStateInboxHolder] keeps exactly one
     * message, the newest by revision, and PROTOCOL §4.4's revision rule is what makes that correct
     * rather than lossy.
     */
    fun submit(message: AudioStateMessage)
}

/**
 * The `AUDIO_STATE` half of the control plane (PROTOCOL §4.4): decode inbound frames, encode outbound
 * ones, and count what was refused.
 *
 * **Why this is a separate type**, exactly as `VoiceSignalRelay` is: `ControlSessionManager` is the
 * largest class in the codebase and `docs/STATUS.md` §4 problem 18 says so. None of what is here
 * touches the session, the handshake, pairing, reconnect or the clock, so none of it belongs there.
 *
 * **What it deliberately does not decide.** Whether a frame is *allowed* is
 * `ControlSessionManager`'s pre-authentication frame allowlist, and `AUDIO_STATE` is **absent** from
 * that list — PROTOCOL §4.1 admits only `PING`, `PONG`, the three pairing frames, `BYE` and `ERROR`
 * before the trust gate, and §4.1's diagram puts `AUDIO_STATE` on the trusted path. An unauthenticated
 * peer's `AUDIO_STATE` is dropped before the read loop's dispatch can reach [deliver], and
 * [countPreAuthenticationDrop] records that it tried.
 *
 * The one guard this type enforces is on the way out: [send] refuses unless the caller's supplier
 * yields an **authenticated** connection.
 */
class AudioStateRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
) {
    @Volatile
    var sink: AudioStateSink? = null

    private val rejections = ConcurrentHashMap<AudioStateRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    val rejectionCounts: Map<AudioStateRejection, Int> get() = rejections.toMap()

    /** @return true if the message was handed to a live authenticated control connection. */
    suspend fun send(message: AudioStateMessage): Boolean {
        val write = authenticatedWriter() ?: return false
        return runCatching {
            write.write(
                ControlMessages.audioState(
                    localPeerId = localPeerId,
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    message = message,
                ),
            )
        }.isSuccess
    }

    /**
     * PROTOCOL §4.4: parse, bounds-check, hand over — and on any failure, **drop the frame and keep
     * the connection**. The framing was intact; only this message's shape was wrong, exactly as for a
     * malformed `PING` (§6) or `VOICE_*` (§7.4).
     *
     * Called only from the read loop's authenticated dispatch.
     */
    fun deliver(payload: JsonObject) {
        when (val result = AudioStateCodec.parse(payload)) {
            is AudioStateCodec.Result.Parsed -> sink?.submit(result.message)
            is AudioStateCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /**
     * An `AUDIO_STATE` frame arrived on a connection that had not passed the trust gate. Counted rather
     * than merely dropped: "it never happened" and "it happened and was refused" are different facts on
     * a diagnostics screen, and only the second one tells you something tried.
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

/**
 * Builds the `AUDIO_STATE` payload from [AudioStateCodec]'s field list.
 *
 * Kept next to the relay rather than inline in [ControlMessages] for the same reason
 * `ControlMessages.voiceSignal` defers to `VoiceSignalCodec`: the field names and the shape live in
 * `core`, shared with iOS and pinned by `protocol/vectors/audio-state/`, so encoding is a mechanical
 * transcription of an already-checked value rather than a second hand-written builder that could
 * disagree.
 */
internal fun audioStatePayload(message: AudioStateMessage): JsonObject =
    JsonObject(
        AudioStateCodec.encode(message).mapValues { (_, value) ->
            when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                // Unreachable: `AudioStateCodec.encode` produces only the four types above, and its
                // field list is asserted against the shared vectors. Failing loudly here rather than
                // silently coercing means a future field of a new type cannot slip onto the wire
                // as a stringified something.
                else -> error("AUDIO_STATE encodes no value of type ${value::class.simpleName}")
            }
        },
    )

/**
 * Holds the peer's latest `AUDIO_STATE`, applying PROTOCOL §4.4's revision rule via the shared
 * `AudioStateInbox`.
 *
 * A thin `@Volatile` wrapper rather than a queue: exactly one message is ever meaningful — the newest
 * by revision — so there is nothing to buffer and nothing that could grow (this phase's brief §38).
 */
class AudioStateInboxHolder {
    private val lock = Any()
    private val inbox = AudioStateInbox()

    fun accept(message: AudioStateMessage): Boolean = synchronized(lock) { inbox.accept(message) }

    val current: AudioStateMessage? get() = synchronized(lock) { inbox.current }

    val droppedStale: Int get() = synchronized(lock) { inbox.droppedStale }

    fun reset() = synchronized(lock) { inbox.reset() }
}
