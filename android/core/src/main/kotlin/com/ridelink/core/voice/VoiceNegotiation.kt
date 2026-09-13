package com.ridelink.core.voice

import com.ridelink.core.model.PeerId
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState

/**
 * PROTOCOL §7.3 — who creates the WebRTC offer.
 *
 * Derived from ADR-010 leadership (the lexicographically smaller `peer_id`) and from **nothing
 * else**. In particular not from which side dialled the TCP connection and not from which
 * connection survived PROTOCOL §4.2: `conn_tiebreak` and `peer_id` are uncorrelated by
 * construction (ADR-015 Amendment A2), so inferring the offerer from the initiator would work by
 * coincidence in a lab and fail on a ride.
 */
enum class VoiceRole {
    OFFERER,
    ANSWERER,
    ;

    companion object {
        /** [isLocalLeader] is the value `HELLO_ACK.leader_peer_id` already establishes (PROTOCOL §4.1). */
        fun forLeadership(isLocalLeader: Boolean): VoiceRole = if (isLocalLeader) OFFERER else ANSWERER

        /**
         * The same rule from the two `peer_id`s directly, for callers that have them rather than a
         * precomputed flag. Kept next to [forLeadership] so there is one definition of the rule.
         */
        fun forPeers(
            localPeerId: PeerId,
            remotePeerId: PeerId,
        ): VoiceRole = forLeadership(localPeerId.value < remotePeerId.value)
    }
}

/**
 * The local voice session's status. These are exactly PROTOCOL §7.4's wire values minus `closed`,
 * which is a *signal* rather than a state this side rests in: teardown returns to [IDLE].
 */
enum class VoiceStatus {
    IDLE,
    NEGOTIATING,
    CONNECTING,
    ACTIVE,
    FAILED,
    ;

    val wire: VoiceWireState
        get() =
            when (this) {
                IDLE -> VoiceWireState.IDLE
                NEGOTIATING -> VoiceWireState.NEGOTIATING
                CONNECTING -> VoiceWireState.CONNECTING
                ACTIVE -> VoiceWireState.ACTIVE
                FAILED -> VoiceWireState.FAILED
            }

    val isNegotiationLive: Boolean get() = this == NEGOTIATING || this == CONNECTING || this == ACTIVE
}

/** An offer that arrived before this user had consented to voice for the ride segment (§7.3). */
data class HeldRemoteOffer(
    val voiceSessionId: VoiceSessionId,
    val sdp: String,
)

/**
 * Everything the negotiation decision depends on. Deliberately a value type with no clock, no
 * randomness, no I/O and no platform type: it is the reason the whole of PROTOCOL §7's negotiation
 * can be exhausted by a laptop unit test on both platforms rather than only observed on two phones.
 */
data class VoiceNegotiationState(
    val role: VoiceRole,
    val status: VoiceStatus = VoiceStatus.IDLE,
    val voiceSessionId: VoiceSessionId? = null,
    /**
     * Whether this user has consented to voice for **this ride segment** and the capture device and
     * audio session are consequently open.
     *
     * It survives a control-plane link loss on purpose. ARCHITECTURE §6.3/§6.4: the capture device
     * is opened once while the app is foreground-visible and stays open for the whole segment,
     * because on Android there is no second legal opportunity to open it once the screen is locked.
     * A link blip must therefore not close it — only an explicit stop, or `ENDING`, may.
     */
    val localAudioOpen: Boolean = false,
    /** True once `setRemoteDescription` has been applied — the gate for applying ICE candidates. */
    val remoteDescriptionApplied: Boolean = false,
    /** What the peer last told us via `VOICE_STATE` about wanting voice. Diagnostics + glare (§7.3). */
    val peerVoiceEnabled: Boolean = false,
    val peerReportedState: VoiceWireState = VoiceWireState.IDLE,
    val heldRemoteOffer: HeldRemoteOffer? = null,
    val micMuted: Boolean = false,
    val mode: VoiceMode = VoiceMode.CONTINUOUS,
    /**
     * **The authenticated control generation that owns the negotiation state this value holds**, or
     * null when it holds none (STATUS §4 problem 61, ADR-020 Amendment A8).
     *
     * "Negotiation state" is precisely a live [status] or a [heldRemoteOffer]; those two are mutually
     * exclusive by construction, because every branch that goes live requires [localAudioOpen] and
     * every branch that holds an offer requires it to be false, so one field names the owner of
     * whichever exists.
     *
     * It exists because [VoiceInput.ControlLinkLost] is delivered **asynchronously**, and
     * [VoiceInputMailbox]'s identity rule reaches only as far as *queued* work. Once a successor
     * lifetime's offer has been reduced, the negotiation it created is ordinary state with nothing on
     * it to say whose it is — so a predecessor's boundary, arriving later, returned it to [IDLE] and
     * wedged voice for the ride segment.
     *
     * Ownership is **established**, never inferred: it is set only by the transitions that actually
     * create negotiation state, always to the generation carried by the very input that created it.
     * That a newer generation *admitted* something transfers nothing — admission is not application,
     * and a successor's offer refused by [VoiceSignalDropReason.GENERATION_MISMATCH] leaves the
     * predecessor the owner, exactly so its own boundary can still retire it.
     *
     * A third identity, and never to be conflated with the other two: `voice_session_id` owns one
     * WebRTC negotiation, [VoiceInput.SignalReceived.controlGeneration] owns the frame that admitted
     * a signal, and this owns the control lifetime a negotiation belongs to.
     */
    val negotiationControlGeneration: Long? = null,
)

/**
 * **An action that puts a `VOICE_*` frame on the control connection, and the control lifetime that
 * authorises it to** (STATUS §4 problem 64, ADR-020 Amendment A9).
 *
 * [controlGeneration] is the [VoiceNegotiationState.negotiationControlGeneration] of the negotiation
 * this frame belongs to, captured by the very transition that produced the action. It is **not** a
 * `voice_session_id` (that owns one WebRTC negotiation) and **not** a `revision_epoch` (ADR-021 A7's
 * sender lifetime): it is the authenticated control lifetime whose connection this frame may be
 * written to, and no other.
 *
 * Why it is on the action rather than derived by the driver. `VoiceSignalTransport.send` suspends —
 * a dispatcher hop, a write lock, a socket flush — and so does every engine callback that leads to
 * one, so the instant an action is *performed* is not the instant it was *authorised*. A driver that
 * asked "which connection is authenticated now" at performance time would answer a different
 * question, which is ADR-024 Amendment A7's defect in the outbound direction: an offer authorised by
 * a lifetime that has since ended was written to its successor's socket, where the peer accepted it
 * as current. Carrying the value means the transport can *compare* rather than re-read, and rule 20's
 * distinction holds in both directions.
 *
 * Null would mean "authorised by no control lifetime". No transition produces one — every branch that
 * sends holds negotiation state, and negotiation state always names its owner, which
 * `VoiceNegotiationVectorTest` asserts over every row — and the transport therefore treats null as a
 * refusal rather than as permission to use whatever is live.
 */
