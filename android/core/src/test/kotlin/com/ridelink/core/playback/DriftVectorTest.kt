package com.ridelink.core.playback

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/drift/drift_vectors.json` — ARCHITECTURE §7.3 / ADR-004's ladder, shared
 * byte-for-byte with `DriftVectorTests` on iOS.
 */
class DriftVectorTest {
    private val doc by lazy { Vectors.load("drift/drift_vectors.json").jsonObject }

    @Test
    fun constantsMatchTheVectorFile() {
        val constants = doc["constants"]!!.jsonObject
        assertEquals(constants["dead_band_ms"]!!.jsonPrimitive.long, DriftController.DEAD_BAND_MS)
        assertEquals(constants["nudge_max_ms"]!!.jsonPrimitive.long, DriftController.NUDGE_MAX_MS)
        assertEquals(constants["fail_ms"]!!.jsonPrimitive.long, DriftController.FAIL_MS)
        assertEquals(constants["converged_ms"]!!.jsonPrimitive.long, DriftController.CONVERGED_MS)
        assertEquals(constants["rate_slower"]!!.jsonPrimitive.double, DriftController.RATE_SLOWER)
        assertEquals(constants["rate_normal"]!!.jsonPrimitive.double, DriftController.RATE_NORMAL)
        assertEquals(constants["rate_faster"]!!.jsonPrimitive.double, DriftController.RATE_FASTER)
        assertEquals(constants["max_hard_seeks_in_window"]!!.jsonPrimitive.content.toInt(), DriftController.MAX_HARD_SEEKS_IN_WINDOW)
        assertEquals(constants["hard_seek_window_us"]!!.jsonPrimitive.long, DriftController.HARD_SEEK_WINDOW_US)
        assertEquals(constants["reference_settle_us"]!!.jsonPrimitive.long, DriftController.REFERENCE_SETTLE_US)
        assertEquals(constants["max_reference_age_us"]!!.jsonPrimitive.long, DriftController.MAX_REFERENCE_AGE_US)
        assertEquals(
            constants["max_restart_compensation_ms"]!!.jsonPrimitive.long,
            DriftController.MAX_RESTART_COMPENSATION_MS,
        )
    }

    @Test
    fun singleEvaluations() {
        val rows = doc["single"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val outcome = DriftController.evaluate(stateOf(input["state"]!!.jsonObject), inputOf(input))
            val expected = row["expected"]!!.jsonObject
            assertAction(name, expected["action"]!!.jsonObject, outcome.action)
            assertState(name, expected["state"]!!.jsonObject, outcome.state)
            assertEquals(nullableLong(expected["error_ms"]!!), outcome.errorMs, "$name: error")
        }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun sequences() {
        val sequences = doc["sequences"]!!.jsonArray
        for (element in sequences) {
            val sequence = element.jsonObject
            val name = sequence["name"]!!.jsonPrimitive.content
            var state = DriftController.reset()
            for (stepElement in sequence["steps"]!!.jsonArray) {
                val step = stepElement.jsonObject
                val outcome = DriftController.evaluate(state, inputOf(step["input"]!!.jsonObject))
                assertAction(name, step["action"]!!.jsonObject, outcome.action)
                assertState(name, step["state_after"]!!.jsonObject, outcome.state)
                assertEquals(nullableLong(step["error_ms"]!!), outcome.errorMs, "$name: error")
                state = outcome.state
            }
        }
        assertTrue(sequences.isNotEmpty())
    }

    @Test
    fun epochs() {
        val rows = doc["epochs"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val before = stateOf(row["state"]!!.jsonObject)
            val after =
                when (val op = row["op"]!!.jsonPrimitive.content) {
                    "new_epoch" -> DriftController.newEpoch(before)
                    "reset" -> DriftController.reset()
                    else -> error("$name: unknown op $op")
                }
            assertState(name, row["expected"]!!.jsonObject, after)
        }
        assertTrue(rows.isNotEmpty())
    }

    private fun nullableLong(json: JsonElement): Long? = if (json is JsonNull) null else json.jsonPrimitive.long

    private fun stateOf(json: JsonObject): DriftState =
        DriftState(
            nudging = json["nudging"]!!.jsonPrimitive.content.toBoolean(),
            nudgeRate = json["nudge_rate"]!!.jsonPrimitive.double,
            hardSeekAtSessionUs = json["hard_seek_at_session_us"]!!.jsonArray.map { it.jsonPrimitive.long },
            failed = json["failed"]!!.jsonPrimitive.content.toBoolean(),
            restartCompensationMs = json["restart_compensation_ms"]!!.jsonPrimitive.long,
            pendingSeek =
                (json["pending_seek"] as? JsonObject)?.let {
                    PendingSeek(it["compensation_ms"]!!.jsonPrimitive.long, it["anchor_session_us"]!!.jsonPrimitive.long)
                },
            routeChangeCount = nullableLong(json["route_change_count"]!!),
        )

    private fun inputOf(json: JsonObject): DriftInput =
        DriftInput(
            driftMs = json["drift_ms"]!!.jsonPrimitive.long,
            nowSessionUs = json["now_session_us"]!!.jsonPrimitive.long,
            expectedPositionMs = json["expected_position_ms"]!!.jsonPrimitive.long,
            playing = json["playing"]!!.jsonPrimitive.content.toBoolean(),
            routeTransitioning = json["route_transitioning"]!!.jsonPrimitive.content.toBoolean(),
            anchorSessionUs = json["anchor_session_us"]!!.jsonPrimitive.long,
            reference =
                (json["reference"] as? JsonObject)?.let {
                    DriftReference(
                        driftMs = it["drift_ms"]!!.jsonPrimitive.long,
                        atSessionUs = it["at_session_us"]!!.jsonPrimitive.long,
                        playing = it["playing"]!!.jsonPrimitive.content.toBoolean(),
                    )
                },
            routeChangeCount = json["route_change_count"]!!.jsonPrimitive.long,
        )

    private fun assertAction(
        name: String,
        expected: JsonObject,
        actual: DriftAction,
    ) {
        when (expected["kind"]!!.jsonPrimitive.content) {
            "NONE" -> assertEquals(DriftAction.None, actual, "$name: action")
            "RESTORE_RATE" -> assertEquals(DriftAction.RestoreRate, actual, "$name: action")
            "DECLARE_SYNC_FAILURE" -> assertEquals(DriftAction.DeclareSyncFailure, actual, "$name: action")
            "NUDGE" -> {
                assertTrue(actual is DriftAction.Nudge, "$name: expected NUDGE, got $actual")
                assertEquals(expected["rate"]!!.jsonPrimitive.double, actual.rate, "$name: nudge rate")
            }
            else -> {
                assertTrue(actual is DriftAction.HardSeek, "$name: expected HARD_SEEK, got $actual")
                assertEquals(expected["position_ms"]!!.jsonPrimitive.long, actual.positionMs, "$name: seek position")
            }
        }
    }

    private fun assertState(
        name: String,
        expected: JsonObject,
        actual: DriftState,
    ) {
        assertEquals(stateOf(expected), actual, "$name: state after")
    }
}
