package com.ridelink.core.protocol

import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.RouteState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** PROTOCOL §3: `AUDIO_STATE` is a Session-group message, present from Phase 1's message catalogue. */
object AudioStateMessageTypes {
    const val AUDIO_STATE = "AUDIO_STATE"
}

/**
 * PROTOCOL §4.4 — the **effective duplex state right now**, as a value.
 *
 * This is the wire projection of [AudioRouteSnapshot] and is deliberately narrower than it:
 * `interrupted`, `lastChangeReason` and `lastTransitionDurationUs` are diagnostics that ADR-016's
 * §4.4 field table does not carry, and [AudioStateCodec] has an explicit field list so they cannot
 * leak onto the wire by accident. `AudioStateCodecTest` asserts the encoded key set is exactly
 * §4.4's.
 *
 * **No platform vocabulary reaches this type.** Every enum here is ADR-016's shared vocabulary, and
 * the only place a platform profile name is translated into it is each platform's single route
 * mapper (PROTOCOL §4.3.1).
 */
data class AudioStateMessage(
    /** Strictly increasing per sender per session (§4.4). A receiver drops a lower or equal value. */
    val revision: Long,
    val endpointClass: EndpointClass,
    /**
     * Whether the capture *device* is open — **not** whether speech is being transmitted. PTT, VOX
     * and mute gate transmission, not the device (ARCHITECTURE §6.3); `VOICE_STATE.mic_muted` is the
     * field that reports transmission.
     */
    val microphoneOpen: Boolean,
    val effectiveOutputProfile: AudioProfile,
    val effectiveInputProfile: AudioProfile,
    val effectiveOutputSampleRateHz: Int?,
    val effectiveInputSampleRateHz: Int?,
    /** Derived from [effectiveOutputProfile] by ADR-016 Amendment A1, never measured from the audio. */
    val mediaQuality: MediaQuality,
    val routeState: RouteState,
    val intercomMode: IntercomMode,
    val confidence: AudioConfidence,
) {
    companion object {
        /**
         * Builds the wire projection of a route snapshot. [mediaQuality] is taken from the snapshot's
         * own derivation so the two cannot disagree about what the user is told, on either platform.
         */
        fun from(
            revision: Long,
            snapshot: AudioRouteSnapshot,
            intercomMode: IntercomMode,
        ): AudioStateMessage =
            AudioStateMessage(
                revision = revision,
                endpointClass = snapshot.endpointClass,
                microphoneOpen = snapshot.microphoneOpen,
                effectiveOutputProfile = snapshot.effectiveOutputProfile,
                effectiveInputProfile = snapshot.effectiveInputProfile,
                effectiveOutputSampleRateHz = snapshot.effectiveOutputSampleRateHz,
                effectiveInputSampleRateHz = snapshot.effectiveInputSampleRateHz,
                mediaQuality = snapshot.mediaQuality,
                routeState = snapshot.routeState,
                intercomMode = intercomMode,
                confidence = snapshot.confidence,
            )
    }
}

/** Why an `AUDIO_STATE` payload was refused. Recorded in diagnostics; never sent back verbatim. */
enum class AudioStateRejection {
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    REVISION_OUT_OF_RANGE,
    SAMPLE_RATE_OUT_OF_RANGE,
}

/**
 * Parses, bounds-checks and encodes `AUDIO_STATE` (PROTOCOL §4.4).
 *
 * **Total and non-throwing**, exactly like [VoiceSignalCodec]: every peer-controlled field is read
 * through an accessor that returns null rather than throwing, so a malformed frame is dropped and
 * the control read loop survives — the rule the §2e hardening pass established for `PING`/`PONG`.
 *
 * Unrecognised enum values are tolerated as `unknown` rather than making the frame malformed, per
 * §4.3.1's forward-compatibility rule for audio vocabulary. A *structural* problem — a missing key, a
 * wrong JSON type, a negative revision — is a rejection.
 */
object AudioStateCodec {
    sealed class Result {
        data class Parsed(
            val message: AudioStateMessage,
        ) : Result()

