import Foundation

/// ARCHITECTURE §7.1's `session_time = local_mono + offset_to_leader`, and §7.2's scheduling lead.
/// Pure: no clock reads, no I/O (CLAUDE.md rule 9), and **no wall-clock arithmetic anywhere** —
/// every value here is monotonic or session microseconds, which is why the field names carry
/// `MonoUs` and `SessionUs` rather than a bare `time` (PROTOCOL §2 rule 5).
///
/// The session clock is *the leader's monotonic clock*. The leader's own offset is therefore exactly
/// zero and it needs no estimate to schedule; a follower's offset is `ClockSync`'s estimate of "add
/// this to my clock to get the peer's". There is one estimator (`ClockSync`) and one mapping (this),
/// and nothing else in the codebase may convert between the two timebases.
///
/// Mirrors Android `core.sync.SessionClock`; both run `protocol/vectors/session-clock/`.
public enum SessionClock {
    /// ARCHITECTURE §7.2 / ADR-004: the floor of `LEAD = max(120 ms, 4 x rtt_p95)`.
    public static let minLeadUs: Int64 = 120_000

    /// ARCHITECTURE §7.2 / ADR-004: the multiplier applied to `rtt_p95`.
    public static let leadRttMultiplier: Int64 = 4

    /// Defensive ceiling on the computed lead. A pathological `rtt_p95` (a peer stalled for seconds
    /// behind a saturated AP) would otherwise schedule a `PLAY` minutes into the future, which reads
    /// to the user as "the button did nothing". Capped instead, and the cap is a *scheduling*
    /// decision only — it never suppresses the command.
    public static let maxLeadUs: Int64 = 2_000_000

    /// `session_us = local_mono_us + offset_to_leader_us`. On the leader `offsetToLeaderUs` is 0 and
    /// this is the identity.
    public static func sessionUs(localMonoUs: Int64, offsetToLeaderUs: Int64) -> Int64 {
        localMonoUs + offsetToLeaderUs
    }

    /// The inverse of `sessionUs` — the conversion every scheduled deadline goes through.
    public static func localMonoUs(sessionUs: Int64, offsetToLeaderUs: Int64) -> Int64 {
        sessionUs - offsetToLeaderUs
    }

    /// `LEAD = max(120 ms, 4 x rtt_p95)`, clamped by `maxLeadUs`.
    ///
    /// A `nil` `rttP95Us` means no round trip has been measured yet, which yields the floor rather
    /// than a fabricated zero — the floor is the safe answer either way, and pretending 0 us of RTT
    /// had been *measured* is the kind of quiet lie ADR-016's `assumed`/`measured` split exists to
    /// prevent.
    public static func leadUs(rttP95Us: Int64?) -> Int64 {
        let rtt = max(rttP95Us ?? 0, 0)
        let scaled = rtt > maxLeadUs ? maxLeadUs : rtt * leadRttMultiplier
        return min(max(scaled, minLeadUs), maxLeadUs)
    }
}

/// What the session clock currently knows, as one immutable value the playback layer reads.
///
/// `ready` is the gate this phase's brief §7 requires: a synchronised command is never scheduled
/// against a clock the estimator has not accepted. It is **not** the same as "we have an offset" —
/// an unconfirmed 30 ms step (`ClockSync.WindowStatus.rejectedPendingConfirmation`) leaves the last
/// accepted offset in place for playback already in flight while refusing to authorise anything new,
/// which is exactly the distinction between "we have a number" and "we trust it".
///
/// - `offsetToLeaderUs`: add to local monotonic microseconds to get session microseconds; always
///   exactly 0 on the leader, which is why the leader is `ready` the moment it is elected.
public struct SessionClockEstimate: Sendable, Equatable {
    public let offsetToLeaderUs: Int64
    public let rttP95Us: Int64?
    public let ready: Bool

    public init(offsetToLeaderUs: Int64, rttP95Us: Int64?, ready: Bool) {
        self.offsetToLeaderUs = offsetToLeaderUs
        self.rttP95Us = rttP95Us
        self.ready = ready
    }

    public var leadUs: Int64 { SessionClock.leadUs(rttP95Us: rttP95Us) }

    public func sessionUs(localMonoUs: Int64) -> Int64 {
        SessionClock.sessionUs(localMonoUs: localMonoUs, offsetToLeaderUs: offsetToLeaderUs)
    }

    public func localMonoUs(sessionUs: Int64) -> Int64 {
        SessionClock.localMonoUs(sessionUs: sessionUs, offsetToLeaderUs: offsetToLeaderUs)
    }

    /// The leader's own view: the session clock *is* its monotonic clock, so no estimate is needed.
    public static func leader(rttP95Us: Int64?) -> SessionClockEstimate {
        SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: rttP95Us, ready: true)
    }
}
