package com.ridelink.core.protocol

import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.manifest.ManifestKind
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
import com.ridelink.core.model.QuickId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** PROTOCOL §3 Catalogue group. */
object ManifestMessageTypes {
    const val REQUEST = "MANIFEST_REQUEST"
    const val BEGIN = "MANIFEST_BEGIN"
    const val PAGE = "MANIFEST_PAGE"
    const val END = "MANIFEST_END"
    const val ABORT = "MANIFEST_ABORT"

    val ALL = setOf(REQUEST, BEGIN, PAGE, END, ABORT)
}

/** One decoded, bounds-checked `MANIFEST_*` message (PROTOCOL §8.1). */
sealed class ManifestMessage {
    data class Request(
        val sinceRevision: Long?,
        val maxPageBytes: Int,
    ) : ManifestMessage()

    data class Begin(
        val manifestId: ManifestId,
        val kind: ManifestKind,
        val manifestRevision: Long,
        val baseRevision: Long?,
        val totalEntries: Int,
        val totalRemoved: Int,
        val pageCount: Int?,
        val digestAlg: String,
    ) : ManifestMessage()

    data class Page(
        val manifestId: ManifestId,
        val manifestRevision: Long,
        val pageIndex: Int,
        val entries: List<ManifestEntry>,
        val removed: List<ContentHash>,
    ) : ManifestMessage()

    data class End(
        val manifestId: ManifestId,
        val manifestRevision: Long,
        val pageCount: Int,
        val totalEntries: Int,
        val totalRemoved: Int,
        val digest: String,
    ) : ManifestMessage()

    data class Abort(
        val manifestId: ManifestId,
        /** A closed vocabulary tolerated as `"unknown"` for a value this build does not recognise (PROTOCOL §8.1). */
        val reason: String,
    ) : ManifestMessage()
}

/** Why a `MANIFEST_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim. */
enum class ManifestMessageRejection {
    UNKNOWN_TYPE,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    MALFORMED_MANIFEST_ID,
    INVALID_REVISION,
    INVALID_MANIFEST_KIND,
    INVALID_REQUEST,
    MALFORMED_DIGEST,

    /**
     * Any structural problem inside one manifest entry — missing key, wrong type, malformed
     * hash. Rejects the whole page (ADR-013: nothing partial).
     */
    ENTRY_FIELD_INVALID,

    /**
     * A display field's defensive parse-time bound (brief §30) — distinct from the 512-scalar
     * build-time clamp (ADR-013), which is a sender rule, not a receiver rejection.
     */
    ENTRY_FIELD_TOO_LARGE,
    TOO_MANY_ENTRIES,
}

/**
 * Parses and bounds-checks a `MANIFEST_*` payload. Total and non-throwing, mirroring
 * [AudioStateCodec]/[VoiceSignalCodec]: a malformed frame is dropped and the control connection
 * survives (PROTOCOL §2 rule 2, applied here the same way §7.4 applies it to `VOICE_*`).
 *
 * One object per message family, matching [AudioStateCodec]/[VoiceSignalCodec], rather than
 * splitting parse/encode across two types for five sub-message shapes — that split would scatter
 * each `FIELD_*` constant and rejection reason away from the one function pair that uses it.
 */
@Suppress("TooManyFunctions")
object ManifestCodec {
    private val VALID_ABORT_REASONS = setOf("library_changed", "page_oversize", "cancelled", "internal")
    private const val MAX_ENTRIES_PER_PAGE = com.ridelink.core.manifest.ManifestPaging.MAX_ENTRIES_PER_PAGE
    private const val MAX_DISPLAY_FIELD_BYTES = 4096

    sealed class Result {
        data class Parsed(
            val message: ManifestMessage,
        ) : Result()

        data class Rejected(
            val reason: ManifestMessageRejection,
        ) : Result()
    }

