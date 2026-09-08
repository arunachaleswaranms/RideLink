package com.ridelink.core.protocol

import com.ridelink.core.model.ContentHash
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.QueueAddItem
import com.ridelink.core.playback.QueueCommandHeader
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.playback.SharedQueueItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PROTOCOL §3 Queue group (Phase 5). */
object QueueMessageTypes {
    const val ADD = "QUEUE_ADD"
    const val REMOVE = "QUEUE_REMOVE"
    const val MOVE = "QUEUE_MOVE"
    const val SNAPSHOT = "QUEUE_SNAPSHOT"

    val ALL = setOf(ADD, REMOVE, MOVE, SNAPSHOT)

    /** The mutations, which carry a [QueueCommandHeader]. `QUEUE_SNAPSHOT` is state, not a mutation. */
    val MUTATIONS = setOf(ADD, REMOVE, MOVE)
}

/** Why a `QUEUE_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim. */
enum class QueueMessageRejection {
    UNKNOWN_TYPE,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    MALFORMED_CONTENT_HASH,
    MALFORMED_QUEUE_ITEM_ID,
    MALFORMED_PEER_ID,
    COMMAND_SEQ_OUT_OF_RANGE,
    REVISION_OUT_OF_RANGE,
    ORDER_OUT_OF_RANGE,
    EMPTY_ITEM_LIST,
    TOO_MANY_ITEMS,
    DUPLICATE_QUEUE_ITEM_ID,
    INDEX_OUT_OF_RANGE,
}

/**
 * Parses, bounds-checks and encodes PROTOCOL §9's queue-replication messages. Total and
 * non-throwing, mirroring [PlaybackCodec] and every codec before it.
 *
 * Mirrored by `RideLinkCore.QueueCodec`; both run `protocol/vectors/queue-messages/`.
 *
 * **`status` is deliberately absent from the wire shape.** PROTOCOL §9 listed it on
 * `QUEUE_SNAPSHOT` while stating in the same paragraph that it is "derived locally from presence,
 * never trusted from the peer" — a field that must never be trusted has no reason to be sent, and
 * sending it hands a peer a channel to influence what the local UI claims about local storage.
 * ADR-024 §6 removes it; availability comes from `core.transfer.Availability`, as it always did.
 */
@Suppress("TooManyFunctions")
object QueueCodec {
    sealed class Result {
        data class Parsed(
            val message: QueueMessage,
        ) : Result()

        data class Rejected(
            val reason: QueueMessageRejection,
        ) : Result()
    }

    const val FIELD_COMMAND_SEQ = "command_seq"
    const val FIELD_QUEUE_REVISION = "queue_revision"
    const val FIELD_ITEMS = "items"
    const val FIELD_QUEUE_ITEM_ID = "queue_item_id"
    const val FIELD_QUEUE_ITEM_IDS = "queue_item_ids"
    const val FIELD_TRACK_HASH = "track_hash"
    const val FIELD_ADDED_BY = "added_by"
    const val FIELD_POSITION = "position"
    const val FIELD_ORDER = "order"
    const val FIELD_TO_INDEX = "to_index"
    const val FIELD_CURRENT_INDEX = "current_index"

    fun parse(
        type: String,
        payload: JsonObject,
    ): Result =
        when (type) {
            QueueMessageTypes.ADD -> parseAdd(payload)
            QueueMessageTypes.REMOVE -> parseRemove(payload)
            QueueMessageTypes.MOVE -> parseMove(payload)
            QueueMessageTypes.SNAPSHOT -> parseSnapshot(payload)
            else -> Result.Rejected(QueueMessageRejection.UNKNOWN_TYPE)
        }

    fun wireType(message: QueueMessage): String =
        when (message) {
            is QueueMessage.Add -> QueueMessageTypes.ADD
            is QueueMessage.Remove -> QueueMessageTypes.REMOVE
            is QueueMessage.Move -> QueueMessageTypes.MOVE
            is QueueMessage.Snapshot -> QueueMessageTypes.SNAPSHOT
        }