interface OutboundVoiceAction {
    val controlGeneration: Long?
}

/** What the driver is asked to do. Every payload is a plain primitive (see [VoiceSignal]'s note). */
sealed class VoiceAction {
    /**
     * Open the audio session, select the communication route and open the capture device, then
     * create the peer connection with an **empty ICE server list** (PROTOCOL §7.6).
     */
    object StartLocalAudio : VoiceAction()

    data class CreateOffer(
        val voiceSessionId: VoiceSessionId,
    ) : VoiceAction()

    data class CreateAnswer(
        val voiceSessionId: VoiceSessionId,
    ) : VoiceAction()

    data class ApplyRemoteOffer(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceAction()

    data class ApplyRemoteAnswer(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceAction()

    data class SendOffer(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
        override val controlGeneration: Long?,
    ) : VoiceAction(),
        OutboundVoiceAction

    data class SendAnswer(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
        override val controlGeneration: Long?,
    ) : VoiceAction(),
        OutboundVoiceAction

    data class SendVoiceState(
        val voiceSessionId: VoiceSessionId?,
        val state: VoiceWireState,
        val micMuted: Boolean,
        val mode: VoiceMode,
        override val controlGeneration: Long?,
    ) : VoiceAction(),
        OutboundVoiceAction

    data class ApplyRemoteCandidate(
        val voiceSessionId: VoiceSessionId,
        val candidate: String,
        val sdpMid: String?,
        val sdpMlineIndex: Int,
    ) : VoiceAction()

    /** A locally gathered candidate, to be trickled to the peer as `VOICE_ICE`. */
    data class SendCandidate(
        val voiceSessionId: VoiceSessionId,
        val candidate: String,
        val sdpMid: String?,
        val sdpMlineIndex: Int,
        override val controlGeneration: Long?,
    ) : VoiceAction(),
        OutboundVoiceAction

    /** §7.4: a candidate that arrived before the remote description. Bounded by [PendingCandidates]. */
    data class QueueRemoteCandidate(
        val voiceSessionId: VoiceSessionId,
        val candidate: String,
        val sdpMid: String?,
        val sdpMlineIndex: Int,
    ) : VoiceAction()

    object DrainQueuedCandidates : VoiceAction()

    data class SetMicrophoneMuted(
        val muted: Boolean,
    ) : VoiceAction()

    /**
     * Close the peer connection, both tracks and the ICE state. Does **not** touch the capture
     * device or the audio session — see [VoiceNegotiationState.localAudioOpen].
     */
    object StopMediaTransport : VoiceAction()

    /** Stop capture and release the audio session. Only a deliberate stop or `ENDING` may do this. */
    object ReleaseLocalAudio : VoiceAction()

    /** Diagnostics only. A dropped signal is counted and named, never silently discarded. */
    data class RecordDroppedSignal(
        val reason: VoiceSignalDropReason,
    ) : VoiceAction()

    /** The peer wants voice and this user has not consented yet: the UI should offer to start. */
    object SurfacePeerVoiceRequest : VoiceAction()
}

/** Why a well-formed signal was not acted on. Distinct from a *malformed* one, which never gets here. */
enum class VoiceSignalDropReason {
    /** PROTOCOL §7.3: an offerer received `VOICE_ANSWER`, or an answerer received `VOICE_OFFER`. */
    ROLE_VIOLATION,

    /** PROTOCOL §7.2: the `voice_session_id` is not the one this side currently holds. */
    GENERATION_MISMATCH,

    /** A retransmitted offer or answer for the negotiation already in progress. */
    DUPLICATE,

    /** Well-formed and current, but not meaningful from the status this side is in. */
    UNEXPECTED_FOR_STATUS,

    /** A callback from a peer connection that has already been torn down (PROTOCOL §7.8). */
    STALE_ENGINE_CALLBACK,

    /**
     * A well-formed, already-authenticated input could not be held by [VoiceInputMailbox] — its
     * critical lane was full, or its ICE lane evicted an older candidate to make room for this one.
     * Never produced by this table: [VoiceNegotiation] never sees the input at all in this case,
     * so `VoiceController` counts it directly, one layer earlier than every other reason here.
     */
    INPUT_MAILBOX_OVERFLOW,

    /**
     * A peer signal that [VoiceInputMailbox] was still holding when the control lifetime that
     * admitted it ended (STATUS §4 problem 50). Never produced by this table, for the same reason
     * [INPUT_MAILBOX_OVERFLOW] is not: the input is discarded before `VoiceNegotiation` ever sees
     * it, so `VoiceController` counts it directly.
     *
     * Distinct from `VoiceSignalRelay.droppedRetiredGeneration`, which counts a frame that had
     * *already* lost its lifetime when it arrived (ADR-025). This one counts a frame that was
     * admitted perfectly legitimately and then outlived the link that admitted it.
     *
     * Since STATUS §4 problem 60 it is the sum of [VoiceInputMailbox]'s two counters — the signals
     * a retirement found already queued, and the ones that arrived after it. Both name the same
     * fact ("this signal's admitting control generation is retired") caught at the two different
     * instants it can be caught at, and only the mailbox needs to tell them apart.
     */
    RETIRED_CONTROL_LIFETIME,

    /**
     * A [HeldRemoteOffer] discarded because the control lifetime that delivered it is **older** than
     * the one authorising the local consent that would have answered it (STATUS §4 problem 63,
     * ADR-020 Amendment A9).
     *
     * The peer that sent that offer tore its own negotiation down when *its* copy of that link died,
     * so answering the offer would name a `voice_session_id` the peer no longer holds — and worse,
     * would move the negotiation's owner to the consenting lifetime, leaving the boundary that could
     * still have retired it inert. PROTOCOL §7.8 wants a **fresh** negotiation after a reconnect, and
     * an answerer reaches one by stating its intent again, not by answering a dead lifetime's SDP.
     */
    RETIRED_HELD_OFFER,

    /**
     * A [VoiceInput.StartRequested] whose authorising control lifetime is **older** than the lifetime
     * that owns negotiation state this side already holds (STATUS §4 problem 63, ADR-020 Amendment
     * A9) — the opposite ordering to [RETIRED_HELD_OFFER], and reachable the same way: the press was
     * queued while its lifetime was live and drained after a successor's frame had been reduced.
     *
     * The press's *consent* is still honoured — capture opens, because ARCHITECTURE §6.4 may give no
     * second foreground-visible chance — but it establishes **no** negotiation: there is no link left
     * to negotiate over, and a negotiation owned by a lifetime that has ended is exactly what
     * ADR-020 Amendment A8 exists to prevent being created.
     */
    SUPERSEDED_START_LIFETIME,

    /**
     * A [VoiceInput.ControlLinkLost] naming a control lifetime **older** than the one that owns the
     * negotiation this side is holding (STATUS §4 problem 61, ADR-020 Amendment A8).
     *
     * Not a signal, like [INPUT_MAILBOX_OVERFLOW] is not — but recorded through the same counter for
     * the same reason: a preserved successor is a fact about the ride ("a predecessor's boundary
     * arrived late and was correctly ignored"), and a silent no-op would make the one case this
     * amendment exists for the only one with no evidence that it happened.
     */
    SUPERSEDED_CONTROL_LIFETIME,
}

/** What drives the table. [VoiceInput.freshVoiceSessionId] exists because the table is pure. */
sealed class VoiceInput {
    /**
     * This user pressed Start Voice, or a control reconnect is rebuilding voice for a segment the
     * user had already consented to (PROTOCOL §7.8).
     *
     * [freshVoiceSessionId] is generated by the caller and consumed only if this input actually
     * starts a negotiation. Generating it here would make the table impure — the same reason
     * `SessionFsm` takes time as a parameter (CLAUDE.md rule 9).
     */
    data class StartRequested(
        val freshVoiceSessionId: VoiceSessionId,
        /**
         * **The authenticated control generation this press is authorised by**, supplied by the
         * caller from `ControlSessionManager` — `ControlEvent.Connected.authGeneration` for
         * PROTOCOL §7.8's reconnect rebuild, and the live generation for a user's tap (STATUS §4
         * problem 61).
         *
         * Reading the live generation for a *local* press is correct and is not ADR-025's defect:
         * there is no frame here whose provenance could be discarded, and "which lifetime is
         * authenticated right now" is exactly the question a press asks. The defect is re-reading a
         * live generation to label a frame that was already read, which this is not.
         *
         * **Null means no control lifetime is authenticated**, which a user can reach by pressing
         * Start in the gap between one link dying and the ladder restoring the next. A negotiation
         * needs a link to negotiate over and an owner to be retired by, and there is neither — so
         * this records the user's consent (opening capture, which ARCHITECTURE §6.4 requires be done
         * while foreground-visible and is the whole reason the press must not simply be refused) and
         * starts **no** negotiation. `SessionCoordinator.attachVoice` then rebuilds it under the
         * successor's generation the moment one authenticates, because it starts voice for any
         * segment whose capture is already open. The alternative — a negotiation owned by a lifetime
         * that does not exist — is the one thing no boundary could ever retire.
         */
        val controlGeneration: Long?,
    ) : VoiceInput()

    /** This user pressed End Voice, or the session is entering `ENDING` (ARCHITECTURE §3 rule 3). */
    object StopRequested : VoiceInput()

    data class MuteRequested(
        val muted: Boolean,
    ) : VoiceInput()

    /**
     * The intercom policy's `VOICE_STATE.mode` changed (PROTOCOL §7.4) — Phase 2b, where Phase 2a
     * always sent `continuous`.
     *
     * The mode is a property of *this* peer's policy, not a negotiated value: each user chooses
     * their own gate and each tells the other what theirs is, so the diagnostics screen can say
     * "your peer is on push-to-talk" rather than leaving a silent peer ambiguous. Nothing about the
     * media plane depends on the peer's mode, which is why this changes no status and touches no
     * generation.
     */
    data class ModeSelected(
        val mode: VoiceMode,
    ) : VoiceInput()

    /**
     * A `VOICE_*` frame that has already passed the trust gate (PROTOCOL §7.1) **and** the codec's
     * bounds ([com.ridelink.core.protocol.VoiceSignalCodec]).
     *
     * [freshVoiceSessionId] is supplied on every signal for the one case that needs it: an offerer
     * whose user has already consented, receiving the answerer's `negotiating` intent, begins a
     * negotiation and therefore needs an id (§7.3 glare).
     */
    data class SignalReceived(
        val signal: VoiceSignal,
        /**
         * **The control authentication generation that admitted this frame** — the one
         * `ReadFrameBinding` captured when the frame was read, carried unchanged through
         * `VoiceSignalRelay.deliver` and `VoiceSignalSink.submit` (STATUS §4 problem 60,
         * ADR-020 Amendment A7).
         *
         * Receiver-local provenance: it is **not** on the wire, it is not negotiated, and no peer
         * can influence it. This reducer reads it nowhere — it is [VoiceInputMailbox]'s, and only
         * [VoiceInputMailbox]'s, because the question it answers ("which control lifetime is this
         * semantic work's?") is a lifetime question and not a negotiation one.
         *
         * It is a different identity from [freshVoiceSessionId] and from `voice_session_id`, and
         * the three must never be conflated: `voice_session_id` owns one WebRTC negotiation, this
         * owns the authenticated control lifetime that let the frame in.
         */
        val controlGeneration: Long,
        val freshVoiceSessionId: VoiceSessionId,
    ) : VoiceInput()

    data class LocalOfferCreated(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceInput()

    data class LocalAnswerCreated(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceInput()

    /**
     * The media stack gathered a local ICE candidate. It goes through the table rather than
     * straight to the wire for two reasons: the generation guard applies to it exactly as to an
     * inbound frame (a candidate gathered by a peer connection we have since closed must not be
     * sent), and routing it through the single input queue is what stops it overtaking the
     * `VOICE_OFFER` it has to follow.
     */
    data class LocalCandidateGathered(
        val voiceSessionId: VoiceSessionId,
        val candidate: String,
        val sdpMid: String?,
        val sdpMlineIndex: Int,
    ) : VoiceInput()

    /** The remote audio track appeared or went. Diagnostics only, but still generation-guarded. */
    data class RemoteTrackChanged(
        val voiceSessionId: VoiceSessionId,
        val present: Boolean,
    ) : VoiceInput()

    /** The media stack's own state changed. Carries its `voice_session_id` so a stale one is inert. */
    data class MediaConnectivityChanged(
        val voiceSessionId: VoiceSessionId,
        val connected: Boolean,
        val failed: Boolean,
    ) : VoiceInput()

    /**
     * The control plane was lost. §7.8: media goes, local capture stays, and voice does not retry.
     *
     * [retiredControlGeneration] is **the authentication generation that ended**, taken from the
     * `AuthenticatedConnection` record the dying connection owned and captured before that record
     * was cleared (STATUS §4 problem 60, ADR-020 Amendment A7). It is what makes this a statement
     * about *one identified lifetime* rather than about whatever happens to be queued when it is
     * applied — see [VoiceInputMailbox.offer].
     *
     * Null means **no control lifetime ended**, and there are exactly two such producers:
     *
     * - a connection that died before it was ever authenticated, so nothing voice-related was ever
     *   admitted under it and there is nothing to retire;
     * - the mailbox-overflow degrade, which is a *local* fact about this device's own bounded queue
     *   and not a lifetime boundary at all (CLAUDE.md rule 22's parenthetical, now with the
     *   identity it was missing).
     *
     * Either way the reducer's response is identical — it never reads this field — and the
     * difference is entirely in what the mailbox is thereby entitled to discard.
     */
    data class ControlLinkLost(
        val retiredControlGeneration: Long?,
    ) : VoiceInput()

    /**
     * An outbound frame this negotiation **depended on** could not be put on the wire
     * (STATUS §4 problems 56, 57 and 59).
     *
     * **This is not [ControlLinkLost], and conflating the two was a defect.** They ask the table for
     * the same thing — drop the media transport, keep this user's capture device (ARCHITECTURE
     * §6.3/§6.4), let PROTOCOL §10's ladder own the link — but they are different *events*:
     *
     * - [ControlLinkLost] is a **control-lifetime boundary**. The lifetime that admitted every
     *   `SignalReceived` still queued has ended, which is why [VoiceInputMailbox] gives it ownership
     *   of that queued remote work (problem 50).
     * - This is a **local, in-lifetime** fact about one frame. `VoiceSignalTransport.send` suspends —
     *   `withContext(ioDispatcher)`, a write lock, a socket flush — so its `Boolean` can arrive long
     *   after the lifetime that authorised it has been replaced. Letting it speak for a lifetime
     *   boundary let a retired send discard a **successor's** freshly admitted offer, and let it
     *   displace a pending `StopRequested` in the one-slot teardown lane (problems 57 and 59).
     *
     * [voiceSessionId] is the generation the failed frame belonged to — null for an answerer's
     * intent-to-talk, which names none (§7.3) — and the reducer refuses to act on any other, so this
     * input can only ever retire the negotiation it was actually authorised by.
     */
    data class NegotiationSendFailed(
        val voiceSessionId: VoiceSessionId?,
    ) : VoiceInput()
}

data class VoiceOutcome(
    val state: VoiceNegotiationState,
    val actions: List<VoiceAction>,
)

/**
 * The complete PROTOCOL §7 negotiation table, as a pure `(state, input) -> (state, actions)`
 * reducer.
 *
 * It is a separate object for the same reason `SessionGate` is (ADR-019): the properties that
 * matter here — a deterministic offerer, exactly one negotiation per generation, a stale callback
 * that cannot touch the next session, a candidate that arrives early being queued rather than lost
 * — are properties of *this table*, and a table is exhaustible by a laptop unit test on both
 * platforms. `RideLinkCore.VoiceNegotiation` is the mirror; the two must agree case for case, and
 * `protocol/vectors/voice-fsm/` is what makes a disagreement fail a build instead of a ride.
 *
 * It owns no session state, holds no trust, reads no clock, opens no socket and knows nothing about
 * WebRTC. `VoiceController` drives it and performs the effects.
 */
object VoiceNegotiation {
    fun reduce(
        state: VoiceNegotiationState,
        input: VoiceInput,
    ): VoiceOutcome =
        when (input) {
            is VoiceInput.StartRequested -> start(state, input.freshVoiceSessionId, input.controlGeneration)
            VoiceInput.StopRequested -> stop(state)
            is VoiceInput.MuteRequested -> mute(state, input.muted)
            is VoiceInput.ModeSelected -> modeSelected(state, input.mode)
            // `controlGeneration` decides **ownership** and nothing else: which control lifetime a
            // negotiation this signal *establishes* belongs to (STATUS §4 problem 61). It still
            // decides no negotiation — `VoiceInputMailbox` remains the only place it gates admission.
            is VoiceInput.SignalReceived ->
                signal(state, input.signal, input.freshVoiceSessionId, input.controlGeneration)
            is VoiceInput.LocalOfferCreated -> localOfferCreated(state, input.voiceSessionId, input.sdp)
            is VoiceInput.LocalAnswerCreated -> localAnswerCreated(state, input.voiceSessionId, input.sdp)
            is VoiceInput.LocalCandidateGathered -> localCandidateGathered(state, input)
            is VoiceInput.RemoteTrackChanged -> remoteTrackChanged(state, input)
            is VoiceInput.MediaConnectivityChanged -> connectivity(state, input)
            is VoiceInput.ControlLinkLost -> controlLinkLost(state, input.retiredControlGeneration)
            is VoiceInput.NegotiationSendFailed -> negotiationSendFailed(state, input.voiceSessionId)
        }

    // --- local user actions -------------------------------------------------------------------

    @Suppress("ReturnCount", "LongMethod") // one early-out per guard, in the order they have to be asked
    private fun start(
        state: VoiceNegotiationState,
        fresh: VoiceSessionId,
        owner: Long?,
    ): VoiceOutcome {
        // Idempotent: pressing Start Voice twice, or a reconnect rebuild racing a manual start,
        // must not produce a second negotiation. The owner is deliberately **not** refreshed here:
        // the negotiation that is already live was established by whichever lifetime established it,
        // and a later press observing a newer one does not move it (STATUS §4 problem 61).
        if (state.status.isNegotiationLive) return VoiceOutcome(state, emptyList())

        val actions = mutableListOf<VoiceAction>()
        if (!state.localAudioOpen) actions += VoiceAction.StartLocalAudio

        // No authenticated control lifetime: consent, and only consent. See
        // [VoiceInput.StartRequested.controlGeneration] for why this is not a refusal and not a
        // negotiation owned by nobody.
        if (owner == null) return VoiceOutcome(state.copy(localAudioOpen = true), actions)

        // **A press authorised by a lifetime older than the one that owns state we are already
        // holding is a press from a lifetime that has ended** (STATUS §4 problem 63). Generations
        // strictly increase and one connection is authenticated at a time, so the existence of
        // newer-owned state proves this press's link is gone. Consent is still honoured — capture is
        // the one thing ARCHITECTURE §6.4 may never give a second chance to open — but there is
        // nothing to negotiate over, and a negotiation owned by a dead lifetime is what Amendment A8
        // exists to prevent. The held offer is left exactly where it is: it belongs to the newer
        // lifetime, and a press from an older one has no standing to consume it.
        val existing = state.negotiationControlGeneration
        if (existing != null && existing > owner) {
            actions += VoiceAction.RecordDroppedSignal(VoiceSignalDropReason.SUPERSEDED_START_LIFETIME)
            return VoiceOutcome(state.copy(localAudioOpen = true), actions)
        }

        return when (state.role) {
            VoiceRole.OFFERER -> {
                actions += VoiceAction.SendVoiceState(fresh, VoiceWireState.NEGOTIATING, state.micMuted, state.mode, owner)
                actions += VoiceAction.CreateOffer(fresh)
                VoiceOutcome(
                    state.copy(
                        status = VoiceStatus.NEGOTIATING,
                        voiceSessionId = fresh,
                        localAudioOpen = true,
                        remoteDescriptionApplied = false,
                        heldRemoteOffer = null,
                        negotiationControlGeneration = owner,
                    ),
                    actions,
                )
            }
            VoiceRole.ANSWERER -> {
                // **A held offer may be answered only by the lifetime that delivered it** (STATUS §4
                // problem 63, ADR-020 Amendment A9). `existing` names that lifetime, and `existing >
                // owner` was already refused above, so what is left here is `existing == owner` —
                // answer it — or `existing < owner`, where the offerer's own link died with the
                // lifetime that carried it and the offerer has therefore already torn its side down.
                // Answering then would name a `voice_session_id` the peer no longer holds *and* move
                // the owner to the consenting lifetime, so the predecessor's boundary could never
                // retire it. §7.8 wants a fresh negotiation; an answerer reaches one by stating its
                // intent again, which is exactly the no-held-offer branch below.
                val held = state.heldRemoteOffer?.takeIf { existing == null || existing == owner }
                if (state.heldRemoteOffer != null && held == null) {
                    actions += VoiceAction.RecordDroppedSignal(VoiceSignalDropReason.RETIRED_HELD_OFFER)
                }
                if (held != null) {
                    // The offerer got there first and we held its offer for want of local consent
                    // (§7.3). Consent has now arrived, so answer the offer we already have rather
                    // than asking the offerer to send it again.
                    actions += VoiceAction.ApplyRemoteOffer(held.voiceSessionId, held.sdp)
                    actions += VoiceAction.DrainQueuedCandidates
                    actions += VoiceAction.CreateAnswer(held.voiceSessionId)
                    VoiceOutcome(
                        state.copy(
                            status = VoiceStatus.NEGOTIATING,
                            voiceSessionId = held.voiceSessionId,
                            localAudioOpen = true,
                            remoteDescriptionApplied = true,
                            heldRemoteOffer = null,
                            // The press's lifetime, which the guard above has just proved is also
                            // the held offer's: the answer goes out on that link and it is that
                            // link's loss which must be able to retire it.
                            negotiationControlGeneration = owner,
                        ),
                        actions,
                    )
                } else {
                    // An answerer never offers. It states its intent and waits (§7.3). The id is
                    // null because the offerer, not this side, creates one.
                    actions += VoiceAction.SendVoiceState(null, VoiceWireState.NEGOTIATING, state.micMuted, state.mode, owner)
                    VoiceOutcome(
                        state.copy(
                            status = VoiceStatus.NEGOTIATING,
                            voiceSessionId = null,
                            localAudioOpen = true,
                            remoteDescriptionApplied = false,
                            heldRemoteOffer = null,
                            negotiationControlGeneration = owner,
                        ),
                        actions,
                    )
                }
            }
        }
    }

    private fun stop(state: VoiceNegotiationState): VoiceOutcome {
        if (state.status == VoiceStatus.IDLE && !state.localAudioOpen && state.heldRemoteOffer == null) {
            return VoiceOutcome(state, emptyList())
        }
        val actions = mutableListOf<VoiceAction>()
        // Tell the peer before closing, and only if there is a negotiation to name. `closed` is the
        // teardown signal; PROTOCOL §7.4 deliberately has no separate VOICE_END.
        state.voiceSessionId?.let {
            // The lifetime that owns the negotiation being closed, read before the reset below — a
            // `closed` naming this generation belongs to this generation's link and no other.
            actions +=
                VoiceAction.SendVoiceState(
                    it,
                    VoiceWireState.CLOSED,
                    state.micMuted,
                    state.mode,
                    state.negotiationControlGeneration,
                )
        }
        actions += VoiceAction.StopMediaTransport
        // A deliberate stop is the case that *may* release capture: the user is present, so a later
        // restart can legally reopen it (ARCHITECTURE §6.4).
        if (state.localAudioOpen) actions += VoiceAction.ReleaseLocalAudio
        return VoiceOutcome(
            VoiceNegotiationState(role = state.role, micMuted = state.micMuted, mode = state.mode),
            actions,
        )
    }

    private fun mute(
        state: VoiceNegotiationState,
        muted: Boolean,
    ): VoiceOutcome {
        if (state.micMuted == muted) return VoiceOutcome(state, emptyList())
        val actions = mutableListOf<VoiceAction>()
        if (state.localAudioOpen) actions += VoiceAction.SetMicrophoneMuted(muted)
        state.voiceSessionId?.let {
            actions += VoiceAction.SendVoiceState(it, state.status.wire, muted, state.mode, state.negotiationControlGeneration)
        }
        return VoiceOutcome(state.copy(micMuted = muted), actions)
    }

    /**
     * PROTOCOL §7.4's `mode`, changed by the local intercom policy. Idempotent, and it announces
     * itself only when there is a generation to name: with no live negotiation there is nothing to
     * report the mode *of*, and the next `VOICE_STATE` this side sends will carry the new value
     * anyway.
     *
     * The status is deliberately unchanged and re-sent as-is: switching from PTT to continuous is
     * not a state transition of the voice session, and treating it as one would put a spurious
     * `negotiating` on the wire.
     */
    private fun modeSelected(
        state: VoiceNegotiationState,
        mode: VoiceMode,
    ): VoiceOutcome {
        if (state.mode == mode) return VoiceOutcome(state, emptyList())
        val actions =
            state.voiceSessionId?.let {
                listOf(
                    VoiceAction.SendVoiceState(
                        it,
                        state.status.wire,
                        state.micMuted,
                        mode,
                        state.negotiationControlGeneration,
                    ),
                )
            } ?: emptyList()
        return VoiceOutcome(state.copy(mode = mode), actions)
    }

    // --- control-plane lifecycle --------------------------------------------------------------

    /**
     * PROTOCOL §7.8, scoped to the lifetime that actually ended (STATUS §4 problem 61, ADR-020
     * Amendment A8).
     *
     * > A control-lifetime boundary may retire only negotiation state **owned by that lifetime**. It
     * > may never retire state that has already transferred to a successor.
     *
     * The whole rule is one comparison, and it is deliberately expressed as "is the lifetime that
     * ended **older** than the owner" rather than "is it a different one":
     *
     * - `owner > retired` — a *predecessor's* boundary, delivered after the successor's work was
     *   already reduced. This is problem 61 itself. Preserved, and recorded rather than silent.
     * - `owner == retired` — the ordinary case, and PROTOCOL §7.8 unchanged. Torn down.
     * - `owner < retired` — a *newer* lifetime ended while an older one still owns the negotiation.
     *   The owner's lifetime must therefore already be over:
     *   `ControlSessionManager` holds one authenticated connection at a time and allocates a strictly
     *   greater generation for each, so the existence of a newer lifetime **proves** the older one
     *   ended (the same fact `VoiceInputMailbox.newestAdmittedControlGeneration` rests on). Torn
     *   down — which is what stops a lost or never-emitted predecessor boundary stranding a dead
     *   negotiation forever, the exact wedge that made the naïve "suppress a superseded boundary"
     *   fix strictly worse than the defect.
     * - `owner == null` — there is negotiation state but nothing owns it. Unreachable by
     *   construction (every establishing transition sets an owner, and `start` with no lifetime
     *   establishes nothing), and torn down rather than trusted: an un-retirable negotiation is the
     *   one outcome with no way out of it.
     * - `retired == null` — **no lifetime ended at all.** Its two producers are a connection that
     *   died before it authenticated and the mailbox-overflow degrade, and the second is why this
     *   must tear down unconditionally: the degrade is a local safety valve that has to work
     *   whoever owns what.
     *
     * Note what is *not* consulted: nothing live, nothing about arrival order, and nothing about
     * what the mailbox has admitted. Admission is not application — a successor's offer refused by
     * `offerReceived`'s `GENERATION_MISMATCH` leaves the predecessor the owner, and the predecessor's
     * own boundary then correctly retires it.
     */
    @Suppress("ReturnCount") // nothing-to-retire, not-ours, retire -- in that order and no other
    private fun controlLinkLost(
        state: VoiceNegotiationState,
        retired: Long?,
    ): VoiceOutcome {
        if (state.status == VoiceStatus.IDLE && state.voiceSessionId == null && state.heldRemoteOffer == null) {
            return VoiceOutcome(state, emptyList())
        }
        val owner = state.negotiationControlGeneration
        if (owner != null && retired != null && owner > retired) {
            return dropped(state, VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME)
        }
        // Media goes; the capture device does not (ARCHITECTURE §6.3/§6.4 — see localAudioOpen).
        // No VOICE_STATE is sent: there is no link to send it on. And nothing is retried here —
        // PROTOCOL §10's control ladder is the only reconnect loop in the app (§7.8).
        return VoiceOutcome(
            VoiceNegotiationState(
                role = state.role,
                localAudioOpen = state.localAudioOpen,
                micMuted = state.micMuted,
                mode = state.mode,
            ),
            listOf(VoiceAction.StopMediaTransport),
        )
    }

    /**
     * [VoiceInput.NegotiationSendFailed]: the same degrade [controlLinkLost] performs, scoped to the
     * one negotiation whose frame was lost.
     *
     * The generation guard is what makes this input safe to apply late. It is the same guard every
     * engine callback carries ([localOfferCreated], [connectivity]) and it answers the same question:
     * does the thing that produced this input still own the negotiation the table is holding? A send
     * authorised by a retired generation names an id the table has already moved past — or the table
     * has been reset to `IDLE` and holds none — and in both cases this is a no-op rather than a
     * teardown of whatever came next.
     *
     * `null == null` is a deliberate match, not an accident: an answerer's intent-to-talk names no
     * generation because the offerer has not created one yet (§7.3), so "the negotiation this side is
     * holding also names none" is exactly the right identity for it. [VoiceStatus.isNegotiationLive]
     * is what stops that matching an idle table.
     */
    @Suppress("ReturnCount") // one early-out per guard, in the order they have to be asked
    private fun negotiationSendFailed(
        state: VoiceNegotiationState,
        voiceSessionId: VoiceSessionId?,
    ): VoiceOutcome {
        if (!state.status.isNegotiationLive) return dropped(state, VoiceSignalDropReason.UNEXPECTED_FOR_STATUS)
        if (state.voiceSessionId != voiceSessionId) return dropped(state, VoiceSignalDropReason.GENERATION_MISMATCH)
        return VoiceOutcome(
            VoiceNegotiationState(
                role = state.role,
                localAudioOpen = state.localAudioOpen,
                micMuted = state.micMuted,
                mode = state.mode,
            ),
            listOf(VoiceAction.StopMediaTransport),
        )
    }

    // --- engine callbacks ---------------------------------------------------------------------

    private fun localOfferCreated(
        state: VoiceNegotiationState,
        id: VoiceSessionId,
        sdp: String,
    ): VoiceOutcome =
        when {
            state.voiceSessionId != id -> dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
            state.status != VoiceStatus.NEGOTIATING -> dropped(state, VoiceSignalDropReason.UNEXPECTED_FOR_STATUS)
            else -> VoiceOutcome(state, listOf(VoiceAction.SendOffer(id, sdp, state.negotiationControlGeneration)))
        }

    private fun localAnswerCreated(
        state: VoiceNegotiationState,
        id: VoiceSessionId,
        sdp: String,
    ): VoiceOutcome =
        when {
            state.voiceSessionId != id -> dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
            state.status != VoiceStatus.NEGOTIATING -> dropped(state, VoiceSignalDropReason.UNEXPECTED_FOR_STATUS)
            else ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.CONNECTING),
                    listOf(
                        VoiceAction.SendAnswer(id, sdp, state.negotiationControlGeneration),
                        VoiceAction.SendVoiceState(
                            id,
                            VoiceWireState.CONNECTING,
                            state.micMuted,
                            state.mode,
                            state.negotiationControlGeneration,
                        ),
                    ),
                )
        }

    private fun localCandidateGathered(
        state: VoiceNegotiationState,
        input: VoiceInput.LocalCandidateGathered,
    ): VoiceOutcome =
        if (state.voiceSessionId != input.voiceSessionId) {
            dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
        } else {
            VoiceOutcome(
                state,
                listOf(
                    VoiceAction.SendCandidate(
                        input.voiceSessionId,
                        input.candidate,
                        input.sdpMid,
                        input.sdpMlineIndex,
                        state.negotiationControlGeneration,
                    ),
                ),
            )
        }

    private fun remoteTrackChanged(
        state: VoiceNegotiationState,
        input: VoiceInput.RemoteTrackChanged,
    ): VoiceOutcome =
        if (state.voiceSessionId != input.voiceSessionId) {
            dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
        } else {
            VoiceOutcome(state, emptyList())
        }

    private fun connectivity(
        state: VoiceNegotiationState,
        input: VoiceInput.MediaConnectivityChanged,
    ): VoiceOutcome {
        // The generation guard applied to the media stack's own callbacks, not just to the wire
        // (§7.8): a delegate call from a peer connection we already closed carries the old id.
        if (state.voiceSessionId != input.voiceSessionId) {
            return dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
        }
        val id = input.voiceSessionId
        return when {
            input.failed ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.FAILED, remoteDescriptionApplied = false),
                    listOf(
                        VoiceAction.StopMediaTransport,
                        VoiceAction.SendVoiceState(
                            id,
                            VoiceWireState.FAILED,
                            state.micMuted,
                            state.mode,
                            state.negotiationControlGeneration,
                        ),
                    ),
                )
            input.connected && state.status != VoiceStatus.ACTIVE ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.ACTIVE),
                    listOf(
                        VoiceAction.SendVoiceState(
                            id,
                            VoiceWireState.ACTIVE,
                            state.micMuted,
                            state.mode,
                            state.negotiationControlGeneration,
                        ),
                    ),
                )
            !input.connected && state.status == VoiceStatus.ACTIVE ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.CONNECTING),
                    listOf(
                        VoiceAction.SendVoiceState(
                            id,
                            VoiceWireState.CONNECTING,
                            state.micMuted,
                            state.mode,
                            state.negotiationControlGeneration,
                        ),
                    ),
                )
            else -> VoiceOutcome(state, emptyList())
        }
    }
}

