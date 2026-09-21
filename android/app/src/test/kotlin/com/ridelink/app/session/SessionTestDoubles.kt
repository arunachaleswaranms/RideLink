package com.ridelink.app.session

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
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
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.discovery.AdvertiseState
import com.ridelink.network.discovery.DiscoveryController
import com.ridelink.network.discovery.DiscoveryEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicInteger

// Shared session-level test doubles.
//
// Extracted verbatim from `SessionLifecycleRestartTest` (independent-review round 3, Blocker C) so
// that `ResyncRecoveryTest` can build the **real** `SessionCoordinator` — the production End Ride
// entry point — rather than substituting a lower seam for it. Nothing in them changed except their
// visibility.

/**
 * A `ControlChannel` whose `bind()` records the attempt and then never returns.
 *
 * `ControlListener`'s constructor is `internal` to `:network`, so one cannot be built from `:app`
 * at all. Parking is the honest alternative, and it happens to be exactly what these tests need:
 * `bindCalls` answers "has this session reached the control plane?", which is the whole question
 * the teardown ordering is about.
 */
internal class ParkingControlChannel : ControlChannel {
    override val transportLabel: String = "test"
    override val isSecure: Boolean = true
    val bindCalls = AtomicInteger(0)
    private val never = CompletableDeferred<Unit>()

    override suspend fun bind(): ControlListener {
        bindCalls.incrementAndGet()
        never.await()
        error("unreachable: this channel never finishes binding")
    }

    override suspend fun connect(
        host: String,
        port: Int,
    ): ControlSocket = error("not used by this test")
}

internal class SilentDiscoveryController : DiscoveryController {
    override fun advertise(
        port: Int,
        rotationIntervalMs: Long,
    ): Flow<AdvertiseState> = emptyFlow()

    override fun browse(): Flow<DiscoveryEvent> = emptyFlow()
}

internal class FakeForegroundService : ForegroundServiceController {
    @Volatile var stopCalls = 0
        private set

    override fun stop() {
        stopCalls += 1
    }
}

/** Mirrors `SessionCoordinatorEndingEffectTest.FakeVoiceAudioSession`, kept local and minimal. */
internal class FakeVoiceAudioSession : VoiceAudioSession {
    @Volatile var closeCaptureCount = 0
        private set

    @Volatile var openCaptureCount = 0
        private set

    @Volatile var closeCalls = 0
        private set

    override var isOpen: Boolean = false
        private set

    override var route: AudioRouteSnapshot = AudioRouteSnapshot()
        private set

    var closeGate: CompletableDeferred<Unit>? = null
    private var sink: ((AudioRouteSnapshot) -> Unit)? = null

    override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
        this.sink = sink
    }

    override suspend fun open(): Result<Unit> {
        if (isOpen) return Result.success(Unit)
        isOpen = true
        openCaptureCount += 1
        sink?.invoke(route)
        return Result.success(Unit)
    }

    override suspend fun close() {
        closeCalls += 1
        closeGate?.await()
        if (isOpen) closeCaptureCount += 1
        isOpen = false
    }
}

internal class FakeVoiceEngine : VoiceEngine {
    override var diagnostics: VoiceEngineDiagnostics =
        VoiceEngineDiagnostics(audioProcessing = AudioProcessingStatus(true, true, true, false))
    private var sink: ((VoiceEngineEvent) -> Unit)? = null

    override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) {
        this.sink = sink
    }

    override suspend fun start(config: VoiceEngineConfig): Result<Unit> {
        diagnostics = diagnostics.copy(transportState = MediaTransportState.NEW, localAudioTrackPresent = true)
        return Result.success(Unit)
    }

    override suspend fun createOffer(): Result<Unit> = Result.success(Unit)

    override suspend fun createAnswer(): Result<Unit> = Result.success(Unit)

    override suspend fun applyRemoteDescription(
        kind: SdpKind,
        sdp: String,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int,
    ): Result<Unit> = Result.success(Unit)

    override fun setMicrophoneMuted(muted: Boolean) = Unit

    override suspend fun stop() {
        diagnostics =
            diagnostics.copy(
                transportState = MediaTransportState.CLOSED,
                iceGatheringState = IceGatheringState.NEW,
                remoteAudioTrackPresent = false,
            )
    }

    override suspend fun release() {
        diagnostics = VoiceEngineDiagnostics(transportState = MediaTransportState.CLOSED)
    }

    override suspend fun refreshDiagnostics() = Unit
}

internal class NoOpVoiceTransport : VoiceSignalTransport {
    override suspend fun send(
        signal: VoiceSignal,
        controlGeneration: Long?,
    ): Boolean = false
}
