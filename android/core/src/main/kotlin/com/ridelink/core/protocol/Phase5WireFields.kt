package com.ridelink.core.protocol

import com.ridelink.core.model.PeerId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Non-throwing readers for peer-chosen JSON, shared by [PlaybackCodec] and [QueueCodec] — the
// Phase 5 twin of the per-object `stringField`/`longField` helpers `TransferCodec`,
// `ManifestCodec`, `AudioStateCodec` and `VoiceSignalCodec` each keep privately.
//
// Shared here (rather than duplicated once per codec) precisely because the two Phase 5 codecs
// validate the **same** header fields — `command_seq` and `queue_revision` appear in both families
// — and a bound enforced by one reader and not its copy is exactly the divergence
// `protocol/vectors/` exists to catch. The `phase5` prefix keeps them distinguishable from the four
// private sets above at every call site.

/** Crockford base32, 26 characters — PROTOCOL's ULID shape for `queue_item_id`. */
private val PHASE5_ULID_FORMAT = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
private val PHASE5_PEER_ID_FORMAT = Regex("^[0-9a-f]{16}$")

internal fun phase5IsUlid(value: String): Boolean = PHASE5_ULID_FORMAT.matches(value)

/**
 * Non-throwing [PeerId] construction for a value that arrives off the wire — [PeerId]'s own
 * `require` is right for our values, where a malformed one is a bug, but a peer chooses what it
 * sends and must not be able to kill a read loop with one bad frame.
 */
internal fun phase5ParsePeerId(value: String): PeerId? = if (PHASE5_PEER_ID_FORMAT.matches(value)) PeerId(value) else null

internal fun phase5StringField(
    payload: JsonObject,
    key: String,
): String? = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun phase5LongField(
    payload: JsonObject,
    key: String,
): Long? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun phase5IntField(
    payload: JsonObject,
    key: String,
): Int? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun phase5DoubleField(
    payload: JsonObject,
    key: String,
): Double? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

internal fun phase5BooleanField(
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

/**
 * Distinguishes "absent" from "explicitly `null`" from "present", which `PLAYBACK_STATE`'s two
 * nullable identity fields need — the same three-way distinction `protocol/vectors/audio-state/`
 * already pins for `AUDIO_STATE`'s nullable fields.
 *
 * A present-but-wrong-typed value reports [Missing] so the caller's own `missingOrWrongType` can
 * see the key is there and answer `WRONG_FIELD_TYPE`.
 */
internal sealed class Phase5NullableString {
    object Missing : Phase5NullableString()

    object ExplicitNull : Phase5NullableString()

    data class Present(
        val value: String,
    ) : Phase5NullableString()
}

@Suppress("ReturnCount") // one early-out per JSON shape the three-way result distinguishes
internal fun phase5NullableStringField(
    payload: JsonObject,
    key: String,
): Phase5NullableString {
    val entry = payload[key] ?: return Phase5NullableString.Missing
    if (entry is JsonNull) return Phase5NullableString.ExplicitNull
    val primitive = entry as? JsonPrimitive ?: return Phase5NullableString.Missing
    return if (primitive.isString) Phase5NullableString.Present(primitive.content) else Phase5NullableString.Missing
}