// The inbound-signal half of the table lives at file level rather than inside [VoiceNegotiation].
// Same reasoning as `ControlSessionManager`'s wire-field readers: none of these touch the object's
// state — each is a pure function of one `VoiceNegotiationState` and one signal — and keeping them
// out leaves the object small enough to read top to bottom. They are `private`, so the table is
// still the only way to reach them.

// --- inbound signals ----------------------------------------------------------------------

private fun signal(
    state: VoiceNegotiationState,
    signal: VoiceSignal,
    fresh: VoiceSessionId,
    owner: Long,
): VoiceOutcome =
    when (signal) {
        // Only the two branches that can *establish* negotiation state are given the owner.
        // `answerReceived` and `candidateReceived` advance a negotiation that already exists and
        // therefore already has one, and moving it because a successor's link carried a later frame
        // would be inferring ownership rather than establishing it (STATUS §4 problem 61).
        is VoiceSignal.Offer -> offerReceived(state, signal, owner)
        is VoiceSignal.Answer -> answerReceived(state, signal)
        is VoiceSignal.IceCandidate -> candidateReceived(state, signal)
        is VoiceSignal.State -> peerStateReceived(state, signal, fresh, owner)
    }

@Suppress("ReturnCount") // one early-out per PROTOCOL §7.4 receiver rule, in spec order
private fun offerReceived(
    state: VoiceNegotiationState,
    offer: VoiceSignal.Offer,
    owner: Long,
): VoiceOutcome {
    // §7.3: only the answerer may receive an offer. An offerer receiving one has met a peer
    // that disagrees about leadership — the same condition §4.1 calls leader_mismatch.
    if (state.role != VoiceRole.ANSWERER) return dropped(state, VoiceSignalDropReason.ROLE_VIOLATION)

    val id = offer.voiceSessionId
    if (state.voiceSessionId == id && state.remoteDescriptionApplied) {
        return dropped(state, VoiceSignalDropReason.DUPLICATE)
    }
    // §7.2: a live negotiation is not displaced by an offer from a different generation.
    if (state.voiceSessionId != null && state.voiceSessionId != id && state.status.isNegotiationLive) {
        return dropped(state, VoiceSignalDropReason.GENERATION_MISMATCH)
    }

    val withPeer = state.copy(peerVoiceEnabled = true, peerReportedState = VoiceWireState.NEGOTIATING)

    // The microphone is never opened because a *peer* asked. ARCHITECTURE §6.4 makes that
    // illegal on Android from the background, and it would be wrong on iOS too. The offer is
    // held and the UI offers to start; consent then answers it from `start()`.
    if (!state.localAudioOpen) {
        return VoiceOutcome(
            withPeer.copy(
                heldRemoteOffer = HeldRemoteOffer(id, offer.sdp),
                // A held offer is negotiation state too — it is what a later consent answers — so it
                // is owned by the lifetime that delivered it and retired with that lifetime.
                negotiationControlGeneration = owner,
            ),
            listOf(VoiceAction.SurfacePeerVoiceRequest),
        )
    }

    return VoiceOutcome(
        withPeer.copy(
            status = VoiceStatus.NEGOTIATING,
            voiceSessionId = id,
            remoteDescriptionApplied = true,
            heldRemoteOffer = null,
            negotiationControlGeneration = owner,
        ),
        listOf(
            VoiceAction.ApplyRemoteOffer(id, offer.sdp),
            VoiceAction.DrainQueuedCandidates,
            VoiceAction.CreateAnswer(id),
        ),
    )
}