    fun encode(message: QueueMessage): JsonObject =
        when (message) {
            is QueueMessage.Add ->
                buildJsonObject {
                    put(FIELD_COMMAND_SEQ, message.header.commandSeq)
                    put(FIELD_QUEUE_REVISION, message.header.queueRevision)
                    put(
                        FIELD_ITEMS,
                        buildJsonArray {
                            message.items.forEach { item ->
                                add(
                                    buildJsonObject {
                                        put(FIELD_QUEUE_ITEM_ID, item.queueItemId)
                                        put(FIELD_TRACK_HASH, item.trackHash.value)
                                        put(FIELD_ADDED_BY, item.addedBy.value)
                                        put(FIELD_POSITION, item.position)
                                    },
                                )
                            }
                        },
                    )
                }
            is QueueMessage.Remove ->
                buildJsonObject {
                    put(FIELD_COMMAND_SEQ, message.header.commandSeq)
                    put(FIELD_QUEUE_REVISION, message.header.queueRevision)
                    put(FIELD_QUEUE_ITEM_IDS, buildJsonArray { message.queueItemIds.forEach { add(JsonPrimitive(it)) } })
                }
            is QueueMessage.Move ->
                buildJsonObject {
                    put(FIELD_COMMAND_SEQ, message.header.commandSeq)
                    put(FIELD_QUEUE_REVISION, message.header.queueRevision)
                    put(FIELD_QUEUE_ITEM_ID, message.queueItemId)
                    put(FIELD_TO_INDEX, message.toIndex)
                }
            is QueueMessage.Snapshot ->
                buildJsonObject {
                    put(FIELD_QUEUE_REVISION, message.queueRevision)
                    put(
                        FIELD_ITEMS,
                        buildJsonArray {
                            message.items.forEach { item ->
                                add(
                                    buildJsonObject {
                                        put(FIELD_QUEUE_ITEM_ID, item.queueItemId)
                                        put(FIELD_TRACK_HASH, item.trackHash.value)
                                        put(FIELD_ADDED_BY, item.addedBy.value)
                                        put(FIELD_ORDER, item.order)
                                    },
                                )
                            }
                        },
                    )
                    put(FIELD_CURRENT_INDEX, message.currentIndex)
                }
        }

    private sealed class HeaderResult {
        data class Ok(
            val header: QueueCommandHeader,
        ) : HeaderResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : HeaderResult()
    }

