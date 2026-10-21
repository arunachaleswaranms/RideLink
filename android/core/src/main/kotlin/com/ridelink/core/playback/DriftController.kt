package com.ridelink.core.playback

/**
 * What the drift ladder decides to do this tick. A diff, not a restatement — the same convention
 * [com.ridelink.core.audiopolicy.IntercomTransmission] and [com.ridelink.core.player.LocalQueue]
 * use, so "nothing changed" is representable without re-issuing a command to the player.
 */
sealed class DriftAction {
    /** Inside the dead band, or suspended, or already failed. Touch nothing. */
    object None : DriftAction()

    /** ARCHITECTURE §7.3's rate-nudge tier: set the player's rate to [rate] and leave it there. */
    data class Nudge(
        val rate: Double,
    ) : DriftAction()

    /** Converged. Restore the rate to **exactly** 1.0 (this phase's brief §38). */
    object RestoreRate : DriftAction()

    /** ARCHITECTURE §7.3's hard-seek tier: jump straight to [positionMs]. */
    data class HardSeek(
        val positionMs: Long,
    ) : DriftAction()

    /**
     * ARCHITECTURE §7.3's fourth tier. The caller must restore rate 1.0, stop correcting, and
     * surface amber status while leaving local playback usable (FR-025). Correction does not resume
     * until the timeline changes (a new track/command) or the session is re-established.
     */
    object DeclareSyncFailure : DriftAction()
}

/**
 * A hard seek whose residual has not been observed yet (ADR-004 Amendment A1 decision 4): the
 * compensation it was made with, and the timeline anchor it was made under. A residual observed
 * under a different anchor belongs to a different restart and teaches nothing.
 */
data class PendingSeek(
    val compensationMs: Long,
    val anchorSessionUs: Long,
)

/**
 * The ladder's carried state. [nudgeRate] is what makes the hysteresis real rather than nominal:
 * an evaluation that would ask for a rate already in force emits [DriftAction.None], so a drift
 * hovering at the 25 ms boundary cannot produce a stream of identical rate commands.
 */
data class DriftState(
    val nudging: Boolean = false,
    val nudgeRate: Double = 1.0,
    /** Session instants of the hard seeks still inside the 60 s window. Bounded by that window. */
    val hardSeekAtSessionUs: List<Long> = emptyList(),
    val failed: Boolean = false,
    /**
     * ADR-004 Amendment A1: how long this device's output takes to restart after a seek, learned
     * from its own seeks' residuals. A property of the device and its route, so [DriftController.newEpoch]
     * keeps it.
     */
    val restartCompensationMs: Long = 0,
    val pendingSeek: PendingSeek? = null,
)

/**
 * The output this device must match (ADR-004 Amendment A1 decision 2): the leader's latest
 * `POSITION_REPORT`, as its drift against the **same** [PlaybackTimeline] at the session instant it
 * was sampled. A leader has none — its own output is the reference.
 */
data class DriftReference(
    val driftMs: Long,
    val atSessionUs: Long,
    val playing: Boolean,
)

/**
 * One tick of input. [driftMs] is always `actual - expected` against the authoritative
 * [PlaybackTimeline], sampled at [nowSessionUs]; [routeTransitioning] is true while **either** peer
 * reports `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL §4.4). [anchorSessionUs] is that
 * timeline's anchor — the last instant this device's output restarted because a command said so.
 */
data class DriftInput(
    val driftMs: Long,
    val nowSessionUs: Long,
    val expectedPositionMs: Long,
    val playing: Boolean,
    val routeTransitioning: Boolean,
    val anchorSessionUs: Long,
    val reference: DriftReference?,
)

/** [errorMs] is the inter-phone error the ladder acted on, or null when it had no usable reference. */
data class DriftOutcome(
    val action: DriftAction,
    val state: DriftState,
    val errorMs: Long? = null,
)

