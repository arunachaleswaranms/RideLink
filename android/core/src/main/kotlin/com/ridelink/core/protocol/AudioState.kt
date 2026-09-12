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
 * PROTOCOL §4.4 — the identity of one sender's `revision` **namespace**.
 *
 * 16 CSPRNG bytes as 32 lowercase hex characters, minted when a sender's [AudioStatePublisher]
 * begins a lifetime and constant for the whole of it. §4.4's `revision` is "per sender per session",
 * and this is the only thing on the wire that says *which* session — so a `revision` is comparable
 * only against another one carrying the same epoch.
 *
 * **Why a new value and not an existing one** (ADR-021 Amendment A7 §2). `peer_id` is durable across
 * a process restart; `session_id` is minted per *handshake*, so it changes on an ordinary reconnect
 * the publisher deliberately survives; `conn_tiebreak` lives for the `ControlSessionManager`
 * instance and is not reset when a discovery session is; and the receiver's own
 * `authenticationGeneration` answers a question about a *connection*, not about the peer's counter.
 * Reusing one random value for two jobs is the mistake [com.ridelink.core.model.ConnTiebreak]'s own
 * documentation warns about, so this is a distinct type as well as a distinct value.
 *
 * Never persisted, never derived from `peer_id`, `session_id` or the identity key. Redacted to 6 hex
 * in logs, exactly as `conn_tiebreak` and `voice_session_id` are.
 */
@JvmInline
value class AudioStateEpoch(
    val value: String,
) {
    init {
        require(HEX32.matches(value)) { "AudioStateEpoch must be 32 lowercase hex characters" }
    }

    override fun toString(): String = "epoch:${value.take(EPOCH_REDACTED_PREFIX_LEN)}\u2026"

    companion object {
        private val HEX32 = Regex("^[0-9a-f]{32}$")

        /**
         * Non-throwing constructor for a value that arrives **off the wire**. Uppercase hex is
         * rejected rather than normalised — one canonical form, as for `voice_session_id`.
         */
        fun parse(value: String): AudioStateEpoch? = if (HEX32.matches(value)) AudioStateEpoch(value) else null
    }
}

