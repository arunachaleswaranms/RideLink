import Foundation
import RideLinkCore

/// The session's single source of clock truth: `ClockSync`'s estimator state, its bounded RTT
/// window, and the `SessionClockEstimate` the Phase 5 playback layer schedules against.
///
/// **There is exactly one of these per control session, and no second RTT tracker anywhere.**
/// Mirrors Android `com.ridelink.network.control.SessionClockTracker`.
///
/// **Readiness is not "we have a number".** `SessionClockEstimate.ready` is false until a window has
/// been *accepted or confirmed*, and goes false again the moment one is rejected pending
/// confirmation or produces no estimate — ARCHITECTURE §7.1 rule 5's unconfirmed 30 ms step is
/// precisely a clock nobody should schedule music against, even though the previous offset is still
/// the best number available for playback already in flight (this phase's brief §7/§41).
///
/// A `struct` rather than an actor: its one owner is the `ControlSessionManager` actor, which
/// already serialises every access, and `ClockSync.RttWindow` is deliberately not thread-safe.
public struct SessionClockTracker: Sendable {
    private var estimatorState: ClockSync.EstimatorState?
    private var rttWindow = ClockSync.RttWindow()

    /// `nil` until a first window has produced an estimate. On the **leader** the playback layer
    /// ignores the offset entirely and uses `SessionClockEstimate.leader` with this window's
    /// `rtt_p95`, because the session clock *is* the leader's own monotonic clock.
    public private(set) var estimate: SessionClockEstimate?

    public init() {}

    /// The bounded RTT history's current p95 in microseconds, or `nil` before any measurement.
    public var rttP95Us: Int64? { rttWindow.p95Us() }

    /// Records one round trip. Called for **every** `PONG`, keepalive included, not only for the
    /// ARCHITECTURE §7.1 burst samples — the scheduling lead wants as much RTT history as the link
    /// has produced, while the offset estimate deliberately still only moves on a full window.
    public mutating func recordRtt(_ rttUs: Int64) {
        rttWindow.record(rttUs)
        if let current = estimate {
            estimate = SessionClockEstimate(
                offsetToLeaderUs: current.offsetToLeaderUs,
                rttP95Us: rttWindow.p95Us(),
                ready: current.ready
            )
        }
    }

    /// Runs one ARCHITECTURE §7.1 window through the shared estimator and republishes the estimate.
    @discardableResult
    public mutating func applyWindow(_ samples: [ClockSync.Sample]) -> ClockSync.WindowResult {
        let result = ClockSync.applyWindow(previous: estimatorState, samples: samples)
        estimatorState = result.newState
        if let offsetUs = result.offsetUs {
            estimate = SessionClockEstimate(
                offsetToLeaderUs: offsetUs,
                rttP95Us: rttWindow.p95Us(),
                ready: result.status == .accepted || result.status == .confirmed
            )
        } else {
            estimate = nil
        }
        return result
    }

    /// A session boundary. ADR-023 §3's lesson applied to timing: an offset measured against the
    /// previous authenticated session describes a clock relationship that no longer exists, and
    /// PROTOCOL §10 already says a reconnect re-runs clock sync "from scratch (11 samples) — the old
    /// offset is stale".
    public mutating func reset() {
        estimatorState = nil
        rttWindow.reset()
        estimate = nil
    }
}
