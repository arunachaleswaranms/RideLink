package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.core.voice.IceCandidateType
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.PendingCandidates
import com.ridelink.core.voice.RemoteCandidate
import com.ridelink.core.voice.SdpKind
import com.ridelink.core.voice.VoiceAction
import com.ridelink.core.voice.VoiceAudioSession
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceInput
import com.ridelink.core.voice.VoiceNegotiation
import com.ridelink.core.voice.VoiceNegotiationState
import com.ridelink.core.voice.VoiceRole
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** FR-023 voice diagnostics, as one observable value. Contains nothing PROTOCOL §7.7 forbids. */
data class VoiceDiagnostics(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val role: VoiceRole? = null,
    /** Redacted to 6 characters, per the ARCHITECTURE §11 rule for ephemeral hex identifiers. */
    val voiceSessionPrefix: String? = null,
    val micMuted: Boolean = false,
    val mode: VoiceMode = VoiceMode.CONTINUOUS,
    val peerReportedState: VoiceWireState = VoiceWireState.IDLE,
    val peerRequestedVoice: Boolean = false,
    val localAudioOpen: Boolean = false,
    val engine: VoiceEngineDiagnostics = VoiceEngineDiagnostics(),
    val route: AudioRouteSnapshot = AudioRouteSnapshot(),
    val queuedCandidates: Int = 0,
    val droppedQueuedCandidates: Int = 0,
    /** Counted by reason, so "why did voice not come up" has an answer that is not a guess. */
    val droppedSignals: Map<VoiceSignalDropReason, Int> = emptyMap(),
    /** How many times the media transport has been rebuilt in this control session (§7.8). */
    val rebuildCount: Int = 0,
    /**
     * True if any candidate type other than `host` was ever gathered or received. PROTOCOL §7.6
     * configures an empty ICE server list, so this must stay false — it is surfaced rather than
     * asserted because a false alarm on a ride is better than a crash.
     */
    val unexpectedCandidateTypeSeen: Boolean = false,
)

/** ADR-020: 16 CSPRNG bytes as 32 lowercase hex, fresh per negotiation (PROTOCOL §7.2). */
object VoiceSessionIdGenerator {
    private const val BYTES = 16
    private val random = SecureRandom()

    fun generate(): VoiceSessionId {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return VoiceSessionId(bytes.joinToString("") { "%02x".format(it) })
    }
}