        data class Rejected(
            val reason: AudioStateRejection,
        ) : Result()
    }

    const val FIELD_REVISION = "revision"
    const val FIELD_ENDPOINT_CLASS = "endpoint_class"
    const val FIELD_MICROPHONE_OPEN = "microphone_open"
    const val FIELD_EFFECTIVE_OUTPUT_PROFILE = "effective_output_profile"
    const val FIELD_EFFECTIVE_INPUT_PROFILE = "effective_input_profile"
    const val FIELD_EFFECTIVE_OUTPUT_SAMPLE_RATE_HZ = "effective_output_sample_rate_hz"
    const val FIELD_EFFECTIVE_INPUT_SAMPLE_RATE_HZ = "effective_input_sample_rate_hz"
    const val FIELD_MEDIA_QUALITY = "media_quality"
    const val FIELD_ROUTE_STATE = "route_state"
    const val FIELD_INTERCOM_MODE = "intercom_mode"
    const val FIELD_CONFIDENCE = "confidence"

    /** 768 kHz is beyond any audio endpoint that exists; this bounds the field without guessing. */
    const val MAX_SAMPLE_RATE_HZ = 768_000L

    /**
     * PROTOCOL §4.4 types `revision` as a uint64, but a JSON number is a double in the iOS decoder
     * (`RideLinkCore.JSONValue`), so anything above 2^53 - 1 cannot round-trip identically on both
     * platforms. Bounding it here rather than discovering it on a ride is the same reasoning
     * `MAX_VOICE_MLINE_INDEX` follows: a bound both platforms enforce beats a range only one of them
     * can represent. A revision counts observable audio-state changes in one session, so 2^53 is
     * roughly 285 million years of one change per microsecond — the bound costs nothing real.
     */
    const val MAX_REVISION = 9_007_199_254_740_991L

    /**
     * The complete PROTOCOL §4.4 field list, in spec order. Both the encoder and the "no platform
     * vocabulary on the wire" test read this, so an added field cannot escape either.
     */
    val FIELDS =
        listOf(
            FIELD_REVISION,
            FIELD_ENDPOINT_CLASS,
            FIELD_MICROPHONE_OPEN,
            FIELD_EFFECTIVE_OUTPUT_PROFILE,
            FIELD_EFFECTIVE_INPUT_PROFILE,
            FIELD_EFFECTIVE_OUTPUT_SAMPLE_RATE_HZ,
            FIELD_EFFECTIVE_INPUT_SAMPLE_RATE_HZ,
            FIELD_MEDIA_QUALITY,
            FIELD_ROUTE_STATE,
            FIELD_INTERCOM_MODE,
            FIELD_CONFIDENCE,
        )

    /**
     * The wire form as plain values, so the one place that builds the JSON object is the platform's
     * envelope builder and this stays free of any serialisation library choice.
     *
     * A null sample rate is an explicit JSON null rather than an absent key (§4.4: "int, or `null` if
     * unknown") — the same distinction `VOICE_ICE.sdp_mid` draws, and for the same reason: a null is
     * the sender saying "not known", not the sender having forgotten the field.
     */
    fun encode(message: AudioStateMessage): Map<String, Any?> =
        mapOf(
            FIELD_REVISION to message.revision,
            FIELD_ENDPOINT_CLASS to message.endpointClass.wire,
            FIELD_MICROPHONE_OPEN to message.microphoneOpen,
            FIELD_EFFECTIVE_OUTPUT_PROFILE to message.effectiveOutputProfile.wire,
            FIELD_EFFECTIVE_INPUT_PROFILE to message.effectiveInputProfile.wire,
            FIELD_EFFECTIVE_OUTPUT_SAMPLE_RATE_HZ to message.effectiveOutputSampleRateHz,
            FIELD_EFFECTIVE_INPUT_SAMPLE_RATE_HZ to message.effectiveInputSampleRateHz,
            FIELD_MEDIA_QUALITY to message.mediaQuality.wire,
            FIELD_ROUTE_STATE to message.routeState.wire,
            FIELD_INTERCOM_MODE to message.intercomMode.wire,
            FIELD_CONFIDENCE to message.confidence.wire,
        )

