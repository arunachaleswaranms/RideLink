package com.ridelink.core.protocol

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.TransferId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** PROTOCOL §3 Transfer group. */
object TransferMessageTypes {
    const val REQUEST = "TRANSFER_REQUEST"
    const val OFFER = "TRANSFER_OFFER"
    const val PROGRESS = "TRANSFER_PROGRESS"
    const val RESULT = "TRANSFER_RESULT"
    const val CANCEL = "TRANSFER_CANCEL"

    val ALL = setOf(REQUEST, OFFER, PROGRESS, RESULT, CANCEL)
}

/** PROTOCOL §8.2 bounds, fixed in V1 (ADR-023 §30/§23 of the brief). */
object TransferBounds {
    const val CHUNK_SIZE = 65_536
    const val MAX_TRANSFER_SIZE_BYTES = 2_147_483_648L // 2 GiB defensive cap
    val VALID_CANCEL_REASONS = setOf("user_cancelled", "disconnected", "superseded", "error")
}

/** One decoded, bounds-checked `TRANSFER_*` message (PROTOCOL §8.2). */
sealed class TransferMessage {
    data class Request(
        val contentHash: ContentHash,
        val transferId: TransferId,
    ) : TransferMessage()

    data class Offer(
        val transferId: TransferId,
        val sizeBytes: Long,
        val chunkSize: Int,
        val chunkCount: Int,
        val bulkPort: Int,
        /** 64 lowercase hex characters (32 bytes). Never logged (ADR-023 §2). */
        val bulkToken: String,
    ) : TransferMessage()

    data class Progress(
        val transferId: TransferId,
        val bytes: Long,
        val pct: Int,
    ) : TransferMessage()

    data class Result(
        val transferId: TransferId,
        val ok: Boolean,
        val sha256: ContentHash?,
    ) : TransferMessage()

    data class Cancel(
        val transferId: TransferId,
        /** A closed vocabulary tolerated as `"unknown"` for a value this build does not recognise. */
        val reason: String,
    ) : TransferMessage()
}

/** Why a `TRANSFER_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim. */
enum class TransferMessageRejection {
    UNKNOWN_TYPE,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    MALFORMED_CONTENT_HASH,
    MALFORMED_TRANSFER_ID,
    INVALID_SIZE,
    SIZE_TOO_LARGE,
    UNSUPPORTED_CHUNK_SIZE,
    CHUNK_COUNT_MISMATCH,
    PORT_OUT_OF_RANGE,
    MALFORMED_BULK_TOKEN,
    PROGRESS_OUT_OF_RANGE,
}

/**
 * Parses and bounds-checks a `TRANSFER_*` payload. Total and non-throwing, mirroring
 * [AudioStateCodec]/[VoiceSignalCodec] — a malformed frame is dropped and the control connection
 * survives.
 *
 * One object per message family, matching [AudioStateCodec]/[VoiceSignalCodec] — see
 * [ManifestCodec]'s identical note.
 */
@Suppress("TooManyFunctions")
object TransferCodec {
    sealed class Result {
        data class Parsed(
            val message: TransferMessage,
        ) : Result()

        data class Rejected(
            val reason: TransferMessageRejection,
        ) : Result()
    }

    const val FIELD_CONTENT_HASH = "content_hash"
    const val FIELD_TRANSFER_ID = "transfer_id"
    const val FIELD_SIZE_BYTES = "size_bytes"
    const val FIELD_CHUNK_SIZE = "chunk_size"
    const val FIELD_CHUNK_COUNT = "chunk_count"
    const val FIELD_BULK_PORT = "bulk_port"
    const val FIELD_BULK_TOKEN = "bulk_token"
    const val FIELD_BYTES = "bytes"
    const val FIELD_PCT = "pct"
    const val FIELD_OK = "ok"
    const val FIELD_SHA256 = "sha256"
    const val FIELD_REASON = "reason"

    private val BULK_TOKEN_FORMAT = Regex("^[0-9a-f]{64}$")
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val MIN_PCT = 0
    private const val MAX_PCT = 100

