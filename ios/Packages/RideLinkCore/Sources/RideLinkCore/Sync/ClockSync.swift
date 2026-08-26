import Foundation

/// PROTOCOL §6 / ARCHITECTURE §7.1 clock-offset estimation. Pure: no clock reads, no I/O
/// (CLAUDE.md rule 9). Every timestamp is monotonic microseconds (never wall-clock). Mirrors
/// Android `core.sync.ClockSync` line for line so both platforms run
/// `protocol/vectors/clock/clock_vectors.json` to the same result.
///
/// The wire exchange is `PING { t1 } -> PONG { t1, t2, t3 } -> (t4 recorded on receipt)`:
///  - `t1` — A's monotonic send time
///  - `t2` — B's monotonic receive time
///  - `t3` — B's monotonic send time (reply)
///  - `t4` — A's monotonic receive time
///
/// `rtt = (t4-t1) - (t3-t2)`, `offset = ((t2-t1)+(t3-t4))/2` (add to A's clock to get B's).
///
/// This estimator additionally pins two implementation parameters that ARCHITECTURE §7.1 leaves
/// as prose ("two consecutive windows agree", "expose the residual spread as jitter"): jitter is
/// `(max_rtt - min_rtt)/2` among the kept samples, and step confirmation requires the next
/// window's candidate to land within `stepConfirmToleranceUs` of the previously rejected one.
/// Neither is a wire value — only the shared vectors need both platforms to agree on them, which
/// is what pins the numbers here.
///
/// All arithmetic is exact-rational integer arithmetic (division truncating toward zero, matching
/// Kotlin `Long` and Swift `Int64` division identically) rather than floating point, so both
/// platforms produce byte-identical results from the same vectors.
public enum ClockSync {
    public static let stepRejectThresholdUs: Int64 = 30_000
    public static let stepConfirmToleranceUs: Int64 = 10_000
    private static let ewmaAlphaNum: Int64 = 2
    private static let ewmaAlphaDen: Int64 = 10

    /// One `(t1,t2,t3,t4)` round trip, all monotonic microseconds.
    public struct Sample: Sendable, Equatable {
        public let t1MonoUs: Int64
        public let t2MonoUs: Int64
        public let t3MonoUs: Int64
        public let t4MonoUs: Int64

        public init(t1MonoUs: Int64, t2MonoUs: Int64, t3MonoUs: Int64, t4MonoUs: Int64) {
            self.t1MonoUs = t1MonoUs
            self.t2MonoUs = t2MonoUs
            self.t3MonoUs = t3MonoUs
            self.t4MonoUs = t4MonoUs
        }

        public var rttUs: Int64 { (t4MonoUs - t1MonoUs) - (t3MonoUs - t2MonoUs) }
        public var offsetUs: Int64 { ((t2MonoUs - t1MonoUs) + (t3MonoUs - t4MonoUs)) / 2 }
    }

    /// Carried across windows (`CONNECTING`'s 11-sample burst, then every 10s per PROTOCOL §6).
    public struct EstimatorState: Sendable, Equatable {
        public let offsetUs: Int64
        public let pendingOffsetUs: Int64?

        public init(offsetUs: Int64, pendingOffsetUs: Int64?) {
            self.offsetUs = offsetUs
            self.pendingOffsetUs = pendingOffsetUs
        }
    }

    public enum WindowStatus: Sendable, Equatable {
        case accepted
        case rejectedPendingConfirmation
        case confirmed
        case noEstimate
    }

    /// - `offsetUs`: the current best estimate after this window (may equal the previous state's
    ///   offset when the window was rejected or produced no estimate), or `nil` if there has
    ///   never been a valid estimate.
    /// - `rttUs` / `jitterUs`: this window's raw measurement, `nil` when `status == .noEstimate`.
    public struct WindowResult: Sendable, Equatable {
        public let status: WindowStatus
        public let offsetUs: Int64?
        public let rttUs: Int64?
        public let jitterUs: Int64?
        public let newState: EstimatorState?
    }

    private struct RawEstimate {
        let offsetUs: Int64
        let rttUs: Int64
        let jitterUs: Int64
        let keptCount: Int
    }

    private static func truncDiv(_ numerator: Int64, _ denominator: Int64) -> Int64 {
        numerator / denominator // Swift Int64 division already truncates toward zero.
    }

    private static func rawWindowEstimate(_ samples: [Sample]) -> RawEstimate? {
        let valid = samples.filter { $0.rttUs > 0 }
        guard !valid.isEmpty else { return nil }
        let minRtt = valid.map(\.rttUs).min()!
        let threshold = 2 * minRtt
        let kept = valid.filter { $0.rttUs <= threshold }
        var best = kept[0]
        for s in kept where s.rttUs < best.rttUs { best = s }
        let rtts = kept.map(\.rttUs)
        let jitterUs = truncDiv(rtts.max()! - rtts.min()!, 2)
        return RawEstimate(offsetUs: best.offsetUs, rttUs: best.rttUs, jitterUs: jitterUs, keptCount: kept.count)
    }

    /// Processes one window of samples (11 at `CONNECTING`, per PROTOCOL §6 every 10s
    /// thereafter) against the estimator's prior state. Pure and stateless itself — the caller
    /// threads `EstimatorState` through successive calls.
    public static func applyWindow(previous: EstimatorState?, samples: [Sample]) -> WindowResult {
        guard let raw = rawWindowEstimate(samples) else {
            return WindowResult(status: .noEstimate, offsetUs: previous?.offsetUs, rttUs: nil, jitterUs: nil, newState: previous)
        }

        guard let previous else {
            let newState = EstimatorState(offsetUs: raw.offsetUs, pendingOffsetUs: nil)
            return WindowResult(status: .accepted, offsetUs: raw.offsetUs, rttUs: raw.rttUs, jitterUs: raw.jitterUs, newState: newState)
        }

        let delta = abs(raw.offsetUs - previous.offsetUs)
        if delta <= stepRejectThresholdUs {
            let smoothed = previous.offsetUs + truncDiv((raw.offsetUs - previous.offsetUs) * ewmaAlphaNum, ewmaAlphaDen)
            let newState = EstimatorState(offsetUs: smoothed, pendingOffsetUs: nil)
            return WindowResult(status: .accepted, offsetUs: smoothed, rttUs: raw.rttUs, jitterUs: raw.jitterUs, newState: newState)
        }

        if let pending = previous.pendingOffsetUs, abs(raw.offsetUs - pending) <= stepConfirmToleranceUs {
            let smoothed = previous.offsetUs + truncDiv((raw.offsetUs - previous.offsetUs) * ewmaAlphaNum, ewmaAlphaDen)
            let newState = EstimatorState(offsetUs: smoothed, pendingOffsetUs: nil)
            return WindowResult(status: .confirmed, offsetUs: smoothed, rttUs: raw.rttUs, jitterUs: raw.jitterUs, newState: newState)
        }

        let newState = EstimatorState(offsetUs: previous.offsetUs, pendingOffsetUs: raw.offsetUs)
        return WindowResult(status: .rejectedPendingConfirmation, offsetUs: previous.offsetUs, rttUs: raw.rttUs, jitterUs: raw.jitterUs, newState: newState)
    }
}
