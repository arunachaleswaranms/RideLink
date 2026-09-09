import Foundation
import RideLinkCore

/// The explicit boundary between LOCAL playback and SYNCHRONIZED peer playback this phase's brief
/// §40 requires. Phase 3 playback is unaffected by every value except `synced` and `scheduled` — a
/// peer that never connects leaves this at `inactive` forever and the local player behaves exactly
/// as it did before Phase 5 existed.
public enum SyncState: String, Sendable, Equatable {
    /// No synchronised session, or the user has not started one. Local playback only.
    case inactive = "INACTIVE"
    /// A session exists but the clock estimator has no accepted offset, or has an unconfirmed step
    /// (ARCHITECTURE §7.1 rule 5). **No synchronised command is issued against a dubious clock.**
    case clockUnready = "CLOCK_UNREADY"
    /// REQUIREMENTS §9.4: the selected track is not yet playable on both phones.
    ///
    /// ADR-024 Amendment A1 Finding E: **the wait now ends by itself.** The one Play the user
    /// pressed is retained, fenced to its session and operation token, and issued automatically once
    /// the verified cache reports the content — never by asking the user to press Play again.
    case waitingForContent = "WAITING_FOR_CONTENT"
    /// ADR-024 Amendment A1 Finding A: a synchronised Play was pressed for a track that was not yet
    /// in the **authoritative** shared queue, so the request is waiting for the leader's
    /// `QUEUE_SNAPSHOT` to name it. The revision rule did not move; the Play waits for the revision
    /// it needs, instead of being refused and silently costing the user a second press.
    case waitingForQueue = "WAITING_FOR_QUEUE"
    /// An authoritative command is scheduled and its deadline has not arrived.
    case scheduled = "SCHEDULED"
    /// Both phones are tracking the same authoritative timeline.
    case synced = "SYNCED"
    /// ARCHITECTURE §7.3's fourth tier: >2 s of drift, or three hard seeks in 60 s. Correction has
    /// stopped, the playback rate is back to exactly 1.0, and **local music keeps playing** (FR-025).
    case syncFailed = "SYNC_FAILED"
    /// ADR-024 Amendment A1 Finding C/D: the bounded post-TCP ingress overflowed, or more
    /// authoritative commands are waiting for a trustworthy clock than may be held. **Incremental
    /// Phase 5 state is no longer trusted** — no further command is applied — until authoritative
    /// full state arrives (PROTOCOL §5's `PLAYBACK_STATE`, §9's `QUEUE_SNAPSHOT`) or the session
    /// ends.
    ///
    /// Deliberately distinct from `syncFailed`: that one means correction gave up on a timeline both
    /// phones agree about, this one means we may no longer know what the timeline *is*. Local music
    /// keeps playing either way (ADR-004, FR-025).
    case desynchronized = "DESYNCHRONIZED"

    /// ADR-024 Amendment A2: an **authoritative** frame this device produced never reached the peer
    /// — the one ordered outbound path refused it, or the authenticated write returned false — so
    /// this device stopped issuing authority rather than continuing from a state only it knows
    /// about.
    ///
    /// The three states above are all about what *arrives*; this one is about what *leaves*. It is
    /// latched for the whole authentication generation and cleared only by a new session, because
    /// there is no protocol message that re-synchronises a peer which never learned of a command
    /// (see ADR-024 Amendment A2 §H on `STATE_REQUEST`).
    ///
    /// Synchronised mode is left when it latches, so the transport controls go straight back to
    /// Phase 3 behaviour and the user keeps control of their own music. **Local music keeps
    /// playing** (ADR-004, FR-025), exactly as for every other failure in this enum.
    case transportFailed = "TRANSPORT_FAILED"
}

/// What the ladder last decided, for the FR-023 diagnostics surface.
public enum SyncCorrection: String, Sendable, Equatable {
    case none = "NONE"
    case nudge = "NUDGE"
    case restoreRate = "RESTORE_RATE"
    case hardSeek = "HARD_SEEK"
    case syncFailed = "SYNC_FAILED"
}

