package com.ridelink.core.protocol

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/resync-messages/resync_messages_vectors.json` — PROTOCOL §10's field
 * validation (Phase 7, ADR-028), shared byte-for-byte with `ResyncMessagesVectorTests` on iOS.
 *
 * Mirrors [PlaybackMessagesVectorTest] exactly: accepted rows assert the **re-encoded** payload,
 * pinning `encode` and `parse` as inverses.
 */
class ResyncMessagesVectorTest {
    @Test
    fun runVectors() {
        val doc = Vectors.load("resync-messages/resync_messages_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var accepted = 0
        var rejected = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val type = row["type"]!!.jsonPrimitive.content
            val payload = row["payload"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val result = ResyncCodec.parse(type, payload)
            if (expected["accepted"]!!.jsonPrimitive.content.toBoolean()) {
                assertTrue(result is ResyncCodec.Result.Parsed, "vector $name expected a parse, got $result")
                assertEquals(type, ResyncCodec.wireType(result.message), "$name: wire type round trip")
                assertJsonEquals(name, expected["encoded"]!!.jsonObject, ResyncCodec.encode(result.message))
                accepted += 1
            } else {
                val reason = expected["rejection"]!!.jsonPrimitive.content
                assertTrue(result is ResyncCodec.Result.Rejected, "vector $name expected a rejection, got $result")
                assertEquals(ResyncMessageRejection.valueOf(reason), result.reason, "vector $name rejection reason")
                rejected += 1
            }
        }
        assertEquals(rows.size, accepted + rejected, "every row must be classified")
        assertTrue(accepted > 0 && rejected > 0, "the file must exercise both outcomes")
    }

    /** Numeric equality by value, not by JSON literal — see [PlaybackMessagesVectorTest]'s twin. */
    private fun assertJsonEquals(
        name: String,
        expected: JsonObject,
        actual: JsonObject,
    ) {
        assertEquals(expected.keys, actual.keys, "$name: field set")
        for (key in expected.keys) {
            assertTrue(sameValue(expected[key]!!, actual[key]!!), "$name: field $key expected ${expected[key]} got ${actual[key]}")
        }
    }

    @Suppress("ReturnCount")
    private fun sameValue(
        expected: JsonElement,
        actual: JsonElement,
    ): Boolean {
        if (expected is kotlinx.serialization.json.JsonNull || actual is kotlinx.serialization.json.JsonNull) {
            return expected is kotlinx.serialization.json.JsonNull && actual is kotlinx.serialization.json.JsonNull
        }
        if (expected is JsonObject && actual is JsonObject) {
            if (expected.keys != actual.keys) return false
            return expected.keys.all { sameValue(expected[it]!!, actual[it]!!) }
        }
        if (expected is kotlinx.serialization.json.JsonArray && actual is kotlinx.serialization.json.JsonArray) {
            if (expected.size != actual.size) return false
            return expected.indices.all { sameValue(expected[it], actual[it]) }
        }
        val e = expected.jsonPrimitive
        val a = actual.jsonPrimitive
        if (e.isString || a.isString) return e.isString == a.isString && e.content == a.content
        val eDouble = e.content.toDoubleOrNull()
        val aDouble = a.content.toDoubleOrNull()
        if (eDouble != null && aDouble != null) return eDouble == aDouble
        return e.content == a.content
    }
}
