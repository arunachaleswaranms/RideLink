import Foundation

/// PROTOCOL §7.3 — who creates the WebRTC offer.
///
/// Derived from ADR-010 leadership (the lexicographically smaller `peer_id`) and from **nothing
/// else**. In particular not from which side dialled the TCP connection and not from which connection
/// survived PROTOCOL §4.2: `conn_tiebreak` and `peer_id` are uncorrelated by construction (ADR-015
/// Amendment A2), so inferring the offerer from the initiator would work by coincidence in a lab and
/// fail on a ride.
public enum VoiceRole: String, Sendable, Equatable, CaseIterable {
    case offerer = "OFFERER"
    case answerer = "ANSWERER"

    /// `isLocalLeader` is the value `HELLO_ACK.leader_peer_id` already establishes (PROTOCOL §4.1).
    public static func forLeadership(isLocalLeader: Bool) -> VoiceRole {
        isLocalLeader ? .offerer : .answerer
    }

    /// The same rule from the two `peer_id`s directly, for callers that have them rather than a
    /// precomputed flag. Kept next to `forLeadership` so there is one definition of the rule.
    public static func forPeers(localPeerId: PeerId, remotePeerId: PeerId) -> VoiceRole {
        forLeadership(isLocalLeader: localPeerId.value < remotePeerId.value)
    }
}

/// The local voice session's status. These are exactly PROTOCOL §7.4's wire values minus `closed`,
/// which is a *signal* rather than a state this side rests in: teardown returns to `.idle`.
public enum VoiceStatus: String, Sendable, Equatable, CaseIterable {
    case idle = "IDLE"
    case negotiating = "NEGOTIATING"
    case connecting = "CONNECTING"
    case active = "ACTIVE"
    case failed = "FAILED"

    public var wire: VoiceWireState {
        switch self {
        case .idle: return .idle
        case .negotiating: return .negotiating
        case .connecting: return .connecting
        case .active: return .active
        case .failed: return .failed
        }
    }

    public var isNegotiationLive: Bool {
        self == .negotiating || self == .connecting || self == .active
    }
}

/// An offer that arrived before this user had consented to voice for the ride segment (§7.3).
public struct HeldRemoteOffer: Sendable, Equatable {
    public let voiceSessionId: VoiceSessionId
    public let sdp: String

    public init(voiceSessionId: VoiceSessionId, sdp: String) {
        self.voiceSessionId = voiceSessionId
        self.sdp = sdp
    }
}

/// Everything the negotiation decision depends on. Deliberately a value type with no clock, no
/// randomness, no I/O and no platform type: it is the reason the whole of PROTOCOL §7's negotiation
/// can be exhausted by a laptop unit test on both platforms rather than only observed on two phones.
public struct VoiceNegotiationState: Sendable, Equatable {
    public var role: VoiceRole
    public var status: VoiceStatus
    public var voiceSessionId: VoiceSessionId?
    /// Whether this user has consented to voice for **this ride segment** and the capture device and
    /// audio session are consequently open.
    ///
    /// It survives a control-plane link loss on purpose. ARCHITECTURE §6.3/§6.4: the capture device is
    /// opened once while the app is foreground-visible and stays open for the whole segment, because
    /// on Android there is no second legal opportunity to open it once the screen is locked. A link
    /// blip must therefore not close it — only an explicit stop, or `ENDING`, may. iOS is less strict,
    /// and the behaviour is deliberately identical anyway: the two platforms share this table.
    public var localAudioOpen: Bool
    /// True once `setRemoteDescription` has been applied — the gate for applying ICE candidates.
    public var remoteDescriptionApplied: Bool
    /// What the peer last told us via `VOICE_STATE` about wanting voice. Diagnostics + glare (§7.3).
    public var peerVoiceEnabled: Bool
    public var peerReportedState: VoiceWireState
    public var heldRemoteOffer: HeldRemoteOffer?
    public var micMuted: Bool
    public var mode: VoiceMode

    public init(
        role: VoiceRole,
        status: VoiceStatus = .idle,
        voiceSessionId: VoiceSessionId? = nil,
        localAudioOpen: Bool = false,
        remoteDescriptionApplied: Bool = false,
        peerVoiceEnabled: Bool = false,
        peerReportedState: VoiceWireState = .idle,
        heldRemoteOffer: HeldRemoteOffer? = nil,
        micMuted: Bool = false,
        mode: VoiceMode = .continuous
    ) {
        self.role = role
        self.status = status
        self.voiceSessionId = voiceSessionId
        self.localAudioOpen = localAudioOpen
        self.remoteDescriptionApplied = remoteDescriptionApplied
        self.peerVoiceEnabled = peerVoiceEnabled
        self.peerReportedState = peerReportedState
        self.heldRemoteOffer = heldRemoteOffer
        self.micMuted = micMuted
        self.mode = mode
    }
}

