import Foundation

/// The decisions the Phase 5 closure audits (ADR-024 Amendments A1 and A2) moved out of the two
/// coordinators and into pure, mirrored, vector-pinned tables — pinned by
/// `protocol/vectors/phase5-gates/`, and mirroring `com.ridelink.core.playback.Phase5Gates` line
/// for line. A1 contributed three (`Phase5Ingress`, `PendingCommandGate`, `PendingPlayGate`); A2
/// added two more at the bottom of this file (`OutboundCommitGate`, `AuthoritativeHoldGate`).
///
/// They exist for the reason CLAUDE.md rule 18 gives and ADR-019 taught: a distributed rule that
/// lives inside a coordinator is a rule no vector can pin, and both audits found every one of these
/// rules living inside coordinator control flow on both platforms, each already divergent in some
/// detail. Nothing here reads a clock, touches a player or knows what a session is.

/// What the bounded post-TCP inbound handoff does with an arriving Phase 5 frame
/// (Amendment A1 Finding C).
///
/// The handoff exists because the control read loop must not block and must not reorder: one bounded
/// queue, one consumer, arrival order preserved. What it must **not** do is silently lose an
/// authoritative command that reliable ordered TCP already delivered — which is exactly what
/// `.bufferingNewest` / `BufferOverflow.DROP_OLDEST` did before this amendment.
public enum IngressAdmission: String, Sendable, Equatable, CaseIterable {
    /// There was room. Append and preserve arrival order.
    case admit = "ADMIT"

    /// The queue is full, and this frame is a `latestWins` frame that already has an older sibling
    /// queued. Replace the sibling with this frame at *this* frame's arrival position: the older one
    /// carried strictly less information than the newer, so nothing is lost.
    case coalesce = "COALESCE"

    /// The queue is full of frames that cannot be superseded. **Nothing is dropped silently:** the
    /// caller counts this, refuses to apply further incremental frames, and waits for authoritative
    /// full state (PROTOCOL §5's `PLAYBACK_STATE`, §9's `QUEUE_SNAPSHOT`) or a session boundary
    /// before trusting incremental state again.
    case overflow = "OVERFLOW"
}

/// Whether a Phase 5 frame carries information a strictly newer frame of the same kind cannot
/// replace.
///
/// The split is a property of PROTOCOL §5/§9, not a convenience: `POSITION_REPORT` produces one
/// diagnostics number, `PLAYBACK_STATE` is by §5's own words "the full authoritative snapshot… the
/// reconciliation anchor, not an incremental update", and §9's `QUEUE_SNAPSHOT` is the one where
/// "the snapshot always wins". For all three, applying only the newest of a run reaches the same
/// state as applying every one of them in order. A command is the opposite: `PAUSE` after a dropped
/// `PLAY` is a coherent-looking frame describing a track that was never loaded.
public enum Phase5FrameKind: String, Sendable, Equatable, CaseIterable {
    /// `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS` and `QUEUE_ADD`/`QUEUE_REMOVE`/`QUEUE_MOVE`.
    case command = "COMMAND"

    /// `POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`.
    case latestWins = "LATEST_WINS"
}

public enum Phase5Ingress {
    /// - Parameters:
    ///   - queuedTotal: how many frames the queue already holds.
    ///   - capacity: the queue's bound. Injectable so a deterministic test can force overflow at 1
    ///     or 2 rather than racing 256 frames against a sleep (Amendment A1's test rule).
    ///   - hasQueuedSameKind: whether a frame of exactly this `Phase5FrameKind` variant is already
    ///     queued — only meaningful for `.latestWins`, and only consulted when full.
    public static func decide(
        kind: Phase5FrameKind,
        queuedTotal: Int,
        capacity: Int,
        hasQueuedSameKind: Bool
    ) -> IngressAdmission {
        if capacity <= 0 { return .overflow }
        if queuedTotal < capacity { return .admit }
        if kind == .latestWins, hasQueuedSameKind { return .coalesce }
        return .overflow
    }
}