@Suppress("ReturnCount")
private fun answerReceived(
    state: VoiceNegotiationState,
    answer: VoiceSignal.Answer,
): VoiceOutcome {
    if (state.role != VoiceRole.OFFERER) return dropped(state, VoiceSignalDropReason.ROLE_VIOLATION)
    if (state.voiceSessionId != answer.voiceSessionId) {
        return dropped(state, VoiceSignalDropReason.GENERATION_MISMATCH)
    }
    if (state.remoteDescriptionApplied) return dropped(state, VoiceSignalDropReason.DUPLICATE)
    if (state.status != VoiceStatus.NEGOTIATING) return dropped(state, VoiceSignalDropReason.UNEXPECTED_FOR_STATUS)

    val id = answer.voiceSessionId
    return VoiceOutcome(
        state.copy(
            status = VoiceStatus.CONNECTING,
            remoteDescriptionApplied = true,
            peerVoiceEnabled = true,
        ),
        listOf(
            VoiceAction.ApplyRemoteAnswer(id, answer.sdp),
            VoiceAction.DrainQueuedCandidates,
            VoiceAction.SendVoiceState(
                id,
                VoiceWireState.CONNECTING,
                state.micMuted,
                state.mode,
                state.negotiationControlGeneration,
            ),
        ),
    )
}

