package com.ridelink.core.voice

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.protocol.VoiceSessionId

/**
 * ICE candidate types, by PROTOCOL §7.6. Only [HOST] is expected: RideLink configures an **empty**
 * ICE server list, so a reflexive or relayed candidate cannot legitimately occur.
 *
 * The others exist so their appearance can be *reported* rather than silently tolerated — an
 * `srflx` candidate would mean something contacted a STUN server, which is the accidental-egress
 * path ADR-003 removed on purpose.
 */
enum class IceCandidateType {
    HOST,
    SRFLX,
    PRFLX,
    RELAY,
    UNKNOWN,
    ;

    /** True for anything that implies a server outside the local network was involved. */
    val impliesNonLocalDependency: Boolean get() = this == SRFLX || this == PRFLX || this == RELAY

    companion object {
        /**
         * Reads the `typ` token out of an ICE candidate line. The **type only** — the address and
         * port are deliberately not extracted, because PROTOCOL §7.7 gives them no log path and a
         * value that is never produced cannot be leaked.
         */
        fun fromCandidateLine(line: String): IceCandidateType {
            val tokens = line.trim().split(' ')
            val typIndex = tokens.indexOf("typ")
            if (typIndex < 0 || typIndex + 1 >= tokens.size) return UNKNOWN
            return when (tokens[typIndex + 1].lowercase()) {
                "host" -> HOST
                "srflx" -> SRFLX
                "prflx" -> PRFLX
                "relay" -> RELAY
                else -> UNKNOWN
            }
        }
    }
}

/** Mirrors WebRTC's own peer-connection state names, so a diagnostic reads the same on both phones. */
enum class MediaTransportState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    UNKNOWN,
}

/** Mirrors WebRTC's ICE gathering state. */
enum class IceGatheringState {
    NEW,
    GATHERING,
    COMPLETE,
    UNKNOWN,
}

/**
 * The FR-023 voice diagnostics surface, and the whole of what Phase 2a exposes about the media
 * plane.
 *
 * Everything here is safe to display and to log by PROTOCOL §7.7. What is **absent** is the point:
 * no SDP, no candidate string, no address or port, no DTLS or SRTP key material. `selectedLocalType`
 * and `selectedRemoteType` are candidate *types*, not candidates.
 *
 * Nullable fields mean "the platform has not told us", never zero. Inventing a value here would be
 * inventing a measurement, which is the one thing this file must not do.
 */
data class VoiceEngineDiagnostics(
    val transportState: MediaTransportState = MediaTransportState.NEW,
    val iceGatheringState: IceGatheringState = IceGatheringState.NEW,
    /** As reported by the media stack, e.g. `"connected"`. Null until DTLS has a state to report. */
    val dtlsState: String? = null,
    val selectedLocalType: IceCandidateType? = null,
    val selectedRemoteType: IceCandidateType? = null,
    /** Every candidate type this side gathered or received. §7.6 reports anything but `host`. */
    val observedCandidateTypes: Set<IceCandidateType> = emptySet(),
    /** e.g. `"audio/opus"`. Null until an answer has been applied. */
    val negotiatedCodec: String? = null,
    val negotiatedClockRateHz: Int? = null,
    val negotiatedChannels: Int? = null,
    /** e.g. `"SRTP_AES128_CM_HMAC_SHA1_80"` — the cipher name, never the keys. */
    val srtpCipher: String? = null,
    val dtlsCipher: String? = null,
    val packetsSent: Long? = null,
    val packetsReceived: Long? = null,
    val packetsLost: Long? = null,
    val jitterMs: Double? = null,
    val roundTripTimeMs: Double? = null,
    val localAudioTrackPresent: Boolean = false,
    val remoteAudioTrackPresent: Boolean = false,
    /** Whether the built-in AEC/NS/AGC stages are available and enabled (ADR-003). */
    val audioProcessing: AudioProcessingStatus = AudioProcessingStatus(),
)

/**
 * Whether WebRTC's built-in audio processing is available and on. ADR-003 requires each stage to be
 * individually disableable so a suspect stage can be turned off and the result measured again
 * (FR-005) — this is the reporting half of that.
 *
 * **These flags say what was requested and what the stack reported, not that echo is solved.**
 * Whether AEC copes with a helmet unit's acoustics at 100 km/h is a real-device measurement and
 * nothing here may be read as evidence about it.
 */
data class AudioProcessingStatus(
    val echoCancellationEnabled: Boolean? = null,
    val noiseSuppressionEnabled: Boolean? = null,
    val autoGainControlEnabled: Boolean? = null,
    /** True when the platform reported hardware acceleration for the stage rather than software. */
    val hardwareEchoCancellation: Boolean? = null,
)

/** ADR-003: each stage individually switchable, for exactly the reason FR-005 gives. */
data class AudioProcessingConfig(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
)

/**
 * How the media plane is set up for one negotiation.
 *
 * [iceServers] is not a field. That is deliberate: PROTOCOL §7.6 configures an empty ICE server
 * list, and a config object with no way to express a STUN or TURN server cannot grow one by
 * accident in a later phase. If a future topology genuinely needs one, adding the field is a
 * protocol and ADR change, which is the point.
 */
data class VoiceEngineConfig(
    val voiceSessionId: VoiceSessionId,
    /** One audio track per peer (ADR-003). Used as the WebRTC track id. */
    val localTrackId: String,
    val audioProcessing: AudioProcessingConfig = AudioProcessingConfig(),
)

/** Which kind of session description is being applied. */
enum class SdpKind { OFFER, ANSWER }

/** What went wrong in the media stack. Coarse on purpose — the detail belongs in a local log line. */
sealed class VoiceEngineError {
    data class NotStarted(
        val detail: String,
    ) : VoiceEngineError()

