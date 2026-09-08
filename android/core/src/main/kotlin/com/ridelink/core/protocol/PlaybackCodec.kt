package com.ridelink.core.protocol

import com.ridelink.core.model.ContentHash
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PROTOCOL §3 Playback group (Phase 5). */
object PlaybackMessageTypes {
    const val PLAY = "PLAY"
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val SEEK = "SEEK"
    const val NEXT = "NEXT"
    const val PREVIOUS = "PREVIOUS"
    const val POSITION_REPORT = "POSITION_REPORT"
    const val PLAYBACK_STATE = "PLAYBACK_STATE"

    val ALL = setOf(PLAY, PAUSE, RESUME, SEEK, NEXT, PREVIOUS, POSITION_REPORT, PLAYBACK_STATE)

    /** The subset that carries a [PlaybackCommandHeader] and is therefore ordered by `command_seq`. */
    val COMMANDS = setOf(PLAY, PAUSE, RESUME, SEEK, NEXT, PREVIOUS)
}

/** Why a playback payload was refused. Recorded in diagnostics; never sent to the peer verbatim. */
enum class PlaybackMessageRejection {
    UNKNOWN_TYPE,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    MALFORMED_CONTENT_HASH,
    MALFORMED_QUEUE_ITEM_ID,
    MALFORMED_PEER_ID,
    COMMAND_SEQ_OUT_OF_RANGE,
    REVISION_OUT_OF_RANGE,
    SESSION_TIME_OUT_OF_RANGE,
    POSITION_OUT_OF_RANGE,
    RATE_OUT_OF_RANGE,
}

/**
 * Parses, bounds-checks and encodes PROTOCOL §5's playback messages. Total and non-throwing,
 * mirroring [TransferCodec]/[AudioStateCodec]/[VoiceSignalCodec] — a malformed frame is dropped and
 * the control connection survives, because the framing was intact and only this message's shape was
 * wrong.
 *
 * Mirrored by `RideLinkCore.PlaybackCodec`; both run `protocol/vectors/playback-messages/`.
 */
@Suppress("TooManyFunctions")
object PlaybackCodec {
    sealed class Result {
        data class Parsed(
            val message: PlaybackMessage,
        ) : Result()

        data class Rejected(
            val reason: PlaybackMessageRejection,
        ) : Result()
    }

    const val FIELD_COMMAND_SEQ = "command_seq"
    const val FIELD_EFFECTIVE_AT_SESSION_US = "effective_at_session_us"
    const val FIELD_ISSUED_BY = "issued_by"
    const val FIELD_QUEUE_REVISION = "queue_revision"
    const val FIELD_TRACK_HASH = "track_hash"
    const val FIELD_POSITION_MS = "position_ms"
    const val FIELD_TARGET_POSITION_MS = "target_position_ms"
    const val FIELD_QUEUE_ITEM_ID = "queue_item_id"
    const val FIELD_AT_SESSION_US = "at_session_us"
    const val FIELD_PLAYING = "playing"
    const val FIELD_PLAYBACK_RATE = "playback_rate"

    fun parse(
        type: String,
        payload: JsonObject,
    ): Result =
        when (type) {
            PlaybackMessageTypes.PLAY -> parsePlay(payload)
            PlaybackMessageTypes.PAUSE -> parsePauseOrResume(payload, resume = false)
            PlaybackMessageTypes.RESUME -> parsePauseOrResume(payload, resume = true)
            PlaybackMessageTypes.SEEK -> parseSeek(payload)
            PlaybackMessageTypes.NEXT -> parseStep(payload, next = true)
            PlaybackMessageTypes.PREVIOUS -> parseStep(payload, next = false)
            PlaybackMessageTypes.POSITION_REPORT -> parsePositionReport(payload)
            PlaybackMessageTypes.PLAYBACK_STATE -> parsePlaybackState(payload)
            else -> Result.Rejected(PlaybackMessageRejection.UNKNOWN_TYPE)
        }