private fun candidateReceived(
    state: VoiceNegotiationState,
    ice: VoiceSignal.IceCandidate,
): VoiceOutcome {
    // §7.2/§7.4: including the case that matters most — a candidate arriving after teardown,
    // when voiceSessionId is null, cannot resurrect anything.
    if (state.voiceSessionId != ice.voiceSessionId) {
        return dropped(state, VoiceSignalDropReason.GENERATION_MISMATCH)
    }
    val action =
        if (state.remoteDescriptionApplied) {
            VoiceAction.ApplyRemoteCandidate(ice.voiceSessionId, ice.candidate, ice.sdpMid, ice.sdpMlineIndex)
        } else {
            // Trickle ICE: early candidates are queued, not dropped, up to
            // MAX_QUEUED_VOICE_CANDIDATES. Dropping them would make a slow SDP round trip look
            // like a connectivity failure.
            VoiceAction.QueueRemoteCandidate(ice.voiceSessionId, ice.candidate, ice.sdpMid, ice.sdpMlineIndex)
        }
    return VoiceOutcome(state, listOf(action))
}

@Suppress("ReturnCount")
private fun peerStateReceived(
    state: VoiceNegotiationState,
    peer: VoiceSignal.State,
    fresh: VoiceSessionId,
    owner: Long,
): VoiceOutcome {
    // A peer state naming a generation that is not ours is not about our session. `null` is
    // legal and carries no generation claim, so it is never a mismatch (§7.4).
    if (peer.voiceSessionId != null && state.voiceSessionId != null && peer.voiceSessionId != state.voiceSessionId) {
        return dropped(state, VoiceSignalDropReason.GENERATION_MISMATCH)
    }
    val observed = state.copy(peerReportedState = peer.state)
    return when (peer.state) {
        VoiceWireState.CLOSED -> teardownFromPeer(observed, VoiceStatus.IDLE)
        VoiceWireState.FAILED -> teardownFromPeer(observed, VoiceStatus.FAILED)
        VoiceWireState.NEGOTIATING -> peerWantsVoice(observed, fresh, owner)
        VoiceWireState.IDLE -> VoiceOutcome(observed.copy(peerVoiceEnabled = false), emptyList())
        // Informational. §7.4 requires an unrecognised value to be tolerated as `unknown`
        // rather than treated as malformed, so it lands here alongside the known ones.
        VoiceWireState.CONNECTING, VoiceWireState.ACTIVE, VoiceWireState.UNKNOWN ->
            VoiceOutcome(observed.copy(peerVoiceEnabled = true), emptyList())
    }
}

