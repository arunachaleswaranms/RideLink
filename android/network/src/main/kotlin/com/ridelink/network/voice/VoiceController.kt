package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.IntercomAction
import com.ridelink.core.audiopolicy.IntercomCommandMailbox
import com.ridelink.core.audiopolicy.IntercomInput
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.IntercomTransmission
import com.ridelink.core.audiopolicy.TransmissionState
import com.ridelink.core.audiopolicy.VoiceFailure
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
import com.ridelink.core.voice.VoiceAudioSessionFailure
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceInput
import com.ridelink.core.voice.VoiceInputMailbox
import com.ridelink.core.voice.VoiceMailboxOutcome
import com.ridelink.core.voice.VoiceNegotiation
import com.ridelink.core.voice.VoiceNegotiationState
import com.ridelink.core.voice.VoiceRole
import com.ridelink.core.voice.VoiceSetupMark
import com.ridelink.core.voice.VoiceSetupTimeline
import com.ridelink.core.voice.VoiceSetupTimer
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
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
    /**
     * What `VOICE_STATE.mic_muted` reports: this peer is transmitting silence (PROTOCOL §7.4). Under
     * PTT it is `true` whenever the button is not held, which is correct on the wire and is why the UI
     * shows [userMuted] separately — "not talking right now" and "muted" are different things to a user.
     */
    val micMuted: Boolean = false,
    val mode: VoiceMode = VoiceMode.CONTINUOUS,
    /** ARCHITECTURE §6.3's policy object, as selected. Never five code paths — see [IntercomPolicy]. */
    val policy: IntercomPolicy = IntercomPolicy.DEFAULT,
    /** `AUDIO_STATE.intercom_mode` (PROTOCOL §4.4). Four values, unlike [mode]'s three (ADR-021 §3). */
    val intercomMode: IntercomMode = IntercomPolicy.DEFAULT.intercomWireMode,
    /** Whether outbound audio is flowing **right now**. The gate's whole output. */
    val transmitting: Boolean = false,
    /** The PTT control's current position, for the UI to reflect back at the user. */
    val pttHeld: Boolean = false,
    /** The user's own Mute toggle, as distinct from [micMuted]. Survives a policy change. */
    val userMuted: Boolean = false,
    /**
     * False for as long as no microphone-driven input level exists on this platform, which is
     * **currently always** — see [com.ridelink.core.audiopolicy.TransmissionGate.Vox] and ADR-021 §6.
     * Surfaced rather than hidden, because selecting Mode B while this is false means the VOX gate can
     * never open and the user is entitled to be told that rather than to discover it by silence.
     */
    val voxLevelSourceAvailable: Boolean = false,
    /**
     * Software setup timing (PROTOCOL §7.8 / TEST_PLAN V-01). **Not latency** — see
     * [VoiceSetupTimeline]'s own doc. Mouth-to-ear latency is A-09/V-11 and requires hardware.
     */
    val setup: VoiceSetupTimeline = VoiceSetupTimeline(),
    /** The last named reason the intercom could not run. Never a generic "connection failed" (§41). */
    val lastFailure: VoiceFailure? = null,
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
    /**
     * Monotonic microseconds, for [VoiceSetupTimeline] and nothing else. A parameter rather than a
     * clock read here, so the timings are deterministic in a test and CLAUDE.md rule 5 holds.
     */
    private val monotonicNowUs: () -> Long = { 0 },
    private val newVoiceSessionId: () -> VoiceSessionId = { VoiceSessionIdGenerator.generate() },
) : VoiceSignalSink {
    private var state = VoiceNegotiationState(role = VoiceRole.forLeadership(isLocalLeader))
    private val pending = PendingCandidates()
    private val dropCounts = mutableMapOf<VoiceSignalDropReason, Int>()
    private var rebuildCount = 0
    private var unexpectedCandidateSeen = false
    private var setup = VoiceSetupTimeline()
    private var lastFailure: VoiceFailure? = null

    /**
     * The intercom transmission gate's state (ARCHITECTURE §6.3, ADR-021). Guarded by [mailboxLock]
     * along with both mailboxes, and mutated only by the single consumer in [drainMailboxes] — the
     * offer path never touches it.
     *
     * Starts at [IntercomPolicy.DEFAULT] — Mode C, by architecture rather than by measurement. The
     * owner (`SessionCoordinator`) calls [selectPolicy] immediately after construction with whatever
     * the user has actually chosen, so there is one source of that choice rather than a constructor
     * parameter and a setter that could disagree.
     */
    private var transmission = TransmissionState(policy = IntercomPolicy.DEFAULT)

    private val _diagnostics = MutableStateFlow(VoiceDiagnostics(role = state.role))
    val diagnostics: StateFlow<VoiceDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Guards [mailbox], which is a plain (not thread-safe) data structure mutated from whatever
     * thread produced an input — the control read loop, a WebRTC callback, the UI — while [poll]
     * happens only on [consumerJob]. Every critical section here is an in-memory deque/map
     * operation, so this lock is never held across a suspension point and never blocks its caller
     * for any meaningful time — the property [submit] always had as an unbounded `Channel`.
     */
    private val mailboxLock = Any()
    private val mailbox = VoiceInputMailbox()

    /**
     * The intercom commands' own mailbox — bounded **by construction** at one slot per
     * [com.ridelink.core.audiopolicy.IntercomCommandKind], so no burst of PTT edges, mute taps or
     * policy switches can grow it (this phase's brief §38). Drained by the same single consumer as
     * [mailbox], which is what keeps a press and its release in order without a `Task`/coroutine per
     * event (§39).
     */
    private val intercomMailbox = IntercomCommandMailbox()

    /**
     * The only thing sent across threads now: a wake-up, not the input itself (that lives in
     * [mailbox]). Conflated, so a flood of producers rings the bell at most once between drains —
     * [consumerJob] always drains [mailbox] to empty on each wake, so nothing is lost by coalescing
     * the ring itself.
     */
    private val doorbell = Channel<Unit>(Channel.CONFLATED)
    private var consumerJob: Job? = null
    private var diagnosticsPollJob: Job? = null

    init {
        engine.setEventSink { event -> offer(engineEventToInput(event)) }
        audioSession.setRouteSink { snapshot -> publishRoute(snapshot) }
        consumerJob =
            scope.launch {
                doorbell.consumeEach { drainMailboxes() }
            }
    }

    // --- the four things the app asks for ------------------------------------------------------

    /** The user pressed Start Voice, or a control reconnect is rebuilding voice (PROTOCOL §7.8). */
    fun start() {
        // A fresh negotiation is a fresh measurement (V-01's setup figure is per generation, not a
        // lifetime average), and the mark is taken here rather than in the consumer so it times the
        // user's tap rather than when the queue got round to it.
        val at = monotonicNowUs()
        synchronized(mailboxLock) { setup = VoiceSetupTimer.restart(at) }
        offer(VoiceInput.StartRequested(newVoiceSessionId()))
    }

    /** The user pressed End Voice, or the session is entering `ENDING`. */
    fun stop() {
        offer(VoiceInput.StopRequested)
    }

    /**
     * The user's own Mute toggle. It goes through the intercom gate rather than straight to the
     * negotiation table, because mute is one of five inputs that decide whether audio leaves — the
     * others being the policy, the PTT button, the capture path and any platform interruption — and
     * having two paths to `SetMicrophoneMuted` is how they would come to disagree (ADR-021 §4).
     */
    fun setMicrophoneMuted(muted: Boolean) {
        offerIntercom(IntercomInput.UserMuted(muted))
    }

    /**
     * ARCHITECTURE §6.3's five modes, selected as one policy object. Takes effect immediately and is
     * announced to the peer as `VOICE_STATE.mode` and `AUDIO_STATE.intercom_mode` when either changes.
     *
     * Selecting a policy **never touches the capture device.** That is the whole point of the mode
     * model: `mic_always_open: false` means outbound speech is gated, not that the microphone is
     * reopened per utterance.
     */
    fun selectPolicy(policy: IntercomPolicy) {
        offerIntercom(IntercomInput.PolicySelected(policy))
    }

    /**
     * The PTT control's current position — `true` on press, `false` on release, on touch-cancel, and
     * when the app is backgrounded ([onAppBackgrounded]).
     *
     * **This gates the outbound WebRTC track and nothing else.** It does not open, close, reopen or
     * reconfigure the capture device, the audio session or the peer connection, and it does not change
     * `voice_session_id`. `VoiceControllerIntercomTest` counts the capture operations across 50 presses
     * and asserts they are zero; TEST_PLAN A-10 is the same assertion against real hardware.
     */
    fun setPushToTalkHeld(held: Boolean) {
        offerIntercom(IntercomInput.PttHeld(held))
    }

    /**
     * The app left the foreground while a PTT press may still have been outstanding.
     *
     * This phase's brief §25: backgrounding while held must not leave transmission stuck on. It is the
     * same absolute assignment a release is, deliberately — one code path, so the two cannot diverge.
     * Nothing about capture changes: the ride segment continues and ARCHITECTURE §6.4 gives no second
     * chance to reopen a microphone once the screen is locked.
     */
    fun onAppBackgrounded() {
        offerIntercom(IntercomInput.PttHeld(false))
    }

    /**
     * The control plane was lost. Media goes; capture stays open for the ride segment
     * (ARCHITECTURE §6.3/§6.4). Nothing is retried here — PROTOCOL §10's ladder is the only
     * reconnect loop in the app, and a second one competing with it is the bug the §2e hardening
     * pass fixed for the control plane.
     */
    fun onControlLinkLost() {
        lastFailure = VoiceFailure.CONTROL_LINK_LOST
        offer(VoiceInput.ControlLinkLost)
    }

    /**
     * A `VOICE_*` frame that has **already** passed the ADR-019 trust gate. There is no other entry
     * point: an unauthenticated peer's frame is dropped by `ControlSessionManager` before it can
     * reach this method (PROTOCOL §7.1).
     */
    override fun submit(signal: VoiceSignal) {
        offer(VoiceInput.SignalReceived(signal, newVoiceSessionId()))
    }

    /** Releases every task this controller owns. After this, no callback can mutate anything. */
    suspend fun shutdown() {
        apply(VoiceInput.StopRequested)
        diagnosticsPollJob?.cancel()
        consumerJob?.cancel()
        doorbell.close()
        synchronized(mailboxLock) {
            mailbox.clear()
            intercomMailbox.clear()
        }
        pending.reset()
    }

    // --- the mailbox ------------------------------------------------------------------------

    /**
     * Never suspends and never blocks: [mailboxLock] guards only in-memory deque/map work, so this
     * is safe to call from the control read loop, a WebRTC callback thread, or the UI, exactly as
     * `Channel.trySend` was.
     *
     * A [VoiceMailboxOutcome.CriticalOverflow] or [VoiceMailboxOutcome.TerminalPeerStateOverflow]
     * cannot simply be swallowed — a lost `VOICE_OFFER`/`VOICE_ANSWER`, or a lost peer `closed`/
     * `failed`, would wedge a negotiation with no error anywhere, the same failure mode the old
     * unbounded channel existed to avoid. The response mirrors an actual control-link blip rather
     * than inventing a new one: force [VoiceInput.ControlLinkLost] through the always-accepting
     * teardown lane, which drops the media transport and keeps this user's local capture and the
     * TLS control session both untouched (ARCHITECTURE §6.3/§6.4) — a safe, already-proven-correct
     * degrade, not a new failure path.
     */
    private fun offer(input: VoiceInput) {
        val outcome = synchronized(mailboxLock) { mailbox.offer(input) }
        if (outcome is VoiceMailboxOutcome.CriticalOverflow || outcome is VoiceMailboxOutcome.TerminalPeerStateOverflow) {
            synchronized(mailboxLock) { mailbox.offer(VoiceInput.ControlLinkLost) }
        }
        doorbell.trySend(Unit)
    }

    /**
     * Offers an intercom command. Never suspends and never blocks, exactly like [offer]: the mailbox is
     * bounded by construction, so there is no capacity check to fail and no overflow to degrade from.
     *
     * Safe to call from the UI thread, a lifecycle callback or a platform audio callback.
     */
    private fun offerIntercom(input: IntercomInput) {
        synchronized(mailboxLock) { intercomMailbox.offer(input) }
        doorbell.trySend(Unit)
    }

    /**
     * Drains **both** mailboxes to empty on each wake, intercom commands first.
     *
     * Intercom first because an intercom command's whole output is one or two [VoiceInput]s, which then
     * need draining in the same pass — otherwise a PTT press would sit until the next doorbell ring.
     * The outer loop re-checks both, so the pass ends only when neither has anything left.
     */
    private suspend fun drainMailboxes() {
        while (true) {
            val command = synchronized(mailboxLock) { intercomMailbox.poll() }
            if (command != null) {
                applyIntercom(command)
            } else {
                val next = synchronized(mailboxLock) { mailbox.poll() } ?: break
                apply(next)
            }
        }
    }

    /**
     * Applies one intercom command through the pure [IntercomTransmission] table and performs what
     * comes back.
     *
     * The resulting actions are turned into ordinary [VoiceInput]s — `MuteRequested` and
     * `ModeSelected` — rather than reaching the engine directly, so every effect on the media plane
     * still goes through `VoiceNegotiation`'s generation guard and through the one bounded queue. There
     * is deliberately no second path to `engine.setMicrophoneMuted`.
     */
    private suspend fun applyIntercom(input: IntercomInput) {
        // Read and write in one critical section: `transmission` is only ever mutated here, on the
        // single consumer, but `publishDiagnostics` reads it and a split read/modify/write would be a
        // gap for no benefit. Every operation inside is in-memory, so the lock is never held across a
        // suspension point.
        val outcome =
            synchronized(mailboxLock) {
                IntercomTransmission.reduce(transmission, input).also { transmission = it.state }
            }
        for (action in outcome.actions) {
            when (action) {
                // The gate's absolute value is what reaches the negotiation table (below), not this
                // action, so there is nothing to do on the transition itself.
                is IntercomAction.SetTransmitting -> Unit
                is IntercomAction.AnnounceVoiceMode -> apply(VoiceInput.ModeSelected(action.mode))
                // The coordinator publishes `AUDIO_STATE` from a diagnostics change, and a policy
                // change is one, so there is nothing further to do here.
                IntercomAction.PublishAudioState -> Unit
            }
        }
        // **The gate is the single source of `VOICE_STATE.mic_muted`** (PROTOCOL §7.4: "transmitting
        // silence"), and the driver takes its **absolute** value rather than the
        // `SetTransmitting` diff.
        //
        // The diff is right for the table — it is what `protocol/vectors/intercom/` pins, and a
        // restated unchanged value would be noise there. It is the wrong thing for a driver, because
        // it cannot correct a value that was never established: with a gated policy, capture opening
        // leaves `transmitting` false on both sides of the transition, so no diff is emitted, while
        // `VoiceNegotiationState.micMuted` still holds its `false` default and the wire would claim
        // this side is transmitting. `VoiceNegotiation.mute` is itself idempotent, so offering the
        // absolute value on every intercom input costs nothing and closes that gap.
        // Applied **directly**, not offered.
        //
        // Both are produced by this consumer, on this consumer, so they cannot flood — the mailbox
        // exists to bound *external* producers (the read loop, a WebRTC callback, the UI), and routing
        // these through it would only reintroduce its lane priorities: `MuteRequested` and
        // `ModeSelected` are coalesced-lane inputs, so a `StartRequested` already waiting in the
        // critical lane would be reduced **before** them and would put the previous policy's mode and a
        // stale `mic_muted` on the wire. Applying in place is what makes the first `VOICE_STATE` after a
        // policy change carry that policy.
        apply(VoiceInput.MuteRequested(outcome.state.micMutedForWire))
        publishDiagnostics()
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
            is VoiceAction.ApplyRemoteOffer -> {
                mark(VoiceSetupMark.REMOTE_DESCRIPTION)
                startEngineThen(action.voiceSessionId) { engine.applyRemoteDescription(SdpKind.OFFER, action.sdp) }
            }
            is VoiceAction.ApplyRemoteAnswer -> {
                mark(VoiceSetupMark.REMOTE_DESCRIPTION)
                engine.applyRemoteDescription(SdpKind.ANSWER, action.sdp)
            }
            is VoiceAction.SendOffer -> {
                mark(VoiceSetupMark.LOCAL_DESCRIPTION)
                transport.send(VoiceSignal.Offer(action.voiceSessionId, action.sdp))
            }
            is VoiceAction.SendAnswer -> {
                mark(VoiceSetupMark.LOCAL_DESCRIPTION)
                transport.send(VoiceSignal.Answer(action.voiceSessionId, action.sdp))
            }
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
                // And the gate closes with it, so a later reopen cannot resume a stale press.
                offerIntercom(IntercomInput.CaptureOpen(false))
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
        audioSession
            .open()
            .onSuccess {
                mark(VoiceSetupMark.CAPTURE_OPEN)
                lastFailure = null
            }.onFailure { failure ->
                // FR-025 graceful degradation, with a **named** reason rather than a generic one
                // (this phase's brief §41): the negotiation continues without a local microphone, the
                // control session is untouched, and the UI can say which of permission, activation,
                // route selection or capture actually refused.
                lastFailure = (failure as? VoiceAudioSessionFailure)?.failure ?: VoiceFailure.CAPTURE_START_FAILED
                dropCounts[VoiceSignalDropReason.UNEXPECTED_FOR_STATUS] =
                    (dropCounts[VoiceSignalDropReason.UNEXPECTED_FOR_STATUS] ?: 0) + 1
            }
        // The gate needs to know whether there is a capture path before it can ever transmit
        // (ARCHITECTURE §6.4): a PTT press must never be what opens one.
        offerIntercom(IntercomInput.CaptureOpen(audioSession.isOpen))
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
            // **A new peer connection is a new track, and its enabled state must come from the gate.**
            // Both engines enable the local track when they build it, which is right for full duplex and
            // wrong for every gated policy: under PTT a rebuild would go live before the first press.
            // Pushing the gate's current value here — on every engine start, including a reconnect
            // rebuild — is what makes the track's state a consequence of the policy rather than of a
            // constructor default. Idempotent: the engine just sets a boolean.
            engine.setMicrophoneMuted(synchronized(mailboxLock) { transmission.micMutedForWire })
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

    /**
     * Records one [VoiceSetupMark], first-write-wins within the current negotiation
     * ([VoiceSetupTimer]).
     *
     * Called from the consumer and from engine callbacks, so it takes the lock — the marks are plain
     * `Long`s and the critical section never suspends.
     */
    private fun mark(mark: VoiceSetupMark) {
        val at = monotonicNowUs()
        synchronized(mailboxLock) { setup = VoiceSetupTimer.mark(setup, mark, at) }
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
            is VoiceEngineEvent.RemoteTrackChanged -> {
                if (event.present) mark(VoiceSetupMark.REMOTE_TRACK)
                VoiceInput.RemoteTrackChanged(event.voiceSessionId, event.present)
            }
            is VoiceEngineEvent.TransportStateChanged -> {
                if (event.state == MediaTransportState.CONNECTED) mark(VoiceSetupMark.MEDIA_CONNECTED)
                if (event.state == MediaTransportState.FAILED) lastFailure = VoiceFailure.WEBRTC_FAILED
                VoiceInput.MediaConnectivityChanged(
                    voiceSessionId = event.voiceSessionId,
                    connected = event.state == MediaTransportState.CONNECTED,
                    failed = event.state == MediaTransportState.FAILED,
                )
            }
            is VoiceEngineEvent.Failed -> {
                lastFailure = VoiceFailure.WEBRTC_FAILED
                VoiceInput.MediaConnectivityChanged(
                    voiceSessionId = event.voiceSessionId,
                    connected = false,
                    failed = true,
                )
            }
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
        // An interruption is a *route* fact (ADR-016), and it is one of the two overrides that can
        // only ever stop transmission. Routed through the gate rather than acted on here, so there is
        // one place that decides whether audio leaves.
        offerIntercom(IntercomInput.Interrupted(snapshot.interrupted))
    }

    private fun publishDiagnostics() {
        val snapshot =
            synchronized(mailboxLock) {
                DiagnosticsSnapshot(
                    mailboxOverflows = mailbox.overflowCount,
                    transmission = transmission,
                    setup = setup,
                )
            }
        val droppedSignals =
            if (snapshot.mailboxOverflows > 0) {
                dropCounts + (VoiceSignalDropReason.INPUT_MAILBOX_OVERFLOW to snapshot.mailboxOverflows)
            } else {
                dropCounts.toMap()
            }
        _diagnostics.value =
            _diagnostics.value.copy(
                status = state.status,
                role = state.role,
                voiceSessionPrefix = state.voiceSessionId?.toString(),
                micMuted = state.micMuted,
                mode = state.mode,
                policy = snapshot.transmission.policy,
                intercomMode = snapshot.transmission.policy.intercomWireMode,
                transmitting = snapshot.transmission.transmitting,
                pttHeld = snapshot.transmission.pttHeld,
                userMuted = snapshot.transmission.userMuted,
                // False until a microphone-driven level exists on this platform, which is currently
                // always — see the field's own doc and ADR-021 §6.
                voxLevelSourceAvailable = false,
                setup = snapshot.setup,
                lastFailure = lastFailure,
                peerReportedState = state.peerReportedState,
                peerRequestedVoice = state.heldRemoteOffer != null || (state.peerVoiceEnabled && !state.localAudioOpen),
                // The gate's own view of the capture path, not the session object's, so this field can
                // never disagree with `transmitting` — which is derived from the same value. It is
                // "consent AND a real capture path": `VoiceNegotiationState.localAudioOpen` records that
                // the user consented for this ride segment, which stays true even when the platform
                // refused the microphone (FR-025 graceful degradation), so consent alone would render as
                // "mic: open" on a device that has none.
                localAudioOpen = state.localAudioOpen && snapshot.transmission.captureOpen,
                engine = engine.diagnostics,
                queuedCandidates = pending.size,
                droppedQueuedCandidates = pending.droppedCount,
                droppedSignals = droppedSignals,
                rebuildCount = rebuildCount,
                unexpectedCandidateTypeSeen = unexpectedCandidateSeen,
            )
    }

    /** The three lock-guarded values [publishDiagnostics] needs, read in one critical section. */
    private data class DiagnosticsSnapshot(
        val mailboxOverflows: Int,
        val transmission: TransmissionState,
        val setup: VoiceSetupTimeline,
    )

    private companion object {
        const val DIAGNOSTICS_POLL_MS = 2_000L
    }
}
