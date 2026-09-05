package com.ridelink.core.protocol

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs `protocol/vectors/transfer-messages/transfer_messages_vectors.json`. */
class TransferMessagesVectorTest {
    private fun payloadOf(row: JsonObject): JsonObject = buildJsonObject { for ((k, v) in row["payload"]!!.jsonObject) put(k, v) }

    @Test
    fun runVectors() {
        val doc = Vectors.load("transfer-messages/transfer_messages_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val type = row["type"]!!.jsonPrimitive.content
            val result = TransferCodec.parse(type, payloadOf(row))
            val expect = row["expect"]!!.jsonObject
            val parsedSpec = expect["parsed"] as? JsonObject
            if (parsedSpec != null) {
                assertTrue(result is TransferCodec.Result.Parsed, "vector $name expected a parse, got $result")
                assertParsedMatches(name, parsedSpec, result.message)
            } else {
                val reason = expect["rejected"]!!.jsonPrimitive.content
                assertTrue(result is TransferCodec.Result.Rejected, "vector $name expected a rejection, got $result")
                assertEquals(TransferMessageRejection.valueOf(reason), result.reason, "vector $name rejection reason")
            }
            checked += 1
        }
        assertEquals(44, checked, "expected 44 rows")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun assertParsedMatches(
        name: String,
        spec: JsonObject,
        message: TransferMessage,
    ) {
        when (val kind = spec["kind"]!!.jsonPrimitive.content) {
            "Request" -> {
                val m = message as TransferMessage.Request
                assertEquals(spec["content_hash"]!!.jsonPrimitive.content, m.contentHash.value, "$name: content_hash")
                assertEquals(spec["transfer_id"]!!.jsonPrimitive.content, m.transferId.value, "$name: transfer_id")
            }
            "Offer" -> {
                val m = message as TransferMessage.Offer
                assertEquals(spec["transfer_id"]!!.jsonPrimitive.content, m.transferId.value, "$name: transfer_id")
                assertEquals(spec["size_bytes"]!!.jsonPrimitive.long, m.sizeBytes, "$name: size_bytes")
                assertEquals(spec["chunk_size"]!!.jsonPrimitive.int, m.chunkSize, "$name: chunk_size")
                assertEquals(spec["chunk_count"]!!.jsonPrimitive.int, m.chunkCount, "$name: chunk_count")
                assertEquals(spec["bulk_port"]!!.jsonPrimitive.int, m.bulkPort, "$name: bulk_port")
                assertEquals(spec["bulk_token"]!!.jsonPrimitive.content, m.bulkToken, "$name: bulk_token")
            }
            "Progress" -> {
                val m = message as TransferMessage.Progress
                assertEquals(spec["transfer_id"]!!.jsonPrimitive.content, m.transferId.value, "$name: transfer_id")
                assertEquals(spec["bytes"]!!.jsonPrimitive.long, m.bytes, "$name: bytes")
                assertEquals(spec["pct"]!!.jsonPrimitive.int, m.pct, "$name: pct")
            }
            "Result" -> {
                val m = message as TransferMessage.Result
                assertEquals(spec["transfer_id"]!!.jsonPrimitive.content, m.transferId.value, "$name: transfer_id")
                assertEquals(spec["ok"]!!.jsonPrimitive.content.toBoolean(), m.ok, "$name: ok")
                val expectedSha = spec["sha256"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
                assertEquals(expectedSha, m.sha256?.value, "$name: sha256")
            }
            "Cancel" -> {
                val m = message as TransferMessage.Cancel
                assertEquals(spec["transfer_id"]!!.jsonPrimitive.content, m.transferId.value, "$name: transfer_id")
                assertEquals(spec["reason"]!!.jsonPrimitive.content, m.reason, "$name: reason")
            }
            else -> error("unrecognised parsed kind $kind")
        }
    }
}