/// FR-023's Phase 5 half. Every field is either a measurement or a count of something refused —
/// nothing here is a claim about audio, and nothing here is derived from a wall clock.
///
/// Redaction: this carries no `peer_id`, no path, no token and no SAS, so it needs none. The one
/// identity it does carry is a `ContentHash`, which CLAUDE.md's redaction table deliberately does
/// not list — a music file's hash names no peer and no secret.
public struct SyncPlaybackDiagnostics: Sendable, Equatable {
    public var role: PlaybackRole?
    public var syncState: SyncState = .inactive
    public var clockReady = false
    public var clockOffsetUs: Int64?
    public var rttP95Us: Int64?
    public var leadUs: Int64?
    public var lastAppliedCommandSeq: Int64?
    public var nextCommandSeq: Int64?
    public var lateCommandCount = 0
    public var duplicateCommandCount = 0
    public var staleCommandCount = 0
    public var roleViolationCount = 0
    public var staleRevisionCount = 0
    public var queueRevision: Int64 = 0
    public var queueSize = 0
    public var currentTrackHash: ContentHash?
    /// This device's own drift against the authoritative timeline — the figure the ladder acts on.
    public var localDriftMs: Int64?
    /// The peer's drift against the **same** authoritative timeline, from its `POSITION_REPORT`.
    /// Diagnostics only; it is never a correction input (brief §33).
    public var peerDriftMs: Int64?
    public var lastCorrection: SyncCorrection = .none
    public var playbackRate = 1.0
    public var hardSeekCount = 0
    /// Measured `actual - deadline` for the last scheduled start, in microseconds. Software
    /// scheduling error only — it says nothing about audible alignment.
    public var lastScheduleErrorUs: Int64?
    public var routeTransitioning = false
    /// ADR-023 §3's authentication generation. A Phase 5 event tagged with an older one is inert.
    public var sessionGeneration: Int64 = 0
    /// How many PROTOCOL §5 cadence ticks have completed — one report sent and one ladder decision
    /// applied. A real FR-023 figure (a stalled counter means correction has stopped, which is worth
    /// seeing), and the precise completion signal a test needs instead of guessing how many
    /// scheduler yields a tick takes.
    public var correctionTickCount = 0
    /// How many inbound Phase 5 frames this coordinator has finished considering — applied, or
    /// deliberately refused as duplicate/stale/role-violating. A real FR-023 figure, and the precise
    /// signal a test needs instead of guessing how many scheduler turns a frame takes.
    public var inboundProcessedCount = 0
    /// How many inbound Phase 5 frames the bounded handoff refused because it was full of frames
    /// that cannot be superseded (ADR-024 Amendment A1 Finding C). Nothing is evicted now; a
    /// refusal is returned to the caller, counted here, and latches `ingressDesynchronized`.
    public var inboundOverflowCount = 0
    /// How many latest-wins frames (`POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`) were
    /// coalesced onto a newer sibling because the handoff was full. Lossless by construction, and
    /// the reason `inboundOverflowCount` stays at zero under a peer's ordinary 5 s report cadence.
    public var inboundCoalescedCount = 0
    /// True while incremental Phase 5 state is not trusted. Cleared only by authoritative full state
    /// or a session boundary — never by time passing, and never by guessing.
    public var ingressDesynchronized = false
    /// The highest `command_seq` this device has taken *responsibility* for — applied, or accepted
    /// and still held pending a trustworthy clock. Distinct from `lastAppliedCommandSeq`, and the
    /// distinction is ADR-024 Amendment A1 Finding D: recording an accepted command as *applied*
    /// before the clock was known to be trustworthy spent its sequence number, so the leader's
    /// replay of it became a duplicate and nothing ever applied it.
    public var lastReceivedCommandSeq: Int64?
    /// How many authoritative events are held, in **arrival order**, waiting for a trustworthy
    /// clock.
    ///
    /// ADR-024 Amendment A2 Finding D widened this from commands alone: once a command is held,
    /// every later authoritative frame whose semantics could change that command's meaning — a
    /// `QUEUE_SNAPSHOT`, a `PLAYBACK_STATE` — is held behind it too, so the leader's semantic stream
    /// replays in the order the leader chose. A `POSITION_REPORT` is deliberately never held.
    public var deferredCommandCount = 0
    /// How many held commands were applied once the clock became trustworthy again.
    public var recoveredCommandCount = 0
    /// How many outbound Phase 5 frames this device could not hand to its own ordered outbound
    /// queue because that queue was full (Amendment A1 Finding B). Locally produced, so a nonzero
    /// value means the control socket is wedged, never a pathological peer.
    ///
    /// ADR-024 Amendment A2 Finding A: this is **admission refusal**, and it is no longer only a
    /// count. An authoritative operation whose frame is refused here commits nothing — no
    /// `command_seq`, no `queue_revision`, no local audible effect — and latches
    /// `outboundAuthorityLost`.
    public var outboundOverflowCount = 0
    /// How many frames have been accepted onto the one ordered outbound path.
    public var outboundEnqueuedCount = 0
    /// How many admitted frames the single writer has **tried** to send (Amendment A2 Finding C).
    /// Exactly `outboundSentCount` + `outboundFailedCount` + `outboundStaleCount`.
    public var outboundAttemptCount = 0
    /// How many frames the authenticated transport actually accepted — `send` returned **true**, and
    /// nothing weaker.
    ///
    /// Amendment A2 Finding C: this used to increment for every dequeued frame, including ones the
    /// write had just refused, so `outboundSentCount == outboundEnqueuedCount` could be reported
    /// while frames had been silently discarded. The gap between this and `outboundEnqueuedCount` is
    /// a real FR-023 figure — a persistent one means the control socket is not draining — and it is
    /// also the precise signal a test needs to know the wire has caught up, instead of guessing how
    /// many scheduler turns a send takes.
    public var outboundSentCount = 0
    /// How many frames the authenticated transport refused: no live authenticated writer, or the
    /// write itself threw (Amendment A2 Finding C). For an authoritative frame this is a fail-closed
    /// event, not a statistic.
    public var outboundFailedCount = 0
    /// How many frames were **never written** because the authentication generation that authorised
    /// them was no longer the live one, or its Phase 5 authority had already been abandoned
    /// (Amendment A2 Finding B).
    ///
    /// A nonzero value is the session-confusion class Phase 4 Amendments A3/A5 hardened against,
    /// caught at the boundary rather than written under the new session's `session_id`.
    public var outboundStaleCount = 0
    /// ADR-024 Amendment A2: an authoritative frame this device produced never reached the peer, so
    /// Phase 5 authority is over for this authentication generation. Latched until a new session;
    /// see `SyncState.transportFailed`.
    public var outboundAuthorityLost = false
    /// How many retained one-press synchronised Plays were issued automatically once their queue
    /// revision or their content arrived (Amendment A1 Findings A and E) — the figure that
    /// distinguishes "the user pressed Play once and it worked" from "the user pressed Play twice".
    public var resumedPendingPlayCount = 0
    /// How many retained Plays were dropped by supersession, a session boundary or leaving sync mode.
    public var cancelledPendingPlayCount = 0

    public init() {}
}
