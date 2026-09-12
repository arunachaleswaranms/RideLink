package com.ridelink.app.session

import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.AudioStateEpoch
import com.ridelink.core.protocol.AudioStateMessage
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.ridelink.core.sessionfsm.LinkLossReason as FsmLinkLossReason

/**
 * The **wiring** half of ADR-021 Amendment A7 (`docs/STATUS.md` §4 problem 47), at the one layer that
 * owns it: `SessionCoordinator` decides when an `AUDIO_STATE` sender lifetime begins and when the
 * peer's held state stops applying, and it must get both of those exactly right.
 *
 * `AudioStateSenderLifetimeTest` (network module) proves what the *rule* does once a lifetime has
 * restarted, end to end over real TLS. It cannot prove the two facts below, because they are
 * decisions this class makes:
 *
 * 1. **A new discovery session begins a new sender lifetime, with an epoch that has never been used.**
 *    A constant here — or a `resetForNewSession` that moved the counter without moving the epoch —
 *    would tell the peer that a restarted counter was a continuation of the old one, which is exactly
 *    the state problem 47 leaves a receiver in.
 * 2. **A control reconnect begins no lifetime at all**, because PROTOCOL §4.4.1 says the `revision` is
 *    not reset by one. A coordinator that reset here would break the continuity §4.4 promises and
 *    make the floor unable to refuse a straggler from before the blip.
 *
 * Both are asserted through the **real** `SessionCoordinator`, its real FSM and its real
 * `AudioStatePublisher`/`AudioStateInboxHolder`. The transport is absent on purpose (see
 * [FixtureControlChannel]): everything under test is synchronous state this class owns, and giving it
 * a socket would only add a second thing that could fail.
 *
 * There is **no iOS mirror**: `ios/RideLink/SessionCoordinator.swift` lives in the Xcode app target,
 * which has no test bundle at all (`docs/STATUS.md` §4 problem 48). The iOS code is mirrored
 * line-for-line and is unproven at this layer, which problem 48 already records for every coordinator
 * in that target.
 */
class SessionCoordinatorAudioStateLifetimeTest {
    // --- the sending side: when does a lifetime begin ----------------------------------------------

    @Test
    fun `each discovery session begins a sender lifetime that has never been used before`() =
        withCoordinator { coordinator, manager ->
            val first = coordinator.audioStateSenderEpoch

            coordinator.restartDiscovery(manager)
            val second = coordinator.audioStateSenderEpoch
            coordinator.restartDiscovery(manager)
            val third = coordinator.audioStateSenderEpoch

            assertNotEquals(first, second, "a new discovery session must announce a new lifetime")
            assertNotEquals(second, third, "and so must the next one")
            assertNotEquals(first, third, "an epoch is never reused")
        }

    /**
     * PROTOCOL §4.4.1, asserted rather than assumed: the `revision` is not reset by a control
     * reconnect, so the lifetime that scopes it must not move either. This walks the real
     * `LinkLost -> RECONNECTING -> PeerTrusted -> Connected` path the FSM and `SessionGate` define.
     */
    @Test
    fun `a control reconnect does not begin a new sender lifetime`() =
        withCoordinator { coordinator, _ ->
            val before = coordinator.audioStateSenderEpoch

            coordinator.reconnect()

            assertEquals(before, coordinator.audioStateSenderEpoch, "a reconnect resumes, it does not restart")
        }

    /** Nor does a voice rebuild or a mode change — §4.4.1 names all three in one sentence. */
    @Test
    fun `selecting an intercom mode does not begin a new sender lifetime`() =
        withCoordinator { coordinator, _ ->
            val before = coordinator.audioStateSenderEpoch

            coordinator.selectIntercomPolicy(coordinator.intercomPolicy.value)
            coordinator.reconnect()

            assertEquals(before, coordinator.audioStateSenderEpoch)
        }

    // --- the receiving side: when does the peer's held state stop applying --------------------------

