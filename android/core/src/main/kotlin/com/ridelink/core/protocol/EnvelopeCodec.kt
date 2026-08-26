package com.ridelink.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** PROTOCOL §1. This is a defensive limit and does not move (CLAUDE.md rule 11). */
object FrameLimits {
    const val MAX_CONTROL_FRAME_BYTES: Int = 262_144
}

object ProtocolVersion {
    const val CURRENT: Int = 1
}

sealed class DecodeResult {
    data class Success(
        val envelope: Envelope,
        val versionOk: Boolean,
    ) : DecodeResult()

    data class Failure(
        val errorCode: String,
    ) : DecodeResult()
}

/**
 * Envelope encode/decode + the two structural checks that must happen before a single JSON
 * field is inspected: frame-size rejection (PROTOCOL §1) and version compatibility (PROTOCOL §2).
 *
 * Deliberately permissive on unknown fields/types (PROTOCOL §2 rules 1-2) and deliberately
 * strict on type mismatches — [Json.coerceInputValues] stays false so a wrong-typed field fails
 * closed rather than silently coercing into something plausible.
 */
object EnvelopeCodec {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = false
            coerceInputValues = false
        }

    @Suppress("ReturnCount")
    fun decode(bytes: ByteArray): DecodeResult {
        // The length check happens before any attempt to interpret the bytes as text or JSON —
        // PROTOCOL §1: a length prefix over the cap is rejected without reading the body.
        if (bytes.size > FrameLimits.MAX_CONTROL_FRAME_BYTES) {
            return DecodeResult.Failure("frame_too_large")
        }
        val text =
            runCatching { bytes.toString(Charsets.UTF_8) }.getOrElse {
                return DecodeResult.Failure("malformed_frame")
            }
        return decode(text)
    }

    @Suppress("ReturnCount", "SwallowedException")
    fun decode(text: String): DecodeResult {
        val envelope =
            try {
                json.decodeFromString(Envelope.serializer(), text)
            } catch (malformed: SerializationException) {
                return DecodeResult.Failure("malformed_frame")
            } catch (malformed: IllegalArgumentException) {
                return DecodeResult.Failure("malformed_frame")
            }
        return DecodeResult.Success(envelope, versionOk = envelope.v == ProtocolVersion.CURRENT)
    }

    fun encode(envelope: Envelope): ByteArray = json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)

    fun encodeToString(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)
}