/// Why a well-formed signal was not acted on. Distinct from a *malformed* one, which never gets here.
public enum VoiceSignalDropReason: String, Sendable, Equatable {
    /// PROTOCOL §7.3: an offerer received `VOICE_ANSWER`, or an answerer received `VOICE_OFFER`.
    case roleViolation = "ROLE_VIOLATION"
    /// PROTOCOL §7.2: the `voice_session_id` is not the one this side currently holds.
    case generationMismatch = "GENERATION_MISMATCH"
    /// A retransmitted offer or answer for the negotiation already in progress.
    case duplicate = "DUPLICATE"
    /// Well-formed and current, but not meaningful from the status this side is in.
    case unexpectedForStatus = "UNEXPECTED_FOR_STATUS"
    /// A callback from a peer connection that has already been torn down (PROTOCOL §7.8).
    case staleEngineCallback = "STALE_ENGINE_CALLBACK"
}

/// What the driver is asked to do. Every payload is a plain value (see `VoiceSignal`'s note).
public enum VoiceAction: Sendable, Equatable {
    /// Open the audio session, select the communication route and open the capture device, then create
    /// the peer connection with an **empty ICE server list** (PROTOCOL §7.6).
    case startLocalAudio
    case createOffer(voiceSessionId: VoiceSessionId)
    case createAnswer(voiceSessionId: VoiceSessionId)
    case applyRemoteOffer(voiceSessionId: VoiceSessionId, sdp: String)
    case applyRemoteAnswer(voiceSessionId: VoiceSessionId, sdp: String)
    case sendOffer(voiceSessionId: VoiceSessionId, sdp: String)
    case sendAnswer(voiceSessionId: VoiceSessionId, sdp: String)
    case sendVoiceState(voiceSessionId: VoiceSessionId?, state: VoiceWireState, micMuted: Bool, mode: VoiceMode)
    case applyRemoteCandidate(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int)
    /// A locally gathered candidate, to be trickled to the peer as `VOICE_ICE`.
    case sendCandidate(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int)
    /// §7.4: a candidate that arrived before the remote description. Bounded by `PendingCandidates`.
    case queueRemoteCandidate(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int)
    case drainQueuedCandidates
    case setMicrophoneMuted(muted: Bool)
    /// Close the peer connection, both tracks and the ICE state. Does **not** touch the capture device
    /// or the audio session — see `VoiceNegotiationState.localAudioOpen`.
    case stopMediaTransport
    /// Stop capture and release the audio session. Only a deliberate stop or `ENDING` may do this.
    case releaseLocalAudio
    /// Diagnostics only. A dropped signal is counted and named, never silently discarded.
    case recordDroppedSignal(reason: VoiceSignalDropReason)
    /// The peer wants voice and this user has not consented yet: the UI should offer to start.
    case surfacePeerVoiceRequest
}

/// What drives the table. `freshVoiceSessionId` exists because the table is pure.
public enum VoiceInput: Sendable {
    /// This user pressed Start Voice, or a control reconnect is rebuilding voice for a segment the
    /// user had already consented to (PROTOCOL §7.8).
    ///
    /// The id is generated by the caller and consumed only if this input actually starts a
    /// negotiation. Generating it here would make the table impure — the same reason `SessionFsm`
    /// takes time as a parameter (CLAUDE.md rule 9).
    case startRequested(freshVoiceSessionId: VoiceSessionId)
    /// This user pressed End Voice, or the session is entering `ENDING` (ARCHITECTURE §3 rule 3).
    case stopRequested
    case muteRequested(muted: Bool)
    /// A `VOICE_*` frame that has already passed the trust gate (PROTOCOL §7.1) **and** the codec's
    /// bounds.
    ///
    /// `freshVoiceSessionId` is supplied on every signal for the one case that needs it: an offerer
    /// whose user has already consented, receiving the answerer's `negotiating` intent, begins a
    /// negotiation and therefore needs an id (§7.3 glare).
    case signalReceived(signal: VoiceSignal, freshVoiceSessionId: VoiceSessionId)
    case localOfferCreated(voiceSessionId: VoiceSessionId, sdp: String)
    case localAnswerCreated(voiceSessionId: VoiceSessionId, sdp: String)
    /// The media stack gathered a local ICE candidate. It goes through the table rather than straight
    /// to the wire for two reasons: the generation guard applies to it exactly as to an inbound frame
    /// (a candidate gathered by a peer connection we have since closed must not be sent), and routing
    /// it through the single input queue is what stops it overtaking the `VOICE_OFFER` it must follow.
    case localCandidateGathered(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int)
    /// The remote audio track appeared or went. Diagnostics only, but still generation-guarded.
    case remoteTrackChanged(voiceSessionId: VoiceSessionId, present: Bool)
    /// The media stack's own state changed. Carries its `voice_session_id` so a stale one is inert.
    case mediaConnectivityChanged(voiceSessionId: VoiceSessionId, connected: Bool, failed: Bool)
    /// The control plane was lost. §7.8: media goes, local capture stays, and voice does not retry.
    case controlLinkLost
}