    fun wireType(message: PlaybackMessage): String =
        when (message) {
            is PlaybackMessage.Play -> PlaybackMessageTypes.PLAY
            is PlaybackMessage.Pause -> PlaybackMessageTypes.PAUSE
            is PlaybackMessage.Resume -> PlaybackMessageTypes.RESUME
            is PlaybackMessage.Seek -> PlaybackMessageTypes.SEEK
            is PlaybackMessage.Next -> PlaybackMessageTypes.NEXT
            is PlaybackMessage.Previous -> PlaybackMessageTypes.PREVIOUS
            is PlaybackMessage.PositionReport -> PlaybackMessageTypes.POSITION_REPORT
            is PlaybackMessage.PlaybackStateSnapshot -> PlaybackMessageTypes.PLAYBACK_STATE
        }

    /** The outbound side of [parse] — the shape lives here, once, shared by both directions. */
    fun encode(message: PlaybackMessage): JsonObject =
        when (message) {
            is PlaybackMessage.Play ->
                buildJsonObject {
                    putHeader(message.header)
                    put(FIELD_TRACK_HASH, message.trackHash.value)
                    put(FIELD_POSITION_MS, message.positionMs)
                    put(FIELD_QUEUE_ITEM_ID, message.queueItemId)
                }
            is PlaybackMessage.Pause ->
                buildJsonObject {
                    putHeader(message.header)
                    put(FIELD_POSITION_MS, message.positionMs)
                }
            is PlaybackMessage.Resume ->
                buildJsonObject {
                    putHeader(message.header)
                    put(FIELD_POSITION_MS, message.positionMs)
                }
            is PlaybackMessage.Seek ->
                buildJsonObject {
                    putHeader(message.header)
                    put(FIELD_TARGET_POSITION_MS, message.targetPositionMs)
                }
            is PlaybackMessage.Next -> buildJsonObject { putHeader(message.header) }
            is PlaybackMessage.Previous -> buildJsonObject { putHeader(message.header) }
            is PlaybackMessage.PositionReport ->
                buildJsonObject {
                    put(FIELD_TRACK_HASH, message.trackHash.value)
                    put(FIELD_POSITION_MS, message.positionMs)
                    put(FIELD_AT_SESSION_US, message.atSessionUs)
                    put(FIELD_PLAYING, message.playing)
                    put(FIELD_PLAYBACK_RATE, message.playbackRate)
                }
            is PlaybackMessage.PlaybackStateSnapshot ->
                buildJsonObject {
                    put(FIELD_COMMAND_SEQ, message.commandSeq)
                    put(FIELD_QUEUE_REVISION, message.queueRevision)
                    put(FIELD_TRACK_HASH, message.trackHash?.value)
                    put(FIELD_QUEUE_ITEM_ID, message.queueItemId)
                    put(FIELD_POSITION_MS, message.positionMs)
                    put(FIELD_PLAYING, message.playing)
                    put(FIELD_AT_SESSION_US, message.atSessionUs)
                }
        }

    private fun JsonObjectBuilder.putHeader(header: PlaybackCommandHeader) {
        put(FIELD_COMMAND_SEQ, header.commandSeq)
        put(FIELD_EFFECTIVE_AT_SESSION_US, header.effectiveAtSessionUs)
        put(FIELD_ISSUED_BY, header.issuedBy.value)
        put(FIELD_QUEUE_REVISION, header.queueRevision)
    }

    // --- headers -------------------------------------------------------------------------------

    private sealed class HeaderResult {
        data class Ok(
            val header: PlaybackCommandHeader,
        ) : HeaderResult()

        data class Bad(
            val rejected: Result.Rejected,
        ) : HeaderResult()
    }