/**
 * The peer ended or failed its side. Media goes and the local capture device stays, exactly as
 * for a link loss: the peer may come back within this ride segment, and this user's consent
 * ([VoiceNegotiationState.localAudioOpen]) has not been withdrawn.
 */
private fun teardownFromPeer(
    state: VoiceNegotiationState,
    newStatus: VoiceStatus,
): VoiceOutcome {
    if (state.status == VoiceStatus.IDLE && state.voiceSessionId == null && state.heldRemoteOffer == null) {
        return VoiceOutcome(state.copy(peerVoiceEnabled = false), emptyList())
    }
    return VoiceOutcome(
        VoiceNegotiationState(
            role = state.role,
            status = newStatus,
            localAudioOpen = state.localAudioOpen,
            peerReportedState = state.peerReportedState,
            micMuted = state.micMuted,
            mode = state.mode,
        ),
        listOf(VoiceAction.StopMediaTransport),
    )
}

/**
 * §7.3 glare. The answerer's `negotiating` is an intent, not an offer. If this side is the
 * offerer and its own user has already consented, the intent begins the negotiation; otherwise
 * it is recorded and surfaced, and this user's own Start Voice is what proceeds.
 *
 * Receiving it while a negotiation is already live is **idempotent** — which is precisely what
 * makes two simultaneous presses produce one offer rather than two.
 */
