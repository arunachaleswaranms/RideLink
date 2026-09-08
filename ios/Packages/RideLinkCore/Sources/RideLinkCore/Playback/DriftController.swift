import Foundation

/// What the drift ladder decides to do this tick. A diff, not a restatement — the same convention
/// `IntercomTransmission` and `LocalQueue` use, so "nothing changed" is representable without
/// re-issuing a command to the player.
public enum DriftAction: Sendable, Equatable {
    /// Inside the dead band, or suspended, or already failed. Touch nothing.
    case none
    /// ARCHITECTURE §7.3's rate-nudge tier: set the player's rate and leave it there.
    case nudge(rate: Double)
    /// Converged. Restore the rate to **exactly** 1.0 (this phase's brief §38).
    case restoreRate
    /// ARCHITECTURE §7.3's hard-seek tier: jump straight to this position.
    case hardSeek(positionMs: Int64)
    /// ARCHITECTURE §7.3's fourth tier. The caller must restore rate 1.0, stop correcting, and
    /// surface amber status while leaving local playback usable (FR-025). Correction does not resume
    /// until the timeline changes (a new track/command) or the session is re-established.
    case declareSyncFailure
}

/// The ladder's carried state. `nudgeRate` is what makes the hysteresis real rather than nominal: an
/// evaluation that would ask for a rate already in force emits `.none`, so a drift hovering at the
/// 25 ms boundary cannot produce a stream of identical rate commands.
public struct DriftState: Sendable, Equatable {
    public let nudging: Bool
    public let nudgeRate: Double
    /// Session instants of the hard seeks still inside the 60 s window. Bounded by that window.
    public let hardSeekAtSessionUs: [Int64]
    public let failed: Bool

    public init(
        nudging: Bool = false,
        nudgeRate: Double = DriftController.rateNormal,
        hardSeekAtSessionUs: [Int64] = [],
        failed: Bool = false
    ) {
        self.nudging = nudging
        self.nudgeRate = nudgeRate
        self.hardSeekAtSessionUs = hardSeekAtSessionUs
        self.failed = failed
    }

    func with(
        nudging: Bool? = nil,
        nudgeRate: Double? = nil,
        hardSeekAtSessionUs: [Int64]? = nil,
        failed: Bool? = nil
    ) -> DriftState {
        DriftState(
            nudging: nudging ?? self.nudging,
            nudgeRate: nudgeRate ?? self.nudgeRate,
            hardSeekAtSessionUs: hardSeekAtSessionUs ?? self.hardSeekAtSessionUs,
            failed: failed ?? self.failed
        )
    }
}

/// One tick of input. `driftMs` is always `actual - expected` against the authoritative
/// `PlaybackTimeline`; `routeTransitioning` is true while **either** peer reports
/// `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL §4.4).
public struct DriftInput: Sendable, Equatable {
    public let driftMs: Int64
    public let nowSessionUs: Int64
    public let expectedPositionMs: Int64
    public let playing: Bool
    public let routeTransitioning: Bool

    public init(driftMs: Int64, nowSessionUs: Int64, expectedPositionMs: Int64, playing: Bool, routeTransitioning: Bool) {
        self.driftMs = driftMs
        self.nowSessionUs = nowSessionUs
        self.expectedPositionMs = expectedPositionMs
        self.playing = playing
        self.routeTransitioning = routeTransitioning
    }
}

public struct DriftOutcome: Sendable, Equatable {
    public let action: DriftAction
    public let state: DriftState
}

/// ARCHITECTURE §7.3 / ADR-004's four-tier drift ladder, as a pure `(state, input) -> (action, state)`
/// table — the same shape as `VoiceNegotiation` and `IntercomTransmission`, and for the same reason
/// (ADR-019's lesson: a distributed rule that lives inside a coordinator is a rule no vector can
/// pin). Mirrors Android `core.playback.DriftController`; both run `protocol/vectors/drift/`.
///
/// The boundaries below are **the vectors' authority**, transcribed from ARCHITECTURE §7.3's table
/// and TEST_PLAN §2's boundary list (24/25/119/120/121/1999/2000/2001):
///
/// | `abs(drift_ms)` | tier |
/// |---|---|
/// | `< 25` | dead band |
/// | `25 ... 120` | rate nudge |
/// | `121 ... 2000` | hard seek |
/// | `> 2000` | sync failure |
///
/// Suspension, hysteresis and the seek budget sit on top of that table:
/// - while `routeTransitioning`, **nothing** happens — no nudge, no seek, and the hard-seek counter
///   does not advance (ARCHITECTURE §7.3's final paragraph);
/// - a nudge disengages only once drift falls below `convergedMs`, not merely below the 25 ms engage
///   threshold, which is the whole of the anti-oscillation guarantee;
/// - the *third* qualifying hard seek inside `hardSeekWindowUs` is replaced by `.declareSyncFailure`:
///   seeking a third time in a minute is the definition of not converging, and ADR-004's ladder gives
///   up rather than seeking forever.
public enum DriftController {
    public static let deadBandMs: Int64 = 25
    public static let nudgeMaxMs: Int64 = 120
    public static let failMs: Int64 = 2_000

