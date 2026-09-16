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
    /// **The authenticated control generation that owns the negotiation state this value holds**, or
    /// nil when it holds none (STATUS §4 problem 61, ADR-020 Amendment A8).
    ///
    /// "Negotiation state" is precisely a live `status` or a `heldRemoteOffer`; those two are
    /// mutually exclusive by construction, because every branch that goes live requires
    /// `localAudioOpen` and every branch that holds an offer requires it to be false, so one field
    /// names the owner of whichever exists.
    ///
    /// It exists because `.controlLinkLost` is delivered **asynchronously**, and `VoiceInputMailbox`'s
    /// identity rule reaches only as far as *queued* work. Once a successor lifetime's offer has been
    /// reduced, the negotiation it created is ordinary state with nothing on it to say whose it is —
    /// so a predecessor's boundary, arriving later, returned it to `.idle` and wedged voice for the
    /// ride segment.
    ///
    /// Ownership is **established**, never inferred: it is set only by the transitions that actually
    /// create negotiation state, always to the generation carried by the very input that created it.
    /// That a newer generation *admitted* something transfers nothing — admission is not application,
    /// and a successor's offer refused by `.generationMismatch` leaves the predecessor the owner,
    /// exactly so its own boundary can still retire it.
    ///
    /// A third identity, and never to be conflated with the other two: `voice_session_id` owns one
    /// WebRTC negotiation, `.signalReceived`'s `controlGeneration` owns the frame that admitted a
    /// signal, and this owns the control lifetime a negotiation belongs to.
    public var negotiationControlGeneration: Int64?

    /// Unresolved local Start consent, with no control authority of its own (ADR-020 A11).
    /// Consumed by negotiation establishment; Stop clears it. Send failure never creates it.
    public var pendingStartIntent: Bool

    /// Authority supplied by an explicit authenticated-lifetime input, retained for a delayed
    /// Start(nil). Separate from the owner of any live negotiation; a boundary clears only the
    /// availability it actually retires. No live coordinator state is read by this table.
    public var authenticatedControlGeneration: Int64?

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
        mode: VoiceMode = .continuous,
        negotiationControlGeneration: Int64? = nil,
        pendingStartIntent: Bool = false,
        authenticatedControlGeneration: Int64? = nil
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
        self.negotiationControlGeneration = negotiationControlGeneration
        self.pendingStartIntent = pendingStartIntent
        self.authenticatedControlGeneration = authenticatedControlGeneration
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
    /// A well-formed, already-authenticated input could not be held by `VoiceInputMailbox` — its
    /// critical lane was full, or its ICE lane evicted an older candidate to make room for this one.
    /// Never produced by this table: `VoiceNegotiation` never sees the input at all in this case, so
    /// `VoiceController` counts it directly, one layer earlier than every other reason here.
    case inputMailboxOverflow = "INPUT_MAILBOX_OVERFLOW"
    /// A peer signal that `VoiceInputMailbox` was still holding when the control lifetime that
    /// admitted it ended (STATUS §4 problem 50). Never produced by this table, for the same reason
    /// `.inputMailboxOverflow` is not: the input is discarded before `VoiceNegotiation` ever sees it,
    /// so `VoiceController` counts it directly.
    ///
    /// Distinct from `VoiceSignalRelay.droppedRetiredGeneration`, which counts a frame that had
    /// *already* lost its lifetime when it arrived (ADR-025). This one counts a frame that was admitted
    /// perfectly legitimately and then outlived the link that admitted it.
    ///
    /// Since STATUS §4 problem 60 it is the sum of `VoiceInputMailbox`'s two counters — the signals a
    /// retirement found already queued, and the ones that arrived after it. Both name the same fact
    /// ("this signal's admitting control generation is retired") caught at the two different instants
    /// it can be caught at, and only the mailbox needs to tell them apart.
    case retiredControlLifetime = "RETIRED_CONTROL_LIFETIME"
    /// A `.controlLinkLost` naming a control lifetime **older** than the one that owns the
    /// negotiation this side is holding (STATUS §4 problem 61, ADR-020 Amendment A8).
    ///
    /// Not a signal, like `.inputMailboxOverflow` is not — but recorded through the same counter for
    /// the same reason: a preserved successor is a fact about the ride ("a predecessor's boundary
    /// arrived late and was correctly ignored"), and a silent no-op would make the one case this
    /// amendment exists for the only one with no evidence that it happened.
    case supersededControlLifetime = "SUPERSEDED_CONTROL_LIFETIME"
    /// A `HeldRemoteOffer` discarded because the control lifetime that delivered it is **older** than
    /// the one authorising the local consent that would have answered it (STATUS §4 problem 63,
    /// ADR-020 Amendment A9).
    ///
    /// The peer that sent that offer tore its own negotiation down when *its* copy of that link died,
    /// so answering the offer would name a `voice_session_id` the peer no longer holds — and worse,
    /// would move the negotiation's owner to the consenting lifetime, leaving the boundary that could
    /// still have retired it inert. PROTOCOL §7.8 wants a **fresh** negotiation after a reconnect, and
    /// an answerer reaches one by stating its intent again, not by answering a dead lifetime's SDP.
    case retiredHeldOffer = "RETIRED_HELD_OFFER"
    /// A `.startRequested` whose authorising control lifetime is **older** than the lifetime that owns
    /// negotiation state this side already holds, **and that state is not a held remote offer**
    /// (STATUS §4 problems 63 and 66, ADR-020 Amendments A9 and A10).
    ///
    /// The press's *consent* is still honoured — capture opens, because ARCHITECTURE §6.4 may give no
    /// second foreground-visible chance — but it establishes **no** negotiation: there is no link left
    /// to negotiate over, and a negotiation owned by a lifetime that has ended is exactly what
    /// ADR-020 Amendment A8 exists to prevent being created.
    ///
    /// Amendment A10 narrowed this to the residue. The case it used to cover — a *held remote offer*
    /// owned by a newer lifetime — now progresses instead: the stale press contributes consent, the
    /// held offer contributes the authenticated lifetime and the `voice_session_id`, and the answer
    /// goes out on the offer's own link. Leaving it held was a liveness defect, because the peer sends
    /// one offer per `voice_session_id` and nothing would have pressed Start a second time.
    ///
    /// What is left is unreachable by construction — an owner is set only alongside a live status or a
    /// held offer, a live status returns before this, and only an answerer can hold an offer — and is
    /// kept as a refusal rather than removed, for the same reason `controlLinkLost` keeps its
    /// `owner == nil` branch: the alternative is a negotiation owned by a lifetime that has ended.
    case supersededStartLifetime = "SUPERSEDED_START_LIFETIME"
    /// A `controlAuthenticated` that found a **live negotiation already established** (STATUS §4
    /// problem 69, ADR-020 Amendment A11). Not a failure: the successor lifetime's authentication
    /// arrived after whatever press, intent or offer already started this one, and that negotiation
    /// keeps the owner it was established with (Amendment A8's "never re-own" rule). Recorded rather
    /// than silent for the same reason `.supersededControlLifetime` is: a no-op that leaves no
    /// evidence cannot be told apart from an input that never arrived.
    case authenticatedDuringLiveNegotiation = "AUTHENTICATED_DURING_LIVE_NEGOTIATION"
}

