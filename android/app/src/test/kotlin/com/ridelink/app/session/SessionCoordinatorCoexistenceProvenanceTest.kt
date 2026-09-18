package com.ridelink.app.session

import com.ridelink.app.music.CoexistenceEventSink
import com.ridelink.app.music.GainRampSleeper
import com.ridelink.app.music.IntercomMusicCoexistenceCoordinator
import com.ridelink.app.music.MusicCoexistencePort
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.player.PlayerState
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Phase 6 independent-review blocker 2, at the seam `SessionCoordinator` owns.**
 *
 * `VoiceController` is retained across a reconnect (ADR-020 §6), and the one diagnostics collector
 * `attachVoice` sets up on first construction keeps forwarding every snapshot it publishes to
 * coexistence for the controller's whole lifetime — spanning as many control lifetimes as the ride
 * segment does. Each snapshot is stamped, at production time, with the control lifetime that owned
 * `VoiceNegotiationState` when it was computed (`VoiceDiagnostics.controlGeneration`,
 * `VoiceNegotiationState.negotiationControlGeneration`); `SessionCoordinator.updateCoexistence`
 * refuses one whose provenance does not match the control lifetime its coexistence generation was
 * begun under, rather than relabelling it as the successor's merely because it is the live one when
 * the snapshot happens to be consumed.
 *
 * Everything here — `SessionCoordinator`, `ControlSessionManager`, `VoiceController` and
 * `IntercomMusicCoexistenceCoordinator` — shares one `kotlinx.coroutines.test` scheduler, so a
 * production-shaped sequence of calls with **no** `runCurrent()`/`advanceUntilIdle()` in between is a
 * fact, not a hope: nothing any of them launched can have run yet. That is what lets these tests
 * reproduce the exact race the original bug did not even need — a synchronous seed read — as a
 * deterministic, always-reproducing case, and prove it via the real objects rather than a poke at a
 * private field.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorCoexistenceProvenanceTest {
    /**
     * **The exact production bug, reproduced without any synthetic hook.** The original
     * `attachVoice`'s reconnect branch read `_voiceDiagnostics.value` — whatever `VoiceController`'s
     * persistent collector last wrote — and forwarded it to coexistence synchronously, under B's
     * brand-new generation. The user's PTT release and the reconnect are fired back to back with no
     * scheduler advance in between, so at the instant `Connected(B)` runs, the release has been
     * *offered* to the retained controller's own mailbox but provably not yet *reduced* — the
     * freshest diagnostics value that exists is still "A, actively ducking." A single `runCurrent()`
     * afterwards then runs every queued and chained effect (the release, the link loss, both
     * coexistence lifetimes' ramps) to completion in one deterministic pass.
     */
    @Test
    fun `a predecessor's PTT duck cannot cross a reconnect onto the successor, and the successor still ducks normally`() =
        withCoordinator(policy = IntercomPolicy.MODE_C) { coordinator, music ->
            val voiceA = requireNotNull(lastVoiceController)
            coordinator.startIntercom()
            runCurrent()
            assertTrue(voiceA.diagnostics.value.localAudioOpen, "precondition: capture is open")

            // A genuinely talks: a real PTT press, reduced by the real transmission gate, publishing a
            // real generation-A-owned diagnostics snapshot, settled so the precondition is unambiguous.
            coordinator.setPushToTalkHeld(true)
            runCurrent()
            assertEquals(TARGET_MODE_C, music.gains.last())

            // The user releases the button and the control lifetime ends in the same synchronous
            // burst: no `runCurrent()` between any of these three calls, so none of their effects have
            // been reduced yet when the last one returns.
            val gainsBeforeReconnect = music.gains.size
            coordinator.setPushToTalkHeld(false)
            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            assertEquals(SessionStatus.RECONNECTING, coordinator.state.value.status)
            coordinator.handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
            coordinator.handleControlEvent(connected(CONTROL_B))
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)

            // One pass runs everything queued by the burst above to completion: the release, the link
            // loss, and B's coexistence lifetime resetting to full volume. The property under test is
            // not merely the final value — the buggy seed's spurious re-duck is itself corrected a
            // moment later once the release catches up, which would make a final-value-only check pass
            // even with the bug present — but that **exactly one** ramp happened at all, its full ten
            // deterministic steps (ADR-027's own contract), ending at full: never a second, spurious
            // ramp chained in from A's stale/foreign-generation diagnostics.
            runCurrent()
            val reconnectRamp = music.gains.drop(gainsBeforeReconnect)
            assertEquals(RAMP_STEPS, reconnectRamp.size, "exactly one ramp, not a spurious second one: $reconnectRamp")
            assertEquals(FULL_GAIN, reconnectRamp.last(), "B must never be left ducked by A's stale diagnostics")

            // Liveness, through the same retained controller: B's own genuine PTT press ducks exactly
            // as A's did.
            coordinator.setPushToTalkHeld(true)
            runCurrent()
            assertEquals(TARGET_MODE_C, music.gains.last())
            coordinator.setPushToTalkHeld(false)
            runCurrent()
            assertEquals(FULL_GAIN, music.gains.last())
        }

    /**
     * The same property for Mode D's pause/resume actions rather than a duck ramp. This device must
     * still have a real, A-owned negotiation ([VoiceNegotiationState.negotiationControlGeneration] is
     * only ever established by a `start`/`offerReceived`/`peerWantsVoice` transition — ADR-020 rule
     * 23) for the peer's speech to have any generation to be honestly stamped with at all, so
     * `startIntercom()` runs first; the pause itself is still driven purely by the **peer's** honest
     * signal, never by anything local.
     */
    @Test
    fun `a predecessor's Mode D pause cannot be re-applied to the successor at reconnect`() =
        withCoordinator(policy = IntercomPolicy.MODE_D) { coordinator, music ->
            val voiceA = requireNotNull(lastVoiceController)
            coordinator.startIntercom()
            runCurrent()
            assertTrue(voiceA.diagnostics.value.localAudioOpen, "precondition: capture is open")

            // The peer genuinely talks under a real gated policy (PTT), which is an honest signal —
            // never `mic_muted` alone — and Mode D pauses this device's music for it. `voiceSessionId
            // = null` carries no generation claim (PROTOCOL §7.4) rather than guessing the offer's own
            // freshly generated one.
            voiceA.submit(
                VoiceSignal.State(voiceSessionId = null, VoiceWireState.ACTIVE, micMuted = false, mode = VoiceMode.PTT),
                controlGeneration = CONTROL_A,
            )
            runCurrent()
            assertEquals(listOf(TRACK), music.pauseCalls)

            // The peer's speech ends and the control lifetime ends, back to back with no
            // `runCurrent()` between any of them and the reconnect that follows.
            voiceA.submit(
                VoiceSignal.State(voiceSessionId = null, VoiceWireState.IDLE, micMuted = true, mode = VoiceMode.PTT),
                controlGeneration = CONTROL_A,
            )
            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            coordinator.handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
            coordinator.handleControlEvent(connected(CONTROL_B))
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)

            // One pass runs the peer's speech-end, the link loss and B's coexistence lifetime (which
            // legitimately resumes the track its own reducer found paused — ADR-027's own
            // reconnect-inherits-only-the-restoration-obligation rule, not a bug) to completion. The
            // critical safety property is the pause count: exactly one, ever, however many times the
            // legitimate resume itself is asserted underneath it.
            runCurrent()
            assertEquals(1, music.pauseCalls.size, "no second, spurious pause from A's stale data")
            assertTrue(music.resumeCalls.isNotEmpty(), "the legitimate reconnect resume must still happen")
        }

    // --- harness --------------------------------------------------------------------------------------

    private var lastVoiceController: VoiceController? = null

    private fun withCoordinator(
        policy: IntercomPolicy,
        body: suspend TestScope.(SessionCoordinator, FakeMusicPort) -> Unit,
    ) = runTest {
        // `backgroundScope`, not `this`: `VoiceController`'s diagnostics-poll loop and mailbox
        // consumer are infinite by design (this phase's own review already establishes why — they
        // outlive any one control lifetime) and are meant to be torn down by `shutdown()`/teardown,
        // never by the test framework's own end-of-test job audit. `backgroundScope` shares this
        // same scheduler (so `runCurrent()` still governs everything launched on it) and is
        // auto-cancelled when the test body returns, which is what a real `SessionCoordinator`
        // teardown would otherwise do.
        val scope = backgroundScope
        val manager =
            ControlSessionManager(
                scope = scope,
                monotonicNowUs = { 0L },
                localPeerId = PeerId("fedcba9876543210"),
                channel = FixtureControlChannel(),
                trustedPeers = InMemoryTrustedPeerStore(),
            )
        val music = FakeMusicPort()
        // An instant sleeper: this file is about generation provenance, not ramp timing, which
        // `IntercomMusicCoexistenceCoordinatorTest` already covers deterministically. No real delay
        // means every ramp step drains under a single `runCurrent()`, with no virtual-time advance
        // needed (and none attempted — `VoiceController`'s own infinite diagnostics-poll loop would
        // make `advanceUntilIdle()` hang, which is exactly why this file uses `runCurrent()` only).
        val coexistence = IntercomMusicCoexistenceCoordinator(scope = scope, music = music, sleeper = GainRampSleeper { })
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
                            scope = scope,
                            engine = FixtureVoiceEngine(),
                            audioSession = FixtureVoiceAudioSession(),
                            transport = AcceptingVoiceTransport(),
                            isLocalLeader = isLocalLeader,
                            localTrackId = "test-track",
                            audioProcessing = AudioProcessingConfig(),
                        )
                    requireNotNull(lastVoiceController)
                },
                coexistence = coexistence,
            )
        coordinator.startDiscovery()
        assertEquals(SessionStatus.DISCOVERING, coordinator.state.value.status)
        assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
        coordinator.handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER_ID))
        coordinator.handleControlEvent(connected(CONTROL_A))
        assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        coordinator.selectIntercomPolicy(policy)
        runCurrent()
        body(coordinator, music)
    }

    private fun connected(generation: Long) =
        ControlEvent.Connected(REMOTE_PEER_ID, SessionId("test-session"), isLocalLeader = true, authGeneration = generation)

    // --- fixtures ---------------------------------------------------------------------------------

    private class FakeMusicPort : MusicCoexistencePort {
        private val state = MutableStateFlow(PlayerState(localEntryId = LocalEntryId(TRACK), durationMs = 60_000, playing = true))
        private val volume = MutableStateFlow(FULL_GAIN)
        override val coexistencePlayerState: StateFlow<PlayerState> = state
        override val coexistenceBaseVolumePermille: StateFlow<Int> = volume
        override var coexistenceEvents: CoexistenceEventSink? = null
        val gains = mutableListOf<Int>()
        val pauseCalls = mutableListOf<String>()
        val resumeCalls = mutableListOf<String>()
        private var generation = 0L

        override suspend fun beginCoexistenceLifetime(generation: Long) {
            this.generation = generation
        }

        override suspend fun applyCoexistenceGain(
            generation: Long,
            volumePermille: Int,
        ): Boolean {
            if (generation != this.generation) return false
            gains += volumePermille
            return true
        }

        override suspend fun pauseForVoice(
            generation: Long,
            trackToken: String,
        ): Boolean {
            if (generation != this.generation) return false
            pauseCalls += trackToken
            state.value = state.value.copy(playing = false)
            return true
        }

        override suspend fun resumeAfterVoice(
            generation: Long,
            trackToken: String,
        ): Boolean {
            if (generation != this.generation) return false
            resumeCalls += trackToken
            state.value = state.value.copy(playing = true)
            return true
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

    /** Unlike a real link, every send succeeds — this file is about generation provenance, not loss. */
    private class AcceptingVoiceTransport : VoiceSignalTransport {
        override suspend fun send(
            signal: VoiceSignal,
            controlGeneration: Long?,
        ): Boolean = true
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
        const val FULL_GAIN = 1_000

        /** ADR-027's own ramp contract: ten deterministic steps, whatever the sleeper's real speed. */
        const val RAMP_STEPS = 10
        const val TARGET_MODE_C = 350
        const val TRACK = "00000000-0000-0000-0000-0000000000aa"
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
