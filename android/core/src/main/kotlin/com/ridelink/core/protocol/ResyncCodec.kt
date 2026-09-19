package com.ridelink.core.protocol

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.resync.ResyncBounds
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.resync.ResyncPlaybackSnapshot
import com.ridelink.core.resync.ResyncTransferInFlight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PROTOCOL §3 Resync group (Phase 7). */
object ResyncMessageTypes {
    const val STATE_REQUEST = "STATE_REQUEST"
    const val STATE_SNAPSHOT = "STATE_SNAPSHOT"

    val ALL = setOf(STATE_REQUEST, STATE_SNAPSHOT)
}

/** Why a resync payload was refused. Recorded in diagnostics; never sent to the peer verbatim. */
enum class ResyncMessageRejection {
    UNKNOWN_TYPE,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    MALFORMED_CONTENT_HASH,
    MALFORMED_QUEUE_ITEM_ID,
    MALFORMED_PEER_ID,
    MALFORMED_TRANSFER_ID,
    COMMAND_SEQ_OUT_OF_RANGE,
    REVISION_OUT_OF_RANGE,
    MANIFEST_REVISION_OUT_OF_RANGE,
    SESSION_TIME_OUT_OF_RANGE,
    POSITION_OUT_OF_RANGE,
    BYTES_DONE_OUT_OF_RANGE,
    TOO_MANY_TRANSFERS,
    MALFORMED_QUEUE,
    MALFORMED_PLAYBACK,
}

/**
 * Parses, bounds-checks and encodes PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT`. Total and
 * non-throwing, mirroring [PlaybackCodec]/[QueueCodec]/[ManifestCodec]/[TransferCodec] — a
 * malformed frame is dropped and the control connection survives.
 *
 * `STATE_SNAPSHOT.playback` and `.queue` are, by PROTOCOL §10's own words, "`QUEUE_SNAPSHOT` shape"
 * and (per §5's cross-reference) `PLAYBACK_STATE` minus its two ordering fields — so their field
 * validation reuses [QueueCodec.parseSnapshotPayload] and this file's own small playback parser
 * rather than a second, possibly-diverging copy of either (ADR-028).
 *
 * Mirrored by `RideLinkCore.ResyncCodec`; both run `protocol/vectors/resync-messages/`.
 */
@Suppress("TooManyFunctions")
object ResyncCodec {
    sealed class Result {
        data class Parsed(
            val message: ResyncMessage,
        ) : Result()

        data class Rejected(
            val reason: ResyncMessageRejection,
        ) : Result()
    }

    const val FIELD_LEADER_PEER_ID = "leader_peer_id"
    const val FIELD_COMMAND_SEQ = "command_seq"
    const val FIELD_QUEUE_REVISION = "queue_revision"
    const val FIELD_PLAYBACK = "playback"
    const val FIELD_QUEUE = "queue"
    const val FIELD_MANIFEST_REVISION = "manifest_revision"
    const val FIELD_TRANSFERS_IN_FLIGHT = "transfers_in_flight"

    const val FIELD_TRACK_HASH = "track_hash"
    const val FIELD_QUEUE_ITEM_ID = "queue_item_id"
    const val FIELD_POSITION_MS = "position_ms"
    const val FIELD_PLAYING = "playing"
    const val FIELD_AT_SESSION_US = "at_session_us"

    const val FIELD_TRANSFER_ID = "transfer_id"
    const val FIELD_CONTENT_HASH = "content_hash"
    const val FIELD_BYTES_DONE = "bytes_done"

    fun parse(
        type: String,
        payload: JsonObject,
    ): Result =
        when (type) {
            ResyncMessageTypes.STATE_REQUEST -> Result.Parsed(ResyncMessage.StateRequest)
            ResyncMessageTypes.STATE_SNAPSHOT -> parseStateSnapshot(payload)
            else -> Result.Rejected(ResyncMessageRejection.UNKNOWN_TYPE)
        }

    fun wireType(message: ResyncMessage): String =
        when (message) {
            is ResyncMessage.StateRequest -> ResyncMessageTypes.STATE_REQUEST
            is ResyncMessage.StateSnapshot -> ResyncMessageTypes.STATE_SNAPSHOT
        }