    @Suppress("ReturnCount")
    private fun parseHeader(payload: JsonObject): HeaderResult {
        val commandSeq =
            phase5LongField(payload, FIELD_COMMAND_SEQ)
                ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_COMMAND_SEQ))
        if (commandSeq < 0 || commandSeq > PlaybackBounds.MAX_WIRE_INT) {
            return HeaderResult.Bad(Result.Rejected(PlaybackMessageRejection.COMMAND_SEQ_OUT_OF_RANGE))
        }
        val effectiveAt =
            phase5LongField(payload, FIELD_EFFECTIVE_AT_SESSION_US)
                ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_EFFECTIVE_AT_SESSION_US))
        if (effectiveAt < 0 || effectiveAt > PlaybackBounds.MAX_WIRE_INT) {
            return HeaderResult.Bad(Result.Rejected(PlaybackMessageRejection.SESSION_TIME_OUT_OF_RANGE))
        }
        val issuedByRaw =
            phase5StringField(payload, FIELD_ISSUED_BY)
                ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_ISSUED_BY))
        val issuedBy =
            phase5ParsePeerId(issuedByRaw) ?: return HeaderResult.Bad(Result.Rejected(PlaybackMessageRejection.MALFORMED_PEER_ID))
        val queueRevision =
            phase5LongField(payload, FIELD_QUEUE_REVISION) ?: return HeaderResult.Bad(missingOrWrongType(payload, FIELD_QUEUE_REVISION))
        if (queueRevision < 0 || queueRevision > PlaybackBounds.MAX_WIRE_INT) {
            return HeaderResult.Bad(Result.Rejected(PlaybackMessageRejection.REVISION_OUT_OF_RANGE))
        }
        return HeaderResult.Ok(PlaybackCommandHeader(commandSeq, effectiveAt, issuedBy, queueRevision))
    }

    // --- per-type parsers ----------------------------------------------------------------------

    @Suppress("ReturnCount")
    private fun parsePlay(payload: JsonObject): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val hashRaw = phase5StringField(payload, FIELD_TRACK_HASH) ?: return missingOrWrongType(payload, FIELD_TRACK_HASH)
        val trackHash = ContentHash.parse(hashRaw) ?: return Result.Rejected(PlaybackMessageRejection.MALFORMED_CONTENT_HASH)
        val positionMs = phase5LongField(payload, FIELD_POSITION_MS) ?: return missingOrWrongType(payload, FIELD_POSITION_MS)
        if (!isValidPosition(positionMs)) return Result.Rejected(PlaybackMessageRejection.POSITION_OUT_OF_RANGE)
        val queueItemId = phase5StringField(payload, FIELD_QUEUE_ITEM_ID) ?: return missingOrWrongType(payload, FIELD_QUEUE_ITEM_ID)
        if (!phase5IsUlid(queueItemId)) return Result.Rejected(PlaybackMessageRejection.MALFORMED_QUEUE_ITEM_ID)
        return Result.Parsed(PlaybackMessage.Play(header, trackHash, positionMs, queueItemId))
    }

    @Suppress("ReturnCount")
    private fun parsePauseOrResume(
        payload: JsonObject,
        resume: Boolean,
    ): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val positionMs = phase5LongField(payload, FIELD_POSITION_MS) ?: return missingOrWrongType(payload, FIELD_POSITION_MS)
        if (!isValidPosition(positionMs)) return Result.Rejected(PlaybackMessageRejection.POSITION_OUT_OF_RANGE)
        return Result.Parsed(
            if (resume) PlaybackMessage.Resume(header, positionMs) else PlaybackMessage.Pause(header, positionMs),
        )
    }

    @Suppress("ReturnCount")
    private fun parseSeek(payload: JsonObject): Result {
        val header =
            when (val h = parseHeader(payload)) {
                is HeaderResult.Bad -> return h.rejected
                is HeaderResult.Ok -> h.header
            }
        val target = phase5LongField(payload, FIELD_TARGET_POSITION_MS) ?: return missingOrWrongType(payload, FIELD_TARGET_POSITION_MS)
        if (!isValidPosition(target)) return Result.Rejected(PlaybackMessageRejection.POSITION_OUT_OF_RANGE)
        return Result.Parsed(PlaybackMessage.Seek(header, target))
    }

    private fun parseStep(
        payload: JsonObject,
        next: Boolean,
    ): Result =
        when (val h = parseHeader(payload)) {
            is HeaderResult.Bad -> h.rejected
            is HeaderResult.Ok ->
                Result.Parsed(if (next) PlaybackMessage.Next(h.header) else PlaybackMessage.Previous(h.header))
        }

    @Suppress("ReturnCount")
    private fun parsePositionReport(payload: JsonObject): Result {
        val hashRaw = phase5StringField(payload, FIELD_TRACK_HASH) ?: return missingOrWrongType(payload, FIELD_TRACK_HASH)
        val trackHash = ContentHash.parse(hashRaw) ?: return Result.Rejected(PlaybackMessageRejection.MALFORMED_CONTENT_HASH)
        val positionMs = phase5LongField(payload, FIELD_POSITION_MS) ?: return missingOrWrongType(payload, FIELD_POSITION_MS)
        if (!isValidPosition(positionMs)) return Result.Rejected(PlaybackMessageRejection.POSITION_OUT_OF_RANGE)
        val atSessionUs = phase5LongField(payload, FIELD_AT_SESSION_US) ?: return missingOrWrongType(payload, FIELD_AT_SESSION_US)
        if (atSessionUs < 0 || atSessionUs > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(PlaybackMessageRejection.SESSION_TIME_OUT_OF_RANGE)
        }
        val playing = phase5BooleanField(payload, FIELD_PLAYING) ?: return missingOrWrongType(payload, FIELD_PLAYING)
        val rate = phase5DoubleField(payload, FIELD_PLAYBACK_RATE) ?: return missingOrWrongType(payload, FIELD_PLAYBACK_RATE)
        if (rate < PlaybackBounds.MIN_PLAYBACK_RATE || rate > PlaybackBounds.MAX_PLAYBACK_RATE) {
            return Result.Rejected(PlaybackMessageRejection.RATE_OUT_OF_RANGE)
        }
        return Result.Parsed(PlaybackMessage.PositionReport(trackHash, positionMs, atSessionUs, playing, rate))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parsePlaybackState(payload: JsonObject): Result {
        val commandSeq = phase5LongField(payload, FIELD_COMMAND_SEQ) ?: return missingOrWrongType(payload, FIELD_COMMAND_SEQ)
        if (commandSeq < 0 || commandSeq > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(PlaybackMessageRejection.COMMAND_SEQ_OUT_OF_RANGE)
        }
        val queueRevision = phase5LongField(payload, FIELD_QUEUE_REVISION) ?: return missingOrWrongType(payload, FIELD_QUEUE_REVISION)
        if (queueRevision < 0 || queueRevision > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(PlaybackMessageRejection.REVISION_OUT_OF_RANGE)
        }
        val trackHash =
            when (val raw = phase5NullableStringField(payload, FIELD_TRACK_HASH)) {
                Phase5NullableString.Missing -> return missingOrWrongType(payload, FIELD_TRACK_HASH)
                Phase5NullableString.ExplicitNull -> null
                is Phase5NullableString.Present ->
                    ContentHash.parse(raw.value) ?: return Result.Rejected(PlaybackMessageRejection.MALFORMED_CONTENT_HASH)
            }
        val queueItemId =
            when (val raw = phase5NullableStringField(payload, FIELD_QUEUE_ITEM_ID)) {
                Phase5NullableString.Missing -> return missingOrWrongType(payload, FIELD_QUEUE_ITEM_ID)
                Phase5NullableString.ExplicitNull -> null
                is Phase5NullableString.Present ->
                    if (phase5IsUlid(raw.value)) raw.value else return Result.Rejected(PlaybackMessageRejection.MALFORMED_QUEUE_ITEM_ID)
            }
        val positionMs = phase5LongField(payload, FIELD_POSITION_MS) ?: return missingOrWrongType(payload, FIELD_POSITION_MS)
        if (!isValidPosition(positionMs)) return Result.Rejected(PlaybackMessageRejection.POSITION_OUT_OF_RANGE)
        val playing = phase5BooleanField(payload, FIELD_PLAYING) ?: return missingOrWrongType(payload, FIELD_PLAYING)
        val atSessionUs = phase5LongField(payload, FIELD_AT_SESSION_US) ?: return missingOrWrongType(payload, FIELD_AT_SESSION_US)
        if (atSessionUs < 0 || atSessionUs > PlaybackBounds.MAX_WIRE_INT) {
            return Result.Rejected(PlaybackMessageRejection.SESSION_TIME_OUT_OF_RANGE)
        }
        return Result.Parsed(
            PlaybackMessage.PlaybackStateSnapshot(commandSeq, queueRevision, trackHash, queueItemId, positionMs, playing, atSessionUs),
        )
    }

    private fun isValidPosition(positionMs: Long): Boolean = positionMs >= 0 && positionMs <= PlaybackBounds.MAX_POSITION_MS

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(PlaybackMessageRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(PlaybackMessageRejection.MISSING_FIELD)
        }
}
