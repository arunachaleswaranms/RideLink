package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.QuickId
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs `protocol/vectors/manifest-paging/manifest_paging_vectors.json` — see ADR-013 / PROTOCOL §8.1. */
class ManifestPagingVectorTest {
    private fun entryFrom(o: JsonObject): ManifestEntry {
        val chElement = o["content_hash"]
        val contentHash = if (chElement == null || chElement is JsonNull) null else ContentHash(chElement.jsonPrimitive.content)
        return ManifestEntry(
            contentHash = contentHash,
            quickId = QuickId(o.string("quick_id")),
            workKey = o.string("work_key"),
            title = o.string("title"),
            artist = o.string("artist"),
            album = o.string("album"),
            durationMs = o["duration_ms"]!!.jsonPrimitive.long,
            codec = o.string("codec"),
            bitrateKbps = o["bitrate_kbps"]!!.jsonPrimitive.int,
            sizeBytes = o["size_bytes"]!!.jsonPrimitive.long,
            filename = o.string("filename"),
            hasArtwork = o["has_artwork"]!!.jsonPrimitive.boolean,
        )
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun runVectors() {
        val doc = Vectors.load("manifest-paging/manifest_paging_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row.string("name")
            val expect = row["expect"]!!.jsonObject
            when {
                expect.containsKey("page_count") -> checkPaging(name, row, expect)
                expect.containsKey("clamped_title_scalar_count") -> checkClamp(name, row, expect)
                expect.containsKey("content_hash_unchanged") -> checkIdentityUnchanged(name, row)
                expect.containsKey("digest") -> checkDigestOnly(name, row, expect)
                else -> error("vector $name: unrecognised expect shape")
            }
            checked += 1
        }
        assertEquals(13, checked, "expected 13 rows")
    }

    private fun rawEntries(row: JsonObject): List<ManifestEntry> = row["entries"]!!.jsonArray.map { entryFrom(it.jsonObject) }

    private fun rawRemoved(row: JsonObject): List<ContentHash> =
        (row["removed"] as? JsonArray)?.map { ContentHash(it.jsonPrimitive.content) } ?: emptyList()

    private fun checkPaging(
        name: String,
        row: JsonObject,
        expect: JsonObject,
    ) {
        val entries = rawEntries(row)
        val budget = row["budget_bytes"]!!.jsonPrimitive.int
        val pages = ManifestPaging.paginate(entries, budget)
        assertEquals(expect["page_count"]!!.jsonPrimitive.int, pages.size, "vector $name: page_count")
        val expectedCounts = expect["entries_per_page"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(expectedCounts, pages.map { it.size }, "vector $name: entries_per_page")
        val expectedPages = expect["pages"]!!.jsonArray.map { page -> page.jsonArray.map { entryFrom(it.jsonObject) } }
        assertEquals(expectedPages, pages, "vector $name: page contents")
        val allClamped = entries.map(ManifestPaging::clampEntry)
        val removed = rawRemoved(row)
        assertEquals(expect["digest"]!!.jsonPrimitive.content, ManifestPaging.digest(allClamped, removed), "vector $name: digest")
    }

    private fun checkClamp(
        name: String,
        row: JsonObject,
        expect: JsonObject,
    ) {
        val entry = rawEntries(row).single()
        val clamped = ManifestPaging.clampScalars(entry.title)
        assertEquals(expect["clamped_title"]!!.jsonPrimitive.content, clamped, "vector $name: clamped title")
        assertEquals(
            expect["clamped_title_scalar_count"]!!.jsonPrimitive.int,
            clamped.codePointCount(0, clamped.length),
            "vector $name: scalar count",
        )
    }

    private fun checkIdentityUnchanged(
        name: String,
        row: JsonObject,
    ) {
        val entry = rawEntries(row).single()
        val clamped = ManifestPaging.clampEntry(entry)
        assertEquals(entry.contentHash, clamped.contentHash, "vector $name: content_hash")
        assertEquals(entry.quickId, clamped.quickId, "vector $name: quick_id")
        assertEquals(entry.sizeBytes, clamped.sizeBytes, "vector $name: size_bytes")
        assertEquals(entry.durationMs, clamped.durationMs, "vector $name: duration_ms")
    }

    private fun checkDigestOnly(
        name: String,
        row: JsonObject,
        expect: JsonObject,
    ) {
        val entries = rawEntries(row).map(ManifestPaging::clampEntry)
        val removed = rawRemoved(row)
        assertEquals(expect["digest"]!!.jsonPrimitive.content, ManifestPaging.digest(entries, removed), "vector $name: digest")
    }
}
