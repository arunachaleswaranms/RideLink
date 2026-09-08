package com.ridelink.core.playback

import com.ridelink.core.model.ContentHash

/**
 * The authoritative playback timeline both phones track: "at session instant
 * [anchorSessionUs] this track was at [anchorPositionMs], and it is (or is not) advancing."
 *
 * Every drift measurement in the system is `actual local position - expectedPositionMs(now)`
 * against **this** object (this phase's brief §33). Subtracting one phone's reported position from
 * the other's is explicitly not how it is done: those two numbers are sampled at different session
 * instants and separated by a network delay, so their difference is not a drift.
 *
 * [generation] exists because [trackHash] is not enough to identify a playback epoch — the same
 * track can be played again, and a `POSITION_REPORT` or a scheduled timer from the *previous* play
 * of the same hash must be inert (brief §32). It is local bookkeeping, never on the wire; the wire
 * distinguishes epochs by [anchorSessionUs], which strictly increases with every accepted command.
 */
data class PlaybackTimeline(
    val trackHash: ContentHash,
    val queueItemId: String,
    val anchorPositionMs: Long,
    val anchorSessionUs: Long,
    val playing: Boolean,
    val generation: Long,
) {
    /**
     * Where the track should be at [atSessionUs], derived from the anchor alone.
     *
     * Before the anchor (a scheduled command whose deadline has not arrived) the expected position
     * is the anchor position itself — never a negative extrapolation. [durationMs] clamps the top
     * when it is known; `null` means the decoder has not reported one yet and no clamp is applied.
     */
    fun expectedPositionMs(
        atSessionUs: Long,
        durationMs: Long? = null,
    ): Long {
        if (!playing || atSessionUs <= anchorSessionUs) return clamp(anchorPositionMs, durationMs)
        val elapsedMs = (atSessionUs - anchorSessionUs) / MICROS_PER_MS
        return clamp(anchorPositionMs + elapsedMs, durationMs)
    }

    /**
     * `actual - expected`: positive means this device is **ahead** of the authoritative timeline
     * (it must slow down or seek back), negative means behind.
     */
    fun driftMs(
        actualPositionMs: Long,
        atSessionUs: Long,
        durationMs: Long? = null,
    ): Long = actualPositionMs - expectedPositionMs(atSessionUs, durationMs)

    private fun clamp(
        positionMs: Long,
        durationMs: Long?,
    ): Long {
        val floored = positionMs.coerceAtLeast(0L)
        return if (durationMs != null && durationMs > 0) floored.coerceAtMost(durationMs) else floored
    }

    companion object {
        const val MICROS_PER_MS: Long = 1_000
    }
}