/// **An action that puts a `VOICE_*` frame on the control connection, and the control lifetime that
/// authorises it to** (STATUS §4 problem 64, ADR-020 Amendment A9).
///
/// `controlGeneration` is the `VoiceNegotiationState.negotiationControlGeneration` of the negotiation
/// this frame belongs to, captured by the very transition that produced the action. It is **not** a
/// `voice_session_id` (that owns one WebRTC negotiation) and **not** a `revision_epoch` (ADR-021 A7's
/// sender lifetime): it is the authenticated control lifetime whose connection this frame may be
/// written to, and no other.
///
/// Why it is on the action rather than derived by the driver. `VoiceSignalTransport.send` suspends —
/// an actor hop, a write lock, a socket flush — and so does every engine callback that leads to one,
/// so the instant an action is *performed* is not the instant it was *authorised*. A driver that asked
/// "which connection is authenticated now" at performance time would answer a different question,
/// which is ADR-024 Amendment A7's defect in the outbound direction: an offer authorised by a lifetime
/// that had since ended was written to its successor's socket, where the peer accepted it as current.
/// Carrying the value means the transport can *compare* rather than re-read.
///
/// Nil would mean "authorised by no control lifetime". No transition produces one — every branch that
/// sends holds negotiation state, and negotiation state always names its owner, which
/// `VoiceNegotiationVectorTests` asserts over every row — and the transport therefore treats nil as a
/// refusal rather than as permission to use whatever is live.
public extension VoiceAction {
    /// Whether this action puts a `VOICE_*` frame on the control connection. The mirror of Android's
    /// `OutboundVoiceAction` marker, which a Swift enum cannot express as a conformance.
    var isOutbound: Bool {
        switch self {
        case .sendOffer, .sendAnswer, .sendVoiceState, .sendCandidate: true
        default: false
        }
    }

