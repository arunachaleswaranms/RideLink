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
    /** Must be non-suspending and never block: it is called from the control read loop. */
    fun submit(message: PlaybackMessage)
}

/** Receives parsed, bounds-checked PROTOCOL §9 queue messages. */
fun interface QueueSink {
    fun submit(message: QueueMessage)
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

    /** @return true if the message was handed to a live authenticated control connection. */
    suspend fun send(message: PlaybackMessage): Boolean = write(PlaybackCodec.wireType(message), PlaybackCodec.encode(message))

    suspend fun send(message: QueueMessage): Boolean = write(QueueCodec.wireType(message), QueueCodec.encode(message))

    private suspend fun write(
        type: String,
        payload: JsonObject,
    ): Boolean {
        val writer = authenticatedWriter() ?: return false
        return runCatching {
            writer.write(
                ControlMessages.raw(
                    localPeerId = localPeerId,
                    type = type,
                    sessionId = activeSessionId(),
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
    ) {
        when (val result = PlaybackCodec.parse(type, payload)) {
            is PlaybackCodec.Result.Parsed -> playbackSink?.submit(result.message)
            is PlaybackCodec.Result.Rejected -> playbackRejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    fun deliverQueue(
        type: String,
        payload: JsonObject,
    ) {
        when (val result = QueueCodec.parse(type, payload)) {
            is QueueCodec.Result.Parsed -> queueSink?.submit(result.message)
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