public struct VoiceOutcome: Sendable, Equatable {
    public let state: VoiceNegotiationState
    public let actions: [VoiceAction]

    public init(state: VoiceNegotiationState, actions: [VoiceAction]) {
        self.state = state
        self.actions = actions
    }
}

/// The complete PROTOCOL §7 negotiation table, as a pure `(state, input) -> (state, actions)` reducer.
///
/// It is a separate type for the same reason `SessionGate` is (ADR-019): the properties that matter
/// here — a deterministic offerer, exactly one negotiation per generation, a stale callback that
/// cannot touch the next session, a candidate that arrives early being queued rather than lost — are
/// properties of *this table*, and a table is exhaustible by a laptop unit test on both platforms.
/// `com.ridelink.core.voice.VoiceNegotiation` is the mirror; the two must agree case for case, and
/// `protocol/vectors/voice-fsm/` is what makes a disagreement fail a build instead of a ride.
///
/// It owns no session state, holds no trust, reads no clock, opens no socket and knows nothing about
/// WebRTC. `VoiceController` drives it and performs the effects.
public enum VoiceNegotiation {
    public static func reduce(state: VoiceNegotiationState, input: VoiceInput) -> VoiceOutcome {
        switch input {
        case .startRequested(let fresh):
            return start(state, fresh)
        case .stopRequested:
            return stop(state)
        case .muteRequested(let muted):
            return mute(state, muted)
        case .signalReceived(let signal, let fresh):
            return self.signal(state, signal, fresh)
        case .localOfferCreated(let id, let sdp):
            return localOfferCreated(state, id, sdp)
        case .localAnswerCreated(let id, let sdp):
            return localAnswerCreated(state, id, sdp)
        case .localCandidateGathered(let id, let candidate, let mid, let index):
            return localCandidateGathered(state, id, candidate, mid, index)
        case .remoteTrackChanged(let id, _):
            return remoteTrackChanged(state, id)
        case .mediaConnectivityChanged(let id, let connected, let failed):
            return connectivity(state, id, connected: connected, failed: failed)
        case .controlLinkLost:
            return controlLinkLost(state)
        }
    }

    // MARK: - local user actions

    private static func start(_ state: VoiceNegotiationState, _ fresh: VoiceSessionId) -> VoiceOutcome {
        // Idempotent: pressing Start Voice twice, or a reconnect rebuild racing a manual start, must
        // not produce a second negotiation.
        if state.status.isNegotiationLive { return VoiceOutcome(state: state, actions: []) }

        var actions: [VoiceAction] = []
        if !state.localAudioOpen { actions.append(.startLocalAudio) }
        var next = state
        next.localAudioOpen = true

        switch state.role {
        case .offerer:
            actions.append(
                .sendVoiceState(voiceSessionId: fresh, state: .negotiating, micMuted: state.micMuted, mode: state.mode)
            )
            actions.append(.createOffer(voiceSessionId: fresh))
            next.status = .negotiating
            next.voiceSessionId = fresh
            next.remoteDescriptionApplied = false
            next.heldRemoteOffer = nil
        case .answerer:
            if let held = state.heldRemoteOffer {
                // The offerer got there first and we held its offer for want of local consent (§7.3).
                // Consent has now arrived, so answer the offer we already have rather than asking the
                // offerer to send it again.
                actions.append(.applyRemoteOffer(voiceSessionId: held.voiceSessionId, sdp: held.sdp))
                actions.append(.drainQueuedCandidates)
                actions.append(.createAnswer(voiceSessionId: held.voiceSessionId))
                next.status = .negotiating
                next.voiceSessionId = held.voiceSessionId
                next.remoteDescriptionApplied = true
                next.heldRemoteOffer = nil
            } else {
                // An answerer never offers. It states its intent and waits (§7.3). The id is nil
                // because the offerer, not this side, creates one.
                actions.append(
                    .sendVoiceState(voiceSessionId: nil, state: .negotiating, micMuted: state.micMuted, mode: state.mode)
                )
                next.status = .negotiating
                next.voiceSessionId = nil
                next.remoteDescriptionApplied = false
            }
        }
        return VoiceOutcome(state: next, actions: actions)
    }

