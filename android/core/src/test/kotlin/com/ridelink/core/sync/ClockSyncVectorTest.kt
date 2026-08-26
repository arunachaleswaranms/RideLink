package com.ridelink.core.sync

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** protocol/vectors/clock/clock_vectors.json, ARCHITECTURE §7.1. */
class ClockSyncVectorTest {
    private fun sampleOf(obj: JsonObject) =
        ClockSync.Sample(
            t1MonoUs = obj["t1"]!!.jsonPrimitive.long,
            t2MonoUs = obj["t2"]!!.jsonPrimitive.long,
            t3MonoUs = obj["t3"]!!.jsonPrimitive.long,
            t4MonoUs = obj["t4"]!!.jsonPrimitive.long,
        )

    private fun stateOf(obj: JsonObject?): ClockSync.EstimatorState? {
        if (obj == null) return null
        val offset = obj["offsetUs"]!!.jsonPrimitive.long
        val pending = obj["pendingOffsetUs"]?.takeIf { it.jsonPrimitive.content != "null" }?.jsonPrimitive?.long
        return ClockSync.EstimatorState(offset, pending)
    }

    private fun statusOf(name: String): ClockSync.WindowStatus =
        when (name) {
            "accepted" -> ClockSync.WindowStatus.ACCEPTED
            "rejected_pending_confirmation" -> ClockSync.WindowStatus.REJECTED_PENDING_CONFIRMATION
            "confirmed" -> ClockSync.WindowStatus.CONFIRMED
            "no_estimate" -> ClockSync.WindowStatus.NO_ESTIMATE
            else -> error("unknown status $name")
        }

    @TestFactory
    fun clockVectors(): List<DynamicTest> {
        val root = Vectors.load("clock/clock_vectors.json").jsonObject
        return root["vectors"]!!.jsonArray.map { element ->
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            DynamicTest.dynamicTest(name) { runVector(vector) }
        }
    }

    private fun runVector(vector: JsonObject) {
        val input = vector["input"]!!.jsonObject
        val expected = vector["expected"]!!.jsonObject

        val previous = input["previous_state"]?.takeIf { it != kotlinx.serialization.json.JsonNull }?.jsonObject?.let(::stateOf)
        val samples = input["samples"]!!.jsonArray.map { sampleOf(it.jsonObject) }

        val result = ClockSync.applyWindow(previous, samples)

        assertEquals(statusOf(expected["status"]!!.jsonPrimitive.content), result.status, "status")
        assertLongOrNull(expected["offset_us"], result.offsetUs, "offset_us")
        assertLongOrNull(expected["rtt_us"], result.rttUs, "rtt_us")
        assertLongOrNull(expected["jitter_us"], result.jitterUs, "jitter_us")

        val expectedState = expected["new_state"]?.takeIf { it != kotlinx.serialization.json.JsonNull }?.jsonObject?.let(::stateOf)
        assertEquals(expectedState, result.newState, "new_state")
    }

    private fun assertLongOrNull(
        expected: kotlinx.serialization.json.JsonElement?,
        actual: Long?,
        field: String,
    ) {
        if (expected == null || expected == kotlinx.serialization.json.JsonNull) {
            assertNull(actual, field)
        } else {
            assertEquals(expected.jsonPrimitive.long, actual, field)
        }
    }
}