    /**
     * A control reconnect keeps the peer's state, which is what makes a delayed pre-blip frame still
     * refusable. Driven through the coordinator's **own** sink — the one `attachVoice` installs — so
     * this is the production ingress path and not a poke at a private field.
     */
    @Test
    fun `a control reconnect keeps the peer's held audio state and its floor`() =
        withCoordinator { coordinator, manager ->
            val peer = AudioStateEpoch("cccccccccccccccccccccccccccccccc")
            manager.submitPeerAudioState(peerMessage(revision = 9, epoch = peer))
            coordinator.awaitPeerRevision(9)

            coordinator.reconnect()

            assertEquals(9L, coordinator.peerAudioState.value?.revision, "the peer's state survives a blip")
            manager.submitPeerAudioState(peerMessage(revision = 8, epoch = peer))
            coordinator.settle()
            assertEquals(9L, coordinator.peerAudioState.value?.revision, "and its floor still refuses a stale one")
        }

    /**
     * A new discovery session drops it, because it belonged to the old one — and drops the superseded
     * lifetimes with it, so a peer this device is no longer tracking is not one it can still call
     * retired. The second half is what a `reset()` that forgot the ring would fail.
     */
    @Test
    fun `a new discovery session drops the peer's held state and every lifetime it had retired`() =
        withCoordinator { coordinator, manager ->
            val firstPeerLifetime = AudioStateEpoch("cccccccccccccccccccccccccccccccc")
            val secondPeerLifetime = AudioStateEpoch("dddddddddddddddddddddddddddddddd")
            manager.submitPeerAudioState(peerMessage(revision = 40, epoch = firstPeerLifetime))
            coordinator.awaitPeerRevision(40)
            manager.submitPeerAudioState(peerMessage(revision = 1, epoch = secondPeerLifetime))
            coordinator.awaitPeerRevision(1)
            // The first lifetime is now retired, and a straggler from it is refused.
            manager.submitPeerAudioState(peerMessage(revision = 41, epoch = firstPeerLifetime))
            coordinator.settle()
            assertEquals(1L, coordinator.peerAudioState.value?.revision, "the straggler was refused")

            coordinator.restartDiscovery(manager)
            assertNull(coordinator.peerAudioState.value, "the peer's state belonged to the old session")

            // A fresh local session tracks nothing, so it has no standing to call that lifetime dead.
            manager.submitPeerAudioState(peerMessage(revision = 41, epoch = firstPeerLifetime))
            coordinator.awaitPeerRevision(41)
        }

    // --- harness ------------------------------------------------------------------------------------