/**
 * Owns one voice session's WebRTC lifecycle, and nothing else.
 *
 * **What it owns:** the peer connection's creation and disposal, the local capture/audio-session
 * handle, the negotiation state (via the pure [VoiceNegotiation] table), the bounded trickle-ICE
 * queue, mute, and the FR-023 voice diagnostics.
 *
 * **What it deliberately does not own:** RideLink trust, the global session FSM, discovery, peer
 * identity persistence, music, and navigation. `SessionCoordinator` remains the single owner of
 * session state (CLAUDE.md rule 8) — it constructs this controller only for an authenticated
 * session, tells it when the control link goes, and tears it down on `ENDING`.
 *
 * **Every decision is in the pure table, not here.** This class is a driver: it turns inputs into
 * [VoiceInput], applies the [VoiceAction]s that come back, and reports. That division is the direct
 * lesson of ADR-019 and of STATUS §4 problem 20 — the Phase 1b security bug lived in a `when` that
 * no test suite could construct. `VoiceNegotiation` is exhausted by shared vectors on both
 * platforms; what is left here is effects.
 *
 * One controller per two-person session, enforced by there being exactly one construction site
 * (`AppContainer`) and by [start] being idempotent through the table.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val engine: VoiceEngine,
    private val audioSession: VoiceAudioSession,
    private val transport: VoiceSignalTransport,
    isLocalLeader: Boolean,
    private val localTrackId: String,
    private val audioProcessing: AudioProcessingConfig = AudioProcessingConfig(),
    private val newVoiceSessionId: () -> VoiceSessionId = { VoiceSessionIdGenerator.generate() },
) : VoiceSignalSink {
    private var state = VoiceNegotiationState(role = VoiceRole.forLeadership(isLocalLeader))
    private val pending = PendingCandidates()
    private val dropCounts = mutableMapOf<VoiceSignalDropReason, Int>()
    private var rebuildCount = 0
    private var unexpectedCandidateSeen = false

    private val _diagnostics = MutableStateFlow(VoiceDiagnostics(role = state.role))
    val diagnostics: StateFlow<VoiceDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Unbounded and drained by exactly one consumer. Both properties are load-bearing: [submit] is
     * called from the control read loop, so it must not suspend it, and a dropped `VOICE_OFFER`
     * would wedge a negotiation with no error recorded anywhere. Single consumer is what preserves
     * arrival order — the same reasoning as iOS's `OrderedEventChannel` (STATUS §2h).
     */
    private val inputs = Channel<VoiceInput>(Channel.UNLIMITED)
    private var consumerJob: Job? = null
    private var diagnosticsPollJob: Job? = null

    init {
        engine.setEventSink { event -> inputs.trySend(engineEventToInput(event)) }
        audioSession.setRouteSink { snapshot -> publishRoute(snapshot) }
        consumerJob =
            scope.launch {
                for (input in inputs) apply(input)
            }
    }

    // --- the four things the app asks for ------------------------------------------------------

    /** The user pressed Start Voice, or a control reconnect is rebuilding voice (PROTOCOL §7.8). */
    fun start() {
        inputs.trySend(VoiceInput.StartRequested(newVoiceSessionId()))
    }

    /** The user pressed End Voice, or the session is entering `ENDING`. */
    fun stop() {
        inputs.trySend(VoiceInput.StopRequested)
    }

    fun setMicrophoneMuted(muted: Boolean) {
        inputs.trySend(VoiceInput.MuteRequested(muted))
    }

    /**
     * The control plane was lost. Media goes; capture stays open for the ride segment
     * (ARCHITECTURE §6.3/§6.4). Nothing is retried here — PROTOCOL §10's ladder is the only
     * reconnect loop in the app, and a second one competing with it is the bug the §2e hardening
     * pass fixed for the control plane.
     */
    fun onControlLinkLost() {
        inputs.trySend(VoiceInput.ControlLinkLost)
    }

    /**
     * A `VOICE_*` frame that has **already** passed the ADR-019 trust gate. There is no other entry
     * point: an unauthenticated peer's frame is dropped by `ControlSessionManager` before it can
     * reach this method (PROTOCOL §7.1).
     */
    override fun submit(signal: VoiceSignal) {
        inputs.trySend(VoiceInput.SignalReceived(signal, newVoiceSessionId()))
    }

    /** Releases every task this controller owns. After this, no callback can mutate anything. */
    suspend fun shutdown() {
        apply(VoiceInput.StopRequested)
        diagnosticsPollJob?.cancel()
        consumerJob?.cancel()
        inputs.close()
        pending.reset()
    }

    // --- the driver ----------------------------------------------------------------------------

    private suspend fun apply(input: VoiceInput) {
        val outcome = VoiceNegotiation.reduce(state, input)
        state = outcome.state
        for (action in outcome.actions) perform(action)
        publishDiagnostics()
    }

    @Suppress("CyclomaticComplexMethod") // a flat 1:1 dispatch over VoiceAction; splitting it hides the mapping
    private suspend fun perform(action: VoiceAction) {
        when (action) {
            VoiceAction.StartLocalAudio -> startLocalAudio()
            is VoiceAction.CreateOffer -> startEngineThen(action.voiceSessionId) { engine.createOffer() }
            is VoiceAction.CreateAnswer -> startEngineThen(action.voiceSessionId) { engine.createAnswer() }
            is VoiceAction.ApplyRemoteOffer ->
                startEngineThen(action.voiceSessionId) { engine.applyRemoteDescription(SdpKind.OFFER, action.sdp) }
            is VoiceAction.ApplyRemoteAnswer -> engine.applyRemoteDescription(SdpKind.ANSWER, action.sdp)
            is VoiceAction.SendOffer -> transport.send(VoiceSignal.Offer(action.voiceSessionId, action.sdp))
            is VoiceAction.SendAnswer -> transport.send(VoiceSignal.Answer(action.voiceSessionId, action.sdp))
            is VoiceAction.SendVoiceState ->
                transport.send(
                    VoiceSignal.State(action.voiceSessionId, action.state, action.micMuted, action.mode),
                )
            is VoiceAction.ApplyRemoteCandidate -> {
                noteCandidateType(action.candidate)
                engine.addRemoteCandidate(action.candidate, action.sdpMid, action.sdpMlineIndex)
            }
            is VoiceAction.SendCandidate -> {
                // PROTOCOL §7.6 inspects the `typ` of every candidate this side **gathers** as well
                // as every one it receives. The gathering direction is the one that would reveal a
                // STUN server had been contacted, so missing it would miss the case the check is for.
                noteCandidateType(action.candidate)
                transport.send(
                    VoiceSignal.IceCandidate(
                        action.voiceSessionId,
                        action.candidate,
                        action.sdpMid,
                        action.sdpMlineIndex,
                    ),
                )
            }
            is VoiceAction.QueueRemoteCandidate -> {
                noteCandidateType(action.candidate)
                pending.offer(
                    RemoteCandidate(action.voiceSessionId, action.candidate, action.sdpMid, action.sdpMlineIndex),
                )
            }
            VoiceAction.DrainQueuedCandidates -> drainCandidates()
            is VoiceAction.SetMicrophoneMuted -> engine.setMicrophoneMuted(action.muted)
            VoiceAction.StopMediaTransport -> stopMediaTransport()
            VoiceAction.ReleaseLocalAudio -> {
                // Order: media factory and capture device first, then the platform audio session.
                // Releasing the session while WebRTC still holds `AudioRecord` leaves the route in
                // a state neither side owns.
                engine.release()
                audioSession.close()
            }
            is VoiceAction.RecordDroppedSignal -> {
                dropCounts[action.reason] = (dropCounts[action.reason] ?: 0) + 1
            }
            VoiceAction.SurfacePeerVoiceRequest -> Unit // published through diagnostics.peerRequestedVoice
        }
    }

    /**
     * Opens the capture device and the audio session. A failure here is **not** silent and is not a
     * crash: the negotiation continues without a local microphone, which is FR-025's graceful
     * degradation, and the diagnostics show `localAudioOpen = false` so the UI can say why.
     */
    private suspend fun startLocalAudio() {
        audioSession.open().onFailure {
            dropCounts[VoiceSignalDropReason.UNEXPECTED_FOR_STATUS] =
                (dropCounts[VoiceSignalDropReason.UNEXPECTED_FOR_STATUS] ?: 0) + 1
        }
        publishRoute(audioSession.route)
    }

    /**
     * Every SDP action needs a peer connection, and the negotiation table does not model "engine
     * started" — that is an effect, not a decision. Starting it here, idempotently and keyed on the
     * generation, is what keeps the table free of a field that would only ever mirror this class.
     */
    private var startedGeneration: VoiceSessionId? = null

    private suspend fun startEngineThen(
        voiceSessionId: VoiceSessionId,
        block: suspend () -> Result<Unit>,
    ) {
        if (startedGeneration != voiceSessionId) {
            engine
                .start(
                    VoiceEngineConfig(
                        voiceSessionId = voiceSessionId,
                        localTrackId = localTrackId,
                        audioProcessing = audioProcessing,
                    ),
                ).onFailure { return }
            startedGeneration = voiceSessionId
            if (rebuildCount == 0 && diagnosticsPollJob == null) startDiagnosticsPolling()
        }
        block()
    }

    private suspend fun stopMediaTransport() {
        if (startedGeneration != null) rebuildCount += 1
        startedGeneration = null
        engine.stop()
        // The queue belongs to a generation. Clearing it here — rather than relying on `drain`'s
        // generation filter alone — means a candidate from a torn-down negotiation is not merely
        // unusable, it is gone.
        pending.clear()
        publishEngineDiagnostics()
    }

    private suspend fun drainCandidates() {
        val id = state.voiceSessionId ?: return
        for (candidate in pending.drain(id)) {
            engine.addRemoteCandidate(candidate.candidate, candidate.sdpMid, candidate.sdpMlineIndex)
        }
    }

    private fun noteCandidateType(candidateLine: String) {
        // The **type** only. PROTOCOL §7.7 gives an address and port no log path at all, and a
        // value that is never extracted cannot be leaked by a later careless log call.
        if (IceCandidateType.fromCandidateLine(candidateLine).impliesNonLocalDependency) {
            unexpectedCandidateSeen = true
        }
    }

    /**
     * Maps the media stack's callbacks onto table inputs. Every one carries its
     * `voice_session_id`, which is the generation guard applied to callbacks rather than to the
     * wire (PROTOCOL §7.8) — a delegate call from a peer connection this controller has already
     * closed names the old generation and the table drops it.
     */
    private fun engineEventToInput(event: VoiceEngineEvent): VoiceInput =
        when (event) {
            is VoiceEngineEvent.OfferCreated -> VoiceInput.LocalOfferCreated(event.voiceSessionId, event.sdp)
            is VoiceEngineEvent.AnswerCreated -> VoiceInput.LocalAnswerCreated(event.voiceSessionId, event.sdp)
            is VoiceEngineEvent.LocalCandidateGathered ->
                VoiceInput.LocalCandidateGathered(
                    event.voiceSessionId,
                    event.candidate,
                    event.sdpMid,
                    event.sdpMlineIndex,
                )
            is VoiceEngineEvent.RemoteTrackChanged ->
                VoiceInput.RemoteTrackChanged(event.voiceSessionId, event.present)
            is VoiceEngineEvent.TransportStateChanged ->
                VoiceInput.MediaConnectivityChanged(
                    voiceSessionId = event.voiceSessionId,
                    connected = event.state == MediaTransportState.CONNECTED,
                    failed = event.state == MediaTransportState.FAILED,
                )
            is VoiceEngineEvent.Failed ->
                VoiceInput.MediaConnectivityChanged(
                    voiceSessionId = event.voiceSessionId,
                    connected = false,
                    failed = true,
                )
        }

    // --- diagnostics ---------------------------------------------------------------------------

    private fun startDiagnosticsPolling() {
        diagnosticsPollJob =
            scope.launch {
                while (true) {
                    delay(DIAGNOSTICS_POLL_MS)
                    engine.refreshDiagnostics()
                    publishEngineDiagnostics()
                }
            }
    }

    private fun publishEngineDiagnostics() {
        _diagnostics.value = _diagnostics.value.copy(engine = engine.diagnostics)
    }

    private fun publishRoute(snapshot: AudioRouteSnapshot) {
        _diagnostics.value = _diagnostics.value.copy(route = snapshot)
    }

    private fun publishDiagnostics() {
        _diagnostics.value =
            _diagnostics.value.copy(
                status = state.status,
                role = state.role,
                voiceSessionPrefix = state.voiceSessionId?.toString(),
                micMuted = state.micMuted,
                mode = state.mode,
                peerReportedState = state.peerReportedState,
                peerRequestedVoice = state.heldRemoteOffer != null || (state.peerVoiceEnabled && !state.localAudioOpen),
                localAudioOpen = state.localAudioOpen && audioSession.isOpen,
                engine = engine.diagnostics,
                queuedCandidates = pending.size,
                droppedQueuedCandidates = pending.droppedCount,
                droppedSignals = dropCounts.toMap(),
                rebuildCount = rebuildCount,
                unexpectedCandidateTypeSeen = unexpectedCandidateSeen,
            )
    }

    private companion object {
        const val DIAGNOSTICS_POLL_MS = 2_000L
    }
}
