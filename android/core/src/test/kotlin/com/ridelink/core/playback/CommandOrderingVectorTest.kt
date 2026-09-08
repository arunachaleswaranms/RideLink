package com.ridelink.core.playback

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
 * Runs `protocol/vectors/ordering/ordering_vectors.json` — PROTOCOL §2.1/§5's command ordering,
 * shared byte-for-byte with `CommandOrderingVectorTests` on iOS.
 */
class CommandOrderingVectorTest {
    private val doc by lazy { Vectors.load("ordering/ordering_vectors.json").jsonObject }

    private fun role(name: String) = if (name == "LEADER") PlaybackRole.LEADER else PlaybackRole.FOLLOWER

    @Test
    fun crossProduct() {
        val rows = doc["cross_product"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val lastRaw = input["last_applied_seq"]!!
            val last = if (lastRaw is JsonNull) null else lastRaw.jsonPrimitive.long
            val decision =
                CommandOrderGate.decide(
                    role(input["role"]!!.jsonPrimitive.content),
                    last,
                    input["incoming_seq"]!!.jsonPrimitive.long,
                )
            assertEquals(
                CommandOrderDecision.valueOf(row["expected"]!!.jsonObject["decision"]!!.jsonPrimitive.content),
                decision,
                "vector $name",
            )
        }
        assertEquals(60, rows.size, "the cross product is 2 roles x 5 last-applied values x 6 incoming values")
    }

    @Test
    fun streams() {
        val rows = doc["streams"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val input = row["input"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val playbackRole = role(input["role"]!!.jsonPrimitive.content)
            var lastApplied: Long? = null
            val decisions = mutableListOf<String>()
            val applied = mutableListOf<Long>()
            for (seqElement in input["incoming_seqs"]!!.jsonArray) {
                val seq = seqElement.jsonPrimitive.long
                val decision = CommandOrderGate.decide(playbackRole, lastApplied, seq)
                decisions.add(decision.name)
                if (decision == CommandOrderDecision.ACCEPT) {
                    applied.add(seq)
                    lastApplied = seq
                }
            }
            assertEquals(expected["decisions"]!!.jsonArray.map { it.jsonPrimitive.content }, decisions, "$name: decisions")
            assertEquals(expected["applied_seqs"]!!.jsonArray.map { it.jsonPrimitive.long }, applied, "$name: applied set")
            val expectedFinal = expected["final_last_applied_seq"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.long }
            assertEquals(expectedFinal, lastApplied, "$name: final last-applied")
        }
        assertTrue(rows.isNotEmpty())
    }
}
