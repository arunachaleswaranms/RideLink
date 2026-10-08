package com.ridelink.app.ui

import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.PlayerState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 9A.5 §11: Play is offered whenever pressing it can start something. */
class TransportAvailabilityTest {
    private val track = LocalEntryId("cccccccc-0000-0000-0000-000000000001")
    private val queued = LocalQueueState(items = listOf(LocalQueueItem("q1", track, 0)))

    @Test
    fun `a queue with nothing selected can be started with Play`() {
        val available = transportAvailability(queued, PlayerState())
        assertTrue(available.canPlayPause, "Play used to stay disabled here until Next was pressed")
        assertTrue(available.canSkip)
    }

    @Test
    fun `nothing queued and nothing loaded offers no transport`() {
        val available = transportAvailability(LocalQueueState(), PlayerState())
        assertFalse(available.canPlayPause)
        assertFalse(available.canSkip)
    }

    @Test
    fun `a loaded track can be resumed even with an empty queue`() {
        assertTrue(transportAvailability(LocalQueueState(), PlayerState(localEntryId = track)).canPlayPause)
        assertTrue(transportAvailability(LocalQueueState(), PlayerState(playing = true)).canPlayPause)
    }

    @Test
    fun `labels count tracks plainly`() {
        kotlin.test.assertEquals("Empty", upNextSummary(0))
        kotlin.test.assertEquals("1 track", upNextSummary(1))
        kotlin.test.assertEquals("Track 3 of 10", upNextSummary(10, currentIndex = 2))
        val grouped = "%,d".format(3460) // the user's own digit grouping
        kotlin.test.assertEquals("$grouped tracks", tracks(3460))
        kotlin.test.assertEquals("12 of $grouped tracks", libraryCountLabel(12, 3460, "road"))
        kotlin.test.assertEquals("$grouped tracks", libraryCountLabel(3460, 3460, ""))
    }
}