private const val EPOCH_REDACTED_PREFIX_LEN = 6

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
    /**
     * Strictly increasing per sender per session (§4.4). A receiver drops a lower or equal value —
     * but **only against a [revisionEpoch] that matches**, because a number from one sender lifetime
     * orders nothing against a number from another.
     */
    val revision: Long,
    /**
     * Which of the sender's `revision` namespaces [revision] belongs to (§4.4, ADR-021 Amendment A7).
     * Constant for one sender lifetime; a value the receiver has not seen means the sender's counter
     * restarted and the old floor no longer applies to it.
     */
    val revisionEpoch: AudioStateEpoch,
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
            revisionEpoch: AudioStateEpoch,
            snapshot: AudioRouteSnapshot,
            intercomMode: IntercomMode,
        ): AudioStateMessage =
            AudioStateMessage(
                revision = revision,
                revisionEpoch = revisionEpoch,
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

    /** `revision_epoch` was present and a string, but not 32 lowercase hex (§4.4, [AudioStateEpoch]). */
    MALFORMED_REVISION_EPOCH,
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
    const val FIELD_REVISION_EPOCH = "revision_epoch"
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
            FIELD_REVISION_EPOCH,
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
            FIELD_REVISION_EPOCH to message.revisionEpoch.value,
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

        val epochText = stringField(payload, FIELD_REVISION_EPOCH) ?: return missingOrWrongType(payload, FIELD_REVISION_EPOCH)
        val revisionEpoch =
            AudioStateEpoch.parse(epochText) ?: return Result.Rejected(AudioStateRejection.MALFORMED_REVISION_EPOCH)

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
                revisionEpoch = revisionEpoch,
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
 *
 * **[epoch] is what makes "within a session" checkable by the receiver** (ADR-021 Amendment A7).
 * The counter restarts only through [resetForNewSession], which takes a *fresh* epoch, so every
 * message this publisher has ever produced under one epoch is ordered against every other — and a
 * message from a previous lifetime is recognisably from a previous lifetime rather than merely
 * numerically small. Supplying the epoch rather than minting one keeps this type pure (CLAUDE.md
 * rule 9): the CSPRNG lives in each platform's `AudioStateEpochGenerator`.
 */
class AudioStatePublisher(
    private var epoch: AudioStateEpoch,
    private var revision: Long = 0,
) {
    private var last: AudioStateMessage? = null

    /** The last message [next] or [forceNext] actually produced, or null before the first one. */
    val published: AudioStateMessage? get() = last

    val currentRevision: Long get() = revision

    /** The lifetime every message this publisher produces is currently stamped with. */
    val currentEpoch: AudioStateEpoch get() = epoch

    /**
     * @return the message to send, or null when this state is identical to the last published one
     *   apart from its revision — in which case nothing is sent and the revision does not move.
     */
    fun next(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode,
    ): AudioStateMessage? {
        val candidate = AudioStateMessage.from(revision + 1, epoch, snapshot, intercomMode)
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
        return AudioStateMessage.from(revision, epoch, snapshot, intercomMode).also { last = it }
    }

    /**
     * Begins a new sender lifetime: the counter restarts at 0 and every message from here on names
     * [epoch] instead of the old one.
     *
     * A new **discovery** session, not a new connection — §4.4's `revision` is per sender per
     * session and is deliberately *not* reset by a duplicate-connection resolution, a control
     * reconnect or a voice rebuild. The epoch moves with the counter and only with it, which is the
     * whole of the contract: two messages are comparable exactly when their epochs match.
     *
     * @param epoch a value that has never been used before — see each platform's
     *   `AudioStateEpochGenerator`. Reusing one would tell a receiver that a restarted counter was a
     *   continuation of the old one.
     */
    fun resetForNewSession(epoch: AudioStateEpoch) {
        this.epoch = epoch
        revision = 0
        last = null
    }
}

/**
 * Owns the receiver's side of PROTOCOL §4.4's revision rule — **and of which sender lifetime that
 * rule is being applied within** (ADR-021 Amendment A7).
 *
 * "Receiver drops a lower revision" is implemented as "drops anything not strictly greater", which
 * also drops an exact retransmit. Pure and mirrored, so a reordering bug fails a laptop test rather
 * than showing up as a peer's route apparently going backwards on a ride.
 *
 * **A revision floor belongs to exactly one [AudioStateEpoch].** This object deliberately outlives a
 * control-session boundary — §4.4's `revision` keeps climbing across a reconnect, and keeping the
 * floor is what makes a delayed frame from *before* that reconnect still refusable. But the floor
 * says nothing at all about a sender whose counter restarted, and before this amendment it was
 * applied to one anyway: a peer that restarted its process came back at `revision` 1 and had every
 * genuine message dropped until it climbed past the dead lifetime's number.
 *
 * So:
 *
 * - **same epoch** — §4.4's rule, unchanged: strictly greater, or dropped as stale.
 * - **an epoch never seen** — a new sender lifetime. Accepted, and the epoch it replaces is recorded
 *   as superseded.
 * - **a superseded epoch** — a straggler from a lifetime that has already been replaced. Refused and
 *   counted, so solving the first case cannot resurrect a stale route through the second.
 *
 * That last rule is *defence in depth*, not the only defence: a new sender lifetime can only begin
 * after that sender has torn its control session down, so a straggler from the old one is also
 * refused one layer earlier by ADR-025's generation gate. The two answer different questions —
 * "which connection authorised this frame" and "which of the sender's counters is this number
 * from" — and this is deliberately the second one only.
 */
class AudioStateInbox {
    var current: AudioStateMessage? = null
        private set

    var droppedStale: Int = 0
        private set

    /**
     * How many frames were refused because they named a sender lifetime that has already been
     * replaced. Counted rather than merely dropped: "it never happened" and "it happened and was
     * refused" are different facts on a diagnostics screen.
     */
    var droppedRetiredEpoch: Int = 0
        private set

    /**
     * Epochs this inbox has held and moved on from, oldest first.
     *
     * Bounded because its contents come from a peer: an unbounded set would let a sender that
     * rotated its epoch grow it without limit. [MAX_SUPERSEDED_EPOCHS] is far above what a ride can
     * produce — a new epoch costs the sender a full control teardown, re-handshake and re-authentication
     * — and the honest cost of the bound is that a straggler from a lifetime old enough to have been
     * evicted is no longer refused *here*. ADR-025's generation gate still refuses it.
     */
    private val superseded = ArrayDeque<AudioStateEpoch>()

    /**
     * @return true if [message] was accepted and [current] now holds it.
     *
     * One early-out per §4.4 receiving rule, in the order the rules are stated — which is why this
     * carries the same `ReturnCount` suppression [AudioStateCodec.parse] does. Extracting them would
     * split one decision table across two functions, which is exactly what a rule set must not be.
     */
    @Suppress("ReturnCount")
    fun accept(message: AudioStateMessage): Boolean {
        val held = current
        if (held == null) {
            current = message
            return true
        }
        if (message.revisionEpoch == held.revisionEpoch) {
            if (message.revision <= held.revision) {
                droppedStale += 1
                return false
            }
            current = message
            return true
        }
        if (message.revisionEpoch in superseded) {
            droppedRetiredEpoch += 1
            return false
        }
        supersede(held.revisionEpoch)
        current = message
        return true
    }

    private fun supersede(epoch: AudioStateEpoch) {
        superseded.addLast(epoch)
        while (superseded.size > MAX_SUPERSEDED_EPOCHS) superseded.removeFirst()
    }

    /**
     * A new *local* session. Everything held belonged to the old one, superseded epochs included —
     * a lifetime this device is no longer tracking is not a lifetime it can call retired.
     */
    fun reset() {
        current = null
        droppedStale = 0
        droppedRetiredEpoch = 0
        superseded.clear()
    }

    companion object {
        /** See [superseded]. Matches ADR-024 Amendment A6's loss-ledger bound, for the same reason. */
        const val MAX_SUPERSEDED_EPOCHS = 8
    }
}
