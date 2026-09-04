package com.ridelink.app.session

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.AudioProcessingConfig
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
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.control.LinkLossReason
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.AdvertiseState
import com.ridelink.network.discovery.DiscoveryController
import com.ridelink.network.discovery.DiscoveryEvent
import com.ridelink.network.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `SessionCoordinator`/`SessionFsm` integration boundary problem 32 lived at
 * (docs/STATUS.md §4 problem 32, this phase's final hardening pass, Issue 3): `SessionFsm`'s
 * `ENDING` effect promised `ReleaseAudioAndStopForegroundService` and `SessionCoordinator.runEffect`
 * never actually stopped the foreground service. `VoiceControllerStopAwaitTest` (network module)
 * proves `stopAndAwaitRelease()` itself is completion-aware; this proves the **wiring one layer up**
 * — that `runEffect` awaits it and only then calls the foreground-service-stop callback — which is
 * the part problem 32 was actually about. A real `VoiceController` drives this, not a fake, so the
 * whole chain from `SessionFsm` down to `audioSession.close()` is exercised in one test.
 *
 * `discovery`/`controlSessionManager` are real production types wired with fakes/no-op fixtures:
 * this test never calls [SessionCoordinator.startDiscovery] (so neither is ever actually exercised
 * over a socket), driving the FSM instead through [SessionCoordinator.applyEvent] and
 * [SessionCoordinator.handleControlEvent] directly — both `internal` for exactly this seam.
 */
class SessionCoordinatorEndingEffectTest {
    @Test
    fun `a peer BYE awaits capture release, stops the foreground service, then tears down`() =
        withConnectedSession { coordinator, fgs, audio ->
            coordinator.startIntercom()
            awaitTrue("capture open") { audio.isOpen }

            val closeGate = CompletableDeferred<Unit>()
            audio.closeGate = closeGate
            assertEquals(0, fgs.stopCalls)

            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            assertEquals(SessionStatus.ENDING, coordinator.state.value.status)

            // The release is in flight (audioSession.close() is being awaited) but has not settled,
            // so the foreground service must not be told to stop yet.
            awaitTrue("close() called") { audio.closeCalls > 0 }
            delay(SETTLE_MS)
            assertEquals(0, fgs.stopCalls, "the foreground service must not stop before release settles")

            closeGate.complete(Unit)
            awaitTrue("foreground service stopped") { fgs.stopCalls == 1 }
            assertEquals(1, audio.closeCaptureCount)
        }

    @Test
    fun `a peer BYE with no voice ever started still stops the foreground service`() =
        withConnectedSession { coordinator, fgs, _ ->
            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            awaitTrue("foreground service stopped") { fgs.stopCalls == 1 }
        }

    @Test
    fun `an audio release timeout does not stop the foreground service`() =
        withConnectedSession(stopAwaitTimeoutMs = SHORT_TIMEOUT_MS) { coordinator, fgs, audio ->
            coordinator.startIntercom()
            awaitTrue("capture open") { audio.isOpen }
            audio.closeGate = CompletableDeferred() // never completes

            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))

            // Long enough to be well past the short stop-await timeout, short enough the test stays fast.
            delay(SHORT_TIMEOUT_MS * SETTLE_TIMEOUT_MULTIPLIER)
            assertEquals(0, fgs.stopCalls, "a timed-out release must never be treated as proof capture was released")
        }

    /** Link loss (not BYE) must never release capture or stop the foreground service. */
    @Test
    fun `a network link loss does not release capture or stop the foreground service`() =
        withConnectedSession { coordinator, fgs, audio ->
            coordinator.startIntercom()
            awaitTrue("capture open") { audio.isOpen }

            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            delay(SETTLE_MS)

            assertEquals(SessionStatus.RECONNECTING, coordinator.state.value.status)
            assertEquals(0, audio.closeCalls, "a link blip must never release capture")
            assertEquals(0, fgs.stopCalls, "a link blip must never stop the foreground service")
        }

    /** Repeated ENDING (e.g. two BYEs racing) must not double-stop or crash. */
    @Test
    fun `repeated ENDING is safe`() =
        withConnectedSession { coordinator, fgs, audio ->
            coordinator.startIntercom()
            awaitTrue("capture open") { audio.isOpen }

            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            awaitTrue("foreground service stopped") { fgs.stopCalls == 1 }

            // A second BYE-shaped event is illegal from ENDING (SessionFsm) and must not re-fire the effect.
            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            delay(SETTLE_MS)
            assertEquals(1, fgs.stopCalls, "ENDING's effect must not fire twice")
        }

    // --- harness --------------------------------------------------------------------------------

    private class FakeForegroundService : ForegroundServiceController {
        @Volatile var stopCalls = 0
            private set

        override fun stop() {
            stopCalls += 1
        }
    }

    /** Mirrors `network`'s `VoiceTestSupport.FakeVoiceAudioSession`, kept local and minimal. */
    private class FakeVoiceAudioSession : VoiceAudioSession {
        @Volatile var openCaptureCount = 0
            private set

        @Volatile var closeCaptureCount = 0
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

    private class FakeVoiceEngine : VoiceEngine {
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

    private class NoOpVoiceTransport : VoiceSignalTransport {
        override suspend fun send(signal: VoiceSignal): Boolean = false
    }

    private class NoOpControlChannel : ControlChannel {
        override val transportLabel: String = "test"
        override val isSecure: Boolean = true

        override suspend fun bind(): ControlListener = error("not used by this test")

        override suspend fun connect(
            host: String,
            port: Int,
        ): ControlSocket = error("not used by this test")
    }

    private class NoOpDiscoveryController : DiscoveryController {
        override fun advertise(
            port: Int,
            rotationIntervalMs: Long,
        ): Flow<AdvertiseState> = error("not used by this test")

        override fun browse(): Flow<DiscoveryEvent> = error("not used by this test")
    }

    private val remotePeerId = PeerId("0123456789abcdef")

    private fun withConnectedSession(
        stopAwaitTimeoutMs: Long = AWAIT_TIMEOUT_MS,
        body: suspend (SessionCoordinator, FakeForegroundService, FakeVoiceAudioSession) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val fgs = FakeForegroundService()
            val audio = FakeVoiceAudioSession()
            val localIdentity =
                LocalHandshakeIdentity(
                    displayName = "test-device",
                    platform = "android",
                    osVersion = "test",
                    appVersion = "test",
                    connTiebreak = ConnTiebreak("1".repeat(32)),
                    identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                )
            val controlSessionManager =
                ControlSessionManager(
                    scope = scope,
                    monotonicNowUs = { 0L },
                    localPeerId = PeerId("fedcba9876543210"),
                    channel = NoOpControlChannel(),
                    trustedPeers = InMemoryTrustedPeerStore(),
                )
            val coordinator =
                SessionCoordinator(
                    discovery = NoOpDiscoveryController(),
                    controlSessionManager = controlSessionManager,
                    localIdentity = localIdentity,
                    scope = scope,
                    logSink = InMemoryLogSink(),
                    trustedPeers = InMemoryTrustedPeerStore(),
                    environment =
                        SessionEnvironment(
                            monotonicNowUs = { 0L },
                            nowEpochSeconds = { 0L },
                            audioEndpointPresent = { true },
                        ),
                    foregroundService = fgs,
                    buildVoiceController = { isLocalLeader ->
                        VoiceController(
                            scope = scope,
                            engine = FakeVoiceEngine(),
                            audioSession = audio,
                            transport = NoOpVoiceTransport(),
                            isLocalLeader = isLocalLeader,
                            localTrackId = "test-track",
                            audioProcessing = AudioProcessingConfig(),
                        ).also { it.stopAwaitTimeoutMs = stopAwaitTimeoutMs }
                    },
                )

            // Walks the real FSM/trust-gate path to CONNECTED — StartDiscovery -> PeerSelected ->
            // (silent trusted connect) PairingSucceeded -> CONNECTING -> Connected -> CONNECTED —
            // exactly as `SessionGate`/`SessionFsm` require, without a real socket anywhere.
            assertTrue(coordinator.applyEvent(SessionEvent.StartDiscovery))
            assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
            coordinator.handleControlEvent(ControlEvent.PeerTrusted(remotePeerId))
            assertEquals(SessionStatus.CONNECTING, coordinator.state.value.status)
            coordinator.handleControlEvent(
                ControlEvent.Connected(remotePeerId, SessionId("test-session"), isLocalLeader = true),
            )
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)

            body(coordinator, fgs, audio)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(AWAIT_TIMEOUT_MS) {
                while (!condition()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for '$what'", timeout)
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 20L
        const val SETTLE_TIMEOUT_MULTIPLIER = 5L
        const val POLL_MS = 2L
        const val SETTLE_MS = 30L
    }
}
