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

/// A hard seek whose residual has not been observed yet (ADR-004 Amendment A1 decision 4): the
/// compensation it was made with, and the timeline anchor it was made under. A residual observed under
/// a different anchor belongs to a different restart and teaches nothing.
public struct PendingSeek: Sendable, Equatable {
    public let compensationMs: Int64
    public let anchorSessionUs: Int64

    public init(compensationMs: Int64, anchorSessionUs: Int64) {
        self.compensationMs = compensationMs
        self.anchorSessionUs = anchorSessionUs
    }
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
    /// ADR-004 Amendment A1: how long this device's output takes to restart after a seek, learned
    /// from its own seeks' residuals. A property of the device and its route, so
    /// `DriftController.newEpoch` keeps it.
    public let restartCompensationMs: Int64
    public let pendingSeek: PendingSeek?
    /// The `DriftInput.routeChangeCount` that `restartCompensationMs` and `pendingSeek` belong to. Nil
    /// until the first evaluation. Any other count means this device's route has changed since —
    /// possibly and briefly between two ticks — so both are forgotten.
    public let routeChangeCount: Int64?

    public init(
        nudging: Bool = false,
        nudgeRate: Double = DriftController.rateNormal,
        hardSeekAtSessionUs: [Int64] = [],
        failed: Bool = false,
        restartCompensationMs: Int64 = 0,
        pendingSeek: PendingSeek? = nil,
        routeChangeCount: Int64? = nil
    ) {
        self.nudging = nudging
        self.nudgeRate = nudgeRate
        self.hardSeekAtSessionUs = hardSeekAtSessionUs
        self.failed = failed
        self.restartCompensationMs = restartCompensationMs
        self.pendingSeek = pendingSeek
        self.routeChangeCount = routeChangeCount
    }

    func with(
        nudging: Bool? = nil,
        nudgeRate: Double? = nil,
        hardSeekAtSessionUs: [Int64]? = nil,
        failed: Bool? = nil,
        restartCompensationMs: Int64? = nil,
        pendingSeek: PendingSeek?? = nil,
        routeChangeCount: Int64?? = nil
    ) -> DriftState {
        DriftState(
            nudging: nudging ?? self.nudging,
            nudgeRate: nudgeRate ?? self.nudgeRate,
            hardSeekAtSessionUs: hardSeekAtSessionUs ?? self.hardSeekAtSessionUs,
            failed: failed ?? self.failed,
            restartCompensationMs: restartCompensationMs ?? self.restartCompensationMs,
            pendingSeek: pendingSeek ?? self.pendingSeek,
            routeChangeCount: routeChangeCount ?? self.routeChangeCount
        )
    }
}

/// The output this device must match (ADR-004 Amendment A1 decision 2): the leader's latest
/// `POSITION_REPORT`, as its drift against the **same** `PlaybackTimeline` at the session instant it
/// was sampled. A leader has none — its own output is the reference.
///
/// `anchorSessionUs` and `anchorPositionMs` are the anchor of the timeline `driftMs` was measured
/// against. A drift is a difference from one particular anchor, so it means nothing once the timeline
/// has been re-anchored — even to an earlier instant, which a same-track `PLAYBACK_STATE` can do and
/// which every freshness check would otherwise pass (PR #32 review round 2).
public struct DriftReference: Sendable, Equatable {
    public let driftMs: Int64
    public let atSessionUs: Int64
    public let playing: Bool
    public let anchorSessionUs: Int64
    public let anchorPositionMs: Int64

    public init(driftMs: Int64, atSessionUs: Int64, playing: Bool, anchorSessionUs: Int64, anchorPositionMs: Int64) {
        self.driftMs = driftMs
        self.atSessionUs = atSessionUs
        self.playing = playing
        self.anchorSessionUs = anchorSessionUs
        self.anchorPositionMs = anchorPositionMs
    }
}

/// One tick of input. `driftMs` is always `actual - expected` against the authoritative
/// `PlaybackTimeline`, sampled at `nowSessionUs`; `routeTransitioning` is true while **either** peer
/// reports `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL §4.4). `anchorSessionUs` is that
/// timeline's anchor — the last instant this device's output restarted because a command said so.
/// `routeChangeCount` is this device's own count of route transitions begun, as its route layer
/// reports it. It only ever matters whether it *changed*, so a route layer that restarts its count when
/// the audio session is recreated is still read correctly.
public struct DriftInput: Sendable, Equatable {
    public let driftMs: Int64
    public let nowSessionUs: Int64
    public let expectedPositionMs: Int64
    public let playing: Bool
    public let routeTransitioning: Bool
    public let anchorSessionUs: Int64
    public let reference: DriftReference?
    public let routeChangeCount: Int64
    /// The position half of the timeline's anchor, which `DriftReference.anchorPositionMs` must match.
    public let anchorPositionMs: Int64