    private fun withCoordinator(body: suspend (SessionCoordinator, ControlSessionManager) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
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
                            VoiceController(
                                scope = scope,
                                engine = FixtureVoiceEngine(),
                                audioSession = FixtureVoiceAudioSession(),
                                transport = FixtureVoiceTransport(),
                                isLocalLeader = isLocalLeader,
                                localTrackId = "test-track",
                                audioProcessing = AudioProcessingConfig(),
                            )
                        },
                    )
                coordinator.reachConnected()
                body(coordinator, manager)
            } finally {
                scope.cancel()
            }
        }

    /**
     * `StartDiscovery -> PeerSelected -> PeerTrusted -> Connected`, the real trust-gate path
     * `SessionGate`/`SessionFsm` require. `attachVoice` runs on `Connected`, which is what installs
     * the `AUDIO_STATE` sink these rows deliver through.
     */
    private fun SessionCoordinator.reachConnected() {
        startDiscovery()
        assertEquals(SessionStatus.DISCOVERING, state.value.status)
        assertTrue(applyEvent(SessionEvent.PeerSelected))
        handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
        handleControlEvent(connected())
        assertEquals(SessionStatus.CONNECTED, state.value.status)
    }

    /**
     * The real reconnect path: `LinkLost(NETWORK)` puts the FSM in `RECONNECTING`, and the successor
     * connection comes back through the same trust gate a first connection does — there is no
     * separate "reconnect succeeded" control event, which is ADR-019's construction.
     */
    private fun SessionCoordinator.reconnect() {
        handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        assertEquals(SessionStatus.RECONNECTING, state.value.status)
        handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
        handleControlEvent(connected())
        assertEquals(SessionStatus.CONNECTED, state.value.status)
    }

    /**
     * Ends this session and starts a new discovery one — the production path a user takes by leaving
     * and re-entering discovery, and the only thing that begins a new sender lifetime.
     */
    private suspend fun SessionCoordinator.restartDiscovery(manager: ControlSessionManager) {
        assertTrue(applyEvent(SessionEvent.LinkLost(FsmLinkLossReason.BYE)))
        // `ENDING`'s effect releases audio in a **launched** coroutine, and that release is what
        // clears the `AUDIO_STATE` sink. `TeardownComplete` means "that finished", so waiting for it
        // to actually have finished is what the event says rather than a stabilisation: driving the
        // transition first would let Session A's trailing teardown clear the sink the *next* session
        // installs. (On a laptop the launch wins the race every time; on a loaded CI agent it does
        // not — which is the only reason this is written down rather than assumed.)
        awaitTrue("the ENDING release to finish") { manager.audioState.sink == null }
        assertTrue(applyEvent(SessionEvent.TeardownComplete))
        assertEquals(SessionStatus.IDLE, state.value.status)
        reachConnected()
    }

    private suspend fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
        assertTrue(condition(), "timed out waiting for $what")
    }

    private suspend fun SessionCoordinator.awaitPeerRevision(revision: Long) {
        withTimeout(TIMEOUT_MS) {
            while (peerAudioState.value?.revision != revision) delay(POLL_MS)
        }
    }

    /** Lets a message that must change nothing actually arrive, so "nothing happened" is a result. */
    private suspend fun SessionCoordinator.settle() = delay(SETTLE_MS)

    /** The coordinator's own ingress: the sink `attachVoice` installed on the real relay. */
    private fun ControlSessionManager.submitPeerAudioState(message: AudioStateMessage) {
        val sink = requireNotNull(audioState.sink) { "attachVoice must have installed the AUDIO_STATE sink" }
        sink.submit(message)
    }

    private fun connected() = ControlEvent.Connected(REMOTE_PEER_ID, SessionId("test-session"), isLocalLeader = true)

    private fun peerMessage(
        revision: Long,
        epoch: AudioStateEpoch,
        routeState: RouteState = RouteState.STABLE,
    ) = AudioStateMessage(
        revision = revision,
        revisionEpoch = epoch,
        endpointClass = EndpointClass.BLUETOOTH,
        microphoneOpen = true,
        effectiveOutputProfile = AudioProfile.DUPLEX_WIDEBAND,
        effectiveInputProfile = AudioProfile.DUPLEX_WIDEBAND,
        effectiveOutputSampleRateHz = 16_000,
        effectiveInputSampleRateHz = 16_000,
        mediaQuality = MediaQuality.REDUCED,
        routeState = routeState,
        intercomMode = IntercomMode.PTT,
        confidence = AudioConfidence.ASSUMED,
    )

    // --- fixtures -----------------------------------------------------------------------------------

    /**
     * No transport at all. `ControlListener`'s constructor is `internal` to the `network` module, so
     * an app-module test cannot produce one — and does not need to: every property under test is
     * synchronous state `SessionCoordinator` owns, decided before `startDiscovery` launches anything.
     * Cancelling rather than throwing keeps the launched half from filling the log with a failure that
     * is a property of the fixture rather than of the code.
     */
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

    private class FixtureVoiceTransport : VoiceSignalTransport {
        override suspend fun send(signal: VoiceSignal): Boolean = false
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
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 2L
        const val SETTLE_MS = 50L
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
