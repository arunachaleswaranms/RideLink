package com.ridelink.app.service

import com.ridelink.app.session.ForegroundServiceController
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.session.SessionEnvironment
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
 * STATUS §4 problem 129: on the OnePlus, an in-app **End intercom** intermittently left the
 * `microphone`-typed foreground service and an *"Intercom on — Starting the microphone…"*
 * notification behind, while the app said *Intercom off* and no recorder ran.
 *
 * That notification text is a signature. The planner shows *Starting the microphone…* only when
 * the service still believes the intercom is active (`RideForegroundService.stopIntercom` never ran)
 * **and** the voice controller reports capture closed (the release *did* finish). So the release
 * completed, but the foreground-service release that is supposed to follow it never happened.
 *
 * The path that produces exactly that: `VoiceController.stopAndAwaitRelease()` gives the caller a
 * 5 s window that starts before `engine.release()`, while `AndroidVoiceAudioSession.close()` then
 * waits up to its **own** 5 s for the platform to confirm the route change — so an End whose
 * confirmation is late or absent (STATUS §2r/§2s: the outer window is structurally at or before the
 * inner one) returns `TimedOut`, and `IntercomStopOwner` treated `TimedOut` as final. Nothing ever
 * asked again, so the service outlived the capture it existed for. Problem 39 fixed exactly this
 * shape for the session-retirement path (`releaseAndShutdown`); the in-app End path never got it.
 *
 * These drive the **production wiring** — a real [SessionCoordinator], a real [VoiceController] and
 * a real [IntercomStopOwner], connected exactly as `AppContainer` connects them
 * (`sessionCoordinator::endIntercomAndAwaitRelease`) — with a capture release that outlasts the
 * caller window and then completes.
 */
