package com.ridelink.core.playback

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/queue/queue_vectors.json` — PROTOCOL §9's queue algebra, shared
 * byte-for-byte with `SharedQueueVectorTests` on iOS.
 */
class SharedQueueVectorTest {
    private val doc by lazy { Vectors.load("queue/queue_vectors.json").jsonObject }

    @Test
    fun constantsMatchTheVectorFile() {
        val constants = doc["constants"]!!.jsonObject
        assertEquals(constants["order_step"]!!.jsonPrimitive.long, PlaybackBounds.QUEUE_ORDER_STEP)
        assertEquals(constants["max_queue_items"]!!.jsonPrimitive.content.toInt(), PlaybackBounds.MAX_QUEUE_ITEMS)
    }

    @Test
    fun scenarios() {
        val scenarios = doc["scenarios"]!!.jsonArray
        for (element in scenarios) {
            val scenario = element.jsonObject
            val name = scenario["name"]!!.jsonPrimitive.content
            var state = SharedQueueState()
            for (stepElement in scenario["steps"]!!.jsonArray) {
                state = applyStep(name, state, stepElement.jsonObject)
            }
        }
        assertTrue(scenarios.isNotEmpty())
    }

    private fun applyStep(
        name: String,
        state: SharedQueueState,
        step: JsonObject,
    ): SharedQueueState {
        val op = step["op"]!!.jsonObject
        return when (val kind = op["kind"]!!.jsonPrimitive.content) {
            "Add", "Remove", "Move" -> {
                val outcome = SharedQueue.apply(state, mutationOf(kind, op))
                assertOutcome(name, step, outcome)
                outcome.state
            }
            "Next", "Previous" -> {
                val result = SharedQueue.step(state, if (kind == "Next") 1 else -1)
                assertEquals(step["moved"]!!.jsonPrimitive.content.toBoolean(), result.moved, "$name: moved")
                assertSelected(name, step["selected"]!!, result.selected)
                assertState(name, step["state_after"]!!.jsonObject, result.state)
                result.state
            }
            else -> {
                val selected = SharedQueue.select(state, op["queue_item_id"]!!.jsonPrimitive.content)
                assertState(name, step["state_after"]!!.jsonObject, selected)
                selected
            }
        }
    }

    private fun mutationOf(
        kind: String,
        op: JsonObject,
    ): SharedQueueMutation =
        when (kind) {
            "Add" -> SharedQueueMutation.Add(op["items"]!!.jsonArray.map { addItemOf(it.jsonObject) })
            "Remove" -> SharedQueueMutation.Remove(op["queue_item_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
            else ->
                SharedQueueMutation.Move(
                    op["queue_item_id"]!!.jsonPrimitive.content,
                    op["to_index"]!!.jsonPrimitive.content.toInt(),
                )
        }

    private fun assertSelected(
        name: String,
        expected: kotlinx.serialization.json.JsonElement,
        actual: SharedQueueItem?,
    ) {
        if (expected is JsonNull) {
            assertNull(actual, "$name: selected")
        } else {
            assertEquals(expected.jsonObject["queue_item_id"]!!.jsonPrimitive.content, actual?.queueItemId, "$name: selected")
        }
    }

    @Test
    fun snapshots() {
        val rows = doc["snapshots"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val currentIndexRaw = input["current_index"]!!
            val state =
                SharedQueue.applySnapshot(
                    revision = input["revision"]!!.jsonPrimitive.long,
                    items = input["items"]!!.jsonArray.map { snapshotItemOf(it.jsonObject) },
                    currentIndex = if (currentIndexRaw is JsonNull) null else currentIndexRaw.jsonPrimitive.content.toInt(),
                )
            assertState(name, row["expected"]!!.jsonObject, state)
        }
        assertTrue(rows.isNotEmpty())
    }

    /** The 1 000-item cap (ADR-024 §6), built from the row's `item_count` rather than 1 000 literals. */
    @Test
    fun cap() {
        val rows = doc["cap"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val count = input["item_count"]!!.jsonPrimitive.content.toInt()
            val adding = input["adding"]!!.jsonPrimitive.content.toInt()
            val start =
                SharedQueueState(
                    items =
                        (0 until count).map {
                            SharedQueueItem(ulid(it), hashFor(it), PEER, (it + 1L) * PlaybackBounds.QUEUE_ORDER_STEP)
                        },
                )
            val additions =
                (count until count + adding).map {
                    QueueAddItem(ulid(it), hashFor(it), PEER, PlaybackBounds.QUEUE_POSITION_END)
                }
            val outcome = SharedQueue.apply(start, SharedQueueMutation.Add(additions))
            assertEquals(expected["changed"]!!.jsonPrimitive.content.toBoolean(), outcome.changed, "$name: changed")
            val expectedRejection = expected["rejection"]!!
            if (expectedRejection is JsonNull) {
                assertNull(outcome.rejection, "$name: rejection")
            } else {
                assertEquals(SharedQueueRejection.valueOf(expectedRejection.jsonPrimitive.content), outcome.rejection, "$name: rejection")
            }
            assertEquals(expected["revision"]!!.jsonPrimitive.long, outcome.state.revision, "$name: revision")
        }
        assertTrue(rows.isNotEmpty())
    }

    private fun assertOutcome(
        name: String,
        step: JsonObject,
        outcome: SharedQueueOutcome,
    ) {
        assertEquals(step["changed"]!!.jsonPrimitive.content.toBoolean(), outcome.changed, "$name: changed")
        val expectedRejection = step["rejection"]!!
        if (expectedRejection is JsonNull) {
            assertNull(outcome.rejection, "$name: rejection")
        } else {
            assertEquals(SharedQueueRejection.valueOf(expectedRejection.jsonPrimitive.content), outcome.rejection, "$name: rejection")
        }
        assertState(name, step["state_after"]!!.jsonObject, outcome.state)
    }

    private fun assertState(
        name: String,
        expected: JsonObject,
        actual: SharedQueueState,
    ) {
        assertEquals(expected["revision"]!!.jsonPrimitive.long, actual.revision, "$name: revision")
        val expectedCurrent = expected["current_item_id"]!!
        assertEquals(
            if (expectedCurrent is JsonNull) null else expectedCurrent.jsonPrimitive.content,
            actual.currentItemId,
            "$name: current item",
        )
        val expectedItems = expected["items"]!!.jsonArray
        assertEquals(expectedItems.size, actual.items.size, "$name: item count")
        expectedItems.forEachIndexed { index, itemElement ->
            val item = itemElement.jsonObject
            assertEquals(item["queue_item_id"]!!.jsonPrimitive.content, actual.items[index].queueItemId, "$name: item $index id")
            assertEquals(item["track_hash"]!!.jsonPrimitive.content, actual.items[index].trackHash.value, "$name: item $index hash")
            assertEquals(item["added_by"]!!.jsonPrimitive.content, actual.items[index].addedBy.value, "$name: item $index added_by")
            assertEquals(item["order"]!!.jsonPrimitive.long, actual.items[index].order, "$name: item $index order")
        }
    }

    private fun addItemOf(json: JsonObject) =
        QueueAddItem(
            queueItemId = json["queue_item_id"]!!.jsonPrimitive.content,
            trackHash = ContentHash(json["track_hash"]!!.jsonPrimitive.content),
            addedBy = PeerId(json["added_by"]!!.jsonPrimitive.content),
            position = json["position"]!!.jsonPrimitive.content,
        )

    private fun snapshotItemOf(json: JsonObject) =
        SharedQueueItem(
            queueItemId = json["queue_item_id"]!!.jsonPrimitive.content,
            trackHash = ContentHash(json["track_hash"]!!.jsonPrimitive.content),
            addedBy = PeerId(json["added_by"]!!.jsonPrimitive.content),
            order = json["order"]!!.jsonPrimitive.long,
        )

    private fun ulid(n: Int): String = "01J9Z4M0Q7XK2V8R3T6Y1N%04d".format(n).take(26).padEnd(26, '0')

    private fun hashFor(n: Int): ContentHash = ContentHash("sha256:%064x".format(n))

    private companion object {
        val PEER = PeerId("a3f1000000000001")
    }
}
