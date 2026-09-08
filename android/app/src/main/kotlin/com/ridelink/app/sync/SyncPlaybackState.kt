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
     */
    WAITING_FOR_CONTENT,

    /** An authoritative command is scheduled and its deadline has not arrived. */
    SCHEDULED,

    /** Both phones are tracking the same authoritative timeline. */
    SYNCED,

    /**
     * ARCHITECTURE §7.3's fourth tier: >2 s of drift, or three hard seeks in 60 s. Correction has
     * stopped, the playback rate is back to exactly 1.0, and **local music keeps playing** (FR-025).
     */
    SYNC_FAILED,
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
    /** Frames dropped because the bounded inbound channel was full. Nonzero means a pathological peer. */
    val droppedInboundCount: Int = 0,
)