    fun parse(
        type: String,
        payload: JsonObject,
    ): Result =
        when (type) {
            TransferMessageTypes.REQUEST -> parseRequest(payload)
            TransferMessageTypes.OFFER -> parseOffer(payload)
            TransferMessageTypes.PROGRESS -> parseProgress(payload)
            TransferMessageTypes.RESULT -> parseResult(payload)
            TransferMessageTypes.CANCEL -> parseCancel(payload)
            else -> Result.Rejected(TransferMessageRejection.UNKNOWN_TYPE)
        }

    /** The `TRANSFER_*` type a given [TransferMessage] wire-encodes as. */
    fun wireType(message: TransferMessage): String =
        when (message) {
            is TransferMessage.Request -> TransferMessageTypes.REQUEST
            is TransferMessage.Offer -> TransferMessageTypes.OFFER
            is TransferMessage.Progress -> TransferMessageTypes.PROGRESS
            is TransferMessage.Result -> TransferMessageTypes.RESULT
            is TransferMessage.Cancel -> TransferMessageTypes.CANCEL
        }

    /** The outbound side of [parse] — the shape lives here, once, shared by both directions. */
    fun encode(message: TransferMessage): JsonObject =
        when (message) {
            is TransferMessage.Request ->
                buildJsonObject {
                    put(FIELD_CONTENT_HASH, message.contentHash.value)
                    put(FIELD_TRANSFER_ID, message.transferId.value)
                }
            is TransferMessage.Offer ->
                buildJsonObject {
                    put(FIELD_TRANSFER_ID, message.transferId.value)
                    put(FIELD_SIZE_BYTES, message.sizeBytes)
                    put(FIELD_CHUNK_SIZE, message.chunkSize)
                    put(FIELD_CHUNK_COUNT, message.chunkCount)
                    put(FIELD_BULK_PORT, message.bulkPort)
                    put(FIELD_BULK_TOKEN, message.bulkToken)
                }
            is TransferMessage.Progress ->
                buildJsonObject {
                    put(FIELD_TRANSFER_ID, message.transferId.value)
                    put(FIELD_BYTES, message.bytes)
                    put(FIELD_PCT, message.pct)
                }
            is TransferMessage.Result ->
                buildJsonObject {
                    put(FIELD_TRANSFER_ID, message.transferId.value)
                    put(FIELD_OK, message.ok)
                    put(FIELD_SHA256, message.sha256?.value)
                }
            is TransferMessage.Cancel ->
                buildJsonObject {
                    put(FIELD_TRANSFER_ID, message.transferId.value)
                    put(FIELD_REASON, message.reason)
                }
        }

