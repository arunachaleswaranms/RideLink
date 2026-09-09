package com.ridelink.network.playback

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.protocol.PlaybackCodec
import com.ridelink.core.protocol.PlaybackMessageRejection
import com.ridelink.core.protocol.QueueCodec
import com.ridelink.core.protocol.QueueMessageRejection
import com.ridelink.network.control.ControlMessages
import com.ridelink.network.voice.AuthenticatedFrameWriter
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Receives parsed, bounds-checked PROTOCOL §5 playback messages. Implemented by the sync coordinator. */
fun interface PlaybackSink {
    /**
     * Must be non-suspending and never block: it is called from the control read loop.
     *
     * [generation] is the authentication generation that was live **when the frame was read off the
     * wire** (ADR-023 §3). It is a parameter rather than something the receiver looks up, because a
     * receiver that looks it up reads whatever is live when its own work happens to run — the exact
     * shape of bug ADR-023 Amendment A3 found in Phase 4.
     */
    fun submit(
        message: PlaybackMessage,
        generation: Long,
    )
}

/** Receives parsed, bounds-checked PROTOCOL §9 queue messages. */
fun interface QueueSink {
    fun submit(
        message: QueueMessage,
        generation: Long,
    )
}

/**
 * The Phase 5 half of the control plane (PROTOCOL §5 and §9): decode inbound frames, encode
 * outbound ones, and count what was refused. Mirrors [com.ridelink.network.voice.VoiceSignalRelay] /
 * [com.ridelink.network.control.AudioStateRelay] / [com.ridelink.network.transfer.TransferRelay]
 * exactly, and exists as a separate type for the same recorded reason: `ControlSessionManager` is
 * the largest class in the codebase (`docs/STATUS.md` §4 problem 18) and nothing here touches the
 * session, the handshake, pairing, reconnect or the clock.
 *
 * **One relay for two message families, deliberately.** Playback commands and queue mutations share
 * one serialisation point (the ADR-010 leader), one ordering authority (`command_seq`) and one
 * owner, so splitting them would put two halves of one subsystem on two objects and add a second
 * block of wiring to the one class that cannot afford it. The two sinks stay separate because the
 * two codecs produce different types.
 *
 * **What it deliberately does not decide.** Whether a frame is *allowed* is `ControlSessionManager`'s
 * pre-authentication allowlist, and every `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS`/
 * `POSITION_REPORT`/`PLAYBACK_STATE`/`QUEUE_*` type is **absent** from it — that absence *is* their
 * access control, exactly as it is for `VOICE_*` (PROTOCOL §7.1) and `AUDIO_STATE`. Whether an
 * allowed frame may be *applied* is `CommandOrderGate`'s and the session-generation guard's.
 */
class PlaybackRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
    /**
     * ADR-023 §3's authentication generation, live. Phase 5 is the only family whose *outbound*
     * frames outlive the step that created them — they sit on an ordered queue while the socket
     * drains — so it is the only one that needs this (ADR-024 Amendment A2 Finding B).
     */
    private val currentAuthGeneration: () -> Long,
) {
    @Volatile
    var playbackSink: PlaybackSink? = null

    @Volatile
    var queueSink: QueueSink? = null

    private val playbackRejections = ConcurrentHashMap<PlaybackMessageRejection, Int>()
    private val queueRejections = ConcurrentHashMap<QueueMessageRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    val playbackRejectionCounts: Map<PlaybackMessageRejection, Int> get() = playbackRejections.toMap()

    val queueRejectionCounts: Map<QueueMessageRejection, Int> get() = queueRejections.toMap()

    /**
     * @param authorizingGeneration the authentication generation that **authorised** this frame,
     *   captured when the coordinator created it (ADR-024 Amendment A2 Finding B). A Phase 5 frame
     *   waits its turn on an ordered outbound queue that deliberately outlives sessions, so
     *   resolving the writer and the `session_id` "now" is how a Session A frame ends up written
     *   under Session B's identity. Passing the generation is what makes that impossible.
     * @return true if the message was handed to a live authenticated control connection **belonging
     *   to [authorizingGeneration]**.
     */
    suspend fun send(
        message: PlaybackMessage,
        authorizingGeneration: Long,
    ): Boolean = write(PlaybackCodec.wireType(message), PlaybackCodec.encode(message), authorizingGeneration)

    suspend fun send(
        message: QueueMessage,
        authorizingGeneration: Long,
    ): Boolean = write(QueueCodec.wireType(message), QueueCodec.encode(message), authorizingGeneration)

    @Suppress("ReturnCount") // one per way this frame's authorising session can already be gone
    private suspend fun write(
        type: String,
        payload: JsonObject,
        authorizingGeneration: Long,
    ): Boolean {
        if (authorizingGeneration != currentAuthGeneration()) return false
        val writer = authenticatedWriter() ?: return false
        val sessionId = activeSessionId()
        // Re-proved after both reads and immediately before the write: `writer` is a closure over
        // the socket that was active a moment ago, and `sessionId` is that session's id. If the
        // generation has moved between the two, neither belongs to the frame in hand — and if it
        // moves during the write itself, the socket the closure holds is the *old* one, already
        // closed, so the write fails rather than landing on the new session.
        if (authorizingGeneration != currentAuthGeneration()) return false
        return runCatching {
            writer.write(
                ControlMessages.raw(
                    localPeerId = localPeerId,
                    type = type,
                    sessionId = sessionId,
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    payload = payload,
                ),
            )
        }.isSuccess
    }

    /**
     * Called only from the read loop's authenticated dispatch. A malformed frame is dropped and the
     * connection survives — the framing was intact, only this message's shape was wrong, exactly as
     * for a malformed `PING` (§6), `VOICE_*` (§7.4) or `TRANSFER_*` (§8.2).
     */
    fun deliverPlayback(
        type: String,
        payload: JsonObject,
        generation: Long,
    ) {
        when (val result = PlaybackCodec.parse(type, payload)) {
            is PlaybackCodec.Result.Parsed -> playbackSink?.submit(result.message, generation)
            is PlaybackCodec.Result.Rejected -> playbackRejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    fun deliverQueue(
        type: String,
        payload: JsonObject,
        generation: Long,
    ) {
        when (val result = QueueCodec.parse(type, payload)) {
            is QueueCodec.Result.Parsed -> queueSink?.submit(result.message, generation)
            is QueueCodec.Result.Rejected -> queueRejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /**
     * A Phase 5 frame arrived on a connection that had not passed the trust gate. Counted rather
     * than merely dropped, for the same reason every other relay counts it: "it never happened" and
     * "it happened and was refused" are different facts on a diagnostics screen, and only the second
     * one lets a test prove the gate held rather than prove nothing was sent.
     */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    fun reset() {
        playbackSink = null
        queueSink = null
        playbackRejections.clear()
        queueRejections.clear()
        droppedPreAuthentication = 0
    }
}