/// What a receiver does with an authoritative command `CommandOrderGate` has already accepted, given
/// whether the session clock is trustworthy right now (Amendment A1 Finding D).
///
/// Before this amendment the receiver recorded the command as applied and *then* consulted the
/// clock, so an unready estimator turned an accepted command into a permanently lost one: the
/// sequence number was spent, so the leader's replay of it was a duplicate, and nothing ever applied
/// it. "Accepted for ordering" and "applied" are different facts and now have different fields.
public enum CommandAdmission: String, Sendable, Equatable, CaseIterable {
    /// The clock is trusted and nothing is queued ahead. Schedule it now.
    case apply = "APPLY"

    /// Hold it, in authoritative order, until the clock is trusted. Deliberately also the answer
    /// when the clock *is* ready but something is already deferred: `PLAY(n)` then `PAUSE(n+1)` must
    /// not become `PAUSE` alone because the clock happened to converge between the two.
    case defer_ = "DEFER"

    /// The deferred buffer is full. Refused explicitly and counted — the same halt-and-reconcile
    /// posture as `IngressAdmission.overflow`, never a silent drop.
    case overflow = "OVERFLOW"
}

public enum PendingCommandGate {
    public static func decide(clockReady: Bool, deferredCount: Int, capacity: Int) -> CommandAdmission {
        if deferredCount >= capacity { return .overflow }
        if !clockReady || deferredCount > 0 { return .defer_ }
        return .apply
    }
}

/// Whether a retained synchronised-play request may be turned into a command yet
/// (Amendment A1 Findings A and E).
///
/// One press of Play is one logical user action, and it has to survive two waits that Phase 5 as
/// shipped did not survive at all:
///
/// - **the queue** — a follower that adds a track and immediately sends `PLAY` sends it carrying the
///   revision it held *before* the leader accepted the add, so the leader refused its own valid
///   first `PLAY` for a stale revision and the user had to press twice (Finding A);
/// - **the content** — a track only the peer holds requests a Phase 4 transfer and then, before this
///   amendment, simply forgot the request, so the user had to press again after the download
///   finished (Finding E).
///
/// Neither wait is resolved by weakening the revision rule or by starting playback early. The
/// request is held, and this table decides what to do each time one of its preconditions changes.
public enum PendingPlayDecision: String, Sendable, Equatable, CaseIterable {
    /// Every precondition holds. Issue the command (leader) or the intent (follower) exactly once.
    case issue = "ISSUE"

    /// The queue item this play names is not yet in the authoritative queue. Keep waiting.
    case waitForQueue = "WAIT_FOR_QUEUE"

    /// REQUIREMENTS §9.4 / PROTOCOL §5 rule 4: not yet playable on both phones. Keep waiting.
    case waitForContent = "WAIT_FOR_CONTENT"

    /// Drop the request and never resurrect it. A superseded request, a session boundary or leaving
    /// synchronised mode all land here — which is why the caller fences the request with an
    /// `OperationFence` token rather than keying it on `content_hash`: the same track can
    /// legitimately be asked for again in a later epoch (brief §32/§18).
    case cancel = "CANCEL"
}