    @Suppress("ReturnCount")
    private fun parseRequest(payload: JsonObject): Result {
        val contentHash = requiredContentHash(payload, FIELD_CONTENT_HASH) ?: return rejectContentHash(payload, FIELD_CONTENT_HASH)
        val transferId = requiredTransferId(payload, FIELD_TRANSFER_ID) ?: return rejectTransferId(payload, FIELD_TRANSFER_ID)
        return Result.Parsed(TransferMessage.Request(contentHash, transferId))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseOffer(payload: JsonObject): Result {
        val transferId = requiredTransferId(payload, FIELD_TRANSFER_ID) ?: return rejectTransferId(payload, FIELD_TRANSFER_ID)
        val sizeBytes = longField(payload, FIELD_SIZE_BYTES) ?: return missingOrWrongType(payload, FIELD_SIZE_BYTES)
        if (sizeBytes <= 0) return Result.Rejected(TransferMessageRejection.INVALID_SIZE)
        if (sizeBytes > TransferBounds.MAX_TRANSFER_SIZE_BYTES) return Result.Rejected(TransferMessageRejection.SIZE_TOO_LARGE)
        val chunkSize = intField(payload, FIELD_CHUNK_SIZE) ?: return missingOrWrongType(payload, FIELD_CHUNK_SIZE)
        if (chunkSize != TransferBounds.CHUNK_SIZE) return Result.Rejected(TransferMessageRejection.UNSUPPORTED_CHUNK_SIZE)
        val chunkCount = intField(payload, FIELD_CHUNK_COUNT) ?: return missingOrWrongType(payload, FIELD_CHUNK_COUNT)
        val expectedChunkCount = ((sizeBytes + chunkSize - 1) / chunkSize)
        if (chunkCount.toLong() != expectedChunkCount) return Result.Rejected(TransferMessageRejection.CHUNK_COUNT_MISMATCH)
        val bulkPort = intField(payload, FIELD_BULK_PORT) ?: return missingOrWrongType(payload, FIELD_BULK_PORT)
        if (bulkPort < MIN_PORT || bulkPort > MAX_PORT) return Result.Rejected(TransferMessageRejection.PORT_OUT_OF_RANGE)
        val bulkToken = stringField(payload, FIELD_BULK_TOKEN) ?: return missingOrWrongType(payload, FIELD_BULK_TOKEN)
        if (!BULK_TOKEN_FORMAT.matches(bulkToken)) return Result.Rejected(TransferMessageRejection.MALFORMED_BULK_TOKEN)
        return Result.Parsed(TransferMessage.Offer(transferId, sizeBytes, chunkSize, chunkCount, bulkPort, bulkToken))
    }

    @Suppress("ReturnCount")
    private fun parseProgress(payload: JsonObject): Result {
        val transferId = requiredTransferId(payload, FIELD_TRANSFER_ID) ?: return rejectTransferId(payload, FIELD_TRANSFER_ID)
        val bytes = longField(payload, FIELD_BYTES) ?: return missingOrWrongType(payload, FIELD_BYTES)
        if (bytes < 0) return Result.Rejected(TransferMessageRejection.INVALID_SIZE)
        val pct = intField(payload, FIELD_PCT) ?: return missingOrWrongType(payload, FIELD_PCT)
        if (pct < MIN_PCT || pct > MAX_PCT) return Result.Rejected(TransferMessageRejection.PROGRESS_OUT_OF_RANGE)
        return Result.Parsed(TransferMessage.Progress(transferId, bytes, pct))
    }

    @Suppress("ReturnCount")
    private fun parseResult(payload: JsonObject): Result {
        val transferId = requiredTransferId(payload, FIELD_TRANSFER_ID) ?: return rejectTransferId(payload, FIELD_TRANSFER_ID)
        val ok = booleanField(payload, FIELD_OK) ?: return missingOrWrongType(payload, FIELD_OK)
        val shaEntry = payload[FIELD_SHA256]
        val sha256: ContentHash? =
            when {
                shaEntry == null || shaEntry is JsonNull -> {
                    if (ok) return Result.Rejected(TransferMessageRejection.MISSING_FIELD)
                    null
                }
                else -> {
                    val s =
                        (shaEntry as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return Result.Rejected(TransferMessageRejection.WRONG_FIELD_TYPE)
                    ContentHash.parse(s) ?: return Result.Rejected(TransferMessageRejection.MALFORMED_CONTENT_HASH)
                }
            }
        return Result.Parsed(TransferMessage.Result(transferId, ok, sha256))
    }

    @Suppress("ReturnCount")
    private fun parseCancel(payload: JsonObject): Result {
        val transferId = requiredTransferId(payload, FIELD_TRANSFER_ID) ?: return rejectTransferId(payload, FIELD_TRANSFER_ID)
        val reasonRaw = stringField(payload, FIELD_REASON) ?: return missingOrWrongType(payload, FIELD_REASON)
        val reason = if (reasonRaw in TransferBounds.VALID_CANCEL_REASONS) reasonRaw else "unknown"
        return Result.Parsed(TransferMessage.Cancel(transferId, reason))
    }

    private fun requiredContentHash(
        payload: JsonObject,
        key: String,
    ): ContentHash? = stringField(payload, key)?.let(ContentHash::parse)

    private fun rejectContentHash(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (stringField(payload, key) != null) {
            Result.Rejected(TransferMessageRejection.MALFORMED_CONTENT_HASH)
        } else {
            missingOrWrongType(payload, key)
        }

    private fun requiredTransferId(
        payload: JsonObject,
        key: String,
    ): TransferId? = stringField(payload, key)?.let(TransferId::parse)

    private fun rejectTransferId(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (stringField(payload, key) != null) {
            Result.Rejected(TransferMessageRejection.MALFORMED_TRANSFER_ID)
        } else {
            missingOrWrongType(payload, key)
        }

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(TransferMessageRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(TransferMessageRejection.MISSING_FIELD)
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
}