    /// The control lifetime this action is authorised to be written on, or nil when it is not an
    /// outbound action at all — read together with `isOutbound`, never on its own.
    var controlGeneration: Int64? {
        switch self {
        case .sendOffer(_, _, let owner): owner
        case .sendAnswer(_, _, let owner): owner
        case .sendVoiceState(_, _, _, _, let owner): owner
        case .sendCandidate(_, _, _, _, let owner): owner
        default: nil
        }
    }
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
    case sendOffer(voiceSessionId: VoiceSessionId, sdp: String, controlGeneration: Int64?)
    case sendAnswer(voiceSessionId: VoiceSessionId, sdp: String, controlGeneration: Int64?)
    case sendVoiceState(
        voiceSessionId: VoiceSessionId?,
        state: VoiceWireState,
        micMuted: Bool,
        mode: VoiceMode,
        controlGeneration: Int64?
    )
    case applyRemoteCandidate(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int)
    /// A locally gathered candidate, to be trickled to the peer as `VOICE_ICE`.
    case sendCandidate(
        voiceSessionId: VoiceSessionId,
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int,
        controlGeneration: Int64?
    )
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
    /// `controlGeneration` is **the authenticated control generation this press is authorised by**,
    /// supplied by the caller from `ControlSessionManager` — `.connected`'s `authGeneration` for
    /// PROTOCOL §7.8's reconnect rebuild, and the live generation for a user's tap (STATUS §4
    /// problem 61).
    ///
    /// Reading the live generation for a *local* press is correct and is not ADR-025's defect: there
    /// is no frame here whose provenance could be discarded, and "which lifetime is authenticated
    /// right now" is exactly the question a press asks. The defect is re-reading a live generation to
    /// label a frame that was already read, which this is not.
    ///
    /// **Nil means no control lifetime is authenticated**, which a user can reach by pressing Start
    /// in the gap between one link dying and the ladder restoring the next. A negotiation needs a
    /// link to negotiate over and an owner to be retired by, and there is neither — so this records
    /// the user's consent (opening capture, which ARCHITECTURE §6.4 requires be done while
    /// foreground-visible and is the whole reason the press must not simply be refused) and starts
    /// **no** negotiation. `SessionCoordinator.attachVoice` then rebuilds it under the successor's
    /// generation the moment one authenticates, because it starts voice for any segment whose capture
    /// is already open. The alternative — a negotiation owned by a lifetime that does not exist — is
    /// the one thing no boundary could ever retire.
    case startRequested(freshVoiceSessionId: VoiceSessionId, controlGeneration: Int64?)
    /// This user pressed End Voice, or the session is entering `ENDING` (ARCHITECTURE §3 rule 3).
    case stopRequested
    case muteRequested(muted: Bool)