public enum PendingPlayGate {
    /// - Parameters:
    ///   - operationCurrent: the request still owns its `OperationFence` token — false once a newer
    ///     Play superseded it.
    ///   - sessionCurrent: the authentication generation the request was made under is still live
    ///     (ADR-023 §3).
    ///   - syncEnabled: synchronised mode has not been left since the request (brief §38).
    ///   - queueSettled: the request's `queue_item_id` is present in the authoritative queue.
    ///   - localContentReady: this device can play it *now* — a Phase 3 library row or a Phase 4
    ///     **verified, committed** cache entry, never a download that merely reported complete.
    ///   - peerContentRequired: whether the peer half of brief §19's gate is *this* device's
    ///     question. It is the **leader's**, because the leader is the one about to name an instant
    ///     at which both phones become audible. It is deliberately **not** a follower's: PROTOCOL §5
    ///     rule 4 makes requesting the transfer the leader's job, and the leader cannot do that job
    ///     without receiving the intent — so a follower that gated on the peer half would silently
    ///     withhold the one message that unblocks it.
    ///   - peerHasContent: the peer half itself. Ignored unless `peerContentRequired`.
    public static func decide(
        operationCurrent: Bool,
        sessionCurrent: Bool,
        syncEnabled: Bool,
        queueSettled: Bool,
        localContentReady: Bool,
        peerContentRequired: Bool,
        peerHasContent: Bool
    ) -> PendingPlayDecision {
        if !operationCurrent || !sessionCurrent || !syncEnabled { return .cancel }
        if !queueSettled { return .waitForQueue }
        if !localContentReady { return .waitForContent }
        if peerContentRequired, !peerHasContent { return .waitForContent }
        return .issue
    }
}

/// Amendment A1 bounds, injectable at every call site so a test can force the edge deterministically.
public enum Phase5GateBounds {
    /// The inbound handoff's default bound. Unchanged from the value Phase 5 shipped with; what
    /// changed is that reaching it is now an explicit, counted, reconcilable failure rather than a
    /// silent eviction.
    public static let defaultInboundCapacity = 256

    /// How many authoritative commands may wait for a trustworthy clock. Small on purpose: an
    /// estimator that has not converged after this many commands is not about to, and the honest
    /// answer is the explicit halt rather than an ever-growing buffer of stale deadlines.
    public static let defaultDeferredCommandCapacity = 16

    /// How often a receiver re-checks whether the clock has become trustworthy while commands wait.
    public static let deferredRetryIntervalUs: Int64 = 100_000

    /// The one ordered **outbound** path's bound (Amendment A2). Generous relative to what one
    /// device can generate — a cadence tick every 5 s plus whatever two people press — so reaching
    /// it means the control socket is not draining. Reaching it is now an explicit refusal that
    /// fails an authoritative operation closed rather than a counter nobody consults.
    public static let defaultOutboundCapacity = 256
}

// MARK: - ADR-024 Amendment A2 (the second Phase 5 closure audit)
//
// Two more decisions the A2 audit found living inside coordinator control flow on both platforms,
// each already a silent divergence risk. Same reason as the three above (CLAUDE.md rule 18): a
// distributed rule a vector cannot pin is a rule the two phones will eventually disagree about.

/// What a Phase 5 frame this device is *sending* is authorised to change locally
/// (Amendment A2 Findings A and C).
///
/// The distinction is the whole of A2: a leader's `PLAY` or `QUEUE_SNAPSHOT` **is** authority — the
/// local effect and the peer's copy are two halves of one fact — while a follower's intent and a
/// cadence report are not. Losing the first silently is a divergence; losing either of the others
/// is a missed button press or a missed diagnostics sample.
public enum OutboundAuthority: String, Sendable, Equatable, CaseIterable {
    /// A leader-stamped command or the `QUEUE_SNAPSHOT` that carries a new `queue_revision`. Its
    /// local effect may not be committed unless the frame actually reached the transport.
    case authoritative = "AUTHORITATIVE"

    /// A follower's `command_seq: 0` intent, or a queue mutation sent as one (ADR-024 §3). The
    /// follower owns no authority, so there is nothing to roll back — but a failure is still not a
    /// send.
    case intent = "INTENT"

    /// `POSITION_REPORT`, and a `PLAYBACK_STATE`/`QUEUE_SNAPSHOT` that re-states authority the peer
    /// has already been told about. Latest-wins by PROTOCOL §5/§9's own words: the next one
    /// subsumes the one that failed.
    case advisory = "ADVISORY"
}

/// What actually became of an outbound Phase 5 frame (Amendment A2 Findings A, B and C).
public enum OutboundOutcome: String, Sendable, Equatable, CaseIterable {
    /// The authenticated transport accepted it: `send` returned true. The **only** success.
    case sent = "SENT"

