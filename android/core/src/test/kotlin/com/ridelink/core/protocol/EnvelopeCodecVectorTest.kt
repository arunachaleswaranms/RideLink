package com.ridelink.core.protocol

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** protocol/vectors/envelope/envelope_vectors.json, PROTOCOL §2, TEST_PLAN §2/§11. */
class EnvelopeCodecVectorTest {
    @TestFactory
    fun envelopeVectors(): List<DynamicTest> {
        val root = Vectors.load("envelope/envelope_vectors.json").jsonObject
        return root["vectors"]!!.jsonArray.map { element ->
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            DynamicTest.dynamicTest(name) { runVector(vector) }
        }
    }

    private fun runVector(vector: JsonObject) {
        val input = vector["input"]!!.jsonObject
        val expected = vector["expected"]!!.jsonObject

        val result: DecodeResult =
            when {
                input.containsKey("body_json") -> {
                    EnvelopeCodec.decode(input["body_json"]!!.jsonPrimitive.content)
                }
                input.containsKey("pad_to_bytes") -> {
                    val bytes = buildPaddedFrame(input)
                    expected["encoded_byte_length"]?.let {
                        assertEquals(it.jsonPrimitive.int, bytes.size, "padded fixture must land exactly on the target length")
                    }
                    EnvelopeCodec.decode(bytes)
                }
                else -> fail("vector input has neither body_json nor pad_to_bytes")
            }

        val expectDecodes = expected["decodes"]!!.jsonPrimitive.boolean
        when (result) {
            is DecodeResult.Success -> {
                assertTrue(expectDecodes, "expected decode failure but got Success")
                expected["version_ok"]?.let { assertEquals(it.jsonPrimitive.boolean, result.versionOk) }
                expected["decoded"]?.jsonObject?.let { assertDecodedMatches(it, result.envelope) }
            }
            is DecodeResult.Failure -> {
                assertTrue(!expectDecodes, "expected decode success but got Failure(${result.errorCode})")
                expected["error_code"]?.let { assertEquals(it.jsonPrimitive.content, result.errorCode) }
            }
        }
    }

    private fun assertDecodedMatches(
        expectedDecoded: JsonObject,
        envelope: Envelope,
    ) {
        expectedDecoded["v"]?.let { assertEquals(it.jsonPrimitive.int, envelope.v) }
        expectedDecoded["type"]?.let { assertEquals(it.jsonPrimitive.content, envelope.type) }
        expectedDecoded["session_id"]?.let { assertEquals(it.jsonPrimitive.content, envelope.sessionId) }
        expectedDecoded["sender_id"]?.let { assertEquals(it.jsonPrimitive.content, envelope.senderId) }
        expectedDecoded["msg_id"]?.let { assertEquals(it.jsonPrimitive.content, envelope.msgId) }
        expectedDecoded["seq"]?.let { assertEquals(it.jsonPrimitive.content.toLong(), envelope.seq) }
        expectedDecoded["sent_at_mono_us"]?.let { assertEquals(it.jsonPrimitive.content.toLong(), envelope.sentAtMonoUs) }
        expectedDecoded["requires_ack"]?.let { assertEquals(it.jsonPrimitive.boolean, envelope.requiresAck) }
        expectedDecoded["payload"]?.jsonObject?.let { expectedPayload ->
            for ((key, value) in expectedPayload) {
                assertEquals(value, envelope.payload[key], "payload.$key mismatch")
            }
        }
    }

    /** Implements the padding recipe documented in protocol/README.md. */
    private fun buildPaddedFrame(input: JsonObject): ByteArray {
        val padToBytes = input["pad_to_bytes"]!!.jsonPrimitive.int
        val template = input["template"]!!.jsonObject
        val payload = template["payload"]!!.jsonObject
        require(payload["pad"]?.jsonPrimitive?.contentOrNull == "") {
            "template.payload.pad must start as the empty string"
        }

        val baseText = EnvelopeCodec.json.encodeToString(JsonObject.serializer(), template)
        val baseLen = baseText.toByteArray(Charsets.UTF_8).size
        val padLen = padToBytes - baseLen
        require(padLen >= 0) { "pad_to_bytes ($padToBytes) is smaller than the unpadded template ($baseLen)" }

        val paddedPayload = JsonObject(payload.toMutableMap().apply { put("pad", JsonPrimitive("a".repeat(padLen))) })
        val paddedTemplate = JsonObject(template.toMutableMap().apply { put("payload", paddedPayload) })
        val finalText = EnvelopeCodec.json.encodeToString(JsonObject.serializer(), paddedTemplate)
        return finalText.toByteArray(Charsets.UTF_8)
    }
}
