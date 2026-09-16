package com.ridelink.app.session

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.core.voice.AudioProcessingStatus
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.SdpKind
import com.ridelink.core.voice.VoiceAudioSession
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.core.voice.VoiceStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The Android half of STATUS §4 problem 66, at the seam that decides whether it is reachable here.**
 *
 * The defect is a *liveness* one in the shared negotiation table: a `StartRequested` authorised by a
 * control lifetime that has already ended, meeting a held `VOICE_OFFER` owned by a newer one, used to
 * refuse the press and leave the offer held — and nothing would ever answer it, because a peer sends
 * one `VOICE_OFFER` per `voice_session_id` (PROTOCOL §7.4) and the user had already consented.
 * `VoiceCrossLifetimeAuthorityTest`'s P63-B1…B4 prove the fixed table on this platform.
 *
 * This file answers the different question: **can Android's own coordinator produce that ordering?**
 * It cannot today, and the reason is a property worth pinning rather than a coincidence.
 * [SessionCoordinator.startIntercom] reads `liveAuthenticatedGeneration` and calls
 * `VoiceController.start` **synchronously** — `start` never suspends, and `VoiceInputMailbox.offer`
 * only touches a lock-guarded deque — so a press is in the mailbox before any frame admitted after it,
 * in the same `CRITICAL` lane and therefore ahead of it. iOS cannot say that: `VoiceController.start`
 * is actor-isolated there (it stamps `VoiceSetupTimeline`), so `SessionCoordinator.startIntercom`
 * wraps it in an unstructured `Task` and the press can enter the mailbox *behind* a successor's offer.
 * That difference is the whole of problem 66's reachability, and
 * `VoiceConsentAcrossLifetimesTests` is its iOS reproduction.
 *
 * The dispatcher is manual so the claim is a fact rather than a race: **nothing** the controller owns
 * runs until `runAll()`, so mailbox order here is decided purely by which call offered first.
 */
class SessionCoordinatorIntercomConsentTest {
    /**
     * A press under A, then a successor's offer under B, with the consumer not yet run. The press is
     * already in the mailbox, so B's offer finds consent recorded and is **answered** rather than held
     * — the ordering problem 66 is about never forms on this platform.
     */
    @Test
    fun `a press reaches the mailbox before a frame admitted after it, so a successor's offer is never held`() =
        withCoordinator { coordinator, voiceScope ->
            val voice = requireNotNull(lastVoiceController)

            coordinator.startIntercom()
            // Not one line of the controller has run yet: `start` offered synchronously, from this
            // thread, which is the property under test.
            assertEquals(VoiceStatus.IDLE, voice.diagnostics.value.status)

            // The successor's offer, admitted under B, reaching the same mailbox afterwards.
            voice.submit(VoiceSignal.Offer(OFFER_B, MINIMAL_SDP), CONTROL_B)
            voiceScope.runAll()

            val diagnostics = voice.diagnostics.value
            assertTrue(diagnostics.localAudioOpen, "the press is consent, and it was reduced first")
            assertFalse(diagnostics.peerRequestedVoice, "so B's offer was never held for want of consent")
            assertEquals(VoiceStatus.NEGOTIATING, diagnostics.status)
            assertEquals(OFFER_B.toString(), diagnostics.voiceSessionPrefix, "answered under B's own generation")
            assertNull(
                diagnostics.droppedSignals[VoiceSignalDropReason.SUPERSEDED_START_LIFETIME],
                "and the stale-press branch was never reached at all",
            )
        }

    /**
     * The same ordering through the coordinator's *other* producer of a press — §7.8's reconnect
     * rebuild — which is likewise synchronous, and which is gated on the published `localAudioOpen`
     * projection. With no consent yet it issues no press, which is the step that leaves iOS's deferred
     * press as the only one still in flight.
     */
    @Test
    fun `the reconnect rebuild issues no press when consent has not been recorded`() =
        withCoordinator { coordinator, voiceScope ->
            val voice = requireNotNull(lastVoiceController)
            assertFalse(voice.diagnostics.value.localAudioOpen, "precondition: nobody has consented")

            coordinator.reconnect()
            voiceScope.runAll()

            assertEquals(
                VoiceStatus.IDLE,
                voice.diagnostics.value.status,
                "§7.8 rebuilds a negotiation the user asked for, and the user has not asked",
            )
            assertFalse(voice.diagnostics.value.localAudioOpen, "and a reconnect never opens capture by itself")
        }

    // --- harness ------------------------------------------------------------------------------------

    /** The controller `buildVoiceController` produced, so a test can drive its inbound side directly. */
    private var lastVoiceController: VoiceController? = null