    fun encode(message: ResyncMessage): JsonObject =
        when (message) {
            is ResyncMessage.StateRequest -> buildJsonObject { }
            is ResyncMessage.StateSnapshot ->
                buildJsonObject {
                    put(FIELD_LEADER_PEER_ID, message.leaderPeerId.value)
                    put(FIELD_COMMAND_SEQ, message.commandSeq)
                    put(FIELD_QUEUE_REVISION, message.queueRevision)
                    put(FIELD_PLAYBACK, message.playback?.let(::encodePlayback) ?: JsonNull)
                    put(
                        FIELD_QUEUE,
                        QueueCodec.encode(
                            com.ridelink.core.playback.QueueMessage.Snapshot(
                                message.queueRevision,
                                message.queueItems,
                                message.queueCurrentIndex,
                            ),
                        ),
                    )
                    put(FIELD_MANIFEST_REVISION, message.manifestRevision)
                    put(
                        FIELD_TRANSFERS_IN_FLIGHT,
                        buildJsonArray {
                            message.transfersInFlight.forEach { transfer ->
                                add(
                                    buildJsonObject {
                                        put(FIELD_TRANSFER_ID, transfer.transferId.value)
                                        put(FIELD_CONTENT_HASH, transfer.contentHash.value)
                                        put(FIELD_BYTES_DONE, transfer.bytesDone)
                                    },
                                )
                            }
                        },
                    )
                }
        }

