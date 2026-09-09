package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.playback.PlaybackRole

/**
 * The explicit boundary between LOCAL playback and SYNCHRONIZED peer playback this phase's brief §40
 * requires. Phase 3 playback is unaffected by every value except [SYNCED] and [SCHEDULED] — a peer
 * that never connects leaves this at [INACTIVE] forever and the local player behaves exactly as it
 * did before Phase 5 existed.
 */
enum class SyncState {
    /** No synchronised session, or the user has not started one. Local playback only. */
    INACTIVE,

    /**
     * A session exists but the clock estimator has no accepted offset, or has an unconfirmed step
     * (ARCHITECTURE §7.1 rule 5). **No synchronised command is issued against a dubious clock**
     * (brief §7/§41). Local playback stays usable; it is simply not synchronised.
     */
    CLOCK_UNREADY,

    /**
     * REQUIREMENTS §9.4: the selected track is not yet playable on both phones. The existing Phase 4
     * transfer machinery is what closes this (brief §20); Phase 5 only waits.
     *
     * ADR-024 Amendment A1 Finding E: **the wait now ends by itself.** The one Play the user
     * pressed is retained, fenced to its session and operation token, and issued automatically once
     * the verified cache reports the content — never by asking the user to press Play again.
     */
    WAITING_FOR_CONTENT,

    /**
     * ADR-024 Amendment A1 Finding A: a synchronised Play was pressed for a track that was not yet
     * in the **authoritative** shared queue, so the request is waiting for the leader's
     * `QUEUE_SNAPSHOT` to name it.
     *
     * This state is what replaced the defect: a follower used to send `QUEUE_ADD` and `PLAY` back to
     * back, so the `PLAY` carried the revision it held *before* the add was accepted and the leader
     * refused its own valid first `PLAY` for a stale revision. The revision rule did not move; the
     * Play waits for the revision it needs.
     */
    WAITING_FOR_QUEUE,

    /** An authoritative command is scheduled and its deadline has not arrived. */
    SCHEDULED,

    /** Both phones are tracking the same authoritative timeline. */
    SYNCED,

    /**
     * ARCHITECTURE §7.3's fourth tier: >2 s of drift, or three hard seeks in 60 s. Correction has
     * stopped, the playback rate is back to exactly 1.0, and **local music keeps playing** (FR-025).
     */
    SYNC_FAILED,

    /**
     * ADR-024 Amendment A1 Finding C/D: the bounded post-TCP ingress overflowed, or more
     * authoritative commands are waiting for a trustworthy clock than may be held. **Incremental
     * Phase 5 state is no longer trusted** — no further command is applied — until authoritative
     * full state arrives (PROTOCOL §5's `PLAYBACK_STATE`, §9's `QUEUE_SNAPSHOT`) or the session
     * ends.
     *
     * It is deliberately distinct from [SYNC_FAILED]: that one means correction gave up on a
     * timeline both phones agree about, this one means we may no longer know what the timeline *is*.
     * Local music keeps playing either way (ADR-004, FR-025).
     */
    DESYNCHRONIZED,

    /**
     * ADR-024 Amendment A2: an **authoritative** frame this device produced never reached the peer —
     * the one ordered outbound path refused it, or the authenticated write returned false — so this
     * device stopped issuing authority rather than continuing from a state only it knows about.
     *
     * The three states above are all about what *arrives*; this one is about what *leaves*. It is
     * latched for the whole authentication generation and cleared only by a new session, because
     * there is no protocol message that re-synchronises a peer which never learned of a command (see
     * ADR-024 Amendment A2 §H on `STATE_REQUEST`).
     *
     * Synchronised mode is left when it latches, so the transport controls go straight back to
     * Phase 3 behaviour and the user keeps control of their own music. **Local music keeps playing**
     * (ADR-004, FR-025), exactly as for every other failure in this enum.
     */
    TRANSPORT_FAILED,
}

/** What the ladder last decided, for the FR-023 diagnostics surface. */
enum class SyncCorrection { NONE, NUDGE, RESTORE_RATE, HARD_SEEK, SYNC_FAILED }