    const val FIELD_MANIFEST_ID = "manifest_id"
    const val FIELD_KIND = "kind"
    const val FIELD_MANIFEST_REVISION = "manifest_revision"
    const val FIELD_BASE_REVISION = "base_revision"
    const val FIELD_TOTAL_ENTRIES = "total_entries"
    const val FIELD_TOTAL_REMOVED = "total_removed"
    const val FIELD_PAGE_COUNT = "page_count"
    const val FIELD_DIGEST_ALG = "digest_alg"
    const val FIELD_SINCE_REVISION = "since_revision"
    const val FIELD_MAX_PAGE_BYTES = "max_page_bytes"
    const val FIELD_PAGE_INDEX = "page_index"
    const val FIELD_ENTRIES = "entries"
    const val FIELD_REMOVED = "removed"
    const val FIELD_DIGEST = "digest"
    const val FIELD_REASON = "reason"

    fun parse(
        type: String,
        payload: JsonObject,
    ): Result =
        when (type) {
            ManifestMessageTypes.REQUEST -> parseRequest(payload)
            ManifestMessageTypes.BEGIN -> parseBegin(payload)
            ManifestMessageTypes.PAGE -> parsePage(payload)
            ManifestMessageTypes.END -> parseEnd(payload)
            ManifestMessageTypes.ABORT -> parseAbort(payload)
            else -> Result.Rejected(ManifestMessageRejection.UNKNOWN_TYPE)
        }

    /** The `MANIFEST_*` type a given [ManifestMessage] wire-encodes as. */
    fun wireType(message: ManifestMessage): String =
        when (message) {
            is ManifestMessage.Request -> ManifestMessageTypes.REQUEST
            is ManifestMessage.Begin -> ManifestMessageTypes.BEGIN
            is ManifestMessage.Page -> ManifestMessageTypes.PAGE
            is ManifestMessage.End -> ManifestMessageTypes.END
            is ManifestMessage.Abort -> ManifestMessageTypes.ABORT
        }

    /** The outbound side of [parse] — the shape lives here, once, shared by both directions. */
    fun encode(message: ManifestMessage): JsonObject =
        when (message) {
            is ManifestMessage.Request ->
                buildJsonObject {
                    put(FIELD_SINCE_REVISION, message.sinceRevision)
                    put(FIELD_MAX_PAGE_BYTES, message.maxPageBytes)
                }
            is ManifestMessage.Begin ->
                buildJsonObject {
                    put(FIELD_MANIFEST_ID, message.manifestId.value)
                    put(FIELD_KIND, message.kind.wire)
                    put(FIELD_MANIFEST_REVISION, message.manifestRevision)
                    put(FIELD_BASE_REVISION, message.baseRevision)
                    put(FIELD_TOTAL_ENTRIES, message.totalEntries)
                    put(FIELD_TOTAL_REMOVED, message.totalRemoved)
                    put(FIELD_PAGE_COUNT, message.pageCount)
                    put(FIELD_DIGEST_ALG, message.digestAlg)
                }
            is ManifestMessage.Page ->
                buildJsonObject {
                    put(FIELD_MANIFEST_ID, message.manifestId.value)
                    put(FIELD_MANIFEST_REVISION, message.manifestRevision)
                    put(FIELD_PAGE_INDEX, message.pageIndex)
                    put(FIELD_ENTRIES, JsonArray(message.entries.map { it.toJsonObject() }))
                    put(FIELD_REMOVED, JsonArray(message.removed.map { JsonPrimitive(it.value) }))
                }
            is ManifestMessage.End ->
                buildJsonObject {
                    put(FIELD_MANIFEST_ID, message.manifestId.value)
                    put(FIELD_MANIFEST_REVISION, message.manifestRevision)
                    put(FIELD_PAGE_COUNT, message.pageCount)
                    put(FIELD_TOTAL_ENTRIES, message.totalEntries)
                    put(FIELD_TOTAL_REMOVED, message.totalRemoved)
                    put(FIELD_DIGEST, message.digest)
                }
            is ManifestMessage.Abort ->
                buildJsonObject {
                    put(FIELD_MANIFEST_ID, message.manifestId.value)
                    put(FIELD_REASON, message.reason)
                }
        }