    private fun encodePlayback(playback: ResyncPlaybackSnapshot): JsonObject =
        buildJsonObject {
            put(FIELD_TRACK_HASH, playback.trackHash?.value)
            put(FIELD_QUEUE_ITEM_ID, playback.queueItemId)
            put(FIELD_POSITION_MS, playback.positionMs)
            put(FIELD_PLAYING, playback.playing)
            put(FIELD_AT_SESSION_US, playback.atSessionUs)
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseStateSnapshot(payload: JsonObject): Result {
        val leaderPeerIdRaw =
            phase5StringField(payload, FIELD_LEADER_PEER_ID) ?: return missingOrWrongType(payload, FIELD_LEADER_PEER_ID)
        val leaderPeerId =
            phase5ParsePeerId(leaderPeerIdRaw) ?: return Result.Rejected(ResyncMessageRejection.MALFORMED_PEER_ID)
        val commandSeq = phase5LongField(payload, FIELD_COMMAND_SEQ) ?: return missingOrWrongType(payload, FIELD_COMMAND_SEQ)
        if (commandSeq < 0 || commandSeq > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(ResyncMessageRejection.COMMAND_SEQ_OUT_OF_RANGE)
        }
        val queueRevision = phase5LongField(payload, FIELD_QUEUE_REVISION) ?: return missingOrWrongType(payload, FIELD_QUEUE_REVISION)
        if (queueRevision < 0 || queueRevision > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(ResyncMessageRejection.REVISION_OUT_OF_RANGE)
        }
        val playback =
            when (val entry = payload[FIELD_PLAYBACK]) {
                null -> return Result.Rejected(ResyncMessageRejection.MISSING_FIELD)
                is JsonNull -> null
                is JsonObject ->
                    when (val r = parsePlaybackField(entry)) {
                        is PlaybackFieldResult.Bad -> return r.rejected
                        is PlaybackFieldResult.Ok -> r.snapshot
                    }
                else -> return Result.Rejected(ResyncMessageRejection.MALFORMED_PLAYBACK)
            }
        val queueObject = payload[FIELD_QUEUE] as? JsonObject ?: return missingOrWrongType(payload, FIELD_QUEUE)
        val (queueItems, queueCurrentIndex) =
            when (val r = QueueCodec.parseSnapshotPayload(queueObject)) {
                is QueueCodec.Result.Rejected -> return Result.Rejected(ResyncMessageRejection.MALFORMED_QUEUE)
                is QueueCodec.Result.Parsed -> {
                    val snapshot = r.message as com.ridelink.core.playback.QueueMessage.Snapshot
                    snapshot.items to snapshot.currentIndex
                }
            }
        val manifestRevision =
            phase5LongField(payload, FIELD_MANIFEST_REVISION) ?: return missingOrWrongType(payload, FIELD_MANIFEST_REVISION)
        if (manifestRevision < 0 || manifestRevision > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(ResyncMessageRejection.MANIFEST_REVISION_OUT_OF_RANGE)
        }
        val transfersRaw =
            payload[FIELD_TRANSFERS_IN_FLIGHT] as? JsonArray ?: return missingOrWrongType(payload, FIELD_TRANSFERS_IN_FLIGHT)
        if (transfersRaw.size > ResyncBounds.MAX_TRANSFERS_IN_FLIGHT) {
            return Result.Rejected(ResyncMessageRejection.TOO_MANY_TRANSFERS)
        }
        val transfers = ArrayList<ResyncTransferInFlight>(transfersRaw.size)
        for (element in transfersRaw) {
            val item = element as? JsonObject ?: return Result.Rejected(ResyncMessageRejection.WRONG_FIELD_TYPE)
            transfers.add(
                when (val r = parseTransferInFlight(item)) {
                    is TransferFieldResult.Bad -> return r.rejected
                    is TransferFieldResult.Ok -> r.transfer
                },
            )
        }
        return Result.Parsed(
            ResyncMessage.StateSnapshot(
                leaderPeerId,
                commandSeq,
                queueRevision,
                playback,
                queueItems,
                queueCurrentIndex,
                manifestRevision,
                transfers,
            ),
        )
    }

    private sealed class PlaybackFieldResult {
        data class Ok(
            val snapshot: ResyncPlaybackSnapshot,
        ) : PlaybackFieldResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : PlaybackFieldResult()
    }

    @Suppress("ReturnCount")
    private fun parsePlaybackField(payload: JsonObject): PlaybackFieldResult {
        val trackHash =
            when (val raw = phase5NullableStringField(payload, FIELD_TRACK_HASH)) {
                Phase5NullableString.Missing -> return PlaybackFieldResult.Bad(missingOrWrongType(payload, FIELD_TRACK_HASH))
                Phase5NullableString.ExplicitNull -> null
                is Phase5NullableString.Present ->
                    ContentHash.parse(raw.value)
                        ?: return PlaybackFieldResult.Bad(Result.Rejected(ResyncMessageRejection.MALFORMED_CONTENT_HASH))
            }
        val queueItemId =
            when (val raw = phase5NullableStringField(payload, FIELD_QUEUE_ITEM_ID)) {
                Phase5NullableString.Missing -> return PlaybackFieldResult.Bad(missingOrWrongType(payload, FIELD_QUEUE_ITEM_ID))
                Phase5NullableString.ExplicitNull -> null
                is Phase5NullableString.Present ->
                    if (phase5IsUlid(raw.value)) {
                        raw.value
                    } else {
                        return PlaybackFieldResult.Bad(Result.Rejected(ResyncMessageRejection.MALFORMED_QUEUE_ITEM_ID))
                    }
            }
        val positionMs =
            phase5LongField(payload, FIELD_POSITION_MS) ?: return PlaybackFieldResult.Bad(missingOrWrongType(payload, FIELD_POSITION_MS))
        if (positionMs < 0 || positionMs > PlaybackBounds.MAX_POSITION_MS) {
            return PlaybackFieldResult.Bad(Result.Rejected(ResyncMessageRejection.POSITION_OUT_OF_RANGE))
        }
        val playing =
            phase5BooleanField(payload, FIELD_PLAYING) ?: return PlaybackFieldResult.Bad(missingOrWrongType(payload, FIELD_PLAYING))
        val atSessionUs =
            phase5LongField(payload, FIELD_AT_SESSION_US)
                ?: return PlaybackFieldResult.Bad(missingOrWrongType(payload, FIELD_AT_SESSION_US))
        if (atSessionUs < 0 || atSessionUs > PlaybackBounds.MAX_WIRE_INT) {
            return PlaybackFieldResult.Bad(Result.Rejected(ResyncMessageRejection.SESSION_TIME_OUT_OF_RANGE))
        }
        return PlaybackFieldResult.Ok(ResyncPlaybackSnapshot(trackHash, queueItemId, positionMs, playing, atSessionUs))
    }

    private sealed class TransferFieldResult {
        data class Ok(
            val transfer: ResyncTransferInFlight,
        ) : TransferFieldResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : TransferFieldResult()
    }

    @Suppress("ReturnCount")
    private fun parseTransferInFlight(payload: JsonObject): TransferFieldResult {
        val transferIdRaw =
            phase5StringField(payload, FIELD_TRANSFER_ID) ?: return TransferFieldResult.Bad(missingOrWrongType(payload, FIELD_TRANSFER_ID))
        val transferId =
            TransferId.parse(transferIdRaw) ?: return TransferFieldResult.Bad(Result.Rejected(ResyncMessageRejection.MALFORMED_TRANSFER_ID))
        val hashRaw =
            phase5StringField(payload, FIELD_CONTENT_HASH)
                ?: return TransferFieldResult.Bad(missingOrWrongType(payload, FIELD_CONTENT_HASH))
        val contentHash =
            ContentHash.parse(hashRaw) ?: return TransferFieldResult.Bad(Result.Rejected(ResyncMessageRejection.MALFORMED_CONTENT_HASH))
        val bytesDone =
            phase5LongField(payload, FIELD_BYTES_DONE) ?: return TransferFieldResult.Bad(missingOrWrongType(payload, FIELD_BYTES_DONE))
        if (bytesDone < 0 || bytesDone > PlaybackBounds.MAX_WIRE_INT) {
            return TransferFieldResult.Bad(Result.Rejected(ResyncMessageRejection.BYTES_DONE_OUT_OF_RANGE))
        }
        return TransferFieldResult.Ok(ResyncTransferInFlight(transferId, contentHash, bytesDone))
    }

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(ResyncMessageRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(ResyncMessageRejection.MISSING_FIELD)
        }
}