    data class SdpFailed(
        val detail: String,
    ) : VoiceEngineError()

    data class CandidateRejected(
        val type: IceCandidateType,
    ) : VoiceEngineError()

    data class PlatformFailure(
        val detail: String,
    ) : VoiceEngineError()
}

/**
 * What the media stack tells the controller. Every payload is a plain primitive, for two reasons
 * that happen to coincide:
 *
 * 1. it keeps `core` free of platform types (CLAUDE.md rule 9), and
 * 2. on iOS the WebRTC ObjC types — `RTCSessionDescription`, `RTCIceCandidate`,
 *    `RTCStatisticsReport` — are **not** `Sendable`, so under Swift 6 strict concurrency a value
 *    leaving a WebRTC callback has to be reduced to primitives *inside* that callback anyway
 *    (ADR-020).
 *
 * The `voiceSessionId` on every event is the generation guard applied to callbacks rather than to
 * the wire: a delegate call from a peer connection that has already been closed carries the old id
 * and is therefore inert (PROTOCOL §7.8).
 */
sealed class VoiceEngineEvent {
    data class OfferCreated(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceEngineEvent()

    data class AnswerCreated(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceEngineEvent()

    data class LocalCandidateGathered(
        val voiceSessionId: VoiceSessionId,
        val candidate: String,
        val sdpMid: String?,
        val sdpMlineIndex: Int,
    ) : VoiceEngineEvent()

    data class TransportStateChanged(
        val voiceSessionId: VoiceSessionId,
        val state: MediaTransportState,
    ) : VoiceEngineEvent()

    data class RemoteTrackChanged(
        val voiceSessionId: VoiceSessionId,
        val present: Boolean,
    ) : VoiceEngineEvent()

    data class Failed(
        val voiceSessionId: VoiceSessionId,
        val error: VoiceEngineError,
    ) : VoiceEngineEvent()
}

/**
 * The media plane, as the controller sees it: WebRTC behind a seam of plain values.
 *
 * ADR-003 isolates WebRTC behind `network/voice` / `RideLinkPlatform.Voice` so it is replaceable.
 * This interface is that isolation made explicit and — because every parameter and every event
 * payload is a primitive — it is also what makes `VoiceController` testable with no WebRTC, no
 * microphone and no network at all.
 *
 * **A fake implementation proves the controller, not the codec.** Nothing driven by a fake engine
 * may be reported as evidence that real voice works; that is what the real-engine tests and the
 * real-device gate are for.
 */
interface VoiceEngine {
    /**
     * Creates the peer connection with an empty ICE server list and attaches the local audio track.
     * Must be called before any other method. Does **not** open the capture device or configure the
     * audio session — that is [VoiceAudioSession]'s job, and it is separate precisely so a control
     * link blip can drop the media transport without closing a microphone Android would not let us
     * reopen (ARCHITECTURE §6.4).
     */
    suspend fun start(config: VoiceEngineConfig): Result<Unit>

    suspend fun createOffer(): Result<Unit>

    suspend fun createAnswer(): Result<Unit>

    suspend fun applyRemoteDescription(
        kind: SdpKind,
        sdp: String,
    ): Result<Unit>

    suspend fun addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int,
    ): Result<Unit>

    /** Gates *transmission* by disabling the sender's track. The capture device stays open. */
    fun setMicrophoneMuted(muted: Boolean)

    /**
     * Closes the peer connection, the remote track and the ICE state, and **keeps** the media
     * factory, the audio device module and the local track alive.
     *
     * The split from [release] is not tidiness. On Android the WebRTC audio device module owns the
     * `AudioRecord`, and closing it is what makes a Bluetooth endpoint renegotiate between its media
     * and duplex profiles — a 0.5–2 s audible route change (ARCHITECTURE §6.2) and the single worst
     * thing this product can do to music (§6.3). A control-plane blip must therefore drop the
     * *transport* without touching the capture path, so that a reconnect rebuilds the peer
     * connection over an audio route that never moved. Idempotent.
     */
    suspend fun stop()

    /**
     * Disposes the media factory, the audio device module and the local track, releasing the capture
     * device. Only a deliberate stop or `ENDING` may call this (ARCHITECTURE §3 rule 3), for the
     * reason in [stop]. Idempotent, and implies [stop].
     */
    suspend fun release()

    /** Refreshes [diagnostics] from the stack's own statistics. Cheap enough to poll while active. */
    suspend fun refreshDiagnostics()

    val diagnostics: VoiceEngineDiagnostics

    /** Set by the controller before [start]. Every event carries its generation. */
    fun setEventSink(sink: (VoiceEngineEvent) -> Unit)
}

/**
 * The capture device and the platform audio session, kept deliberately separate from
 * [VoiceEngine].
 *
 * The split is not tidiness. ARCHITECTURE §6.3/§6.4: the capture device is opened once while the
 * app is foreground-visible and stays open for the whole ride segment, because on Android there is
 * no second legal opportunity to open a microphone once the screen is locked. A link blip must
 * therefore tear down the *peer connection* without touching this. One interface for both would
 * make that distinction impossible to express.
 */
interface VoiceAudioSession {
    /**
     * Configures the duplex audio session, selects the communication route and opens capture.
     *
     * Must be called while the app is foreground-visible on Android (ARCHITECTURE §6.4 step 6).
     * Returns a failure — never throws, and never silently proceeds without a microphone — if the
     * permission is absent or the platform refuses.
     */
    suspend fun open(): Result<Unit>

    /** Releases capture and restores the non-duplex configuration. Idempotent. */
    suspend fun close()

    val isOpen: Boolean

    /** The current route, in ADR-016's platform-neutral vocabulary. */
    val route: AudioRouteSnapshot

    fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit)
}
