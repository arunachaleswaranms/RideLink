package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
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

/** Runs `protocol/vectors/manifest-paging-errors/manifest_paging_errors_vectors.json` — PROTOCOL §8.1 / ADR-013. */
class ManifestPagingErrorsVectorTest {
    /**
     * These vectors' entries carry only `content_hash`/`quick_id` (the two fields the digest
     * actually reads) — everything else is filler so [ManifestEntry] can be constructed at all.
     * Reading the *real* content_hash/quick_id off the JSON (rather than fabricating a
     * placeholder) is what makes the reconstructed digest match `DEFAULT_ENTRY_DIGEST` in
     * `tools/generate_manifest_paging_errors_vectors.py`.
     */
    private fun entryFromPartial(o: JsonObject): ManifestEntry {
        val chElement = o["content_hash"]
        val contentHash = if (chElement == null || chElement is JsonNull) null else ContentHash(chElement.jsonPrimitive.content)
        return ManifestEntry(
            contentHash = contentHash,
            quickId = QuickId(o["quick_id"]!!.jsonPrimitive.content),
            workKey = "k",
            title = "t",
            artist = "a",
            album = "al",
            durationMs = 1000,
            codec = "mp3",
            bitrateKbps = 128,
            sizeBytes = 1000,
            filename = "f.mp3",
            hasArtwork = false,
        )
    }

    private fun eventFrom(o: JsonObject): ManifestSyncEvent =
        when (o["kind"]!!.jsonPrimitive.content) {
            "Begin" ->
                ManifestSyncEvent.Begin(
                    manifestId = ManifestId(o["manifest_id"]!!.jsonPrimitive.content),
                    kind = ManifestKind.parse(o["kind_field"]!!.jsonPrimitive.content)!!,
                    manifestRevision = o["manifest_revision"]!!.jsonPrimitive.long,
                    baseRevision = o["base_revision"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.long },
                    totalEntries = o["total_entries"]!!.jsonPrimitive.int,
                    totalRemoved = o["total_removed"]!!.jsonPrimitive.int,
                )
            "Page" ->
                ManifestSyncEvent.Page(
                    manifestId = ManifestId(o["manifest_id"]!!.jsonPrimitive.content),
                    manifestRevision = o["manifest_revision"]!!.jsonPrimitive.long,
                    pageIndex = o["page_index"]!!.jsonPrimitive.int,
                    entries = o["entries"]!!.jsonArray.map { entryFromPartial(it.jsonObject) },
                    removed = o["removed"]!!.jsonArray.map { ContentHash(it.jsonPrimitive.content) },
                )
            "End" ->
                ManifestSyncEvent.End(
                    manifestId = ManifestId(o["manifest_id"]!!.jsonPrimitive.content),
                    manifestRevision = o["manifest_revision"]!!.jsonPrimitive.long,
                    pageCount = o["page_count"]!!.jsonPrimitive.int,
                    totalEntries = o["total_entries"]!!.jsonPrimitive.int,
                    totalRemoved = o["total_removed"]!!.jsonPrimitive.int,
                    digest = o["digest"]!!.jsonPrimitive.content,
                )
            "Abort" -> ManifestSyncEvent.Abort(ManifestId(o["manifest_id"]!!.jsonPrimitive.content), o["reason"]!!.jsonPrimitive.content)
            "Timeout" -> ManifestSyncEvent.Timeout
            "ControlLinkLost" -> ManifestSyncEvent.ControlLinkLost
            else -> error("unknown event kind ${o["kind"]}")
        }

    @Test
    fun runVectors() {
        val doc = Vectors.load("manifest-paging-errors/manifest_paging_errors_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val previousRevision = row["previous_revision"]!!.jsonPrimitive.long
            val events = (row["events"] as JsonArray).map { eventFrom(it.jsonObject) }
            val expect = row["expect"]!!.jsonObject

            val machine = ManifestSyncStateMachine(previousRevision)
            var lastError: ManifestSyncError? = null
            var committed = false
            for (event in events) {
                lastError = null
                when (val result = machine.apply(event)) {
                    is ManifestSyncStepResult.Aborted -> lastError = result.reason
                    is ManifestSyncStepResult.Committed -> committed = true
                    ManifestSyncStepResult.Continue -> {}
                }
            }

            val expectedError = expect["error"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(expectedError, wireErrorName(lastError), "vector $name: error")
            assertEquals(expect["committed"]!!.jsonPrimitive.boolean, committed, "vector $name: committed")
            assertEquals(expect["final_revision"]!!.jsonPrimitive.long, machine.liveRevision, "vector $name: final_revision")
            checked += 1
        }
        assertEquals(21, checked, "expected 21 rows")
    }

    private fun wireErrorName(error: ManifestSyncError?): String? =
        when (error) {
            null -> null
            ManifestSyncError.SEQUENCE_ERROR -> "manifest_sequence_error"
            ManifestSyncError.DIGEST_MISMATCH -> "manifest_digest_mismatch"
            ManifestSyncError.INCOMPLETE -> "manifest_incomplete"
        }
}
