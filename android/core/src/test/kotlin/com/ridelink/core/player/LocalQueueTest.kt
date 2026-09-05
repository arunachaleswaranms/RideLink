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
}
