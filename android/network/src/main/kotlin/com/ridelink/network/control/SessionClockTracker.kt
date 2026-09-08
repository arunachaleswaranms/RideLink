package com.ridelink.network.control

import com.ridelink.core.sync.ClockSync
import com.ridelink.core.sync.SessionClockEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session's single source of clock truth: [ClockSync]'s estimator state, its bounded RTT window,
 * and the [SessionClockEstimate] the Phase 5 playback layer schedules against.
 *
 * **There is exactly one of these per control session, and no second RTT tracker anywhere.**
 * Extracted from `ControlSessionManager` rather than added to it for the reason
 * `docs/STATUS.md` §4 problem 18 records: that class is at its detekt ceiling and every subsystem
 * attached to it since Phase 2a has been extracted the moment it grew (`VoiceSignalRelay`,
 * `AudioStateRelay`, `ManifestRelay`, `TransferRelay`). This replaces the two fields it used to
 * carry inline (`clockState` and the diagnostics arithmetic) rather than adding to them.
 *
 * **Readiness is not "we have a number".** [SessionClockEstimate.ready] is false until a window has
 * been *accepted or confirmed*, and goes false again the moment one is rejected pending confirmation
 * or produces no estimate — ARCHITECTURE §7.1 rule 5's unconfirmed 30 ms step is precisely a clock
 * nobody should schedule music against, even though the previous offset is still the best number
 * available for playback already in flight (this phase's brief §7/§41).
 *
 * Confined to the control session's own coroutine context, matching
 * [com.ridelink.core.transfer.OperationFence]'s documented confinement — [ClockSync.RttWindow] is
 * deliberately not thread-safe.
 */
class SessionClockTracker {
    private var estimatorState: ClockSync.EstimatorState? = null
    private val rttWindow = ClockSync.RttWindow()

    private val _estimate = MutableStateFlow<SessionClockEstimate?>(null)

    /**
     * `null` until a first window has produced an estimate. On the **leader** the playback layer
     * ignores the offset entirely and uses [SessionClockEstimate.leader] with this window's
     * `rtt_p95`, because the session clock *is* the leader's own monotonic clock.
     */
    val estimate: StateFlow<SessionClockEstimate?> = _estimate.asStateFlow()

    /** The bounded RTT history's current p95 in microseconds, or `null` before any measurement. */
    val rttP95Us: Long? get() = rttWindow.p95Us()

    /**
     * Records one round trip. Called for **every** `PONG`, keepalive included, not only for the
     * ARCHITECTURE §7.1 burst samples — the scheduling lead wants as much RTT history as the link
     * has produced, while the offset estimate deliberately still only moves on a full window.
     */
    fun recordRtt(rttUs: Long) {
        rttWindow.record(rttUs)
        _estimate.value = _estimate.value?.copy(rttP95Us = rttWindow.p95Us())
    }

    /** Runs one ARCHITECTURE §7.1 window through the shared estimator and republishes the estimate. */
    fun applyWindow(samples: List<ClockSync.Sample>): ClockSync.WindowResult {
        val result = ClockSync.applyWindow(estimatorState, samples)
        estimatorState = result.newState
        val offsetUs = result.offsetUs
        _estimate.value =
            if (offsetUs == null) {
                null
            } else {
                SessionClockEstimate(
                    offsetToLeaderUs = offsetUs,
                    rttP95Us = rttWindow.p95Us(),
                    ready = result.status == ClockSync.WindowStatus.ACCEPTED || result.status == ClockSync.WindowStatus.CONFIRMED,
                )
            }
        return result
    }

    /**
     * A session boundary. ADR-023 §3's lesson applied to timing: an offset measured against the
     * previous authenticated session describes a clock relationship that no longer exists, and
     * PROTOCOL §10 already says a reconnect re-runs clock sync "from scratch (11 samples) — the old
     * offset is stale".
     */
    fun reset() {
        estimatorState = null
        rttWindow.reset()
        _estimate.value = null
    }
}
