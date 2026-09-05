package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.QuickId
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs `protocol/vectors/manifest/manifest_vectors.json` — presence classification and delta. */
class ManifestVectorTest {
    private fun entryFrom(o: JsonObject): ManifestEntry =
        ManifestEntry(
            contentHash = ContentHash(o["content_hash"]!!.jsonPrimitive.content),
            quickId = QuickId(o["quick_id"]!!.jsonPrimitive.content),
            workKey = "k",
            title = (o["title"] as? JsonPrimitive)?.content ?: "t",
            artist = "a",
            album = "al",
            durationMs = 1000,
            codec = "mp3",
            bitrateKbps = 128,
            sizeBytes = 1000,
            filename = "f.mp3",
            hasArtwork = false,
        )

    @Test
    fun runVectors() {
        val doc = Vectors.load("manifest/manifest_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val expect = row["expect"]!!.jsonObject
            when {
                expect.containsKey("presence_by_content_hash") -> checkPresence(name, row, expect)
                expect.containsKey("added") -> checkDelta(name, row, expect)
                else -> error("vector $name: unrecognised expect shape")
            }
            checked += 1
        }
        assertEquals(13, checked, "expected 13 rows")
    }

    private fun checkPresence(
        name: String,
        row: JsonObject,
        expect: JsonObject,
    ) {
        val local = row["local_content_hashes"]!!.jsonArray.map { ContentHash(it.jsonPrimitive.content) }.toSet()
        val peer = row["peer_content_hashes"]!!.jsonArray.map { ContentHash(it.jsonPrimitive.content) }.toSet()
        val result = Presence.classify(local, peer)
        val expectedMap = expect["presence_by_content_hash"]!!.jsonObject
        assertEquals(expectedMap.keys.size, result.size, "vector $name: classification size")
        for ((hash, classification) in expectedMap) {
            assertEquals(
                Presence.Classification.valueOf(classification.jsonPrimitive.content),
                result[ContentHash(hash)],
                "vector $name: classification of $hash",
            )
        }
    }

    private fun checkDelta(
        name: String,
        row: JsonObject,
        expect: JsonObject,
    ) {
        val old = row["old_manifest"]!!.jsonArray.map { entryFrom(it.jsonObject) }
        val new = row["new_manifest"]!!.jsonArray.map { entryFrom(it.jsonObject) }
        val delta = ManifestDelta.compute(old, new)
        val expectedAddedHashes = expect["added"]!!.jsonArray.map { it.jsonObject["content_hash"]!!.jsonPrimitive.content }
        assertEquals(expectedAddedHashes, delta.added.map { it.contentHash!!.value }, "vector $name: added")
        val expectedRemoved = expect["removed"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedRemoved, delta.removed.map { it.value }, "vector $name: removed")
    }
}