    /// ARCHITECTURE §7.3: "until drift < 15 ms, then restore 1.0". The hysteresis floor.
    public static let convergedMs: Int64 = 15

    /// ARCHITECTURE §7.3's +/-0.2 %. Written as two literals rather than `1.0 +/- 0.002` so the value
    /// is the exact `Double` a vector's `0.998`/`1.002` parses to on both platforms — computing it
    /// would risk a last-bit difference between `1.0 - 0.002` and the literal.
    public static let rateSlower = 0.998
    public static let rateNormal = 1.0
    public static let rateFaster = 1.002

    public static let maxHardSeeksInWindow = 3
    public static let hardSeekWindowUs: Int64 = 60_000_000

    public static func evaluate(state: DriftState, input: DriftInput) -> DriftOutcome {
        // Already failed: correction is over until the caller resets this state for a new timeline
        // or a new session. The rate was restored when the failure was declared.
        if state.failed { return DriftOutcome(action: .none, state: state) }

        // Not playing: nothing to correct, but a nudge left in force must not survive the pause.
        if !input.playing { return state.nudging ? releaseNudge(state) : DriftOutcome(action: .none, state: state) }

        // ARCHITECTURE §7.3: the ladder is suspended while either peer's route is transitioning.
        // Deliberately *before* every tier, and deliberately leaving `nudging` untouched — suspended
        // means "make no new decision", not "undo the last one".
        if input.routeTransitioning { return DriftOutcome(action: .none, state: state) }

        let magnitude = input.driftMs < 0 ? -input.driftMs : input.driftMs

        if magnitude > failMs { return fail(state) }
        if magnitude > nudgeMaxMs { return hardSeek(state, input) }
        if magnitude >= deadBandMs { return nudge(state, input) }
        if state.nudging, magnitude < convergedMs { return releaseNudge(state) }
        return DriftOutcome(action: .none, state: state)
    }

    /// Everything a new playback epoch, a new session, or a user cancellation resets.
    public static func reset() -> DriftState { DriftState() }

    private static func fail(_ state: DriftState) -> DriftOutcome {
        DriftOutcome(
            action: .declareSyncFailure,
            state: state.with(nudging: false, nudgeRate: rateNormal, failed: true)
        )
    }

    private static func hardSeek(_ state: DriftState, _ input: DriftInput) -> DriftOutcome {
        let recent = state.hardSeekAtSessionUs.filter { input.nowSessionUs - $0 <= hardSeekWindowUs }
        let withThisOne = recent + [input.nowSessionUs]
        if withThisOne.count >= maxHardSeeksInWindow {
            return DriftOutcome(
                action: .declareSyncFailure,
                state: state.with(nudging: false, nudgeRate: rateNormal, hardSeekAtSessionUs: withThisOne, failed: true)
            )
        }
        // A hard seek also ends any nudge in force: the player is being placed exactly where it
        // should be, so there is nothing left to slew toward and rate 1.0 is the correct baseline.
        return DriftOutcome(
            action: .hardSeek(positionMs: input.expectedPositionMs),
            state: state.with(nudging: false, nudgeRate: rateNormal, hardSeekAtSessionUs: withThisOne)
        )
    }

    private static func nudge(_ state: DriftState, _ input: DriftInput) -> DriftOutcome {
        // Ahead of the timeline (positive drift) means play slower; behind means play faster.
        let target = input.driftMs > 0 ? rateSlower : rateFaster
        if state.nudging, state.nudgeRate == target { return DriftOutcome(action: .none, state: state) }
        return DriftOutcome(action: .nudge(rate: target), state: state.with(nudging: true, nudgeRate: target))
    }

    private static func releaseNudge(_ state: DriftState) -> DriftOutcome {
        DriftOutcome(action: .restoreRate, state: state.with(nudging: false, nudgeRate: rateNormal))
    }
}