    /// The intercom policy's `VOICE_STATE.mode` changed (PROTOCOL §7.4) — Phase 2b, where Phase 2a always
    /// sent `continuous`.
    ///
    /// The mode is a property of *this* peer's policy, not a negotiated value: each user chooses their own
    /// gate and each tells the other what theirs is, so the diagnostics screen can say "your peer is on
    /// push-to-talk" rather than leaving a silent peer ambiguous. Nothing about the media plane depends on
    /// the peer's mode, which is why this changes no status and touches no generation.
    case modeSelected(mode: VoiceMode)
    /// A `VOICE_*` frame that has already passed the trust gate (PROTOCOL §7.1) **and** the codec's
    /// bounds.
    ///
    /// `controlGeneration` is **the control authentication generation that admitted this frame** —
    /// the one `ReadFrameBinding` captured when the frame was read, carried unchanged through
    /// `VoiceSignalRelay.deliver` and `VoiceSignalSink.submit` (STATUS §4 problem 60, ADR-020
    /// Amendment A7). Receiver-local provenance: not on the wire, not negotiated, not peer-
    /// influenceable. This reducer reads it nowhere — it is `VoiceInputMailbox`'s, and only its,
    /// because the question it answers ("which control lifetime is this semantic work's?") is a
    /// lifetime question and not a negotiation one. It is a different identity from
    /// `freshVoiceSessionId` and from `voice_session_id`, and the three must never be conflated.
    ///
    /// `freshVoiceSessionId` is supplied on every signal for the one case that needs it: an offerer
    /// whose user has already consented, receiving the answerer's `negotiating` intent, begins a
    /// negotiation and therefore needs an id (§7.3 glare).
    case signalReceived(signal: VoiceSignal, controlGeneration: Int64, freshVoiceSessionId: VoiceSessionId)
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
    ///
    /// `retiredControlGeneration` is **the authentication generation that ended**, taken from the
    /// `AuthenticatedConnection` record the dying connection owned and captured before that record
    /// was cleared (STATUS §4 problem 60, ADR-020 Amendment A7). It is what makes this a statement
    /// about *one identified lifetime* rather than about whatever happens to be queued when it is
    /// applied — see `VoiceInputMailbox.offer`.
    ///
    /// Nil means **no control lifetime ended**, and there are exactly two such producers: a
    /// connection that died before it was ever authenticated, so nothing voice-related was ever
    /// admitted under it; and the mailbox-overflow degrade, which is a *local* fact about this
    /// device's own bounded queue and not a lifetime boundary at all. Either way the reducer's
    /// response is identical — it never reads this field — and the difference is entirely in what
    /// the mailbox is thereby entitled to discard.
    case controlLinkLost(retiredControlGeneration: Int64?)
    /// An outbound frame this negotiation **depended on** could not be put on the wire
    /// (STATUS §4 problems 56, 57 and 59).
    ///
    /// **This is not `.controlLinkLost`, and conflating the two was a defect.** They ask the table for
    /// the same thing — drop the media transport, keep this user's capture device (ARCHITECTURE
    /// §6.3/§6.4), let PROTOCOL §10's ladder own the link — but they are different *events*:
    ///
    /// - `.controlLinkLost` is a **control-lifetime boundary**. The lifetime that admitted every
    ///   `.signalReceived` still queued has ended, which is why `VoiceInputMailbox` gives it ownership
    ///   of that queued remote work (problem 50).
    /// - This is a **local, in-lifetime** fact about one frame. `VoiceSignalTransport.send` suspends —
    ///   on iOS it is three `await`s deep before a byte moves (`authenticatedWriter()`,
    ///   `activeSessionId()`, then the writer itself) and every one of them is an actor re-entrancy
    ///   point — so its `Bool` can arrive long after the lifetime that authorised it has been
    ///   replaced. Letting it speak for a lifetime boundary let a retired send discard a
    ///   **successor's** freshly admitted offer, and let it displace a pending `.stopRequested` in the
    ///   one-slot teardown lane (problems 57 and 59).
    ///
    /// `voiceSessionId` is the generation the failed frame belonged to — nil for an answerer's
    /// intent-to-talk, which names none (§7.3) — and the reducer refuses to act on any other, so this
    /// input can only ever retire the negotiation it was actually authorised by.
    case negotiationSendFailed(voiceSessionId: VoiceSessionId?)
    /// The trust gate admitted this lifetime. Records authority for a delayed Start(nil), consumes
    /// pending intent, or performs the existing consented reconnect rebuild once for a new lifetime.
    /// A duplicate event cannot retry a failed send. This input is local; no wire field is added.
    case controlAuthenticated(controlGeneration: Int64, freshVoiceSessionId: VoiceSessionId)
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
        case .startRequested(let fresh, let owner):
            return start(state, fresh, owner)
        case .stopRequested:
            return stop(state)
        case .modeSelected(let mode):
            return modeSelected(state, mode)
        case .muteRequested(let muted):
            return mute(state, muted)
        // `controlGeneration` decides **ownership** and nothing else: which control lifetime a
        // negotiation this signal *establishes* belongs to (STATUS §4 problem 61). It still decides
        // no negotiation — `VoiceInputMailbox` remains the only place it gates admission.
        case .signalReceived(let signal, let owner, let fresh):
            return self.signal(state, signal, fresh, owner)
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
        case .controlLinkLost(let retired):
            return controlLinkLost(state, retired)
        case .negotiationSendFailed(let id):
            return negotiationSendFailed(state, id)
        case .controlAuthenticated(let generation, let fresh):
            return controlAuthenticated(state, generation, fresh)
        }
    }

    // MARK: - local user actions

    private static func start(
        _ state: VoiceNegotiationState,
        _ fresh: VoiceSessionId,
        _ owner: Int64?
    ) -> VoiceOutcome {
        // Idempotent: pressing Start Voice twice, or a reconnect rebuild racing a manual start, must
        // not produce a second negotiation. The owner is deliberately **not** refreshed here: the
        // negotiation that is already live was established by whichever lifetime established it, and
        // a later press observing a newer one does not move it (STATUS §4 problem 61).
        if state.status.isNegotiationLive { return VoiceOutcome(state: state, actions: []) }

        var actions: [VoiceAction] = []
        if !state.localAudioOpen { actions.append(.startLocalAudio) }
        var next = state
        next.localAudioOpen = true

        // A nil press contributes consent only. Authority must come from an explicit availability
        // event or an authenticated held offer. Neither source relabels the original press.
        let heldOwner = state.heldRemoteOffer == nil ? nil : state.negotiationControlGeneration
        // An explicit successor event retires the older tap's authority, not its consent (A12).
        // The input remains Start(A); the recorded event supplies B. No live-state lookup.
        let available = state.authenticatedControlGeneration
        let resolved: Int64?
        if let available, owner.map({ available > $0 }) ?? true {
            resolved = available
        } else {
            resolved = owner ?? heldOwner
        }
        guard let owner = resolved else {
            next.pendingStartIntent = true
            return VoiceOutcome(state: next, actions: actions)
        }

        // Establishing a negotiation consumes the pending intent: the user's request has been
        // answered by a real negotiation, and a second one would be a retry rather than a resume.
        next.pendingStartIntent = false

        // **A press authorised by a lifetime older than the one that owns state we are already
        // holding is a press from a lifetime that has ended** (STATUS §4 problem 63). Generations
        // strictly increase and one connection is authenticated at a time, so the existence of
        // newer-owned state proves this press's link is gone.
        //
        // What that press still carries is **consent**, which is ride-segment state and outlives a
        // control reconnect by design (see `localAudioOpen`). So the two halves separate (ADR-020
        // Amendment A10, STATUS §4 problem 66): its *control authority* is stale and contributes
        // nothing, while its *consent* is exactly as valid as it was when the user tapped.
        //
        // When the newer-owned state is a held remote offer, that is enough to make progress — and it
        // has to be, because there is no second event coming. The offerer sends one `VOICE_OFFER` per
        // `voice_session_id` (PROTOCOL §7.4), `attachVoice`'s §7.8 rebuild has already run and found
        // no open capture, and the user has already consented, so nothing will press Start again. The
        // negotiation is built from the **held offer's** lifetime throughout: its `voice_session_id`,
        // its generation on every outbound frame, its boundary as the one that retires it. The stale
        // press authorises no write; the offer that lifetime delivered does.
        let existing = state.negotiationControlGeneration
        if let existing, existing > owner {
            if state.role == .answerer, let held = state.heldRemoteOffer {
                actions.append(.applyRemoteOffer(voiceSessionId: held.voiceSessionId, sdp: held.sdp))
                actions.append(.drainQueuedCandidates)
                actions.append(.createAnswer(voiceSessionId: held.voiceSessionId))
                next.status = .negotiating
                next.voiceSessionId = held.voiceSessionId
                next.remoteDescriptionApplied = true
                next.heldRemoteOffer = nil
                // Deliberately **not** `owner`: the negotiation is the held offer's lifetime's from
                // creation, which is what keeps Amendment A8's retirement rule pointed at the link the
                // answer will actually go out on.
                next.negotiationControlGeneration = existing
                return VoiceOutcome(state: next, actions: actions)
            }
            // Newer-owned negotiation state that is *not* a held offer. Unreachable by construction —
            // an owner is set only alongside a live status or a held offer, the live case returned
            // above, and only an answerer can hold an offer — and refused rather than trusted, for
            // `controlLinkLost`'s reason: a negotiation established by a lifetime that has ended is
            // exactly what Amendment A8 exists to prevent being created.
            actions.append(.recordDroppedSignal(reason: .supersededStartLifetime))
            return VoiceOutcome(state: next, actions: actions)
        }
        next.negotiationControlGeneration = owner

        switch state.role {
        case .offerer:
            actions.append(
                .sendVoiceState(
                    voiceSessionId: fresh,
                    state: .negotiating,
                    micMuted: state.micMuted,
                    mode: state.mode,
                    controlGeneration: owner
                )
            )
            actions.append(.createOffer(voiceSessionId: fresh))
            next.status = .negotiating
            next.voiceSessionId = fresh
            next.remoteDescriptionApplied = false
            next.heldRemoteOffer = nil
        case .answerer:
            // **A held offer may be answered only by the lifetime that delivered it** (STATUS §4
            // problem 63, ADR-020 Amendment A9). `existing` names that lifetime, and `existing >
            // owner` was handled above — it *answers* the offer, under the offer's own lifetime — so
            // what is left here is `existing == owner`, answer it, or `existing < owner`, where the
            // offerer's own link died with the lifetime that carried it and the offerer has therefore
            // already torn its side down. Answering then would name a `voice_session_id` the peer no
            // longer holds *and* move the owner to the consenting lifetime, so the predecessor's
            // boundary could never retire it. §7.8 wants a fresh negotiation; an answerer reaches one
            // by stating its intent again, which is exactly the no-held-offer branch below.
            //
            // The two orderings are not symmetric and the asymmetry is the point: `existing < owner`
            // has a stale *remote SDP*, which nothing can repair; `existing > owner` has a stale
            // *local control authority* beside a current remote offer, and consent is not control
            // authority (Amendment A10).
            let held = (existing == nil || existing == owner) ? state.heldRemoteOffer : nil
            if state.heldRemoteOffer != nil, held == nil {
                actions.append(.recordDroppedSignal(reason: .retiredHeldOffer))
            }
            if let held {
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
                    .sendVoiceState(
                        voiceSessionId: nil,
                        state: .negotiating,
                        micMuted: state.micMuted,
                        mode: state.mode,
                        controlGeneration: owner
                    )
                )
                next.status = .negotiating
                next.voiceSessionId = nil
                next.remoteDescriptionApplied = false
                next.heldRemoteOffer = nil
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
                // The lifetime that owns the negotiation being closed, read before the reset below —
                // a `closed` naming this generation belongs to this generation's link and no other.
                .sendVoiceState(
                    voiceSessionId: id,
                    state: .closed,
                    micMuted: state.micMuted,
                    mode: state.mode,
                    controlGeneration: state.negotiationControlGeneration
                )
            )
        }
        actions.append(.stopMediaTransport)
        // A deliberate stop is the case that *may* release capture: the user is present, so a later
        // restart can legally reopen it (ARCHITECTURE §6.4).
        if state.localAudioOpen { actions.append(.releaseLocalAudio) }
        return VoiceOutcome(
            state: VoiceNegotiationState(
                role: state.role, micMuted: state.micMuted, mode: state.mode
            ),
            actions: actions
        )
    }

    /// PROTOCOL §7.4's `mode`, changed by the local intercom policy. Idempotent, and it announces itself
    /// only when there is a generation to name: with no live negotiation there is nothing to report the
    /// mode *of*, and the next `VOICE_STATE` this side sends will carry the new value anyway.
    ///
    /// The status is deliberately unchanged and re-sent as-is: switching from PTT to continuous is not a
    /// state transition of the voice session, and treating it as one would put a spurious `negotiating` on
    /// the wire.
    private static func modeSelected(_ state: VoiceNegotiationState, _ mode: VoiceMode) -> VoiceOutcome {
        if state.mode == mode { return VoiceOutcome(state: state, actions: []) }
        var actions: [VoiceAction] = []
        if let id = state.voiceSessionId {
            actions.append(
                .sendVoiceState(
                    voiceSessionId: id,
                    state: state.status.wire,
                    micMuted: state.micMuted,
                    mode: mode,
                    controlGeneration: state.negotiationControlGeneration
                )
            )
        }
        var next = state
        next.mode = mode
        return VoiceOutcome(state: next, actions: actions)
    }

    private static func mute(_ state: VoiceNegotiationState, _ muted: Bool) -> VoiceOutcome {
        if state.micMuted == muted { return VoiceOutcome(state: state, actions: []) }
        var actions: [VoiceAction] = []
        if state.localAudioOpen { actions.append(.setMicrophoneMuted(muted: muted)) }
        if let id = state.voiceSessionId {
            actions.append(
                .sendVoiceState(
                    voiceSessionId: id,
                    state: state.status.wire,
                    micMuted: muted,
                    mode: state.mode,
                    controlGeneration: state.negotiationControlGeneration
                )
            )
        }
        var next = state
        next.micMuted = muted
        return VoiceOutcome(state: next, actions: actions)
    }

    // MARK: - control-plane lifecycle

    /// PROTOCOL §7.8, scoped to the lifetime that actually ended (STATUS §4 problem 61, ADR-020
    /// Amendment A8).
    ///
    /// > A control-lifetime boundary may retire only negotiation state **owned by that lifetime**. It
    /// > may never retire state that has already transferred to a successor.
    ///
    /// The whole rule is one comparison, and it is deliberately expressed as "is the lifetime that
    /// ended **older** than the owner" rather than "is it a different one":
    ///
    /// - `owner > retired` — a *predecessor's* boundary, delivered after the successor's work was
    ///   already reduced. This is problem 61 itself. Preserved, and recorded rather than silent.
    /// - `owner == retired` — the ordinary case, and PROTOCOL §7.8 unchanged. Torn down.
    /// - `owner < retired` — a *newer* lifetime ended while an older one still owns the negotiation.
    ///   The owner's lifetime must therefore already be over: `ControlSessionManager` holds one
    ///   authenticated connection at a time and allocates a strictly greater generation for each, so
    ///   the existence of a newer lifetime **proves** the older one ended (the same fact
    ///   `VoiceInputMailbox.newestAdmittedControlGeneration` rests on). Torn down — which is what
    ///   stops a lost or never-emitted predecessor boundary stranding a dead negotiation forever, the
    ///   exact wedge that made the naïve "suppress a superseded boundary" fix strictly worse than the
    ///   defect.
    /// - `owner == nil` — there is negotiation state but nothing owns it. Unreachable by construction
    ///   (every establishing transition sets an owner, and `start` with no lifetime establishes
    ///   nothing), and torn down rather than trusted: an un-retirable negotiation is the one outcome
    ///   with no way out of it.
    /// - `retired == nil` — **no lifetime ended at all.** Its two producers are a connection that
    ///   died before it authenticated and the mailbox-overflow degrade, and the second is why this
    ///   must tear down unconditionally: the degrade is a local safety valve that has to work whoever
    ///   owns what.
    ///
    /// Note what is *not* consulted: nothing live, nothing about arrival order, and nothing about what
    /// the mailbox has admitted. Admission is not application — a successor's offer refused by
    /// `offerReceived`'s `.generationMismatch` leaves the predecessor the owner, and the predecessor's
    /// own boundary then correctly retires it.
    private static func controlLinkLost(_ state: VoiceNegotiationState, _ retired: Int64?) -> VoiceOutcome {
        // Availability and negotiation ownership have independent retirement decisions. A delayed
        // LinkLost(A) must preserve recorded B even while there is no negotiation yet.
        var next = state
        if let available = state.authenticatedControlGeneration, let retired, available <= retired {
            next.authenticatedControlGeneration = nil
        }
        if state.status == .idle, state.voiceSessionId == nil, state.heldRemoteOffer == nil {
            return VoiceOutcome(state: next, actions: [])
        }
        if let owner = state.negotiationControlGeneration, let retired, owner > retired {
            return dropped(next, .supersededControlLifetime)
        }
        return VoiceOutcome(
            state: VoiceNegotiationState(
                role: state.role,
                localAudioOpen: state.localAudioOpen,
                micMuted: state.micMuted,
                mode: state.mode,
                pendingStartIntent: state.pendingStartIntent,
                authenticatedControlGeneration: next.authenticatedControlGeneration
            ),
            actions: [.stopMediaTransport]
        )
    }

    /// `.negotiationSendFailed`: the same degrade `controlLinkLost` performs, scoped to the one
    /// negotiation whose frame was lost.
    ///
    /// The generation guard is what makes this input safe to apply late. It is the same guard every
    /// engine callback carries (`localOfferCreated`, `connectivity`) and it answers the same question:
    /// does the thing that produced this input still own the negotiation the table is holding? A send
    /// authorised by a retired generation names an id the table has already moved past — or the table
    /// has been reset to `.idle` and holds none — and in both cases this is a no-op rather than a
    /// teardown of whatever came next.
    ///
    /// `nil == nil` is a deliberate match, not an accident: an answerer's intent-to-talk names no
    /// generation because the offerer has not created one yet (§7.3), so "the negotiation this side is
    /// holding also names none" is exactly the right identity for it. `isNegotiationLive` is what stops
    /// that matching an idle table.
    private static func negotiationSendFailed(
        _ state: VoiceNegotiationState,
        _ voiceSessionId: VoiceSessionId?
    ) -> VoiceOutcome {
        if !state.status.isNegotiationLive { return dropped(state, .unexpectedForStatus) }
        if state.voiceSessionId != voiceSessionId { return dropped(state, .generationMismatch) }
        return VoiceOutcome(
            state: VoiceNegotiationState(
                role: state.role,
                localAudioOpen: state.localAudioOpen,
                // The recorded lifetime survives a send failure — the failure is about one frame,
                // not about the link, and the lifetime is the table's ordered view of the link.
                // `pendingStartIntent` is already false: the establishment that this failure
                // degrades consumed it, and the degrade does not resurrect it, which is the whole
                // reason a send failure cannot loop (ADR-020 Amendment A11). The only thing that
                // could set the intent again is a press in a *new* gap.
                micMuted: state.micMuted,
                mode: state.mode,
                authenticatedControlGeneration: state.authenticatedControlGeneration
            ),
            actions: [.stopMediaTransport]
        )
    }

    // MARK: - successor lifetime availability

    private static func controlAuthenticated(
        _ state: VoiceNegotiationState,
        _ generation: Int64,
        _ fresh: VoiceSessionId
    ) -> VoiceOutcome {
        // Duplicate availability is not another reconnect opportunity, including after send failure.
        if let recorded = state.authenticatedControlGeneration, generation <= recorded {
            return VoiceOutcome(state: state, actions: [])
        }
        var recorded = state
        recorded.authenticatedControlGeneration = generation
        if state.status.isNegotiationLive {
            if let owner = state.negotiationControlGeneration, owner >= generation {
                return VoiceOutcome(
                    state: recorded,
                    actions: [.recordDroppedSignal(reason: .authenticatedDuringLiveNegotiation)]
                )
            }
            // A new authenticated successor proves the old owner ended. Stop its media before
            // establishing a fresh negotiation; never relabel an existing negotiation as B's.
            let retired = controlLinkLost(recorded, state.negotiationControlGeneration)
            let resumed = state.localAudioOpen ? start(retired.state, fresh, generation) : retired
            return VoiceOutcome(
                state: resumed.state,
                actions: state.localAudioOpen ? retired.actions + resumed.actions : retired.actions
            )
        }
        // The existing §7.8 rebuild belongs to this new Connected event, not a later diagnostics
        // publication. Combining it with pending-intent consumption prevents two kicks from one event.
        if state.pendingStartIntent || state.localAudioOpen {
            return start(recorded, fresh, generation)
        }
        return VoiceOutcome(state: recorded, actions: [])
    }

    // MARK: - engine callbacks

    private static func localOfferCreated(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String
    ) -> VoiceOutcome {
        if state.voiceSessionId != id { return dropped(state, .staleEngineCallback) }
        if state.status != .negotiating { return dropped(state, .unexpectedForStatus) }
        return VoiceOutcome(
            state: state,
            actions: [.sendOffer(voiceSessionId: id, sdp: sdp, controlGeneration: state.negotiationControlGeneration)]
        )
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
                .sendAnswer(voiceSessionId: id, sdp: sdp, controlGeneration: state.negotiationControlGeneration),
                .sendVoiceState(
                    voiceSessionId: id,
                    state: .connecting,
                    micMuted: state.micMuted,
                    mode: state.mode,
                    controlGeneration: state.negotiationControlGeneration
                ),
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
            actions: [
                .sendCandidate(
                    voiceSessionId: id,
                    candidate: candidate,
                    sdpMid: mid,
                    sdpMlineIndex: index,
                    controlGeneration: state.negotiationControlGeneration
                ),
            ]
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
                    .sendVoiceState(
                        voiceSessionId: id,
                        state: .failed,
                        micMuted: state.micMuted,
                        mode: state.mode,
                        controlGeneration: state.negotiationControlGeneration
                    ),
                ]
            )
        }
        if connected, state.status != .active {
            next.status = .active
            return VoiceOutcome(
                state: next,
                actions: [
                    .sendVoiceState(
                        voiceSessionId: id,
                        state: .active,
                        micMuted: state.micMuted,
                        mode: state.mode,
                        controlGeneration: state.negotiationControlGeneration
                    ),
                ]
            )
        }
        if !connected, state.status == .active {
            next.status = .connecting
            return VoiceOutcome(
                state: next,
                actions: [
                    .sendVoiceState(
                        voiceSessionId: id,
                        state: .connecting,
                        micMuted: state.micMuted,
                        mode: state.mode,
                        controlGeneration: state.negotiationControlGeneration
                    ),
                ]
            )
        }
        return VoiceOutcome(state: state, actions: [])
    }

    // MARK: - inbound signals

    private static func signal(
        _ state: VoiceNegotiationState,
        _ signal: VoiceSignal,
        _ fresh: VoiceSessionId,
        _ owner: Int64
    ) -> VoiceOutcome {
        switch signal {
        // Only the two branches that can *establish* negotiation state are given the owner.
        // `answerReceived` and `candidateReceived` advance a negotiation that already exists and
        // therefore already has one, and moving it because a successor's link carried a later frame
        // would be inferring ownership rather than establishing it (STATUS §4 problem 61).
        case .offer(let id, let sdp):
            return offerReceived(state, id, sdp, owner)
        case .answer(let id, let sdp):
            return answerReceived(state, id, sdp)
        case .iceCandidate(let id, let candidate, let mid, let index):
            return candidateReceived(state, id, candidate, mid, index)
        case .state(let id, let wire, _, _):
            return peerStateReceived(state, id, wire, fresh, owner)
        }
    }

    private static func offerReceived(
        _ state: VoiceNegotiationState,
        _ id: VoiceSessionId,
        _ sdp: String,
        _ owner: Int64
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
            // A held offer is negotiation state too — it is what a later consent answers — so it is
            // owned by the lifetime that delivered it and retired with that lifetime.
            next.negotiationControlGeneration = owner
            return VoiceOutcome(state: next, actions: [.surfacePeerVoiceRequest])
        }

        next.status = .negotiating
        next.voiceSessionId = id
        next.remoteDescriptionApplied = true
        next.heldRemoteOffer = nil
        next.negotiationControlGeneration = owner
        // The peer's offer answered the pending gap press: consumed, exactly as an establishment
        // via `start` consumes it (ADR-020 Amendment A11).
        next.pendingStartIntent = false
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
                .sendVoiceState(
                    voiceSessionId: id,
                    state: .connecting,
                    micMuted: state.micMuted,
                    mode: state.mode,
                    controlGeneration: state.negotiationControlGeneration
                ),
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
        _ fresh: VoiceSessionId,
        _ owner: Int64
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
            return peerWantsVoice(observed, fresh, owner)
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
                // An involuntary teardown is exactly a link loss in this respect (ADR-020 Amendment
                // A11): consent survives and so does a pending gap-press intent, for the same
                // reason — the peer may come back within this ride segment. The recorded lifetime
                // survives too: the *control* link is untouched by a peer's voice teardown.
                peerReportedState: state.peerReportedState,
                micMuted: state.micMuted,
                mode: state.mode,
                pendingStartIntent: state.pendingStartIntent,
                authenticatedControlGeneration: state.authenticatedControlGeneration
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
        _ fresh: VoiceSessionId,
        _ owner: Int64
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
        next.negotiationControlGeneration = owner
        // Glare establishment consumes the intent for the same reason `offerReceived`'s does.
        next.pendingStartIntent = false
        return VoiceOutcome(
            state: next,
            actions: [
                .sendVoiceState(
                    voiceSessionId: fresh,
                    state: .negotiating,
                    micMuted: state.micMuted,
                    mode: state.mode,
                    controlGeneration: owner
                ),
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