    @Suppress("ReturnCount")
    private fun parseRequest(payload: JsonObject): Result {
        val sinceRevision = nullableLong(payload, FIELD_SINCE_REVISION) ?: return missingOrWrongType(payload, FIELD_SINCE_REVISION)
        if (sinceRevision is LongOrNull.Rejected) return Result.Rejected(sinceRevision.reason)
        val maxPageBytes = intField(payload, FIELD_MAX_PAGE_BYTES) ?: return missingOrWrongType(payload, FIELD_MAX_PAGE_BYTES)
        val value = (sinceRevision as LongOrNull.Accepted).value
        if (value != null && value < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REVISION)
        return Result.Parsed(ManifestMessage.Request(value, maxPageBytes))
    }

    @Suppress("ReturnCount")
    private fun parseBegin(payload: JsonObject): Result {
        val idRaw = stringField(payload, FIELD_MANIFEST_ID) ?: return missingOrWrongType(payload, FIELD_MANIFEST_ID)
        val id = ManifestId.parse(idRaw) ?: return Result.Rejected(ManifestMessageRejection.MALFORMED_MANIFEST_ID)
        val kindRaw = stringField(payload, FIELD_KIND) ?: return missingOrWrongType(payload, FIELD_KIND)
        val kind = ManifestKind.parse(kindRaw) ?: return Result.Rejected(ManifestMessageRejection.INVALID_MANIFEST_KIND)
        val revision = longField(payload, FIELD_MANIFEST_REVISION) ?: return missingOrWrongType(payload, FIELD_MANIFEST_REVISION)
        if (revision < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REVISION)
        val baseRevisionResult = nullableLong(payload, FIELD_BASE_REVISION) ?: return missingOrWrongType(payload, FIELD_BASE_REVISION)
        if (baseRevisionResult is LongOrNull.Rejected) return Result.Rejected(baseRevisionResult.reason)
        val totalEntries = intField(payload, FIELD_TOTAL_ENTRIES) ?: return missingOrWrongType(payload, FIELD_TOTAL_ENTRIES)
        if (totalEntries < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REQUEST)
        val totalRemoved = intField(payload, FIELD_TOTAL_REMOVED) ?: return missingOrWrongType(payload, FIELD_TOTAL_REMOVED)
        if (totalRemoved < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REQUEST)
        val pageCountResult = nullableInt(payload, FIELD_PAGE_COUNT) ?: return missingOrWrongType(payload, FIELD_PAGE_COUNT)
        if (pageCountResult is IntOrNull.Rejected) return Result.Rejected(pageCountResult.reason)
        val digestAlg = stringField(payload, FIELD_DIGEST_ALG) ?: return missingOrWrongType(payload, FIELD_DIGEST_ALG)
        return Result.Parsed(
            ManifestMessage.Begin(
                manifestId = id,
                kind = kind,
                manifestRevision = revision,
                baseRevision = (baseRevisionResult as LongOrNull.Accepted).value,
                totalEntries = totalEntries,
                totalRemoved = totalRemoved,
                pageCount = (pageCountResult as IntOrNull.Accepted).value,
                digestAlg = digestAlg,
            ),
        )
    }