    @Suppress("ReturnCount") // one early-out per PROTOCOL §4.4 field rule, in spec order
    fun parse(payload: JsonObject): Result {
        val revision = longField(payload, FIELD_REVISION) ?: return missingOrWrongType(payload, FIELD_REVISION)
        if (revision < 0 || revision > MAX_REVISION) return Result.Rejected(AudioStateRejection.REVISION_OUT_OF_RANGE)

        val endpointClass = stringField(payload, FIELD_ENDPOINT_CLASS) ?: return missingOrWrongType(payload, FIELD_ENDPOINT_CLASS)
        val microphoneOpen = booleanField(payload, FIELD_MICROPHONE_OPEN) ?: return missingOrWrongType(payload, FIELD_MICROPHONE_OPEN)
        val outputProfile =
            stringField(payload, FIELD_EFFECTIVE_OUTPUT_PROFILE)
                ?: return missingOrWrongType(payload, FIELD_EFFECTIVE_OUTPUT_PROFILE)
        val inputProfile =
            stringField(payload, FIELD_EFFECTIVE_INPUT_PROFILE)
                ?: return missingOrWrongType(payload, FIELD_EFFECTIVE_INPUT_PROFILE)

        val outputRate = nullableRate(payload, FIELD_EFFECTIVE_OUTPUT_SAMPLE_RATE_HZ)
        if (outputRate is RateResult.Rejected) return Result.Rejected(outputRate.reason)
        val inputRate = nullableRate(payload, FIELD_EFFECTIVE_INPUT_SAMPLE_RATE_HZ)
        if (inputRate is RateResult.Rejected) return Result.Rejected(inputRate.reason)

        val mediaQuality = stringField(payload, FIELD_MEDIA_QUALITY) ?: return missingOrWrongType(payload, FIELD_MEDIA_QUALITY)
        val routeState = stringField(payload, FIELD_ROUTE_STATE) ?: return missingOrWrongType(payload, FIELD_ROUTE_STATE)
        val intercomMode = stringField(payload, FIELD_INTERCOM_MODE) ?: return missingOrWrongType(payload, FIELD_INTERCOM_MODE)
        val confidence = stringField(payload, FIELD_CONFIDENCE) ?: return missingOrWrongType(payload, FIELD_CONFIDENCE)

        return Result.Parsed(
            AudioStateMessage(
                revision = revision,
                endpointClass = EndpointClass.parse(endpointClass),
                microphoneOpen = microphoneOpen,
                effectiveOutputProfile = AudioProfile.parse(outputProfile),
                effectiveInputProfile = AudioProfile.parse(inputProfile),
                effectiveOutputSampleRateHz = (outputRate as RateResult.Accepted).value,
                effectiveInputSampleRateHz = (inputRate as RateResult.Accepted).value,
                mediaQuality = MediaQuality.parse(mediaQuality),
                routeState = RouteState.parse(routeState),
                intercomMode = IntercomMode.parse(intercomMode),
                confidence = AudioConfidence.parse(confidence),
            ),
        )
    }

    private sealed class RateResult {
        data class Accepted(
            val value: Int?,
        ) : RateResult()

        data class Rejected(
            val reason: AudioStateRejection,
        ) : RateResult()
    }

