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
)

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
    ) : VoiceAction()

    data class SendAnswer(
        val voiceSessionId: VoiceSessionId,
        val sdp: String,
    ) : VoiceAction()

    data class SendVoiceState(
        val voiceSessionId: VoiceSessionId?,
        val state: VoiceWireState,
        val micMuted: Boolean,
        val mode: VoiceMode,
    ) : VoiceAction()

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
    ) : VoiceAction()

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
    ) : VoiceInput()

    /** This user pressed End Voice, or the session is entering `ENDING` (ARCHITECTURE §3 rule 3). */
    object StopRequested : VoiceInput()

    data class MuteRequested(
        val muted: Boolean,
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

    /** The control plane was lost. §7.8: media goes, local capture stays, and voice does not retry. */
    object ControlLinkLost : VoiceInput()
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
            is VoiceInput.StartRequested -> start(state, input.freshVoiceSessionId)
            VoiceInput.StopRequested -> stop(state)
            is VoiceInput.MuteRequested -> mute(state, input.muted)
            is VoiceInput.SignalReceived -> signal(state, input.signal, input.freshVoiceSessionId)
            is VoiceInput.LocalOfferCreated -> localOfferCreated(state, input.voiceSessionId, input.sdp)
            is VoiceInput.LocalAnswerCreated -> localAnswerCreated(state, input.voiceSessionId, input.sdp)
            is VoiceInput.LocalCandidateGathered -> localCandidateGathered(state, input)
            is VoiceInput.RemoteTrackChanged -> remoteTrackChanged(state, input)
            is VoiceInput.MediaConnectivityChanged -> connectivity(state, input)
            VoiceInput.ControlLinkLost -> controlLinkLost(state)
        }

    // --- local user actions -------------------------------------------------------------------

    private fun start(
        state: VoiceNegotiationState,
        fresh: VoiceSessionId,
    ): VoiceOutcome {
        // Idempotent: pressing Start Voice twice, or a reconnect rebuild racing a manual start,
        // must not produce a second negotiation.
        if (state.status.isNegotiationLive) return VoiceOutcome(state, emptyList())

        val actions = mutableListOf<VoiceAction>()
        if (!state.localAudioOpen) actions += VoiceAction.StartLocalAudio

        return when (state.role) {
            VoiceRole.OFFERER -> {
                actions += VoiceAction.SendVoiceState(fresh, VoiceWireState.NEGOTIATING, state.micMuted, state.mode)
                actions += VoiceAction.CreateOffer(fresh)
                VoiceOutcome(
                    state.copy(
                        status = VoiceStatus.NEGOTIATING,
                        voiceSessionId = fresh,
                        localAudioOpen = true,
                        remoteDescriptionApplied = false,
                        heldRemoteOffer = null,
                    ),
                    actions,
                )
            }
            VoiceRole.ANSWERER -> {
                val held = state.heldRemoteOffer
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
                        ),
                        actions,
                    )
                } else {
                    // An answerer never offers. It states its intent and waits (§7.3). The id is
                    // null because the offerer, not this side, creates one.
                    actions += VoiceAction.SendVoiceState(null, VoiceWireState.NEGOTIATING, state.micMuted, state.mode)
                    VoiceOutcome(
                        state.copy(
                            status = VoiceStatus.NEGOTIATING,
                            voiceSessionId = null,
                            localAudioOpen = true,
                            remoteDescriptionApplied = false,
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
            actions += VoiceAction.SendVoiceState(it, VoiceWireState.CLOSED, state.micMuted, state.mode)
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
            actions += VoiceAction.SendVoiceState(it, state.status.wire, muted, state.mode)
        }
        return VoiceOutcome(state.copy(micMuted = muted), actions)
    }

    // --- control-plane lifecycle --------------------------------------------------------------

    private fun controlLinkLost(state: VoiceNegotiationState): VoiceOutcome {
        if (state.status == VoiceStatus.IDLE && state.voiceSessionId == null && state.heldRemoteOffer == null) {
            return VoiceOutcome(state, emptyList())
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

    // --- engine callbacks ---------------------------------------------------------------------

    private fun localOfferCreated(
        state: VoiceNegotiationState,
        id: VoiceSessionId,
        sdp: String,
    ): VoiceOutcome =
        when {
            state.voiceSessionId != id -> dropped(state, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)
            state.status != VoiceStatus.NEGOTIATING -> dropped(state, VoiceSignalDropReason.UNEXPECTED_FOR_STATUS)
            else -> VoiceOutcome(state, listOf(VoiceAction.SendOffer(id, sdp)))
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
                        VoiceAction.SendAnswer(id, sdp),
                        VoiceAction.SendVoiceState(id, VoiceWireState.CONNECTING, state.micMuted, state.mode),
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
                        VoiceAction.SendVoiceState(id, VoiceWireState.FAILED, state.micMuted, state.mode),
                    ),
                )
            input.connected && state.status != VoiceStatus.ACTIVE ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.ACTIVE),
                    listOf(VoiceAction.SendVoiceState(id, VoiceWireState.ACTIVE, state.micMuted, state.mode)),
                )
            !input.connected && state.status == VoiceStatus.ACTIVE ->
                VoiceOutcome(
                    state.copy(status = VoiceStatus.CONNECTING),
                    listOf(VoiceAction.SendVoiceState(id, VoiceWireState.CONNECTING, state.micMuted, state.mode)),
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
): VoiceOutcome =
    when (signal) {
        is VoiceSignal.Offer -> offerReceived(state, signal)
        is VoiceSignal.Answer -> answerReceived(state, signal)
        is VoiceSignal.IceCandidate -> candidateReceived(state, signal)
        is VoiceSignal.State -> peerStateReceived(state, signal, fresh)
    }

@Suppress("ReturnCount") // one early-out per PROTOCOL §7.4 receiver rule, in spec order
private fun offerReceived(
    state: VoiceNegotiationState,
    offer: VoiceSignal.Offer,
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
            withPeer.copy(heldRemoteOffer = HeldRemoteOffer(id, offer.sdp)),
            listOf(VoiceAction.SurfacePeerVoiceRequest),
        )
    }

    return VoiceOutcome(
        withPeer.copy(
            status = VoiceStatus.NEGOTIATING,
            voiceSessionId = id,
            remoteDescriptionApplied = true,
            heldRemoteOffer = null,
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
            VoiceAction.SendVoiceState(id, VoiceWireState.CONNECTING, state.micMuted, state.mode),
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
        VoiceWireState.NEGOTIATING -> peerWantsVoice(observed, fresh)
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
        ),
        listOf(
            VoiceAction.SendVoiceState(fresh, VoiceWireState.NEGOTIATING, state.micMuted, state.mode),
            VoiceAction.CreateOffer(fresh),
        ),
    )
}

private fun dropped(
    state: VoiceNegotiationState,
    reason: VoiceSignalDropReason,
): VoiceOutcome = VoiceOutcome(state, listOf(VoiceAction.RecordDroppedSignal(reason)))