    @Suppress("ReturnCount")
    private fun parseHeader(payload: JsonObject): HeaderResult {
        val commandSeq =
            phase5LongField(payload, FIELD_COMMAND_SEQ) ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_COMMAND_SEQ))
        if (commandSeq < 0 || commandSeq > PlaybackBounds.MAX_WIRE_INT) {
            return HeaderResult.Bad(Result.Rejected(QueueMessageRejection.COMMAND_SEQ_OUT_OF_RANGE))
        }
        val queueRevision =
            phase5LongField(payload, FIELD_QUEUE_REVISION) ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_QUEUE_REVISION))
        if (queueRevision < 0 || queueRevision > PlaybackBounds.MAX_WIRE_INT) {
            return HeaderResult.Bad(Result.Rejected(QueueMessageRejection.REVISION_OUT_OF_RANGE))
        }
        return HeaderResult.Ok(QueueCommandHeader(commandSeq, queueRevision))
    }

    @Suppress("ReturnCount")
    private fun parseAdd(payload: JsonObject): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val rawItems = payload[FIELD_ITEMS] as? JsonArray ?: return missingOrWrongType(payload, FIELD_ITEMS)
        if (rawItems.isEmpty()) return Result.Rejected(QueueMessageRejection.EMPTY_ITEM_LIST)
        if (rawItems.size > PlaybackBounds.MAX_QUEUE_ITEMS) return Result.Rejected(QueueMessageRejection.TOO_MANY_ITEMS)
        val items = ArrayList<QueueAddItem>(rawItems.size)
        val seen = HashSet<String>()
        for (element in rawItems) {
            val item = element as? JsonObject ?: return Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
            val parsed =
                when (val r = parseAddItem(item)) {
                    is AddItemResult.Bad -> return r.rejected
                    is AddItemResult.Ok -> r.item
                }
            if (!seen.add(parsed.queueItemId)) return Result.Rejected(QueueMessageRejection.DUPLICATE_QUEUE_ITEM_ID)
            items.add(parsed)
        }
        return Result.Parsed(QueueMessage.Add(header, items))
    }

    private sealed class AddItemResult {
        data class Ok(
            val item: QueueAddItem,
        ) : AddItemResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : AddItemResult()
    }

    @Suppress("ReturnCount")
    private fun parseAddItem(item: JsonObject): AddItemResult {
        val queueItemId =
            phase5StringField(item, FIELD_QUEUE_ITEM_ID) ?: return AddItemResult.Bad(missingOrWrongType(item, FIELD_QUEUE_ITEM_ID))
        if (!phase5IsUlid(queueItemId)) return AddItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_QUEUE_ITEM_ID))
        val hashRaw = phase5StringField(item, FIELD_TRACK_HASH) ?: return AddItemResult.Bad(missingOrWrongType(item, FIELD_TRACK_HASH))
        val trackHash =
            ContentHash.parse(hashRaw) ?: return AddItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_CONTENT_HASH))
        val addedByRaw = phase5StringField(item, FIELD_ADDED_BY) ?: return AddItemResult.Bad(missingOrWrongType(item, FIELD_ADDED_BY))
        val addedBy =
            phase5ParsePeerId(addedByRaw) ?: return AddItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_PEER_ID))
        val positionRaw = phase5StringField(item, FIELD_POSITION) ?: return AddItemResult.Bad(missingOrWrongType(item, FIELD_POSITION))
        // PROTOCOL §2 rule 2's forward-compatibility posture applied to a value rather than a type:
        // an unrecognised position degrades to `end` rather than rejecting the whole frame.
        val position = if (positionRaw in PlaybackBounds.VALID_QUEUE_POSITIONS) positionRaw else PlaybackBounds.QUEUE_POSITION_END
        return AddItemResult.Ok(QueueAddItem(queueItemId, trackHash, addedBy, position))
    }

    @Suppress("ReturnCount")
    private fun parseRemove(payload: JsonObject): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val rawIds = payload[FIELD_QUEUE_ITEM_IDS] as? JsonArray ?: return missingOrWrongType(payload, FIELD_QUEUE_ITEM_IDS)
        if (rawIds.isEmpty()) return Result.Rejected(QueueMessageRejection.EMPTY_ITEM_LIST)
        if (rawIds.size > PlaybackBounds.MAX_QUEUE_ITEMS) return Result.Rejected(QueueMessageRejection.TOO_MANY_ITEMS)
        val ids = ArrayList<String>(rawIds.size)
        for (element in rawIds) {
            val primitive = element as? JsonPrimitive ?: return Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
            if (!primitive.isString) return Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
            if (!phase5IsUlid(primitive.content)) return Result.Rejected(QueueMessageRejection.MALFORMED_QUEUE_ITEM_ID)
            ids.add(primitive.content)
        }
        return Result.Parsed(QueueMessage.Remove(header, ids))
    }

    @Suppress("ReturnCount")
    private fun parseMove(payload: JsonObject): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val queueItemId = phase5StringField(payload, FIELD_QUEUE_ITEM_ID) ?: return missingOrWrongType(payload, FIELD_QUEUE_ITEM_ID)
        if (!phase5IsUlid(queueItemId)) return Result.Rejected(QueueMessageRejection.MALFORMED_QUEUE_ITEM_ID)
        val toIndex = phase5IntField(payload, FIELD_TO_INDEX) ?: return missingOrWrongType(payload, FIELD_TO_INDEX)
        if (toIndex < 0 || toIndex >= PlaybackBounds.MAX_QUEUE_ITEMS) return Result.Rejected(QueueMessageRejection.INDEX_OUT_OF_RANGE)
        return Result.Parsed(QueueMessage.Move(header, queueItemId, toIndex))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parseSnapshot(payload: JsonObject): Result {
        val queueRevision = phase5LongField(payload, FIELD_QUEUE_REVISION) ?: return missingOrWrongType(payload, FIELD_QUEUE_REVISION)
        if (queueRevision < 0 || queueRevision > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(QueueMessageRejection.REVISION_OUT_OF_RANGE)
        }
        val rawItems = payload[FIELD_ITEMS] as? JsonArray ?: return missingOrWrongType(payload, FIELD_ITEMS)
        // An empty snapshot is legitimate and must be representable: it is what "the other user
        // cleared the queue" looks like on the wire.
        if (rawItems.size > PlaybackBounds.MAX_QUEUE_ITEMS) return Result.Rejected(QueueMessageRejection.TOO_MANY_ITEMS)
        val items = ArrayList<SharedQueueItem>(rawItems.size)
        val seen = HashSet<String>()
        for (element in rawItems) {
            val item = element as? JsonObject ?: return Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
            val parsed =
                when (val r = parseSnapshotItem(item)) {
                    is SnapshotItemResult.Bad -> return r.rejected
                    is SnapshotItemResult.Ok -> r.item
                }
            if (!seen.add(parsed.queueItemId)) return Result.Rejected(QueueMessageRejection.DUPLICATE_QUEUE_ITEM_ID)
            items.add(parsed)
        }
        // `current_index` is required but nullable: absent is a malformed frame, explicit null is the
        // representable "nothing selected" state, and anything else must be an in-range integer.
        val currentIndexEntry = payload[FIELD_CURRENT_INDEX] ?: return Result.Rejected(QueueMessageRejection.MISSING_FIELD)
        val currentIndex =
            if (currentIndexEntry is JsonNull) {
                null
            } else {
                val value = phase5IntField(payload, FIELD_CURRENT_INDEX) ?: return Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
                if (value < 0 || value >= items.size) return Result.Rejected(QueueMessageRejection.INDEX_OUT_OF_RANGE)
                value
            }
        return Result.Parsed(QueueMessage.Snapshot(queueRevision, items, currentIndex))
    }

    private sealed class SnapshotItemResult {
        data class Ok(
            val item: SharedQueueItem,
        ) : SnapshotItemResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : SnapshotItemResult()
    }

    @Suppress("ReturnCount")
    private fun parseSnapshotItem(item: JsonObject): SnapshotItemResult {
        val queueItemId =
            phase5StringField(item, FIELD_QUEUE_ITEM_ID) ?: return SnapshotItemResult.Bad(missingOrWrongType(item, FIELD_QUEUE_ITEM_ID))
        if (!phase5IsUlid(queueItemId)) return SnapshotItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_QUEUE_ITEM_ID))
        val hashRaw = phase5StringField(item, FIELD_TRACK_HASH) ?: return SnapshotItemResult.Bad(missingOrWrongType(item, FIELD_TRACK_HASH))
        val trackHash =
            ContentHash.parse(hashRaw) ?: return SnapshotItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_CONTENT_HASH))
        val addedByRaw = phase5StringField(item, FIELD_ADDED_BY) ?: return SnapshotItemResult.Bad(missingOrWrongType(item, FIELD_ADDED_BY))
        val addedBy =
            phase5ParsePeerId(addedByRaw) ?: return SnapshotItemResult.Bad(Result.Rejected(QueueMessageRejection.MALFORMED_PEER_ID))
        val order = phase5LongField(item, FIELD_ORDER) ?: return SnapshotItemResult.Bad(missingOrWrongType(item, FIELD_ORDER))
        if (order < 0 || order > PlaybackBounds.MAX_WIRE_INT) {
            return SnapshotItemResult.Bad(Result.Rejected(QueueMessageRejection.ORDER_OUT_OF_RANGE))
        }
        return SnapshotItemResult.Ok(SharedQueueItem(queueItemId, trackHash, addedBy, order))
    }

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(QueueMessageRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(QueueMessageRejection.MISSING_FIELD)
        }
}
