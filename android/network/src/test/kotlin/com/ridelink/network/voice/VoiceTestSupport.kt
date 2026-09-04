package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.model.PeerId
import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.ProtocolVersion
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.voice.AudioProcessingStatus
import com.ridelink.core.voice.IceGatheringState
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.SdpKind
import com.ridelink.core.voice.VoiceAudioSession
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.core.voice.VoiceSignalTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records what an authenticated peer's `VOICE_*` frames actually deliver. */
class VoiceSignalSpy : VoiceSignalSink {
    private val log = CopyOnWriteArrayList<VoiceSignal>()

    val received: List<VoiceSignal> get() = log.toList()

    override fun submit(signal: VoiceSignal) {
        log.add(signal)
    }
}

fun VoiceSignal.kindName(): String =
    when (this) {
        is VoiceSignal.Offer -> "Offer"
        is VoiceSignal.Answer -> "Answer"
        is VoiceSignal.IceCandidate -> "IceCandidate"
        is VoiceSignal.State -> "State"
    }

/** A frame built field by field, so a test can send shapes the real encoder would never produce. */
fun rawEnvelope(
    senderId: PeerId,
    type: String,
    build: JsonObjectBuilder.() -> Unit,
): Envelope =
    Envelope(
        v = ProtocolVersion.CURRENT,
        type = type,
        sessionId = "test-session",
        senderId = senderId.value,
        msgId = UUID.randomUUID().toString(),
        seq = 1,
        sentAtMonoUs = 1,
        requiresAck = false,
        payload = buildJsonObject(build),
    )

/**
 * A [VoiceEngine] with no WebRTC in it: it records what it was asked to do and emits whatever a test
 * tells it to.
 *
 * **A passing test against this proves the controller, not the codec.** It says nothing about
 * whether real Opus over real DTLS-SRTP works between two phones — that is what
 * `WebRtcVoiceEngine`, the iOS real-media loopback test, and the real-device gate are for.
 */
class FakeVoiceEngine : VoiceEngine {
    val calls = CopyOnWriteArrayList<String>()
    var startResult: Result<Unit> = Result.success(Unit)
    var muted: Boolean? = null
    private var sink: ((VoiceEngineEvent) -> Unit)? = null

    override var diagnostics: VoiceEngineDiagnostics =
        VoiceEngineDiagnostics(audioProcessing = AudioProcessingStatus(true, true, true, false))

    override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) {
        this.sink = sink
    }

    fun emit(event: VoiceEngineEvent) {
        sink?.invoke(event)
    }

    override suspend fun start(config: VoiceEngineConfig): Result<Unit> {
        calls.add("start(${config.voiceSessionId.value})")
        if (startResult.isSuccess) {
            diagnostics = diagnostics.copy(transportState = MediaTransportState.NEW, localAudioTrackPresent = true)
        }
        return startResult
    }

    override suspend fun createOffer(): Result<Unit> {
        calls.add("createOffer")
        return Result.success(Unit)
    }

    override suspend fun createAnswer(): Result<Unit> {
        calls.add("createAnswer")
        return Result.success(Unit)
    }

    override suspend fun applyRemoteDescription(
        kind: SdpKind,
        sdp: String,
    ): Result<Unit> {
        calls.add("applyRemote(${kind.name})")
        return Result.success(Unit)
    }

    override suspend fun addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int,
    ): Result<Unit> {
        calls.add("addRemoteCandidate($sdpMlineIndex)")
        return Result.success(Unit)
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        this.muted = muted
        calls.add("setMicrophoneMuted($muted)")
    }

    override suspend fun stop() {
        calls.add("stop")
        diagnostics =
            diagnostics.copy(
                transportState = MediaTransportState.CLOSED,
                iceGatheringState = IceGatheringState.NEW,
                remoteAudioTrackPresent = false,
            )
    }

    override suspend fun release() {
        calls.add("release")
        diagnostics = VoiceEngineDiagnostics(transportState = MediaTransportState.CLOSED)
    }

    override suspend fun refreshDiagnostics() {
        calls.add("refreshDiagnostics")
    }
}

/**
 * A [VoiceAudioSession] that records open/close without touching a real audio route.
 *
 * [openCaptureCount] and [closeCaptureCount] exist for one specific test:
 * `VoiceControllerIntercomTest` presses PTT fifty times and asserts they stay at 1 and 0. That is the
 * laptop half of TEST_PLAN A-10, which asserts the same invariant against a real helmet unit's
 * recorded output — the capture device is opened once for a ride segment, and PTT gates transmission
 * rather than hardware (ARCHITECTURE §6.3).
 */
class FakeVoiceAudioSession : VoiceAudioSession {
    val calls = CopyOnWriteArrayList<String>()
    var openResult: Result<Unit> = Result.success(Unit)

    /** How many times the capture path was **actually** opened (a no-op re-open does not count). */
    @Volatile
    var openCaptureCount: Int = 0
        private set

    @Volatile
    var closeCaptureCount: Int = 0
        private set

    override var isOpen: Boolean = false
        private set

    override var route: AudioRouteSnapshot = AudioRouteSnapshot()
        private set

    private var sink: ((AudioRouteSnapshot) -> Unit)? = null

    /**
     * Set by a test that needs to observe an in-flight `close()` before it completes —
     * `VoiceControllerStopAwaitTest`'s proof that `stopAndAwaitRelease()` really suspends until
     * `audioSession.close()` has run, not merely been called. `null` (the default) means `close()`
     * completes immediately, exactly as before.
     */
    var closeGate: CompletableDeferred<Unit>? = null

    override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
        this.sink = sink
    }

    fun publish(snapshot: AudioRouteSnapshot) {
        route = snapshot
        sink?.invoke(snapshot)
    }

    override suspend fun open(): Result<Unit> {
        calls.add("open")
        // The real sessions are idempotent — `AndroidVoiceAudioSession.open` returns early when
        // already open, and `IosVoiceAudioSession` likewise — so an already-open session does not
        // count as a second capture open. Mirroring that here is what makes the A-10 counters mean
        // the same thing as the hardware measurement will.
        if (isOpen) return Result.success(Unit)
        if (openResult.isSuccess) {
            isOpen = true
            openCaptureCount += 1
        }
        return openResult
    }

    override suspend fun close() {
        closeGate?.await()
        calls.add("close")
        if (isOpen) closeCaptureCount += 1
        isOpen = false
    }
}

/** Records the `VOICE_*` frames the controller decided to send. */
class RecordingVoiceTransport : VoiceSignalTransport {
    private val log = CopyOnWriteArrayList<VoiceSignal>()
    var accept = true

    val sent: List<VoiceSignal> get() = log.toList()

    override suspend fun send(signal: VoiceSignal): Boolean {
        if (!accept) return false
        log.add(signal)
        return true
    }
}

fun vsid(hex: String): VoiceSessionId = VoiceSessionId(hex)

/** A deterministic generation source, so a test can name the ids it expects to see. */
class SequencedVoiceSessionIds(
    private vararg val ids: String,
) {
    private var index = 0

    fun next(): VoiceSessionId = VoiceSessionId(ids[minOf(index++, ids.size - 1)])
}

@Suppress("UNUSED_PARAMETER")
fun unusedPayload(payload: JsonObject) = Unit