    private fun withCoordinator(body: suspend (SessionCoordinator, ManualDispatcher) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val manual = ManualDispatcher()
            val voiceScope = CoroutineScope(SupervisorJob() + manual)
            try {
                val manager =
                    ControlSessionManager(
                        scope = scope,
                        monotonicNowUs = { 0L },
                        localPeerId = PeerId("fedcba9876543210"),
                        channel = FixtureControlChannel(),
                        trustedPeers = InMemoryTrustedPeerStore(),
                    )
                val coordinator =
                    SessionCoordinator(
                        discovery = FixtureDiscoveryController(),
                        controlSessionManager = manager,
                        localIdentity = LOCAL_IDENTITY,
                        scope = scope,
                        logSink = InMemoryLogSink(),
                        trustedPeers = InMemoryTrustedPeerStore(),
                        environment =
                            SessionEnvironment(
                                monotonicNowUs = { 0L },
                                nowEpochSeconds = { 0L },
                                audioEndpointPresent = { true },
                            ),
                        foregroundService = { },
                        buildVoiceController = { isLocalLeader ->
                            lastVoiceController =
                                VoiceController(
                                    scope = voiceScope,
                                    engine = FixtureVoiceEngine(),
                                    audioSession = FixtureVoiceAudioSession(),
                                    transport = FixtureVoiceTransport(),
                                    isLocalLeader = isLocalLeader,
                                    localTrackId = "test-track",
                                    audioProcessing = AudioProcessingConfig(),
                                )
                            requireNotNull(lastVoiceController)
                        },
                    )
                coordinator.reachConnected()
                body(coordinator, manual)
            } finally {
                voiceScope.cancel()
                scope.cancel()
            }
        }

    /** This peer is the **answerer** — `isLocalLeader = false` — because only an answerer holds an offer. */
    private fun SessionCoordinator.reachConnected() {
        startDiscovery()
        assertEquals(SessionStatus.DISCOVERING, state.value.status)
        assertTrue(applyEvent(SessionEvent.PeerSelected))
        handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
        handleControlEvent(connected(CONTROL_A))
        assertEquals(SessionStatus.CONNECTED, state.value.status)
    }

    private fun SessionCoordinator.reconnect() {
        handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        assertEquals(SessionStatus.RECONNECTING, state.value.status)
        handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
        handleControlEvent(connected(CONTROL_B))
        assertEquals(SessionStatus.CONNECTED, state.value.status)
    }

    private fun connected(generation: Long) =
        ControlEvent.Connected(REMOTE_PEER_ID, SessionId("test-session"), isLocalLeader = false, authGeneration = generation)

    /** Runs nothing until told to, so "the press was already in the mailbox" is a fact. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
                next?.run() ?: break
            }
        }
    }

    private class FixtureControlChannel : ControlChannel {
        override val transportLabel: String = "test"
        override val isSecure: Boolean = true

        override suspend fun bind(): ControlListener = throw CancellationException("fixture has no transport")

        override suspend fun connect(
            host: String,
            port: Int,
        ): ControlSocket = throw CancellationException("fixture has no transport")
    }

    private class FixtureDiscoveryController : DiscoveryController {
        override fun advertise(
            port: Int,
            rotationIntervalMs: Long,
        ): Flow<AdvertiseState> = emptyFlow()

        override fun browse(): Flow<DiscoveryEvent> = emptyFlow()
    }

    /** There is no socket, so every write fails — which is exactly what a retired lifetime's link does. */
    private class FixtureVoiceTransport : VoiceSignalTransport {
        override suspend fun send(
            signal: VoiceSignal,
            controlGeneration: Long?,
        ): Boolean = false
    }

    private class FixtureVoiceAudioSession : VoiceAudioSession {
        override var isOpen: Boolean = false
            private set

        override var route: AudioRouteSnapshot = AudioRouteSnapshot()
            private set

        private var sink: ((AudioRouteSnapshot) -> Unit)? = null

        override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
            this.sink = sink
        }

        override suspend fun open(): Result<Unit> {
            isOpen = true
            sink?.invoke(route)
            return Result.success(Unit)
        }

        override suspend fun close() {
            isOpen = false
        }
    }

    private class FixtureVoiceEngine : VoiceEngine {
        override var diagnostics: VoiceEngineDiagnostics =
            VoiceEngineDiagnostics(audioProcessing = AudioProcessingStatus(true, true, true, false))

        override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) = Unit

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
            diagnostics = diagnostics.copy(transportState = MediaTransportState.CLOSED)
        }

        override suspend fun release() {
            diagnostics = VoiceEngineDiagnostics(transportState = MediaTransportState.CLOSED)
        }

        override suspend fun refreshDiagnostics() = Unit
    }

    private companion object {
        val REMOTE_PEER_ID = PeerId("0123456789abcdef")
        const val CONTROL_A = 1L
        const val CONTROL_B = 2L
        const val MINIMAL_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        val OFFER_B = VoiceSessionId("920".padStart(32, '0'))
        val LOCAL_IDENTITY =
            LocalHandshakeIdentity(
                displayName = "test-device",
                platform = "android",
                osVersion = "test",
                appVersion = "test",
                connTiebreak = ConnTiebreak("1".repeat(32)),
                identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
            )
    }
}