/**
 * ARCHITECTURE §7.3 / ADR-004's four-tier drift ladder, as a pure `(state, input) -> (action, state)`
 * table — the same shape as `VoiceNegotiation` and `IntercomTransmission`, and for the same reason
 * (ADR-019's lesson: a distributed rule that lives inside a coordinator is a rule no vector can
 * pin). Mirrored on both platforms and pinned by `protocol/vectors/drift/`.
 *
 * The boundaries below are **the vectors' authority**, transcribed from ARCHITECTURE §7.3's table
 * and TEST_PLAN §2's boundary list (24/25/119/120/121/1999/2000/2001):
 *
 * | `abs(drift_ms)` | tier |
 * |---|---|
 * | `< 25` | dead band |
 * | `25 .. 120` | rate nudge |
 * | `121 .. 2000` | hard seek |
 * | `> 2000` | sync failure |
 *
 * The magnitude is the **inter-phone error** (ADR-004 Amendment A1): this device's drift minus its
 * [DriftReference]'s, both against the same timeline. A lag both phones share is never chased, and a
 * differential one — one phone's Bluetooth path — is corrected exactly as any other error. With no
 * usable reference the ladder makes no new decision and releases any nudge.
 *
 * Suspension, hysteresis and the seek budget sit on top of that table:
 * - while [DriftInput.routeTransitioning], **nothing** happens — no nudge, no seek, and the
 *   hard-seek counter does not advance (ARCHITECTURE §7.3's final paragraph);
 * - a nudge disengages only once drift falls below [CONVERGED_MS], not merely below the 25 ms
 *   engage threshold, which is the whole of the anti-oscillation guarantee;
 * - the *third* qualifying hard seek inside [HARD_SEEK_WINDOW_US] is replaced by
 *   [DriftAction.DeclareSyncFailure]: seeking a third time in a minute is the definition of not
 *   converging, and ADR-004's ladder gives up rather than seeking forever.
 */
object DriftController {
    const val DEAD_BAND_MS: Long = 25
    const val NUDGE_MAX_MS: Long = 120
    const val FAIL_MS: Long = 2_000

    /** ARCHITECTURE §7.3: "until drift < 15 ms, then restore 1.0". The hysteresis floor. */
    const val CONVERGED_MS: Long = 15

    /**
     * ARCHITECTURE §7.3's +/-0.2 %. Written as two literals rather than `1.0 +/- 0.002` so the value
     * is the exact `Double` a vector's `0.998`/`1.002` parses to on both platforms — computing it
     * would risk a last-bit difference between `1.0 - 0.002` and the literal.
     */
    const val RATE_SLOWER: Double = 0.998
    const val RATE_NORMAL: Double = 1.0
    const val RATE_FASTER: Double = 1.002

    const val MAX_HARD_SEEKS_IN_WINDOW: Int = 3
    const val HARD_SEEK_WINDOW_US: Long = 60_000_000

    /**
     * ADR-004 Amendment A1 decision 3: a sample taken this soon after a restart may still be inside
     * the stretch where the position is frozen because output has not begun. More than twice the
     * largest lag measured on a phone (about 650 ms).
     */
    const val REFERENCE_SETTLE_US: Long = 1_500_000

    /** Two and a bit `POSITION_REPORT` intervals: an older reference is a lost link, not a sample. */
    const val MAX_REFERENCE_AGE_US: Long = 12_000_000

    /** A restart longer than this is a stall, not a latency, and must not be learned as one. */
    const val MAX_RESTART_COMPENSATION_MS: Long = 1_000

    @Suppress("ReturnCount") // one early-out per ladder tier reads far clearer than nested whens
    fun evaluate(
        state: DriftState,
        input: DriftInput,
    ): DriftOutcome {
        // Already failed: correction is over until the caller resets this state for a new timeline
        // or a new session. The rate was restored when the failure was declared.
        if (state.failed) return DriftOutcome(DriftAction.None, state)

        // Not playing: nothing to correct, but a nudge left in force must not survive the pause, and
        // a seek's residual can no longer be told apart from the pause's.
        if (!input.playing) return releaseNudgeIfActive(state.copy(pendingSeek = null))

        // ARCHITECTURE §7.3: the ladder is suspended while either peer's route is transitioning.
        // Deliberately *before* every tier, and deliberately leaving `nudging` untouched — suspended
        // means "make no new decision", not "undo the last one". A new route has a new restart
        // latency, so what was learned about the old one is forgotten.
        if (input.routeTransitioning) {
            return DriftOutcome(DriftAction.None, state.copy(restartCompensationMs = 0, pendingSeek = null))
        }

        val reference = input.reference
        if (reference == null || !usable(state, input, reference)) return releaseNudgeIfActive(state)

        val errorMs = input.driftMs - reference.driftMs
        val learned = learn(state, input, errorMs)
        val magnitude = if (errorMs < 0) -errorMs else errorMs

        val outcome =
            when {
                magnitude > FAIL_MS -> fail(learned)
                magnitude > NUDGE_MAX_MS -> hardSeek(learned, input, reference)
                magnitude >= DEAD_BAND_MS -> nudge(learned, errorMs)
                learned.nudging && magnitude < CONVERGED_MS -> releaseNudge(learned)
                else -> DriftOutcome(DriftAction.None, learned)
            }
        return outcome.copy(errorMs = errorMs)
    }

