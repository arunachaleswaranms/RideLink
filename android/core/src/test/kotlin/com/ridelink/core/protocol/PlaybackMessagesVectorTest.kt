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
 * Runs `protocol/vectors/playback-messages/playback_messages_vectors.json` — PROTOCOL §5's field
 * validation, shared byte-for-byte with `PlaybackMessagesVectorTests` on iOS.
 *
 * Accepted rows assert the **re-encoded** payload rather than the parsed object's fields: that pins
 * `encode` and `parse` as inverses in one assertion, and is what makes "an unknown field cannot
 * survive a round trip" checkable rather than assumed.
 */
class PlaybackMessagesVectorTest {
    @Test
    fun runVectors() {
        val doc = Vectors.load("playback-messages/playback_messages_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var accepted = 0
        var rejected = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val type = row["type"]!!.jsonPrimitive.content
            val payload = row["payload"]!!.jsonObject
            val expected = row["expected"]!!.jsonObject
            val result = PlaybackCodec.parse(type, payload)
            if (expected["accepted"]!!.jsonPrimitive.content.toBoolean()) {
                assertTrue(result is PlaybackCodec.Result.Parsed, "vector $name expected a parse, got $result")
                assertEquals(type, PlaybackCodec.wireType(result.message), "$name: wire type round trip")
                assertJsonEquals(name, expected["encoded"]!!.jsonObject, PlaybackCodec.encode(result.message))
                accepted += 1
            } else {
                val reason = expected["rejection"]!!.jsonPrimitive.content
                assertTrue(result is PlaybackCodec.Result.Rejected, "vector $name expected a rejection, got $result")
                assertEquals(PlaybackMessageRejection.valueOf(reason), result.reason, "vector $name rejection reason")
                rejected += 1
            }
        }
        assertEquals(rows.size, accepted + rejected, "every row must be classified")
        assertTrue(accepted > 0 && rejected > 0, "the file must exercise both outcomes")
    }

    /**
     * Numeric equality by value, not by JSON literal: `9007199254740991` and `9.007199254740991E15`
     * are the same number and iOS necessarily produces the second, so a textual comparison would
     * make the shared file un-shareable.
     */
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

    @Suppress("ReturnCount") // one early-out per JSON kind the comparison must distinguish
    private fun sameValue(
        expected: JsonElement,
        actual: JsonElement,
    ): Boolean {
        val e = expected.jsonPrimitive
        val a = actual.jsonPrimitive
        if (e.isString || a.isString) return e.isString == a.isString && e.content == a.content
        val eDouble = e.content.toDoubleOrNull()
        val aDouble = a.content.toDoubleOrNull()
        if (eDouble != null && aDouble != null) return eDouble == aDouble
        return e.content == a.content
    }
}
