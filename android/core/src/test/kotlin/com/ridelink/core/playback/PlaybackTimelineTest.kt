package com.ridelink.core.playback

import com.ridelink.core.model.ContentHash
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PlaybackTimeline`'s extrapolation, which is what every drift measurement in Phase 5 is taken
 * against (this phase's brief §33). Mirrored by `PlaybackTimelineTests` on iOS.
 *
 * Not vector-driven: there is no wire shape here and no cross-platform *encoding* to pin — only
 * arithmetic, which both platforms' own suites assert identically.
 */
class PlaybackTimelineTest {
    private val hash = ContentHash("sha256:" + "1f3a".repeat(16))

    private fun timeline(
        anchorPositionMs: Long = 10_000,
        anchorSessionUs: Long = 1_000_000,
        playing: Boolean = true,
    ) = PlaybackTimeline(hash, ITEM, anchorPositionMs, anchorSessionUs, playing, generation = 1)

    @Test
    fun `a paused timeline never advances`() {
        val paused = timeline(playing = false)
        assertEquals(10_000, paused.expectedPositionMs(1_000_000))
        assertEquals(10_000, paused.expectedPositionMs(999_000_000))
    }

    @Test
    fun `a playing timeline advances one millisecond per thousand microseconds`() {
        val playing = timeline()
        assertEquals(10_000, playing.expectedPositionMs(1_000_000))
        assertEquals(10_001, playing.expectedPositionMs(1_001_000))
        assertEquals(15_000, playing.expectedPositionMs(6_000_000))
    }

    /** A scheduled command whose deadline has not arrived must not extrapolate backwards. */
    @Test
    fun `before the anchor the expected position is the anchor itself`() {
        val playing = timeline(anchorSessionUs = 5_000_000)
        assertEquals(10_000, playing.expectedPositionMs(1_000_000))
        assertEquals(10_000, playing.expectedPositionMs(4_999_999))
    }

    @Test
    fun `elapsed microseconds truncate toward zero, identically on both platforms`() {
        val playing = timeline()
        assertEquals(10_000, playing.expectedPositionMs(1_000_999))
        assertEquals(10_001, playing.expectedPositionMs(1_001_999))
    }

    @Test
    fun `a known duration clamps the top`() {
        val playing = timeline()
        assertEquals(12_000, playing.expectedPositionMs(999_000_000, durationMs = 12_000))
        assertEquals(15_000, playing.expectedPositionMs(6_000_000, durationMs = 12_000_000))
    }

    @Test
    fun `an unknown duration applies no clamp`() {
        assertEquals(1_010_000, timeline().expectedPositionMs(1_001_000_000, durationMs = null))
    }

    @Test
    fun `a negative anchor position floors at zero rather than reporting a negative one`() {
        assertEquals(0, timeline(anchorPositionMs = -500, playing = false).expectedPositionMs(1_000_000))
    }

    @Test
    fun `drift is actual minus expected, and its sign says which way to correct`() {
        val playing = timeline()
        // Ahead of the timeline: positive drift, so the ladder must slow this device down.
        assertEquals(40, playing.driftMs(actualPositionMs = 15_040, atSessionUs = 6_000_000))
        // Behind: negative drift, so it must speed up.
        assertEquals(-40, playing.driftMs(actualPositionMs = 14_960, atSessionUs = 6_000_000))
        assertEquals(0, playing.driftMs(actualPositionMs = 15_000, atSessionUs = 6_000_000))
    }

    private companion object {
        const val ITEM = "01J9Z4M0Q7XK2V8R3T6Y1N5B2C"
    }
}
