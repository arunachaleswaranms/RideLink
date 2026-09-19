package com.ridelink.network.resync

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.ResyncCodec
import com.ridelink.core.protocol.ResyncMessageRejection
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.network.control.ControlMessages
import com.ridelink.network.voice.AuthenticatedFrameWriter
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Receives parsed, bounds-checked `STATE_REQUEST`/`STATE_SNAPSHOT` messages (PROTOCOL §10). */
fun interface ResyncSink {
    /**
     * @param generation the authentication generation that owned **the connection this message's
     *   frame was read from, at the moment of the read** (ADR-025 §1) — the same contract every
     *   other relay's sink has. Must be **non-suspending and must not block**: called from the
     *   control read loop.
     */
    fun submit(
        message: ResyncMessage,
        generation: Long,
    )
}

/**
 * The PROTOCOL §10 half of the control plane: decode inbound `STATE_REQUEST`/`STATE_SNAPSHOT`
 * frames, encode outbound ones, and count what was refused.
 *
 * Mirrors [com.ridelink.network.manifest.ManifestRelay] exactly, for the same recorded reason
 * (`docs/STATUS.md` §4 problem 18): `ControlSessionManager` grows with every message family, and
 * the fix is to extract, not to raise a class-size threshold.
 *
 * **What it deliberately does not decide.** Whether `STATE_REQUEST`/`STATE_SNAPSHOT` is allowed
 * before authentication is `ControlSessionManager`'s pre-authentication frame allowlist — both
 * types are **absent** from it, exactly as `VOICE_*` and `AUDIO_STATE` are, and that absence is
 * the whole of their access control (ADR-028). Whether a *follower* may apply an admitted
 * `STATE_SNAPSHOT` is the existing Phase 5 reconciliation path's role/generation check
 * (`SyncPlaybackCoordinator.adoptSnapshot`/`onPeerPlaybackState`), reused rather than duplicated.
 */
class ResyncRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
    /**
     * ADR-025's liveness half: the generation owning the connection that is an authenticated
     * session **right now**, or null when none is. A frame's own authorising generation is
     * *compared* against it and never replaced by it.
     */
    private val liveGeneration: () -> Long?,
) {
    @Volatile
    var sink: ResyncSink? = null

    private val rejections = ConcurrentHashMap<ResyncMessageRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    /** See [com.ridelink.network.manifest.ManifestRelay.droppedRetiredGeneration]: same meaning here. */
    @Volatile
    var droppedRetiredGeneration: Int = 0
        private set

    val rejectionCounts: Map<ResyncMessageRejection, Int> get() = rejections.toMap()

    suspend fun send(message: ResyncMessage): Boolean {
        val write = authenticatedWriter() ?: return false
        return runCatching {
            write.write(
                ControlMessages.raw(
                    localPeerId = localPeerId,
                    type = ResyncCodec.wireType(message),
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    payload = ResyncCodec.encode(message),
                ),
            )
        }.isSuccess
    }

    /**
     * Called only from the read loop's authenticated dispatch. On any parse failure, the frame is
     * dropped and the connection survives — the framing was intact, only this message's shape was
     * wrong.
     */
    fun deliver(
        type: String,
        payload: JsonObject,
        generation: Long,
    ) {
        if (generation != liveGeneration()) {
            droppedRetiredGeneration += 1
            return
        }
        when (val result = ResyncCodec.parse(type, payload)) {
            is ResyncCodec.Result.Parsed -> sink?.submit(result.message, generation)
            is ResyncCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /** A resync frame arrived on a connection that had not passed the trust gate. Counted, not just dropped. */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    /** See [com.ridelink.network.control.ControlRelays.resetCounters]: the counters, never [sink]. */
    fun resetCounters() {
        rejections.clear()
        droppedPreAuthentication = 0
        droppedRetiredGeneration = 0
    }
}