class IntercomEndForegroundServiceTest {
    @Test
    fun `an in-app End whose release outlasts the caller window still releases the foreground service`() =
        withConnectedIntercom { coordinator, owner, services, audio ->
            val closeGate = CompletableDeferred<Unit>()
            audio.closeGate = closeGate

            owner.requestStop()

            // Well past the caller window: the release is still legitimately in flight.
            delay(SHORT_TIMEOUT_MS * SETTLE_TIMEOUT_MULTIPLIER)
            assertEquals(0, services.intercomReleases, "the service must not drop the microphone type before capture is released")
            assertTrue(audio.isOpen, "capture is still being released")

            // The platform's route confirmation (or its own bounded timeout) finally lands.
            closeGate.complete(Unit)
            awaitTrue("capture released") { audio.closeCaptureCount == 1 }
            awaitTrue("the foreground service drops the intercom once capture is released") { services.intercomReleases == 1 }

            delay(SETTLE_MS)
            assertEquals(1, services.intercomReleases, "released exactly once")
            assertEquals(1, audio.closeCalls, "capture is released once; End does not re-close it")
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status, "End intercom is not End session")
            assertEquals(0, services.sessionStops, "the session's own foreground-service stop is not involved")
        }

    @Test
    fun `an in-app End that releases inside the caller window releases the foreground service once`() =
        withConnectedIntercom { coordinator, owner, services, audio ->
            owner.requestStop()

            awaitTrue("foreground service released") { services.intercomReleases == 1 }
            delay(SETTLE_MS)
            assertEquals(1, services.intercomReleases)
            assertEquals(1, audio.closeCaptureCount)
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        }

    @Test
    fun `a Start between a slow End and its late release keeps the new intercom's service`() =
        withConnectedIntercom { _, owner, services, audio ->
            val closeGate = CompletableDeferred<Unit>()
            audio.closeGate = closeGate

            owner.requestStop()
            delay(SHORT_TIMEOUT_MS * SETTLE_TIMEOUT_MULTIPLIER)
            // The user starts the intercom again before the first release has finished.
            owner.noteStart()

            closeGate.complete(Unit)
            awaitTrue("capture released") { audio.closeCaptureCount == 1 }
            delay(SETTLE_MS)
            assertEquals(0, services.intercomReleases, "a late release must not strip the microphone type from the new intercom")
        }

    @Test
    fun `a release that never completes never releases the foreground service`() =
        withConnectedIntercom { _, owner, services, audio ->
            audio.closeGate = CompletableDeferred() // never completes

            owner.requestStop()
            delay(SHORT_TIMEOUT_MS * SETTLE_TIMEOUT_MULTIPLIER)
            assertEquals(0, services.intercomReleases, "an unproven release is never proof the microphone is free")
        }

    @Test
    fun `End intercom is recorded in the diagnostics log`() =
        withConnectedIntercom { _, owner, services, audio ->
            val closeGate = CompletableDeferred<Unit>()
            audio.closeGate = closeGate
            owner.requestStop()
            delay(SHORT_TIMEOUT_MS * SETTLE_TIMEOUT_MULTIPLIER)
            closeGate.complete(Unit)
            awaitTrue("foreground service released") { services.intercomReleases == 1 }

            val messages = logSink.events.map { it.message }
            assertTrue(messages.any { it.startsWith("intercom start requested") }, "start is logged: $messages")
            assertTrue(messages.any { it.startsWith("intercom end requested") }, "end is logged: $messages")
            assertTrue(messages.any { it.startsWith("intercom end: capture release still running") }, "a slow release is logged: $messages")
            assertTrue(messages.any { it.startsWith("intercom end: capture released") }, "the release outcome is logged: $messages")
            assertTrue("intercom end: foreground service decision RELEASED" in messages, "the service decision is logged: $messages")
        }

    // --- harness --------------------------------------------------------------------------------

    private class ServiceCalls {
        @Volatile var intercomReleases = 0

        @Volatile var sessionStops = 0
    }

    private class FakeVoiceAudioSession : VoiceAudioSession {
        @Volatile var closeCaptureCount = 0
            private set

        @Volatile var closeCalls = 0
            private set

        @Volatile override var isOpen: Boolean = false
            private set

        override val route: AudioRouteSnapshot = AudioRouteSnapshot()

        @Volatile var closeGate: CompletableDeferred<Unit>? = null

        override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) = Unit

        override suspend fun open(): Result<Unit> {
            isOpen = true
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

        override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) = Unit

        override suspend fun start(config: VoiceEngineConfig): Result<Unit> = Result.success(Unit)

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

        override suspend fun stop() = Unit

        override suspend fun release() = Unit

        override suspend fun refreshDiagnostics() = Unit
    }

    private class NoOpVoiceTransport : VoiceSignalTransport {
        override suspend fun send(
            signal: VoiceSignal,
            controlGeneration: Long?,
        ): Boolean = false
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

    private val logSink = InMemoryLogSink()

    private fun withConnectedIntercom(body: suspend (SessionCoordinator, IntercomStopOwner, ServiceCalls, FakeVoiceAudioSession) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                val services = ServiceCalls()
                val audio = FakeVoiceAudioSession()
                val coordinator =
                    SessionCoordinator(
                        discovery = NoOpDiscoveryController(),
                        controlSessionManager =
                            ControlSessionManager(
                                scope = scope,
                                monotonicNowUs = { 0L },
                                localPeerId = PeerId("fedcba9876543210"),
                                channel = NoOpControlChannel(),
                                trustedPeers = InMemoryTrustedPeerStore(),
                            ),
                        localIdentity =
                            LocalHandshakeIdentity(
                                displayName = "test-device",
                                platform = "android",
                                osVersion = "test",
                                appVersion = "test",
                                connTiebreak = ConnTiebreak("1".repeat(32)),
                                identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                            ),
                        scope = scope,
                        logSink = logSink,
                        trustedPeers = InMemoryTrustedPeerStore(),
                        environment =
                            SessionEnvironment(
                                monotonicNowUs = { 0L },
                                nowEpochSeconds = { 0L },
                                audioEndpointPresent = { true },
                            ),
                        foregroundService = ForegroundServiceController { services.sessionStops += 1 },
                        buildVoiceController = { isLocalLeader ->
                            VoiceController(
                                scope = scope,
                                engine = FakeVoiceEngine(),
                                audioSession = audio,
                                transport = NoOpVoiceTransport(),
                                isLocalLeader = isLocalLeader,
                                localTrackId = "test-track",
                                audioProcessing = AudioProcessingConfig(),
                            ).also { it.stopAwaitTimeoutMs = SHORT_TIMEOUT_MS }
                        },
                    )
                // Exactly AppContainer's wiring.
                val owner =
                    IntercomStopOwner(
                        scope = scope,
                        endIntercomAndAwaitRelease = coordinator::endIntercomAndAwaitRelease,
                        releaseForegroundService = { services.intercomReleases += 1 },
                        recordDecision = coordinator::recordIntercomServiceDecision,
                    )

                assertTrue(coordinator.applyEvent(SessionEvent.StartDiscovery))
                assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
                coordinator.handleControlEvent(ControlEvent.PeerTrusted(PeerId("0123456789abcdef")))
                coordinator.handleControlEvent(
                    ControlEvent.Connected(
                        PeerId("0123456789abcdef"),
                        SessionId("test-session"),
                        isLocalLeader = true,
                        authGeneration = 1L,
                    ),
                )
                assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)

                // MainActivity's order: the service is started (not modelled), the owner is told, then voice.
                owner.noteStart()
                coordinator.startIntercom()
                awaitTrue("capture open") { audio.isOpen }

                body(coordinator, owner, services, audio)
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
