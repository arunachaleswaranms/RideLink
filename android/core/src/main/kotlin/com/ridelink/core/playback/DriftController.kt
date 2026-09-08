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
)

/**
 * One tick of input. [driftMs] is always `actual - expected` against the authoritative
 * [PlaybackTimeline]; [routeTransitioning] is true while **either** peer reports
 * `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL §4.4).
 */
data class DriftInput(
    val driftMs: Long,
    val nowSessionUs: Long,
    val expectedPositionMs: Long,
    val playing: Boolean,
    val routeTransitioning: Boolean,
)

data class DriftOutcome(
    val action: DriftAction,
    val state: DriftState,
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

    @Suppress("ReturnCount") // one early-out per ladder tier reads far clearer than nested whens
    fun evaluate(
        state: DriftState,
        input: DriftInput,
    ): DriftOutcome {
        // Already failed: correction is over until the caller resets this state for a new timeline
        // or a new session. The rate was restored when the failure was declared.
        if (state.failed) return DriftOutcome(DriftAction.None, state)

        // Not playing: nothing to correct, but a nudge left in force must not survive the pause.
        if (!input.playing) return releaseNudgeIfActive(state)

        // ARCHITECTURE §7.3: the ladder is suspended while either peer's route is transitioning.
        // Deliberately *before* every tier, and deliberately leaving `nudging` untouched — suspended
        // means "make no new decision", not "undo the last one".
        if (input.routeTransitioning) return DriftOutcome(DriftAction.None, state)

        val magnitude = if (input.driftMs < 0) -input.driftMs else input.driftMs

        if (magnitude > FAIL_MS) return fail(state)
        if (magnitude > NUDGE_MAX_MS) return hardSeek(state, input)
        if (magnitude >= DEAD_BAND_MS) return nudge(state, input)
        if (state.nudging && magnitude < CONVERGED_MS) return releaseNudge(state)
        return DriftOutcome(DriftAction.None, state)
    }

    /** Everything a new playback epoch, a new session, or a user cancellation resets. */
    fun reset(): DriftState = DriftState()

    private fun fail(state: DriftState): DriftOutcome =
        DriftOutcome(
            DriftAction.DeclareSyncFailure,
            state.copy(failed = true, nudging = false, nudgeRate = RATE_NORMAL),
        )

    private fun hardSeek(
        state: DriftState,
        input: DriftInput,
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
        return DriftOutcome(
            DriftAction.HardSeek(input.expectedPositionMs),
            state.copy(nudging = false, nudgeRate = RATE_NORMAL, hardSeekAtSessionUs = withThisOne),
        )
    }

    private fun nudge(
        state: DriftState,
        input: DriftInput,
    ): DriftOutcome {
        // Ahead of the timeline (positive drift) means play slower; behind means play faster.
        val target = if (input.driftMs > 0) RATE_SLOWER else RATE_FASTER
        if (state.nudging && state.nudgeRate == target) return DriftOutcome(DriftAction.None, state)
        return DriftOutcome(DriftAction.Nudge(target), state.copy(nudging = true, nudgeRate = target))
    }

    private fun releaseNudgeIfActive(state: DriftState): DriftOutcome =
        if (state.nudging) releaseNudge(state) else DriftOutcome(DriftAction.None, state)

    private fun releaseNudge(state: DriftState): DriftOutcome =
        DriftOutcome(DriftAction.RestoreRate, state.copy(nudging = false, nudgeRate = RATE_NORMAL))
}
