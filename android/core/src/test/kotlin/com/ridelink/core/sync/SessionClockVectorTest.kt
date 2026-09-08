package com.ridelink.core.sync

import com.ridelink.core.playback.ScheduledCommand
import com.ridelink.core.playback.ScheduledCommandDecision
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/session-clock/session_clock_vectors.json` — ARCHITECTURE §7.1's mapping,
 * §7.2's scheduling lead and PROTOCOL §5 rule 2's schedule-or-apply-immediately decision, shared
 * byte-for-byte with `SessionClockVectorTests` on iOS.
 */
class SessionClockVectorTest {
    private val doc by lazy { Vectors.load("session-clock/session_clock_vectors.json").jsonObject }

    @Test
    fun mappingRoundTrips() {
        val rows = doc["mapping"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val localMonoUs = input["local_mono_us"]!!.jsonPrimitive.long
            val offset = input["offset_to_leader_us"]!!.jsonPrimitive.long
            val sessionUs = SessionClock.sessionUs(localMonoUs, offset)
            assertEquals(expected["session_us"]!!.jsonPrimitive.long, sessionUs, "$name: session_us")
            assertEquals(
                expected["round_trip_local_mono_us"]!!.jsonPrimitive.long,
                SessionClock.localMonoUs(sessionUs, offset),
                "$name: round trip back to local monotonic",
            )
        }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun rttP95AndLead() {
        val rows = doc["rtt_p95"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val rtts = row["input"]!!.jsonObject["rtts_us"]!!.jsonArray.map { it.jsonPrimitive.long }
            val expected = row["expected"]!!.jsonObject
            val p95 = ClockSync.rttP95Us(rtts)
            val expectedP95 = expected["rtt_p95_us"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.long }
            assertEquals(expectedP95, p95, "$name: rtt_p95_us")
            assertEquals(expected["lead_us"]!!.jsonPrimitive.long, SessionClock.leadUs(p95), "$name: lead_us")

            // The bounded window must agree with the pure function it delegates to, for every input
            // short enough to fit — that is what makes the window a cache rather than a second rule.
            if (rtts.size <= ClockSync.RTT_WINDOW_CAPACITY) {
                val window = ClockSync.RttWindow()
                rtts.forEach { window.record(it) }
                assertEquals(p95, window.p95Us(), "$name: RttWindow agrees with rttP95Us")
            }
        }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun leadBoundaries() {
        val rows = doc["lead"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val raw = row["input"]!!.jsonObject["rtt_p95_us"]!!
            val rtt = if (raw is JsonNull) null else raw.jsonPrimitive.long
            val expected = row["expected"]!!.jsonObject["lead_us"]!!.jsonPrimitive.long
            assertEquals(expected, SessionClock.leadUs(rtt), "$name: lead_us")
        }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun scheduleDecisions() {
        val rows = doc["schedule"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val decision =
                ScheduledCommand.decide(
                    effectiveAtSessionUs = input["effective_at_session_us"]!!.jsonPrimitive.long,
                    nowLocalMonoUs = input["now_local_mono_us"]!!.jsonPrimitive.long,
                    offsetToLeaderUs = input["offset_to_leader_us"]!!.jsonPrimitive.long,
                )
            when (expected["decision"]!!.jsonPrimitive.content) {
                "SCHEDULE" -> {
                    assertTrue(decision is ScheduledCommandDecision.Schedule, "$name: expected SCHEDULE, got $decision")
                    assertEquals(expected["at_local_mono_us"]!!.jsonPrimitive.long, decision.atLocalMonoUs, "$name: deadline")
                }
                else -> {
                    assertTrue(decision is ScheduledCommandDecision.ApplyImmediately, "$name: expected APPLY_IMMEDIATELY, got $decision")
                    assertEquals(expected["lateness_us"]!!.jsonPrimitive.long, decision.latenessUs, "$name: lateness")
                    assertTrue(decision.latenessUs >= 0, "$name: lateness is never negative")
                }
            }
        }
        assertTrue(rows.isNotEmpty())
    }
}
