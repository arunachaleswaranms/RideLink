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
    case waitingForContent = "WAITING_FOR_CONTENT"
    /// An authoritative command is scheduled and its deadline has not arrived.
    case scheduled = "SCHEDULED"
    /// Both phones are tracking the same authoritative timeline.
    case synced = "SYNCED"
    /// ARCHITECTURE §7.3's fourth tier: >2 s of drift, or three hard seeks in 60 s. Correction has
    /// stopped, the playback rate is back to exactly 1.0, and **local music keeps playing** (FR-025).
    case syncFailed = "SYNC_FAILED"
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

    public init() {}
}