    private static func stop(_ state: VoiceNegotiationState) -> VoiceOutcome {
        if state.status == .idle, !state.localAudioOpen, state.heldRemoteOffer == nil {
            return VoiceOutcome(state: state, actions: [])
        }
        var actions: [VoiceAction] = []
        // Tell the peer before closing, and only if there is a negotiation to name. `closed` is the
        // teardown signal; PROTOCOL §7.4 deliberately has no separate VOICE_END.
        if let id = state.voiceSessionId {
            actions.append(
                .sendVoiceState(voiceSessionId: id, state: .closed, micMuted: state.micMuted, mode: state.mode)
            )
        }
        actions.append(.stopMediaTransport)
        // A deliberate stop is the case that *may* release capture: the user is present, so a later
        // restart can legally reopen it (ARCHITECTURE §6.4).
        if state.localAudioOpen { actions.append(.releaseLocalAudio) }
        return VoiceOutcome(
            state: VoiceNegotiationState(role: state.role, micMuted: state.micMuted, mode: state.mode),
            actions: actions
        )
    }

    private static func mute(_ state: VoiceNegotiationState, _ muted: Bool) -> VoiceOutcome {
        if state.micMuted == muted { return VoiceOutcome(state: state, actions: []) }
        var actions: [VoiceAction] = []
        if state.localAudioOpen { actions.append(.setMicrophoneMuted(muted: muted)) }
        if let id = state.voiceSessionId {
            actions.append(
                .sendVoiceState(voiceSessionId: id, state: state.status.wire, micMuted: muted, mode: state.mode)
            )
        }
        var next = state
        next.micMuted = muted
        return VoiceOutcome(state: next, actions: actions)
    }

    // MARK: - control-plane lifecycle

    private static func controlLinkLost(_ state: VoiceNegotiationState) -> VoiceOutcome {
        if state.status == .idle, state.voiceSessionId == nil, state.heldRemoteOffer == nil {
            return VoiceOutcome(state: state, actions: [])
        }
        // Media goes; the capture device does not (ARCHITECTURE §6.3/§6.4 — see localAudioOpen). No
        // VOICE_STATE is sent: there is no link to send it on. And nothing is retried here —
        // PROTOCOL §10's control ladder is the only reconnect loop in the app (§7.8).
        return VoiceOutcome(
            state: VoiceNegotiationState(
                role: state.role,
                localAudioOpen: state.localAudioOpen,
                micMuted: state.micMuted,
                mode: state.mode
            ),
            actions: [.stopMediaTransport]
        )
    }

    // MARK: - engine callbacks

    private static func localOfferCreated(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String
    ) -> VoiceOutcome {
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        if state.status != .negotiating { return dropped(state, .unexpectedForStatus) }
        return VoiceOutcome(state: state, actions: [.sendOffer(voiceSessionId: id, sdp: sdp)])
    }

    private static func localAnswerCreated(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String
    ) -> VoiceOutcome {
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        if state.status != .negotiating { return dropped(state, .unexpectedForStatus) }
        var next = state
        next.status = .connecting
        return VoiceOutcome(
            state: next,
            actions: [
                .sendAnswer(voiceSessionId: id, sdp: sdp),
                .sendVoiceState(voiceSessionId: id, state: .connecting, micMuted: state.micMuted, mode: state.mode),
            ]
        )
    }