    @Suppress("ReturnCount")
    private fun parsePage(payload: JsonObject): Result {
        val idRaw = stringField(payload, FIELD_MANIFEST_ID) ?: return missingOrWrongType(payload, FIELD_MANIFEST_ID)
        val id = ManifestId.parse(idRaw) ?: return Result.Rejected(ManifestMessageRejection.MALFORMED_MANIFEST_ID)
        val revision = longField(payload, FIELD_MANIFEST_REVISION) ?: return missingOrWrongType(payload, FIELD_MANIFEST_REVISION)
        val pageIndex = intField(payload, FIELD_PAGE_INDEX) ?: return missingOrWrongType(payload, FIELD_PAGE_INDEX)
        if (pageIndex < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REQUEST)
        val entriesRaw = (payload[FIELD_ENTRIES] as? JsonArray) ?: return missingOrWrongType(payload, FIELD_ENTRIES)
        if (entriesRaw.size > MAX_ENTRIES_PER_PAGE) return Result.Rejected(ManifestMessageRejection.TOO_MANY_ENTRIES)
        val entries = mutableListOf<ManifestEntry>()
        for (raw in entriesRaw) {
            val obj = raw as? JsonObject ?: return Result.Rejected(ManifestMessageRejection.ENTRY_FIELD_INVALID)
            when (val r = parseEntry(obj)) {
                is EntryResult.Ok -> entries.add(r.entry)
                EntryResult.Invalid -> return Result.Rejected(ManifestMessageRejection.ENTRY_FIELD_INVALID)
                EntryResult.TooLarge -> return Result.Rejected(ManifestMessageRejection.ENTRY_FIELD_TOO_LARGE)
            }
        }
        val removedRaw = (payload[FIELD_REMOVED] as? JsonArray) ?: return missingOrWrongType(payload, FIELD_REMOVED)
        val removed = mutableListOf<ContentHash>()
        for (raw in removedRaw) {
            val s =
                (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return Result.Rejected(ManifestMessageRejection.ENTRY_FIELD_INVALID)
            removed.add(ContentHash.parse(s) ?: return Result.Rejected(ManifestMessageRejection.ENTRY_FIELD_INVALID))
        }
        return Result.Parsed(ManifestMessage.Page(id, revision, pageIndex, entries, removed))
    }

    @Suppress("ReturnCount")
    private fun parseEnd(payload: JsonObject): Result {
        val idRaw = stringField(payload, FIELD_MANIFEST_ID) ?: return missingOrWrongType(payload, FIELD_MANIFEST_ID)
        val id = ManifestId.parse(idRaw) ?: return Result.Rejected(ManifestMessageRejection.MALFORMED_MANIFEST_ID)
        val revision = longField(payload, FIELD_MANIFEST_REVISION) ?: return missingOrWrongType(payload, FIELD_MANIFEST_REVISION)
        val pageCount = intField(payload, FIELD_PAGE_COUNT) ?: return missingOrWrongType(payload, FIELD_PAGE_COUNT)
        if (pageCount < 0) return Result.Rejected(ManifestMessageRejection.INVALID_REQUEST)
        val totalEntries = intField(payload, FIELD_TOTAL_ENTRIES) ?: return missingOrWrongType(payload, FIELD_TOTAL_ENTRIES)
        val totalRemoved = intField(payload, FIELD_TOTAL_REMOVED) ?: return missingOrWrongType(payload, FIELD_TOTAL_REMOVED)
        val digest = stringField(payload, FIELD_DIGEST) ?: return missingOrWrongType(payload, FIELD_DIGEST)
        if (!DIGEST_FORMAT.matches(digest)) return Result.Rejected(ManifestMessageRejection.MALFORMED_DIGEST)
        return Result.Parsed(ManifestMessage.End(id, revision, pageCount, totalEntries, totalRemoved, digest))
    }

    @Suppress("ReturnCount")
    private fun parseAbort(payload: JsonObject): Result {
        val idRaw = stringField(payload, FIELD_MANIFEST_ID) ?: return missingOrWrongType(payload, FIELD_MANIFEST_ID)
        val id = ManifestId.parse(idRaw) ?: return Result.Rejected(ManifestMessageRejection.MALFORMED_MANIFEST_ID)
        val reasonRaw = stringField(payload, FIELD_REASON) ?: return missingOrWrongType(payload, FIELD_REASON)
        val reason = if (reasonRaw in VALID_ABORT_REASONS) reasonRaw else "unknown"
        return Result.Parsed(ManifestMessage.Abort(id, reason))
    }

    private sealed class EntryResult {
        data class Ok(
            val entry: ManifestEntry,
        ) : EntryResult()

        object Invalid : EntryResult()

        object TooLarge : EntryResult()
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseEntry(o: JsonObject): EntryResult {
        val contentHashEntry = o[ManifestEntry.FIELD_CONTENT_HASH]
        val contentHash: ContentHash? =
            when {
                contentHashEntry == null || contentHashEntry is JsonNull -> null
                else -> {
                    val s = stringField(o, ManifestEntry.FIELD_CONTENT_HASH) ?: return EntryResult.Invalid
                    ContentHash.parse(s) ?: return EntryResult.Invalid
                }
            }
        val quickIdRaw = stringField(o, ManifestEntry.FIELD_QUICK_ID) ?: return EntryResult.Invalid
        val quickId = QuickId.parse(quickIdRaw) ?: return EntryResult.Invalid
        val workKey = stringField(o, ManifestEntry.FIELD_WORK_KEY) ?: return EntryResult.Invalid
        val title = boundedString(o, ManifestEntry.FIELD_TITLE) ?: return EntryResult.Invalid
        if (title.isTooLarge) return EntryResult.TooLarge
        val artist = boundedString(o, ManifestEntry.FIELD_ARTIST) ?: return EntryResult.Invalid
        if (artist.isTooLarge) return EntryResult.TooLarge
        val album = boundedString(o, ManifestEntry.FIELD_ALBUM) ?: return EntryResult.Invalid
        if (album.isTooLarge) return EntryResult.TooLarge
        val durationMs = longField(o, ManifestEntry.FIELD_DURATION_MS) ?: return EntryResult.Invalid
        val codec = stringField(o, ManifestEntry.FIELD_CODEC) ?: return EntryResult.Invalid
        val bitrateKbps = intField(o, ManifestEntry.FIELD_BITRATE_KBPS) ?: return EntryResult.Invalid
        val sizeBytes = longField(o, ManifestEntry.FIELD_SIZE_BYTES) ?: return EntryResult.Invalid
        val filename = boundedString(o, ManifestEntry.FIELD_FILENAME) ?: return EntryResult.Invalid
        if (filename.isTooLarge) return EntryResult.TooLarge
        val hasArtwork = booleanField(o, ManifestEntry.FIELD_HAS_ARTWORK) ?: return EntryResult.Invalid
        return EntryResult.Ok(
            ManifestEntry(
                contentHash = contentHash,
                quickId = quickId,
                workKey = workKey,
                title = title.value,
                artist = artist.value,
                album = album.value,
                durationMs = durationMs,
                codec = codec,
                bitrateKbps = bitrateKbps,
                sizeBytes = sizeBytes,
                filename = filename.value,
                hasArtwork = hasArtwork,
            ),
        )
    }

    private data class BoundedString(
        val value: String,
        val isTooLarge: Boolean,
    )

    private fun boundedString(
        o: JsonObject,
        key: String,
    ): BoundedString? {
        val s = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val tooLarge = s.toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_FIELD_BYTES
        return BoundedString(s, tooLarge)
    }

    private sealed class LongOrNull {
        data class Accepted(
            val value: Long?,
        ) : LongOrNull()

        data class Rejected(
            val reason: ManifestMessageRejection,
        ) : LongOrNull()
    }

    @Suppress("ReturnCount")
    private fun nullableLong(
        payload: JsonObject,
        key: String,
    ): LongOrNull? {
        val entry = payload[key] ?: return null
        if (entry is JsonNull) return LongOrNull.Accepted(null)
        val primitive = entry as? JsonPrimitive ?: return LongOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        if (primitive.isString) return LongOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        val value = primitive.longOrNull ?: return LongOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        return LongOrNull.Accepted(value)
    }

    private sealed class IntOrNull {
        data class Accepted(
            val value: Int?,
        ) : IntOrNull()

        data class Rejected(
            val reason: ManifestMessageRejection,
        ) : IntOrNull()
    }

    @Suppress("ReturnCount")
    private fun nullableInt(
        payload: JsonObject,
        key: String,
    ): IntOrNull? {
        val entry = payload[key] ?: return null
        if (entry is JsonNull) return IntOrNull.Accepted(null)
        val primitive = entry as? JsonPrimitive ?: return IntOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        if (primitive.isString) return IntOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        val value = primitive.intOrNull ?: return IntOrNull.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        return IntOrNull.Accepted(value)
    }

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(ManifestMessageRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(ManifestMessageRejection.MISSING_FIELD)
        }

    private fun stringField(
        payload: JsonObject,
        key: String,
    ): String? = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun longField(
        payload: JsonObject,
        key: String,
    ): Long? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun intField(
        payload: JsonObject,
        key: String,
    ): Int? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun booleanField(
        payload: JsonObject,
        key: String,
    ): Boolean? =
        (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let {
            when (it.content) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private val DIGEST_FORMAT = Regex("^sha256:[0-9a-f]{64}$")
}
