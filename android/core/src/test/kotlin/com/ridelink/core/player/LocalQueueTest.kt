package com.ridelink.core.player

import com.ridelink.core.model.LocalEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun localId(byte: String): LocalEntryId = LocalEntryId(byte.padEnd(8, '0').take(8) + "-0000-0000-0000-000000000000")

private fun item(
    id: String,
    hashByte: String = id,
): LocalQueueItem = LocalQueueItem(id = id, localEntryId = localId(hashByte), insertedAtMonoUs = 0L)

class LocalQueueTest {
    @Test
    fun `add appends without touching current selection`() {
        val outcome = LocalQueue.reduce(LocalQueueState(), LocalQueueAction.Add(item("a1")))
        assertEquals(listOf(item("a1")), outcome.state.items)
        assertNull(outcome.state.currentId)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `duplicate track added twice produces two independent queue entries`() {
        val sameTrack = localId("aa")
        val first = LocalQueueItem("q1", sameTrack, 0)
        val second = LocalQueueItem("q2", sameTrack, 1)
        var state = LocalQueue.reduce(LocalQueueState(), LocalQueueAction.Add(first)).state
        state = LocalQueue.reduce(state, LocalQueueAction.Add(second)).state
        assertEquals(2, state.items.size)
        // Removing one leaves the other, distinguishable by queue-item id despite equal content.
        val afterRemove = LocalQueue.reduce(state, LocalQueueAction.Remove("q1")).state
        assertEquals(listOf(second), afterRemove.items)
    }

    @Test
    fun `next from no selection starts at the first item and plays it`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Next)
        assertEquals("a1", outcome.state.currentId)
        assertEquals(listOf(LocalQueueEffect.LoadAndPlay(localId("a1"))), outcome.effects)
    }

    @Test
    fun `previous from no selection is a no-op`() {
        val state = LocalQueueState(items = listOf(item("a1")))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Previous)
        assertEquals(state, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `next past the last item stops rather than wrapping`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")), currentId = "b2")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Next)
        assertNull(outcome.state.currentId)
        assertEquals(listOf(LocalQueueEffect.StopPlayback), outcome.effects)
    }

    @Test
    fun `previous at the first item stays put`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Previous)
        assertEquals(state, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `next and previous move between adjacent items and play them`() {
        var state = LocalQueueState(items = listOf(item("a1"), item("b2"), item("c3")), currentId = "a1")
        val toB = LocalQueue.reduce(state, LocalQueueAction.Next)
        assertEquals("b2", toB.state.currentId)
        assertEquals(listOf(LocalQueueEffect.LoadAndPlay(localId("b2"))), toB.effects)
        state = toB.state
        val backToA = LocalQueue.reduce(state, LocalQueueAction.Previous)
        assertEquals("a1", backToA.state.currentId)
        assertEquals(listOf(LocalQueueEffect.LoadAndPlay(localId("a1"))), backToA.effects)
    }

    @Test
    fun `removing the current item hands playback to its successor`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2"), item("c3")), currentId = "b2")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Remove("b2"))
        assertEquals(listOf(item("a1"), item("c3")), outcome.state.items)
        assertEquals("c3", outcome.state.currentId)
        assertEquals(listOf(LocalQueueEffect.LoadAndPlay(localId("c3"))), outcome.effects)
    }

    @Test
    fun `removing the current last item stops playback`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")), currentId = "b2")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Remove("b2"))
        assertEquals(listOf(item("a1")), outcome.state.items)
        assertNull(outcome.state.currentId)
        assertEquals(listOf(LocalQueueEffect.StopPlayback), outcome.effects)
    }

    @Test
    fun `removing a non-current item only shifts positions`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2"), item("c3")), currentId = "c3")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Remove("a1"))
        assertEquals(listOf(item("b2"), item("c3")), outcome.state.items)
        assertEquals("c3", outcome.state.currentId)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `removing an unknown id is a no-op`() {
        val state = LocalQueueState(items = listOf(item("a1")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Remove("ghost"))
        assertEquals(state, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `clearing during playback stops playback`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Clear)
        assertEquals(LocalQueueState(), outcome.state)
        assertEquals(listOf(LocalQueueEffect.StopPlayback), outcome.effects)
    }

    @Test
    fun `clearing an idle queue emits no effect`() {
        val state = LocalQueueState(items = listOf(item("a1")))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Clear)
        assertEquals(LocalQueueState(), outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `select jumps directly to an item and plays it`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2"), item("c3")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Select("c3"))
        assertEquals("c3", outcome.state.currentId)
        assertEquals(listOf(LocalQueueEffect.LoadAndPlay(localId("c3"))), outcome.effects)
    }

    @Test
    fun `select of an unknown id is a no-op`() {
        val state = LocalQueueState(items = listOf(item("a1")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Select("ghost"))
        assertEquals(state, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `move relocates an item without touching current selection`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2"), item("c3")), currentId = "a1")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Move("c3", 0))
        assertEquals(listOf(item("c3"), item("a1"), item("b2")), outcome.state.items)
        assertEquals("a1", outcome.state.currentId)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `move clamps an out-of-range target index`() {
        val state = LocalQueueState(items = listOf(item("a1"), item("b2")))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Move("a1", 99))
        assertEquals(listOf(item("b2"), item("a1")), outcome.state.items)
    }

    @Test
    fun `move of an unknown id is a no-op`() {
        val state = LocalQueueState(items = listOf(item("a1")))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Move("ghost", 0))
        assertEquals(state, outcome.state)
    }

    @Test
    fun `fifty consecutive next presses never crash and always land on a real item or stop`() {
        val items = (0 until 5).map { item("t$it", hashByte = "a$it") }
        var state = LocalQueueState(items = items)
        repeat(50) {
            val outcome = LocalQueue.reduce(state, LocalQueueAction.Next)
            state = outcome.state
            // Every effect must be one of the two legal shapes.
            outcome.effects.forEach { effect ->
                assertTrue(effect is LocalQueueEffect.LoadAndPlay || effect is LocalQueueEffect.StopPlayback)
            }
            if (state.currentId == null) {
                // Stopped at the end; the next Next must restart from the first item, not stay stuck.
                state = LocalQueue.reduce(state, LocalQueueAction.Next).state
            }
        }
    }

    // ---- Phase 9A.5 §11: Play with a queue and no selection -----------------------------------

    @Test
    fun `play with items queued and nothing selected starts the first item`() {
        val outcome = LocalQueue.reduce(LocalQueueState(items = listOf(item("a1"), item("b2"))), LocalQueueAction.Play)
        assertEquals("a1", outcome.state.currentId)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.LoadAndPlay(localId("a1"))), outcome.effects)
    }

    @Test
    fun `play after the queue ran past its end starts it again from the first item`() {
        val ended = LocalQueue.reduce(LocalQueueState(listOf(item("a1")), currentId = "a1"), LocalQueueAction.Next).state
        assertNull(ended.currentId)
        val outcome = LocalQueue.reduce(ended, LocalQueueAction.Play)
        assertEquals("a1", outcome.state.currentId)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.LoadAndPlay(localId("a1"))), outcome.effects)
    }

    @Test
    fun `play with a current item resumes it and changes nothing`() {
        val state = LocalQueueState(listOf(item("a1"), item("b2")), currentId = "b2")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Play)
        assertEquals(state, outcome.state)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.ResumePlayback), outcome.effects)
    }

    @Test
    fun `play with an empty queue does nothing - there is no local track to resume`() {
        val outcome = LocalQueue.reduce(LocalQueueState(), LocalQueueAction.Play)
        assertEquals(LocalQueueState(), outcome.state)
        assertEquals(emptyList(), outcome.effects, "a resume here would restart the track a Clear just removed")
    }

    @Test
    fun `play after Clear does not resume the cleared track`() {
        val cleared = LocalQueue.reduce(LocalQueueState(listOf(item("a1")), currentId = "a1"), LocalQueueAction.Clear)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.StopPlayback), cleared.effects)
        assertEquals(emptyList(), LocalQueue.reduce(cleared.state, LocalQueueAction.Play).effects)
    }

    @Test
    fun `play starts the first entry even when the same track is queued twice`() {
        val sameTrack = localId("aa")
        val state = LocalQueueState(listOf(LocalQueueItem("q1", sameTrack, 0), LocalQueueItem("q2", sameTrack, 1)))
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Play)
        assertEquals("q1", outcome.state.currentId, "the first queue entry, by entry id — not either copy of the track")
    }

    @Test
    fun `removing one of two copies of the current track removes exactly that entry`() {
        val sameTrack = localId("aa")
        val state = LocalQueueState(listOf(LocalQueueItem("q1", sameTrack, 0), LocalQueueItem("q2", sameTrack, 1)), currentId = "q2")
        val outcome = LocalQueue.reduce(state, LocalQueueAction.Remove("q1"))
        assertEquals(listOf("q2"), outcome.state.items.map { it.id })
        assertEquals("q2", outcome.state.currentId)
        assertTrue(outcome.effects.isEmpty(), "removing the other copy must not restart the one playing")
    }
}