@Suppress("ReturnCount") // one early-out per §7.3 glare rule, in spec order
private fun peerWantsVoice(
    state: VoiceNegotiationState,
    fresh: VoiceSessionId,
    owner: Long,
): VoiceOutcome {
    val withPeer = state.copy(peerVoiceEnabled = true)
    if (state.status.isNegotiationLive) return VoiceOutcome(withPeer, emptyList())
    if (state.role != VoiceRole.OFFERER) return VoiceOutcome(withPeer, emptyList())
    if (!state.localAudioOpen) {
        return VoiceOutcome(withPeer, listOf(VoiceAction.SurfacePeerVoiceRequest))
    }
    return VoiceOutcome(
        withPeer.copy(
            status = VoiceStatus.NEGOTIATING,
            voiceSessionId = fresh,
            remoteDescriptionApplied = false,
            heldRemoteOffer = null,
            negotiationControlGeneration = owner,
        ),
        listOf(
            VoiceAction.SendVoiceState(fresh, VoiceWireState.NEGOTIATING, state.micMuted, state.mode, owner),
            VoiceAction.CreateOffer(fresh),
        ),
    )
}

private fun dropped(
    state: VoiceNegotiationState,
    reason: VoiceSignalDropReason,
): VoiceOutcome = VoiceOutcome(state, listOf(VoiceAction.RecordDroppedSignal(reason)))