    /** Everything a new session or a user cancellation resets. */
    fun reset(): DriftState = DriftState()

    /**
     * A new playback epoch: a fresh ladder and a fresh seek budget, but this device's output still
     * restarts as slowly as it did (ADR-004 Amendment A1 decision 4).
     */
    fun newEpoch(state: DriftState): DriftState = DriftState(restartCompensationMs = state.restartCompensationMs)

    /** ADR-004 Amendment A1 decision 3: every reason a reference or a local sample cannot be compared. */
    private fun usable(
        state: DriftState,
        input: DriftInput,
        reference: DriftReference,
    ): Boolean {
        val settledFrom = input.anchorSessionUs + REFERENCE_SETTLE_US
        val lastSeekUs = state.hardSeekAtSessionUs.lastOrNull()
        return reference.playing &&
            reference.atSessionUs >= settledFrom &&
            input.nowSessionUs - reference.atSessionUs <= MAX_REFERENCE_AGE_US &&
            input.nowSessionUs >= settledFrom &&
            (lastSeekUs == null || input.nowSessionUs - lastSeekUs >= REFERENCE_SETTLE_US)
    }

    /**
     * ADR-004 Amendment A1 decision 4. After a seek made with compensation `c`, the output restarts
     * `L` later, so the first settled error is `c - L`: the latency is `c - error`.
     */
    private fun learn(
        state: DriftState,
        input: DriftInput,
        errorMs: Long,
    ): DriftState {
        val pending = state.pendingSeek ?: return state
        // A residual under a different anchor belongs to a different restart: forget the seek, keep
        // what was learned before it.
        val latencyMs =
            if (pending.anchorSessionUs == input.anchorSessionUs) {
                (pending.compensationMs - errorMs).coerceIn(0, MAX_RESTART_COMPENSATION_MS)
            } else {
                state.restartCompensationMs
            }
        return state.copy(restartCompensationMs = latencyMs, pendingSeek = null)
    }

    private fun fail(state: DriftState): DriftOutcome =
        DriftOutcome(
            DriftAction.DeclareSyncFailure,
            state.copy(failed = true, nudging = false, nudgeRate = RATE_NORMAL),
        )

    private fun hardSeek(
        state: DriftState,
        input: DriftInput,
        reference: DriftReference,
    ): DriftOutcome {
        val recent = state.hardSeekAtSessionUs.filter { input.nowSessionUs - it <= HARD_SEEK_WINDOW_US }
        val withThisOne = recent + input.nowSessionUs
        if (withThisOne.size >= MAX_HARD_SEEKS_IN_WINDOW) {
            return DriftOutcome(
                DriftAction.DeclareSyncFailure,
                state.copy(failed = true, nudging = false, nudgeRate = RATE_NORMAL, hardSeekAtSessionUs = withThisOne),
            )
        }
        // A hard seek also ends any nudge in force: the player is being placed exactly where it
        // should be, so there is nothing left to slew toward and rate 1.0 is the correct baseline.
        // "Where it should be" is where the reference output is, plus the time this device's output
        // takes to restart — a seek re-incurs that latency (ADR-004 Amendment A1 decision 4).
        val compensationMs = state.restartCompensationMs
        val targetMs = (input.expectedPositionMs + reference.driftMs + compensationMs).coerceAtLeast(0)
        return DriftOutcome(
            DriftAction.HardSeek(targetMs),
            state.copy(
                nudging = false,
                nudgeRate = RATE_NORMAL,
                hardSeekAtSessionUs = withThisOne,
                pendingSeek = PendingSeek(compensationMs, input.anchorSessionUs),
            ),
        )
    }

    private fun nudge(
        state: DriftState,
        errorMs: Long,
    ): DriftOutcome {
        // Ahead of the reference (positive error) means play slower; behind means play faster.
        val target = if (errorMs > 0) RATE_SLOWER else RATE_FASTER
        if (state.nudging && state.nudgeRate == target) return DriftOutcome(DriftAction.None, state)
        return DriftOutcome(DriftAction.Nudge(target), state.copy(nudging = true, nudgeRate = target))
    }

    private fun releaseNudgeIfActive(state: DriftState): DriftOutcome =
        if (state.nudging) releaseNudge(state) else DriftOutcome(DriftAction.None, state)

    private fun releaseNudge(state: DriftState): DriftOutcome =
        DriftOutcome(DriftAction.RestoreRate, state.copy(nudging = false, nudgeRate = RATE_NORMAL))
}