    /**
     * A sample rate is nullable, so a missing key and an explicit JSON null both mean "unknown". A
     * present-but-implausible value is rejected rather than carried: a negative or absurd rate on a
     * diagnostics screen is worse than no rate at all, and [MAX_SAMPLE_RATE_HZ] is far above any real
     * audio endpoint while still bounding what a peer can put in an int field.
     */
    @Suppress("ReturnCount") // one early-out per PROTOCOL §4.4 nullable-field rule, in spec order
    private fun nullableRate(
        payload: JsonObject,
        key: String,
    ): RateResult {
        val entry = payload[key]
        if (entry == null || entry is JsonNull) return RateResult.Accepted(null)
        val primitive = entry as? JsonPrimitive ?: return RateResult.Rejected(AudioStateRejection.WRONG_FIELD_TYPE)
        if (primitive.isString) return RateResult.Rejected(AudioStateRejection.WRONG_FIELD_TYPE)
        val value = primitive.longOrNull ?: return RateResult.Rejected(AudioStateRejection.WRONG_FIELD_TYPE)
        if (value < 0 || value > MAX_SAMPLE_RATE_HZ) return RateResult.Rejected(AudioStateRejection.SAMPLE_RATE_OUT_OF_RANGE)
        return RateResult.Accepted(value.toInt())
    }

    private fun missingOrWrongType(
        payload: JsonObject,
        key: String,
    ): Result.Rejected =
        if (payload.containsKey(key)) {
            Result.Rejected(AudioStateRejection.WRONG_FIELD_TYPE)
        } else {
            Result.Rejected(AudioStateRejection.MISSING_FIELD)
        }

    private fun stringField(
        payload: JsonObject,
        key: String,
    ): String? = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** PROTOCOL fields are typed, not stringly-typed: a quoted number is a wrong type, not an int. */
    private fun longField(
        payload: JsonObject,
        key: String,
    ): Long? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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

/**
 * Owns the sender's side of PROTOCOL §4.4: the monotonic `revision`, and the decision that there is
 * anything new to say.
 *
 * Pure and mirrored. [next] returns null when nothing observable changed, which is what stops a
 * chatty route layer from spending the control plane on identical frames — and, more importantly,
 * what makes `revision` mean "the state changed" rather than "a callback fired".
 *
 * `revision` is **strictly increasing and never reset within a session**, including across a route
 * transition and across a voice rebuild. A receiver drops anything not greater than what it holds
 * ([AudioStateInbox]), so reordering cannot resurrect a stale route.
 */
class AudioStatePublisher(
    private var revision: Long = 0,
) {
    private var last: AudioStateMessage? = null

    /** The last message [next] or [forceNext] actually produced, or null before the first one. */
    val published: AudioStateMessage? get() = last

    val currentRevision: Long get() = revision

    /**
     * @return the message to send, or null when this state is identical to the last published one
     *   apart from its revision — in which case nothing is sent and the revision does not move.
     */
    fun next(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode,
    ): AudioStateMessage? {
        val candidate = AudioStateMessage.from(revision + 1, snapshot, intercomMode)
        val previous = last
        if (previous != null && previous.copy(revision = candidate.revision) == candidate) return null
        revision = candidate.revision
        last = candidate
        return candidate
    }

    /**
     * Publishes unconditionally, for the two moments PROTOCOL §4.4 names explicitly regardless of
     * whether anything changed: reaching `CONNECTED`, and ride start. A peer that has just connected
     * has never seen any of our state, so "nothing changed" is not a reason to stay silent.
     */
    fun forceNext(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode,
    ): AudioStateMessage {
        revision += 1
        return AudioStateMessage.from(revision, snapshot, intercomMode).also { last = it }
    }

    /** A new control **session**, not a new connection: §4.4's revision is per sender per session. */
    fun resetForNewSession() {
        revision = 0
        last = null
    }
}

/**
 * Owns the receiver's side of PROTOCOL §4.4's revision rule.
 *
 * "Receiver drops a lower revision" is implemented as "drops anything not strictly greater", which
 * also drops an exact retransmit. Pure and mirrored, so a reordering bug fails a laptop test rather
 * than showing up as a peer's route apparently going backwards on a ride.
 */
class AudioStateInbox {
    var current: AudioStateMessage? = null
        private set

    var droppedStale: Int = 0
        private set

    /** @return true if [message] was accepted and [current] now holds it. */
    fun accept(message: AudioStateMessage): Boolean {
        val held = current
        if (held != null && message.revision <= held.revision) {
            droppedStale += 1
            return false
        }
        current = message
        return true
    }

    fun reset() {
        current = null
        droppedStale = 0
    }
}