    public init(
        driftMs: Int64,
        nowSessionUs: Int64,
        expectedPositionMs: Int64,
        playing: Bool,
        routeTransitioning: Bool,
        anchorSessionUs: Int64,
        reference: DriftReference?,
        routeChangeCount: Int64,
        anchorPositionMs: Int64
    ) {
        self.driftMs = driftMs
        self.nowSessionUs = nowSessionUs
        self.expectedPositionMs = expectedPositionMs
        self.playing = playing
        self.routeTransitioning = routeTransitioning
        self.anchorSessionUs = anchorSessionUs
        self.reference = reference
        self.routeChangeCount = routeChangeCount
        self.anchorPositionMs = anchorPositionMs
    }
}

/// `errorMs` is the inter-phone error the ladder acted on, or nil when it had no usable reference.
public struct DriftOutcome: Sendable, Equatable {
    public let action: DriftAction
    public let state: DriftState
    public let errorMs: Int64?

    public init(action: DriftAction, state: DriftState, errorMs: Int64? = nil) {
        self.action = action
        self.state = state
        self.errorMs = errorMs
    }

    func with(errorMs: Int64?) -> DriftOutcome { DriftOutcome(action: action, state: state, errorMs: errorMs) }
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
/// The magnitude is the **inter-phone error** (ADR-004 Amendment A1): this device's drift minus its
/// `DriftReference`'s, both against the same timeline. A lag both phones share is never chased, and a
/// differential one — one phone's Bluetooth path — is corrected exactly as any other error. With no
/// usable reference the ladder makes no new decision and releases any nudge.
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

    /// ADR-004 Amendment A1 decision 3: a sample taken this soon after a restart may still be inside
    /// the stretch where the position is frozen because output has not begun. More than twice the
    /// largest lag measured on a phone (about 650 ms).
    public static let referenceSettleUs: Int64 = 1_500_000

    /// Two and a bit `POSITION_REPORT` intervals: an older reference is a lost link, not a sample.
    public static let maxReferenceAgeUs: Int64 = 12_000_000

    /// A restart longer than this is a stall, not a latency, and must not be learned as one.
    public static let maxRestartCompensationMs: Int64 = 1_000

    public static func evaluate(state: DriftState, input: DriftInput) -> DriftOutcome {
        // ADR-004 Amendment A1 decision 4: a new route has a new restart latency, so what was learned
        // about the old one is forgotten — first, before any early return, so it holds while failed
        // and while paused too, and for a transition that came and went between two ticks.
        let current = followRoute(state, input)

        // Already failed: correction is over until the caller resets this state for a new timeline
        // or a new session. The rate was restored when the failure was declared.
        if current.failed { return DriftOutcome(action: .none, state: current) }

        // Not playing: nothing to correct, but a nudge left in force must not survive the pause, and
        // a seek's residual can no longer be told apart from the pause's.
        if !input.playing { return releaseNudgeIfActive(current.with(pendingSeek: .some(nil))) }

        // ARCHITECTURE §7.3: the ladder is suspended while either peer's route is transitioning.
        // Deliberately *before* every tier, and deliberately leaving `nudging` untouched — suspended
        // means "make no new decision", not "undo the last one".
        if input.routeTransitioning { return DriftOutcome(action: .none, state: current) }

        guard let reference = input.reference, usable(current, input, reference) else {
            return releaseNudgeIfActive(current)
        }

        let errorMs = input.driftMs - reference.driftMs
        let learned = learn(current, input, errorMs)
        let magnitude = errorMs < 0 ? -errorMs : errorMs

        let outcome: DriftOutcome
        if magnitude > failMs {
            outcome = fail(learned)
        } else if magnitude > nudgeMaxMs {
            outcome = hardSeek(learned, input, reference)
        } else if magnitude >= deadBandMs {
            outcome = nudge(learned, errorMs)
        } else if learned.nudging, magnitude < convergedMs {
            outcome = releaseNudge(learned)
        } else {
            outcome = DriftOutcome(action: .none, state: learned)
        }
        return outcome.with(errorMs: errorMs)
    }

    /// Everything a new session or a user cancellation resets.
    public static func reset() -> DriftState { DriftState() }

