package com.ridelink.core.protocol

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/queue-messages/queue_messages_vectors.json` — PROTOCOL §9's field
 * validation, shared byte-for-byte with `QueueMessagesVectorTests` on iOS.
 */
class QueueMessagesVectorTest {
    @Test
    fun runVectors() {
        val doc = Vectors.load("queue-messages/queue_messages_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var accepted = 0
        var rejected = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val type = row["type"]!!.jsonPrimitive.content
            val payload = row["payload"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val result = QueueCodec.parse(type, payload)
            if (expected["accepted"]!!.jsonPrimitive.content.toBoolean()) {
                assertTrue(result is QueueCodec.Result.Parsed, "vector $name expected a parse, got $result")
                assertEquals(type, QueueCodec.wireType(result.message), "$name: wire type round trip")
                assertJsonEquals(name, expected["encoded"]!!, QueueCodec.encode(result.message))
                accepted += 1
            } else {
                val reason = expected["rejection"]!!.jsonPrimitive.content
                assertTrue(result is QueueCodec.Result.Rejected, "vector $name expected a rejection, got $result")
                assertEquals(QueueMessageRejection.valueOf(reason), result.reason, "vector $name rejection reason")
                rejected += 1
            }
        }
        assertEquals(rows.size, accepted + rejected, "every row must be classified")
        assertTrue(accepted > 0 && rejected > 0, "the file must exercise both outcomes")
    }

    /**
     * ADR-024 §6: `status` was removed from the wire because PROTOCOL §9 itself called it untrusted.
     * A structural scan of what this codec can *emit* is what keeps it removed — an encoder that
     * quietly reintroduced it would otherwise only be caught by a reviewer noticing.
     */
    @Test
    fun encoderNeverEmitsAStatusField() {
        val doc = Vectors.load("queue-messages/queue_messages_vectors.json").jsonObject
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val expected = row["expected"]!!.jsonObject
            if (!expected["accepted"]!!.jsonPrimitive.content.toBoolean()) continue
            val result = QueueCodec.parse(row["type"]!!.jsonPrimitive.content, row["payload"]!!.jsonObject)
            val encoded = QueueCodec.encode((result as QueueCodec.Result.Parsed).message)
            assertFalse(containsKeyAnywhere(encoded, "status"), "${row["name"]}: encoder emitted a status field")
        }
    }

    private fun containsKeyAnywhere(
        element: JsonElement,
        key: String,
    ): Boolean =
        when (element) {
            is JsonObject -> element.containsKey(key) || element.values.any { containsKeyAnywhere(it, key) }
            is JsonArray -> element.any { containsKeyAnywhere(it, key) }
            else -> false
        }

    private fun assertJsonEquals(
        name: String,
        expected: JsonElement,
        actual: JsonElement,
    ) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals(expected.keys, actual.keys, "$name: field set")
                for (key in expected.keys) assertJsonEquals("$name.$key", expected[key]!!, actual[key]!!)
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals(expected.size, actual.size, "$name: array size")
                expected.indices.forEach { assertJsonEquals("$name[$it]", expected[it], actual[it]) }
            }
            expected is JsonNull || actual is JsonNull -> assertEquals(expected, actual, "$name: null-ness")
            else -> {
                val e = expected.jsonPrimitive
                val a = actual.jsonPrimitive
                if (e.isString || a.isString) {
                    assertEquals(e.isString, a.isString, "$name: string-ness")
                    assertEquals(e.content, a.content, "$name: value")
                } else {
                    assertEquals(e.content.toDouble(), a.content.toDouble(), "$name: numeric value")
                }
            }
        }
    }
}
