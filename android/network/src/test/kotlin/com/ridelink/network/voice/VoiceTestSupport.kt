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

/**
 * Records what an authenticated peer's `VOICE_*` frames actually deliver, **and which control
 * authentication generation admitted each one** (STATUS §4 problem 60).
 *
 * The generation is recorded rather than ignored for the same reason `ManifestSpy` records it: the
 * claim under test is that the relay passes on the frame's *own* provenance and never substitutes a
 * live read, and a spy that dropped the parameter could not tell the two apart.
 */
class VoiceSignalSpy : VoiceSignalSink {
    private val log = CopyOnWriteArrayList<VoiceSignal>()
    private val generationLog = CopyOnWriteArrayList<Long>()

    val received: List<VoiceSignal> get() = log.toList()

    /** One entry per [received] entry, in the same order. */
    val generations: List<Long> get() = generationLog.toList()

    override fun submit(
        signal: VoiceSignal,
        controlGeneration: Long,
    ) {
        log.add(signal)
        generationLog.add(controlGeneration)
    }
}

/**
 * The one control authentication generation every suite written before STATUS §4 problem 60 is
 * about.
 *
 * Those suites all describe a **single** control lifetime — a signal admitted by it, and its own
 * link loss — so naming one generation for both is exactly faithful to what they assert, and it is
 * what keeps them honest regressions rather than tests that happen to pass because everything is
 * indistinguishable. A suite that needs two lifetimes says so explicitly instead of using these.
 */
const val TEST_CONTROL_GENERATION_A = 1L

/** [TEST_CONTROL_GENERATION_A] admitted this signal. */
fun VoiceController.submit(signal: VoiceSignal) = submit(signal, TEST_CONTROL_GENERATION_A)

/** [TEST_CONTROL_GENERATION_A] is the lifetime that ended. */
fun VoiceController.onControlLinkLost() = onControlLinkLost(TEST_CONTROL_GENERATION_A)

/** [TEST_CONTROL_GENERATION_A] is the lifetime this start is authorised by (STATUS §4 problem 61). */
fun VoiceController.start() = start(TEST_CONTROL_GENERATION_A)

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
    private val generationLog = CopyOnWriteArrayList<Long?>()
    private val attemptLog = CopyOnWriteArrayList<Pair<VoiceSignal, Long?>>()
    var accept = true

    /**
     * Parks the next [send] whose signal matches, **suspending the controller's single consumer
     * inside `perform`**, until [release] says what the write finally reported.
     *
     * This is the production shape, not a contrivance: `VoiceSignalRelay.send` suspends at
     * `withContext(ioDispatcher)` and again inside `ControlSocket.writeFrame`'s write lock and
     * `flush()`, and it reports `false` for a write that threw — so the value a `SendOffer`/
     * `SendAnswer` finally produces can arrive arbitrarily late, after the control lifetime that
     * authorised it has already been replaced. Parking is how a test names that instant instead of
     * racing for it.
     */
    var parkWhen: ((VoiceSignal) -> Boolean)? = null

    private var gate: CompletableDeferred<Boolean>? = null

    /**
     * Which control lifetime owns the surviving connection, as `VoiceSignalRelay` would see it
     * (ADR-020 Amendment A9). `null` — the default — means **this fake refuses nothing**, which is
     * exactly what every suite written before Amendment A9 assumes: they describe one control
     * lifetime and assert what the table decided to send, not which socket it landed on.
     *
     * A suite that is about two lifetimes sets it, and then this fake enforces production's rule:
     * a frame authorised by a generation that no longer owns the connection is refused, so the
     * controller's `degradeIfUnsent` path runs for real rather than being simulated.
     */
    var liveGeneration: (() -> Long?)? = null

    val sent: List<VoiceSignal> get() = log.toList()

    /** One entry per [sent] entry, in the same order: the lifetime the frame was authorised by. */
    val sentGenerations: List<Long?> get() = generationLog.toList()

    /** Every attempted send, refused ones included — the only way to see a refusal at all. */
    val attempted: List<Pair<VoiceSignal, Long?>> get() = attemptLog.toList()

    /** True while a [send] is suspended waiting for [release]. */
    val parked: Boolean get() = gate?.isCompleted == false

    /** Reports [result] to whichever [send] is currently parked, and stops parking. */
    fun release(result: Boolean) {
        parkWhen = null
        gate?.complete(result)
        gate = null
    }

    override suspend fun send(
        signal: VoiceSignal,
        controlGeneration: Long?,
    ): Boolean {
        attemptLog.add(signal to controlGeneration)
        val parked =
            if (parkWhen?.invoke(signal) == true) {
                val pending = CompletableDeferred<Boolean>()
                gate = pending
                parkWhen = null
                pending.await()
            } else {
                accept
            }
        // Production's rule, and read **after** the park: `VoiceSignalRelay` resolves the writer at
        // the instant of the write, which is the whole point of parking one.
        val bound = liveGeneration?.let { live -> controlGeneration != null && controlGeneration == live() } ?: true
        val accepted = parked && bound
        if (accepted) {
            log.add(signal)
            generationLog.add(controlGeneration)
        }
        return accepted
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