    /// A new playback epoch: a fresh ladder and a fresh seek budget, but this device's output still
    /// restarts as slowly as it did (ADR-004 Amendment A1 decision 4).
    public static func newEpoch(_ state: DriftState) -> DriftState {
        DriftState(restartCompensationMs: state.restartCompensationMs, routeChangeCount: state.routeChangeCount)
    }

    /// ADR-004 Amendment A1 decision 4. Either peer transitioning, or this device's count of route
    /// transitions differing from the one the compensation was learned under, forgets it.
    private static func followRoute(_ state: DriftState, _ input: DriftInput) -> DriftState {
        let changed = input.routeTransitioning || (state.routeChangeCount.map { $0 != input.routeChangeCount } ?? false)
        return changed
            ? state.with(restartCompensationMs: 0, pendingSeek: .some(nil), routeChangeCount: .some(input.routeChangeCount))
            : state.with(routeChangeCount: .some(input.routeChangeCount))
    }

    /// ADR-004 Amendment A1 decision 3: every reason a reference or a local sample cannot be compared.
    private static func usable(_ state: DriftState, _ input: DriftInput, _ reference: DriftReference) -> Bool {
        let settledFrom = input.anchorSessionUs + referenceSettleUs
        let lastSeekUs = state.hardSeekAtSessionUs.last
        // A reference sampled after this device's own sample cannot be compared with it. A genuine one
        // that merely arrived early is usable on the next tick; one stamped implausibly far ahead
        // never becomes usable while it is still ahead.
        return reference.playing &&
            reference.anchorSessionUs == input.anchorSessionUs &&
            reference.anchorPositionMs == input.anchorPositionMs &&
            reference.atSessionUs >= settledFrom &&
            reference.atSessionUs <= input.nowSessionUs &&
            input.nowSessionUs - reference.atSessionUs <= maxReferenceAgeUs &&
            input.nowSessionUs >= settledFrom &&
            (lastSeekUs.map { input.nowSessionUs - $0 >= referenceSettleUs } ?? true)
    }

    /// ADR-004 Amendment A1 decision 4. After a seek made with compensation `c`, the output restarts
    /// `L` later, so the first settled error is `c - L`: the latency is `c - error`.
    private static func learn(_ state: DriftState, _ input: DriftInput, _ errorMs: Int64) -> DriftState {
        guard let pending = state.pendingSeek else { return state }
        // A residual under a different anchor belongs to a different restart: forget the seek, keep
        // what was learned before it.
        let latencyMs = pending.anchorSessionUs == input.anchorSessionUs
            ? min(max(pending.compensationMs - errorMs, 0), maxRestartCompensationMs)
            : state.restartCompensationMs
        return state.with(restartCompensationMs: latencyMs, pendingSeek: .some(nil))
    }

    private static func fail(_ state: DriftState) -> DriftOutcome {
        DriftOutcome(
            action: .declareSyncFailure,
            state: state.with(nudging: false, nudgeRate: rateNormal, failed: true)
        )
    }

    private static func hardSeek(_ state: DriftState, _ input: DriftInput, _ reference: DriftReference) -> DriftOutcome {
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
        // "Where it should be" is where the reference output is, plus the time this device's output
        // takes to restart — a seek re-incurs that latency (ADR-004 Amendment A1 decision 4).
        let compensationMs = state.restartCompensationMs
        let targetMs = max(input.expectedPositionMs + reference.driftMs + compensationMs, 0)
        return DriftOutcome(
            action: .hardSeek(positionMs: targetMs),
            state: state.with(
                nudging: false,
                nudgeRate: rateNormal,
                hardSeekAtSessionUs: withThisOne,
                pendingSeek: PendingSeek(compensationMs: compensationMs, anchorSessionUs: input.anchorSessionUs)
            )
        )
    }

    private static func nudge(_ state: DriftState, _ errorMs: Int64) -> DriftOutcome {
        // Ahead of the reference (positive error) means play slower; behind means play faster.
        let target = errorMs > 0 ? rateSlower : rateFaster
        if state.nudging, state.nudgeRate == target { return DriftOutcome(action: .none, state: state) }
        return DriftOutcome(action: .nudge(rate: target), state: state.with(nudging: true, nudgeRate: target))
    }

    private static func releaseNudgeIfActive(_ state: DriftState) -> DriftOutcome {
        state.nudging ? releaseNudge(state) : DriftOutcome(action: .none, state: state)
    }

    private static func releaseNudge(_ state: DriftState) -> DriftOutcome {
        DriftOutcome(action: .restoreRate, state: state.with(nudging: false, nudgeRate: rateNormal))
    }
}