    /// The one bounded ordered outbound path refused it. Produced by the *producer*, before the
    /// frame ever reaches the transport (Finding A). Never a silent drop.
    case admissionRefused = "ADMISSION_REFUSED"

    /// The authenticated session that authorised this frame is no longer the live one — or its
    /// Phase 5 authority has already been abandoned. Finding B: the frame carries the generation
    /// that authorised it, and a Session A frame is never written using Session B's writer.
    case staleSession = "STALE_SESSION"

    /// `send` returned false: no authenticated writer, or the write itself failed (Finding C).
    case transportFailed = "TRANSPORT_FAILED"
}

/// What the producer of an outbound frame must do with the local state it was about to commit.
public enum OutboundCommit: String, Sendable, Equatable, CaseIterable {
    /// The peer has it. Commit the sequence number, the revision and the local audible effect.
    case commit = "COMMIT"

    /// Authority did not reach the peer. Commit **nothing**, and end Phase 5 authority for this
    /// generation rather than continuing from a state only this device knows about.
    case abortFailClosed = "ABORT_FAIL_CLOSED"

    /// Nothing was committed in the first place. Count it and carry on — a follower's undelivered
    /// intent is a button press that did not happen, and an undelivered cadence report is one
    /// missing sample.
    case abortQuiet = "ABORT_QUIET"
}

public enum OutboundCommitGate {
    /// Amendment A2's central rule in four lines: **an authoritative operation commits locally only
    /// when the frame representing it was actually sent.** Admission is not delivery, and a `send`
    /// that returned false is not a send.
    public static func decide(authority: OutboundAuthority, outcome: OutboundOutcome) -> OutboundCommit {
        if outcome == .sent { return .commit }
        if authority == .authoritative { return .abortFailClosed }
        return .abortQuiet
    }
}

/// Whether an authoritative **state** frame may be applied now, or must wait its turn behind
/// authoritative work already held (Amendment A2 Finding D).
///
/// Amendment A1 held a command whose clock was not yet trustworthy. It did not hold anything else —
/// so a `QUEUE_SNAPSHOT` that arrived *after* a held `NEXT` was applied *before* it, and the `NEXT`
/// then resolved against a queue revision it was never authored against. The leader's semantic
/// stream was reordered by the receiver, which is the exact thing `command_seq` and `queue_revision`
/// exist to prevent.
///
/// The rule is therefore not "re-check the revision at drain time and drop the command" — that would
/// lose an authoritative operation, which is Finding A of A1 all over again. It is **nothing may
/// overtake held authoritative work**: once anything is held, every authoritative frame whose
/// semantics could matter joins the queue behind it and the whole stream replays in arrival order.
///
/// `POSITION_REPORT` is deliberately *not* subject to this: it produces one diagnostics number and
/// can change no command's meaning, so holding it would buy nothing and cost the bound.
public enum HoldAdmission: String, Sendable, Equatable, CaseIterable {
    /// Nothing is held, so nothing can be overtaken. Apply it now.
    case processNow = "PROCESS_NOW"

    /// Authoritative work is already waiting. Join the queue behind it, in arrival order.
    case hold = "HOLD"

    /// More authoritative work is outstanding than may be held. The same explicit
    /// halt-and-reconcile posture as `IngressAdmission.overflow` and `CommandAdmission.overflow` —
    /// never an eviction, and never an out-of-order application.
    case overflow = "OVERFLOW"
}

public enum AuthoritativeHoldGate {
    /// - Parameters:
    ///   - heldCount: how many authoritative events are already held, in arrival order.
    ///   - capacity: the same bound `PendingCommandGate` uses, so one buffer has one bound.
    public static func decide(heldCount: Int, capacity: Int) -> HoldAdmission {
        if heldCount <= 0 { return .processNow }
        if heldCount >= capacity { return .overflow }
        return .hold
    }
}
