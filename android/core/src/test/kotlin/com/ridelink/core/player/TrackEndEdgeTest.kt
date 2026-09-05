package com.ridelink.core.player

import com.ridelink.core.model.LocalEntryId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhausts [TrackEndEdge]. The mirror is `RideLinkCoreTests.TrackEndEdgeTests`.
 *
 * Every case here is a state pair a real player binding can actually emit — in particular the two
 * repeated "still ended" pairs are exactly the ExoPlayer `STATE_ENDED` /
 * `onIsPlayingChanged(false)` double-emission that produced the real restart-loop bug this type
 * fixes (see [TrackEndEdge]'s own KDoc).
 */
class TrackEndEdgeTest {
    private val track = LocalEntryId("aa000000-0000-0000-0000-000000000000")

    private fun playing(positionMs: Long = 0) =
        PlayerState(localEntryId = track, positionMs = positionMs, durationMs = DURATION_MS, playing = true)

    private fun ended() = PlayerState(localEntryId = track, positionMs = DURATION_MS, durationMs = DURATION_MS, playing = false)

    private fun missing() = PlayerState(localEntryId = track, error = MusicFailure.FILE_MISSING)

    @Test
    fun `not done to ended fires exactly once`() {
        assertTrue(TrackEndEdge.advancedNow(previous = playing(POSITION_MID_MS), current = ended()))
    }

    @Test
    fun `a repeated ended emission does not fire again`() {
        assertFalse(TrackEndEdge.advancedNow(previous = ended(), current = ended()))
    }

    @Test
    fun `two identical ExoPlayer-shaped emissions for one finish fire only on the first`() {
        // STATE_ENDED's own updateState, then onIsPlayingChanged(false)'s — both produce this exact
        // PlayerState, which is the real sequence that used to double-dispatch Next.
        val fromStateEnded = ended()
        val fromIsPlayingChanged = ended()
        assertTrue(TrackEndEdge.advancedNow(previous = playing(POSITION_MID_MS), current = fromStateEnded))
        assertFalse(TrackEndEdge.advancedNow(previous = fromStateEnded, current = fromIsPlayingChanged))
    }

    @Test
    fun `not done to not done never fires`() {
        assertFalse(TrackEndEdge.advancedNow(previous = playing(0), current = playing(POSITION_MID_MS)))
    }

    @Test
    fun `ended to a fresh load's reset state never fires`() {
        val freshLoad = PlayerState(localEntryId = track)
        assertFalse(TrackEndEdge.advancedNow(previous = ended(), current = freshLoad))
    }

    @Test
    fun `not done to file missing fires exactly once`() {
        assertTrue(TrackEndEdge.advancedNow(previous = playing(POSITION_MID_MS), current = missing()))
    }

    @Test
    fun `a repeated file-missing emission does not fire again`() {
        assertFalse(TrackEndEdge.advancedNow(previous = missing(), current = missing()))
    }

    @Test
    fun `a decode failure does not count as done`() {
        val decodeFailed = PlayerState(localEntryId = track, error = MusicFailure.DECODE_FAILED)
        assertFalse(TrackEndEdge.advancedNow(previous = playing(0), current = decodeFailed))
    }

    private companion object {
        const val DURATION_MS = 509L
        const val POSITION_MID_MS = 200L
    }
}