    private static func localCandidateGathered(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ candidate: String,
        _ mid: String?,
        _ index: Int
    ) -> VoiceOutcome {
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        return VoiceOutcome(
            state: state,
            actions: [.sendCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index)]
        )
    }

    private static func remoteTrackChanged(_ state: VoiceNegotiationState, _ id: VoiceSessionId) -> VoiceOutcome {
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        return VoiceOutcome(state: state, actions: [])
    }

    private static func connectivity(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        connected: Bool,
        failed: Bool
    ) -> VoiceOutcome {
        // The generation guard applied to the media stack's own callbacks, not just to the wire
        // (§7.8): a delegate call from a peer connection we already closed carries the old id.
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        var next = state
        if failed {
            next.status = .failed
            next.remoteDescriptionApplied = false
            return VoiceOutcome(
                state: next,
                actions: [
                    .stopMediaTransport,
                    .sendVoiceState(voiceSessionId: id, state: .failed, micMuted: state.micMuted, mode: state.mode),
                ]
            )
        }
        if connected, state.status != .active {
            next.status = .active
            return VoiceOutcome(
                state: next,
                actions: [
                    .sendVoiceState(voiceSessionId: id, state: .active, micMuted: state.micMuted, mode: state.mode),
                ]
            )
        }
        if !connected, state.status == .active {
            next.status = .connecting
            return VoiceOutcome(
                state: next,
                actions: [
                    .sendVoiceState(voiceSessionId: id, state: .connecting, micMuted: state.micMuted, mode: state.mode),
                ]
            )
        }
        return VoiceOutcome(state: state, actions: [])
    }

    // MARK: - inbound signals

    private static func signal(
        _ state: VoiceNegotiationState,
        _ signal: VoiceSignal,
        _ fresh: VoiceSessionId
    ) -> VoiceOutcome {
        switch signal {
        case .offer(let id, let sdp):
            return offerReceived(state, id, sdp)
        case .answer(let id, let sdp):
            return answerReceived(state, id, sdp)
        case .iceCandidate(let id, let candidate, let mid, let index):
            return candidateReceived(state, id, candidate, mid, index)
        case .state(let id, let wire, _, _):
            return peerStateReceived(state, id, wire, fresh)
        }
    }

    private static func offerReceived(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String
    ) -> VoiceOutcome {
        // §7.3: only the answerer may receive an offer. An offerer receiving one has met a peer that
        // disagrees about leadership — the same condition §4.1 calls leader_mismatch.
        if state.role != .answerer { return dropped(state, .roleViolation) }
        if state.voiceSessionId == id, state.remoteDescriptionApplied { return dropped(state, .duplicate) }
        // §7.2: a live negotiation is not displaced by an offer from a different generation.
        if let held = state.voiceSessionId, held != id, state.status.isNegotiationLive {
            return dropped(state, .generationMismatch)
        }

        var next = state
        next.peerVoiceEnabled = true
        next.peerReportedState = .negotiating

        // The microphone is never opened because a *peer* asked. ARCHITECTURE §6.4 makes that illegal
        // on Android from the background, and it would be wrong on iOS too. The offer is held and the
        // UI offers to start; consent then answers it from `start()`.
        if !state.localAudioOpen {
            next.heldRemoteOffer = HeldRemoteOffer(voiceSessionId: id, sdp: sdp)
            return VoiceOutcome(state: next, actions: [.surfacePeerVoiceRequest])
        }

        next.status = .negotiating
        next.voiceSessionId = id
        next.remoteDescriptionApplied = true
        next.heldRemoteOffer = nil
        return VoiceOutcome(
            state: next,
            actions: [
                .applyRemoteOffer(voiceSessionId: id, sdp: sdp),
                .drainQueuedCandidates,
                .createAnswer(voiceSessionId: id),
            ]
        )
    }

    private static func answerReceived(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String
    ) -> VoiceOutcome {
        if state.role != .offerer { return dropped(state, .roleViolation) }
        if state.voiceSessionId != id { return dropped(state, .generationMismatch) }
        if state.remoteDescriptionApplied { return dropped(state, .duplicate) }
        if state.status != .negotiating { return dropped(state, .unexpectedForStatus) }

        var next = state
        next.status = .connecting
        next.remoteDescriptionApplied = true
        next.peerVoiceEnabled = true
        return VoiceOutcome(
            state: next,
            actions: [
                .applyRemoteAnswer(voiceSessionId: id, sdp: sdp),
                .drainQueuedCandidates,
                .sendVoiceState(voiceSessionId: id, state: .connecting, micMuted: state.micMuted, mode: state.mode),
            ]
        )
    }

    private static func candidateReceived(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ candidate: String,
        _ mid: String?,
        _ index: Int
    ) -> VoiceOutcome {
        // §7.2/§7.4: including the case that matters most — a candidate arriving after teardown, when
        // voiceSessionId is nil, cannot resurrect anything.
        if state.voiceSessionId != id { return dropped(state, .generationMismatch) }
        let action: VoiceAction =
            state.remoteDescriptionApplied
                ? .applyRemoteCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
                // Trickle ICE: early candidates are queued, not dropped, up to
                // MAX_QUEUED_VOICE_CANDIDATES. Dropping them would make a slow SDP round trip look
                // like a connectivity failure.
                : .queueRemoteCandidate(voiceSessionId: id, candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
        return VoiceOutcome(state: state, actions: [action])
    }

    private static func peerStateReceived(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId?,
        _ wire: VoiceWireState,
        _ fresh: VoiceSessionId
    ) -> VoiceOutcome {
        // A peer state naming a generation that is not ours is not about our session. `nil` is legal
        // and carries no generation claim, so it is never a mismatch (§7.4).
        if let peerId = id, let ours = state.voiceSessionId, peerId != ours {
            return dropped(state, .generationMismatch)
        }
        var observed = state
        observed.peerReportedState = wire
        switch wire {
        case .closed:
            return teardownFromPeer(observed, .idle)
        case .failed:
            return teardownFromPeer(observed, .failed)
        case .negotiating:
            return peerWantsVoice(observed, fresh)
        case .idle:
            observed.peerVoiceEnabled = false
            return VoiceOutcome(state: observed, actions: [])
        // Informational. §7.4 requires an unrecognised value to be tolerated as `unknown` rather
        // than treated as malformed, so it lands here alongside the known ones.
        case .connecting, .active, .unknown:
            observed.peerVoiceEnabled = true
            return VoiceOutcome(state: observed, actions: [])
        }
    }

    /// The peer ended or failed its side. Media goes and the local capture device stays, exactly as
    /// for a link loss: the peer may come back within this ride segment, and this user's consent
    /// (`localAudioOpen`) has not been withdrawn.
    private static func teardownFromPeer(
        _ state: VoiceNegotiationState,
        _ newStatus: VoiceStatus
    ) -> VoiceOutcome {
        if state.status == .idle, state.voiceSessionId == nil, state.heldRemoteOffer == nil {
            var next = state
            next.peerVoiceEnabled = false
            return VoiceOutcome(state: next, actions: [])
        }
        return VoiceOutcome(
            state: VoiceNegotiationState(
                role: state.role,
                status: newStatus,
                localAudioOpen: state.localAudioOpen,
                peerReportedState: state.peerReportedState,
                micMuted: state.micMuted,
                mode: state.mode
            ),
            actions: [.stopMediaTransport]
        )
    }

    /// §7.3 glare. The answerer's `negotiating` is an intent, not an offer. If this side is the
    /// offerer and its own user has already consented, the intent begins the negotiation; otherwise it
    /// is recorded and surfaced, and this user's own Start Voice is what proceeds.
    ///
    /// Receiving it while a negotiation is already live is **idempotent** — which is precisely what
    /// makes two simultaneous presses produce one offer rather than two.
    private static func peerWantsVoice(
        _ state: VoiceNegotiationState,
        _ fresh: VoiceSessionId
    ) -> VoiceOutcome {
        var withPeer = state
        withPeer.peerVoiceEnabled = true
        if state.status.isNegotiationLive { return VoiceOutcome(state: withPeer, actions: []) }
        if state.role != .offerer { return VoiceOutcome(state: withPeer, actions: []) }
        if !state.localAudioOpen {
            return VoiceOutcome(state: withPeer, actions: [.surfacePeerVoiceRequest])
        }
        var next = withPeer
        next.status = .negotiating
        next.voiceSessionId = fresh
        next.remoteDescriptionApplied = false
        next.heldRemoteOffer = nil
        return VoiceOutcome(
            state: next,
            actions: [
                .sendVoiceState(voiceSessionId: fresh, state: .negotiating, micMuted: state.micMuted, mode: state.mode),
                .createOffer(voiceSessionId: fresh),
            ]
        )
    }

    private static func dropped(
        _ state: VoiceNegotiationState,
        _ reason: VoiceSignalDropReason
    ) -> VoiceOutcome {
        VoiceOutcome(state: state, actions: [.recordDroppedSignal(reason: reason)])
    }
}
