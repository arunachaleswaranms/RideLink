package com.ridelink.core.transfer

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs `protocol/vectors/bulk-framing/bulk_framing_vectors.json` — the RLB1 header parser. */
class BulkFramingVectorTest {
    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        return out
    }

    @Test
    fun runVectors() {
        val doc = Vectors.load("bulk-framing/bulk_framing_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val buffer = hexToBytes(row["buffer_hex"]!!.jsonPrimitive.content)
            val expect = row["expect"]!!.jsonObject
            val result = BulkFraming.parseAll(buffer)
            when (expect["outcome"]!!.jsonPrimitive.content) {
                "parsed" -> {
                    assertTrue(result is BulkFraming.ParseResult.Parsed, "vector $name expected Parsed, got $result")
                    val expectedFrames = expect["frames"]!!.jsonArray.map { it.jsonObject }
                    assertEquals(expectedFrames.size, result.frames.size, "vector $name: frame count")
                    expectedFrames.forEachIndexed { i, f ->
                        assertEquals(
                            f["chunk_index"]!!.jsonPrimitive.long,
                            result.frames[i].chunkIndex,
                            "vector $name: frames[$i].chunk_index",
                        )
                        assertEquals(
                            f["payload_hex"]!!.jsonPrimitive.content,
                            result.frames[i].payload.toHex(),
                            "vector $name: frames[$i].payload",
                        )
                    }
                    assertEquals(expect["leftover_hex"]!!.jsonPrimitive.content, result.leftover.toHex(), "vector $name: leftover")
                }
                "incomplete" -> assertTrue(result is BulkFraming.ParseResult.Incomplete, "vector $name expected Incomplete, got $result")
                "invalid" -> {
                    assertTrue(result is BulkFraming.ParseResult.Invalid, "vector $name expected Invalid, got $result")
                    assertEquals(expect["reason"]!!.jsonPrimitive.content, result.reason, "vector $name: reason")
                }
                else -> error("vector $name: unrecognised outcome")
            }
            checked += 1
        }
        assertEquals(15, checked, "expected 15 rows")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
