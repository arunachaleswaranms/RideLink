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

/** Runs `protocol/vectors/manifest-messages/manifest_messages_vectors.json`. */
class ManifestMessagesVectorTest {
    private fun payloadOf(row: JsonObject): JsonObject =
        buildJsonObject {
            for ((k, v) in row["payload"]!!.jsonObject) put(k, v)
        }

    @Test
    fun runVectors() {
        val doc = Vectors.load("manifest-messages/manifest_messages_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val type = row["type"]!!.jsonPrimitive.content
            val result = ManifestCodec.parse(type, payloadOf(row))
            val expect = row["expect"]!!.jsonObject
            val parsedSpec = expect["parsed"] as? JsonObject
            if (parsedSpec != null) {
                assertTrue(result is ManifestCodec.Result.Parsed, "vector $name expected a parse, got $result")
                assertParsedMatches(name, parsedSpec, result.message)
            } else {
                val reason = expect["rejected"]!!.jsonPrimitive.content
                assertTrue(result is ManifestCodec.Result.Rejected, "vector $name expected a rejection, got $result")
                assertEquals(ManifestMessageRejection.valueOf(reason), result.reason, "vector $name rejection reason")
            }
            checked += 1
        }
        assertEquals(35, checked, "expected 35 rows")
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun assertParsedMatches(
        name: String,
        spec: JsonObject,
        message: ManifestMessage,
    ) {
        when (val kind = spec["kind"]!!.jsonPrimitive.content) {
            "Request" -> {
                val m = message as ManifestMessage.Request
                assertEquals(nullableLong(spec, "since_revision"), m.sinceRevision, "$name: since_revision")
                assertEquals(spec["max_page_bytes"]!!.jsonPrimitive.int, m.maxPageBytes, "$name: max_page_bytes")
            }
            "Begin" -> {
                val m = message as ManifestMessage.Begin
                assertEquals(spec["manifest_id"]!!.jsonPrimitive.content, m.manifestId.value, "$name: manifest_id")
                assertEquals(spec["manifest_kind"]!!.jsonPrimitive.content, m.kind.wire, "$name: kind")
                assertEquals(spec["manifest_revision"]!!.jsonPrimitive.long, m.manifestRevision, "$name: manifest_revision")
                assertEquals(nullableLong(spec, "base_revision"), m.baseRevision, "$name: base_revision")
                assertEquals(spec["total_entries"]!!.jsonPrimitive.int, m.totalEntries, "$name: total_entries")
                assertEquals(spec["total_removed"]!!.jsonPrimitive.int, m.totalRemoved, "$name: total_removed")
                assertEquals(nullableInt(spec, "page_count"), m.pageCount, "$name: page_count")
                assertEquals(spec["digest_alg"]!!.jsonPrimitive.content, m.digestAlg, "$name: digest_alg")
            }
            "Page" -> {
                val m = message as ManifestMessage.Page
                assertEquals(spec["manifest_id"]!!.jsonPrimitive.content, m.manifestId.value, "$name: manifest_id")
                assertEquals(spec["manifest_revision"]!!.jsonPrimitive.long, m.manifestRevision, "$name: manifest_revision")
                assertEquals(spec["page_index"]!!.jsonPrimitive.int, m.pageIndex, "$name: page_index")
                val expectedEntries = spec["entries"]!!.jsonArray
                assertEquals(expectedEntries.size, m.entries.size, "$name: entry count")
                expectedEntries.forEachIndexed { i, e ->
                    val eo = e.jsonObject
                    assertEquals(eo["quick_id"]!!.jsonPrimitive.content, m.entries[i].quickId.value, "$name: entries[$i].quick_id")
                    assertEquals(eo["title"]!!.jsonPrimitive.content, m.entries[i].title, "$name: entries[$i].title")
                }
                assertEquals(spec["removed"]!!.jsonArray.map { it.jsonPrimitive.content }, m.removed.map { it.value }, "$name: removed")
            }
            "End" -> {
                val m = message as ManifestMessage.End
                assertEquals(spec["manifest_id"]!!.jsonPrimitive.content, m.manifestId.value, "$name: manifest_id")
                assertEquals(spec["manifest_revision"]!!.jsonPrimitive.long, m.manifestRevision, "$name: manifest_revision")
                assertEquals(spec["page_count"]!!.jsonPrimitive.int, m.pageCount, "$name: page_count")
                assertEquals(spec["total_entries"]!!.jsonPrimitive.int, m.totalEntries, "$name: total_entries")
                assertEquals(spec["total_removed"]!!.jsonPrimitive.int, m.totalRemoved, "$name: total_removed")
                assertEquals(spec["digest"]!!.jsonPrimitive.content, m.digest, "$name: digest")
            }
            "Abort" -> {
                val m = message as ManifestMessage.Abort
                assertEquals(spec["manifest_id"]!!.jsonPrimitive.content, m.manifestId.value, "$name: manifest_id")
                assertEquals(spec["reason"]!!.jsonPrimitive.content, m.reason, "$name: reason")
            }
            else -> error("unrecognised parsed kind $kind")
        }
    }

    private fun nullableLong(
        o: JsonObject,
        key: String,
    ): Long? = o[key].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.long }

    private fun nullableInt(
        o: JsonObject,
        key: String,
    ): Int? = o[key].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
}