/**
 * FR-023's Phase 5 half. Every field is either a measurement or a count of something refused —
 * nothing here is a claim about audio, and nothing here is derived from a wall clock.
 *
 * Redaction: this object carries no `peer_id`, no path, no token and no SAS, so it needs none. The
 * one identity it does carry is a [ContentHash], which CLAUDE.md's redaction table deliberately does
 * not list — a music file's hash names no peer and no secret.
 */
data class SyncPlaybackDiagnostics(
    val role: PlaybackRole? = null,
    val syncState: SyncState = SyncState.INACTIVE,
    val clockReady: Boolean = false,
    val clockOffsetUs: Long? = null,
    val rttP95Us: Long? = null,
    val leadUs: Long? = null,
    val lastAppliedCommandSeq: Long? = null,
    val nextCommandSeq: Long? = null,
    val lateCommandCount: Int = 0,
    val duplicateCommandCount: Int = 0,
    val staleCommandCount: Int = 0,
    val roleViolationCount: Int = 0,
    val staleRevisionCount: Int = 0,
    val queueRevision: Long = 0,
    val queueSize: Int = 0,
    val currentTrackHash: ContentHash? = null,
    /** This device's own drift against the authoritative timeline — the figure the ladder acts on. */
    val localDriftMs: Long? = null,
    /** The peer's drift against the **same** authoritative timeline, from its `POSITION_REPORT`. Diagnostics only. */
    val peerDriftMs: Long? = null,
    val lastCorrection: SyncCorrection = SyncCorrection.NONE,
    val playbackRate: Double = 1.0,
    val hardSeekCount: Int = 0,
    /** Measured `actual - deadline` for the last scheduled start, in microseconds. Software scheduling error only. */
    val lastScheduleErrorUs: Long? = null,
    val routeTransitioning: Boolean = false,
    /** ADR-023 §3's authentication generation. A Phase 5 event tagged with an older one is inert. */
    val sessionGeneration: Long = 0,
    /**
     * How many PROTOCOL §5 cadence ticks have completed — one report sent and one ladder decision
     * applied. A real FR-023 figure (a stalled counter means correction has stopped, which is worth
     * seeing), and the precise completion signal a test needs instead of guessing how many scheduler
     * turns a tick takes. Mirrors `RideLinkPlatform.SyncPlaybackDiagnostics.correctionTickCount`,
     * where a stress run found that guessing is a ~7 % flake.
     */
    val correctionTickCount: Int = 0,
    /**
     * How many inbound Phase 5 frames this coordinator has finished considering — applied, or
     * deliberately refused as duplicate/stale/role-violating. A real FR-023 figure, and the precise
     * signal a test needs instead of guessing how many scheduler turns a frame takes.
     */
    val inboundProcessedCount: Int = 0,
    /**
     * How many inbound Phase 5 frames the bounded handoff refused because it was full of frames
     * that cannot be superseded (ADR-024 Amendment A1 Finding C).
     *
     * **This counter could not previously increment at all.** Phase 5 shipped with
     * `BufferOverflow.DROP_OLDEST`, and `trySend` on such a channel returns *success* — so every
     * eviction was silent and this figure was structurally always zero. Nothing is evicted now; a
     * refusal is returned to the caller, counted here, and latches [ingressDesynchronized].
     */
    val inboundOverflowCount: Int = 0,
    /**
     * How many latest-wins frames (`POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`) were
     * coalesced onto a newer sibling because the handoff was full. Lossless by construction —
     * applying only the newest of such a run reaches the same state — and the reason
     * [inboundOverflowCount] stays at zero under a peer's ordinary 5 s report cadence.
     */
    val inboundCoalescedCount: Int = 0,
    /**
     * True while incremental Phase 5 state is not trusted: the ingress overflowed, or the deferred
     * command buffer did. Cleared only by authoritative full state or a session boundary — never by
     * time passing, and never by guessing.
     */
    val ingressDesynchronized: Boolean = false,
    /**
     * The highest `command_seq` this device has taken *responsibility* for — applied, or accepted
     * and still held pending a trustworthy clock. Distinct from [lastAppliedCommandSeq], and the
     * distinction is ADR-024 Amendment A1 Finding D: recording an accepted command as *applied*
     * before the clock was known to be trustworthy spent its sequence number, so the leader's
     * replay of it became a duplicate and nothing ever applied it.
     */
    val lastReceivedCommandSeq: Long? = null,
    /**
     * How many authoritative events are held, in **arrival order**, waiting for a trustworthy clock.
     *
     * ADR-024 Amendment A2 Finding D widened this from commands alone: once a command is held,
     * every later authoritative frame whose semantics could change that command's meaning — a
     * `QUEUE_SNAPSHOT`, a `PLAYBACK_STATE` — is held behind it too, so the leader's semantic stream
     * replays in the order the leader chose. A `POSITION_REPORT` is deliberately never held.
     */
    val deferredCommandCount: Int = 0,
    /** How many held commands were applied once the clock became trustworthy again. */
    val recoveredCommandCount: Int = 0,
    /**
     * How many outbound Phase 5 frames this device could not hand to its own ordered outbound queue
     * because that queue was full (ADR-024 Amendment A1 Finding B). Locally produced, so a nonzero
     * value means the control socket is wedged, never a pathological peer.
     *
     * ADR-024 Amendment A2 Finding A: this is **admission refusal**, and it is no longer only a
     * count. An authoritative operation whose frame is refused here commits nothing — no
     * `command_seq`, no `queue_revision`, no local audible effect — and latches
     * [outboundAuthorityLost].
     */
    val outboundOverflowCount: Int = 0,
    /** How many frames have been accepted onto the one ordered outbound path. */
    val outboundEnqueuedCount: Int = 0,
    /**
     * How many admitted frames the single writer has **tried** to send (ADR-024 Amendment A2
     * Finding C). Exactly [outboundSentCount] + [outboundFailedCount] + [outboundStaleCount].
     */
    val outboundAttemptCount: Int = 0,
    /**
     * How many frames the authenticated transport actually accepted — `send` returned **true**, and
     * nothing weaker.
     *
     * Amendment A2 Finding C: this used to increment for every dequeued frame, including ones the
     * write had just refused, so `outboundSentCount == outboundEnqueuedCount` could be reported
     * while frames had been silently discarded. The gap between this and [outboundEnqueuedCount] is
     * a real FR-023 figure — a persistent one means the control socket is not draining — and it is
     * also the precise signal a test needs to know the wire has caught up, instead of guessing how
     * many scheduler turns a send takes.
     */
    val outboundSentCount: Int = 0,
    /**
     * How many frames the authenticated transport refused: no live authenticated writer, or the
     * write itself threw (ADR-024 Amendment A2 Finding C). For an authoritative frame this is a
     * fail-closed event, not a statistic.
     */
    val outboundFailedCount: Int = 0,
    /**
     * How many frames were **never written** because the authentication generation that authorised
     * them was no longer the live one, or its Phase 5 authority had already been abandoned
     * (ADR-024 Amendment A2 Finding B).
     *
     * A nonzero value is the session-confusion class Phase 4 Amendments A3/A5 hardened against,
     * caught at the boundary rather than written under the new session's `session_id`.
     */
    val outboundStaleCount: Int = 0,
    /**
     * ADR-024 Amendment A2: an authoritative frame this device produced never reached the peer, so
     * Phase 5 authority is over for this authentication generation. Latched until a new session;
     * see [SyncState.TRANSPORT_FAILED].
     */
    val outboundAuthorityLost: Boolean = false,
    /**
     * How many retained one-press synchronised Plays were issued automatically once their queue
     * revision or their content arrived (ADR-024 Amendment A1 Findings A and E) — the figure that
     * distinguishes "the user pressed Play once and it worked" from "the user pressed Play twice".
     */
    val resumedPendingPlayCount: Int = 0,
    /** How many retained Plays were dropped by supersession, a session boundary or leaving sync mode. */
    val cancelledPendingPlayCount: Int = 0,
)
