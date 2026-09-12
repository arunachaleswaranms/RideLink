package com.ridelink.network.manifest

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.ManifestCodec
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.ManifestMessageRejection
import com.ridelink.network.control.ControlMessages
import com.ridelink.network.voice.AuthenticatedFrameWriter
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The `MANIFEST_*` half of the control plane (PROTOCOL §8.1): decode inbound frames, encode
 * outbound ones, and count what was refused.
 *
 * Mirrors [com.ridelink.network.voice.VoiceSignalRelay] exactly, for the reason `docs/STATUS.md`
 * §4 problem 18 already gives: `ControlSessionManager` grows with every message family, and the
 * fix each time is to extract, not to raise a class-size threshold.
 *
 * **What it deliberately does not decide.** Whether a `MANIFEST_*` frame is allowed before
 * authentication is `ControlSessionManager`'s pre-authentication frame allowlist, which drops
 * every `MANIFEST_*` type before the read loop's dispatch ever reaches [deliver] — the same
 * construction PROTOCOL §7.1 uses for `VOICE_*`.
 */
class ManifestRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
    /**
     * ADR-025's liveness half: the generation owning the connection that is an authenticated session
     * **right now**, or null when none is. A frame's own authorising generation is *compared*
     * against it and never replaced by it — reading a live value to label a frame is ADR-024
     * Amendment A7's defect.
     */
    private val liveGeneration: () -> Long?,
) {
    @Volatile
    var sink: ManifestSink? = null

    private val rejections = ConcurrentHashMap<ManifestMessageRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    /**
     * How many frames were dropped because the control session that authorised their read had
     * already been replaced (ADR-025 §1). Distinct from [droppedPreAuthentication]: that one counts
     * a peer that was never authenticated, this one counts a peer that *was*, on a connection that
     * is gone.
     */
    @Volatile
    var droppedRetiredGeneration: Int = 0
        private set

    val rejectionCounts: Map<ManifestMessageRejection, Int> get() = rejections.toMap()

    suspend fun send(message: ManifestMessage): Boolean {
        val write = authenticatedWriter() ?: return false
        return runCatching {
            write.write(
                ControlMessages.raw(
                    localPeerId = localPeerId,
                    type = ManifestCodec.wireType(message),
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    payload = ManifestCodec.encode(message),
                ),
            )
        }.isSuccess
    }

    /**
     * Called only from the read loop's authenticated dispatch. On any parse failure, the frame is
     * dropped and the connection survives — the framing was intact, only this message's shape was
     * wrong (same rule as `VOICE_*`, PROTOCOL §7.4, applied here).
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
        when (val result = ManifestCodec.parse(type, payload)) {
            is ManifestCodec.Result.Parsed -> sink?.submit(result.message, generation)
            is ManifestCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /**
     * A `MANIFEST_*` frame arrived on a connection that had not passed the trust gate. Counted
     * rather than merely dropped, so "it never happened" and "it happened and was refused" stay
     * distinguishable facts on a diagnostics screen (brief §22: unpaired peers never receive the
     * catalogue).
     */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    fun reset() {
        sink = null
        rejections.clear()
        droppedPreAuthentication = 0
        droppedRetiredGeneration = 0
    }
}

/** Receives parsed, bounds-checked `MANIFEST_*` messages. Implemented by whatever owns manifest-sync state. */
fun interface ManifestSink {
    /**
     * @param generation the authentication generation that owned **the connection this message's
     *   frame was read from, at the moment of the read** (ADR-025 §1) — the same contract
     *   `PlaybackSink.submit` already has, for the same reason. A receiver that instead looks the
     *   generation up reads whatever is live when its own work happens to run, which is ADR-024
     *   Amendment A7's defect and was live here until ADR-025 (STATUS §4 problem 44). Compare it
     *   against `ControlSessionManager.liveAuthenticatedGeneration` before mutating anything,
     *   including after any suspension.
     *
     * Must be **non-suspending and must not block**: it is called from the control read loop.
     */
    fun submit(
        message: ManifestMessage,
        generation: Long,
    )
}
