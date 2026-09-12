package com.ridelink.core.protocol

import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.ProfileCoupling
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/audio-state/audio_state_vectors.json` against [AudioStateCodec],
 * [AudioStatePublisher] and [AudioStateInbox].
 *
 * The mirror is `RideLinkCoreTests.AudioStateVectorTests`, running the **same file**. What it pins is
 * the whole of PROTOCOL §4.4: the exact field set, every bound, ADR-016 Amendment A1's
 * `media_quality` derivation, the monotonic `revision` on the sending side, and the
 * drop-anything-not-greater rule on the receiving side.
 *
 * It also pins ADR-016's **privacy** rule mechanically: no platform audio vocabulary appears
 * anywhere in the file's data, so a future field that leaked `A2DP` or a headset model would fail a
 * laptop unit test rather than reach a peer.
 */
class AudioStateVectorTest {
    private val doc = Vectors.load("audio-state/audio_state_vectors.json").jsonObject

    // --- the field set ------------------------------------------------------------------------

    /**
     * §4.4 has twelve fields and this codec must produce exactly those twelve. The assertion is
     * against the shared file rather than against a Kotlin list, so adding a field on one platform
     * only cannot pass.
     */
    @Test
    fun `the encoded field set is exactly the shared file's, in order`() {
        val expected = doc["field_order"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, AudioStateCodec.FIELDS, "field order")
        val encoded = AudioStateCodec.encode(sampleMessage())
        assertEquals(expected.toSet(), encoded.keys, "encoded key set")
    }

    /**
     * ADR-016's privacy rule, enforced against the vector data itself. `_scanned_keys` names the data
     * groups; `_comment` and `_invariants` are excluded because they must *name* the forbidden
     * vocabulary in order to forbid it, and a check that tripped on its own explanation would be
     * useless.
     */
    @Test
    fun `no platform audio vocabulary appears anywhere in the shared file's data`() {
        val forbidden = doc["_forbidden_substrings"]!!.jsonArray.map { it.jsonPrimitive.content }
        val scannedKeys = doc["_scanned_keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(forbidden.isNotEmpty() && scannedKeys.isNotEmpty(), "the file must declare what to scan")
        val scanned =
            Json
                .encodeToString(
                    JsonObject.serializer(),
                    JsonObject(scannedKeys.associateWith { key -> doc[key]!! }),
                ).lowercase()
        for (needle in forbidden) {
            assertFalse(scanned.contains(needle), "forbidden platform vocabulary '$needle' reached the vectors")
        }
    }

    /** The bounds both platforms enforce are the file's, not each platform's own opinion. */
    @Test
    fun `the vector file's bounds match this platform's constants`() {
        val bounds = doc["bounds"]!!.jsonObject
        assertEquals(bounds.long("MAX_SAMPLE_RATE_HZ"), AudioStateCodec.MAX_SAMPLE_RATE_HZ)
        assertEquals(bounds.long("MAX_REVISION"), AudioStateCodec.MAX_REVISION)
        assertEquals(bounds.long("MAX_SUPERSEDED_EPOCHS"), AudioStateInbox.MAX_SUPERSEDED_EPOCHS.toLong())
    }

    /** Every value in §4.3.1's closed vocabularies must round-trip through this platform's enums. */
    @Test
    fun `every vocabulary value in the shared file round-trips`() {
        val vocabulary = doc["vocabulary"]!!.jsonObject
        for (value in vocabulary["endpoint_class"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(value, EndpointClass.parse(value).wire, "endpoint_class $value")
        }
        for (value in vocabulary["profile"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(value, AudioProfile.parse(value).wire, "profile $value")
        }
        for (value in vocabulary["route_state"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(value, RouteState.parse(value).wire, "route_state $value")
        }
        for (value in vocabulary["intercom_mode"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(value, IntercomMode.parse(value).wire, "intercom_mode $value")
        }
        for (value in vocabulary["confidence"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(value, AudioConfidence.parse(value).wire, "confidence $value")
        }
    }

    // --- encode -------------------------------------------------------------------------------

    @Test
    fun `every encode row of the shared file holds`() {
        var checked = 0
        for (element in doc["encode"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val message =
                AudioStateMessage.from(
                    revision = row.long("revision"),
                    revisionEpoch = AudioStateEpoch(row.string("revision_epoch")),
                    snapshot = snapshot(row["snapshot"]!!.jsonObject),
                    intercomMode = IntercomMode.parse(row.string("intercom_mode")),
                )
            val expect = row["expect"]!!.jsonObject
            assertEquals(message(expect["message"]!!.jsonObject), message, "row $name message")
            assertEquals(
                canonical(expect["payload"]!!.jsonObject),
                canonical(AudioStateCodec.encode(message)),
                "row $name payload",
            )
            // A nullable field that is absent must be an explicit JSON null, not a missing key.
            (expect["explicit_nulls"] as? kotlinx.serialization.json.JsonArray)?.forEach { nullable ->
                val key = nullable.jsonPrimitive.content
                val encoded = AudioStateCodec.encode(message)
                assertTrue(encoded.containsKey(key), "row $name must keep $key present")
                assertNull(encoded[key], "row $name must encode $key as an explicit null")
            }
            checked += 1
        }
        assertTrue(checked > 0, "the file must contain encode rows")
    }

    // --- parse --------------------------------------------------------------------------------

    @Test
    fun `every parse row of the shared file holds`() {
        var parsed = 0
        var rejected = 0
        for (element in doc["parse"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val result = AudioStateCodec.parse(row["payload"]!!.jsonObject)
            val expect = row["expect"]!!.jsonObject
            val expectedMessage = expect["parsed"] as? JsonObject
            if (expectedMessage != null) {
                assertTrue(result is AudioStateCodec.Result.Parsed, "row $name expected a parse, got $result")
                assertEquals(message(expectedMessage), result.message, "row $name parsed message")
                parsed += 1
            } else {
                val reason = AudioStateRejection.valueOf(expect.string("rejected"))
                assertTrue(result is AudioStateCodec.Result.Rejected, "row $name expected a rejection, got $result")
                assertEquals(reason, result.reason, "row $name rejection reason")
                rejected += 1
            }
        }
        assertTrue(parsed > 0 && rejected > 0, "the file must contain both parses and rejections")
    }

    /**
     * The parser must be total. §4.4 carries no "end the connection" outcome — a malformed
     * `AUDIO_STATE` is a drop, exactly as for a malformed `VOICE_*` (§7.4) or `PING` (§6) — so it may
     * never throw on any payload a peer can send.
     */
    @Test
    fun `parsing never throws, whatever the payload`() {
        val hostile =
            listOf(
                "{}",
                """{"revision":"nope"}""",
                """{"revision":1.5}""",
                """{"revision":null}""",
                """{"revision":1,"endpoint_class":[],"microphone_open":{}}""",
                """{"revision":1e400}""",
                """{"revision":1,"effective_output_sample_rate_hz":1e400}""",
                """{"revision":-0}""",
            )
        for (raw in hostile) {
            val payload = Json.parseToJsonElement(raw).jsonObject
            // The assertion is that this line returns rather than throws.
            AudioStateCodec.parse(payload)
        }
    }

    // --- media_quality ------------------------------------------------------------------------

    @Test
    fun `media quality is derived exactly as the shared file says`() {
        var checked = 0
        for (element in doc["media_quality"]!!.jsonArray) {
            val row = element.jsonObject
            val profile = AudioProfile.parse(row.string("effective_output_profile"))
            val expected = MediaQuality.parse(row.string("expect"))
            assertEquals(
                expected,
                AudioRouteSnapshot(effectiveOutputProfile = profile).mediaQuality,
                "row ${row.string("name")}",
            )
            checked += 1
        }
        assertEquals(AudioProfile.entries.size, checked, "every profile value must be covered")
    }

    // --- publisher ----------------------------------------------------------------------------

    @Test
    fun `every publisher row of the shared file holds`() {
        for (element in doc["publisher"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val publisher = AudioStatePublisher(AudioStateEpoch(VECTOR_EPOCH_A))
            var lastRevision = 0L
            for ((index, stepElement) in row["steps"]!!.jsonArray.withIndex()) {
                val step = stepElement.jsonObject
                // The one step that may move the epoch is the one that restarts the counter
                // (ADR-021 Amendment A7). The file's `_invariants` say so; this is that, executed.
                val resetTo = (step["reset_to_epoch"] as? JsonPrimitive)?.content
                if (resetTo != null) {
                    publisher.resetForNewSession(AudioStateEpoch(resetTo))
                    assertEquals(0L, publisher.currentRevision, "$name step $index must restart the counter")
                    assertNull(publisher.published, "$name step $index must forget what was published")
                    lastRevision = 0L
                    continue
                }
                val snap = snapshot(step["snapshot"]!!.jsonObject)
                val mode = IntercomMode.parse(step.string("intercom_mode"))
                val produced =
                    if (step.bool("force")) publisher.forceNext(snap, mode) else publisher.next(snap, mode)
                val expected = step.nullableLong("expect_revision")
                if (expected == null) {
                    assertNull(produced, "$name step $index expected no publish")
                    assertEquals(lastRevision, publisher.currentRevision, "$name step $index moved the revision")
                } else {
                    assertEquals(expected, produced?.revision, "$name step $index revision")
                    assertTrue(expected > lastRevision, "$name step $index revision must strictly increase")
                    lastRevision = expected
                }
                val expectedEpoch = (step["expect_epoch"] as? JsonPrimitive)?.content
                if (expectedEpoch != null) {
                    assertEquals(AudioStateEpoch(expectedEpoch), produced?.revisionEpoch, "$name step $index epoch")
                }
                // Every message a publisher emits names the lifetime it is currently in — there is
                // no path that stamps anything else.
                if (produced != null) {
                    assertEquals(publisher.currentEpoch, produced.revisionEpoch, "$name step $index stamped epoch")
                }
            }
        }
    }

    /** A new control session restarts the numbering: §4.4's revision is per sender per session. */
    @Test
    fun `resetForNewSession restarts the revision and names the new lifetime`() {
        val publisher = AudioStatePublisher(AudioStateEpoch(VECTOR_EPOCH_A))
        val snap = AudioRouteSnapshot(endpointClass = EndpointClass.BLUETOOTH)
        val first = publisher.next(snap, IntercomMode.PTT)
        assertEquals(1L, first?.revision)
        assertEquals(AudioStateEpoch(VECTOR_EPOCH_A), first?.revisionEpoch)
        publisher.resetForNewSession(AudioStateEpoch(VECTOR_EPOCH_B))
        assertNull(publisher.published, "a reset forgets what was published")
        val second = publisher.next(snap, IntercomMode.PTT)
        assertEquals(1L, second?.revision, "and numbering starts again")
        assertEquals(
            AudioStateEpoch(VECTOR_EPOCH_B),
            second?.revisionEpoch,
            "under a lifetime the receiver can tell apart — the whole of ADR-021 Amendment A7",
        )
    }

    // --- inbox --------------------------------------------------------------------------------

    @Test
    fun `every inbox row of the shared file holds`() {
        for (element in doc["inbox"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val inbox = AudioStateInbox()
            val offers =
                row["offer"]!!.jsonArray.map { offer ->
                    val pair = offer.jsonArray
                    pair[0].jsonPrimitive.longOrNull!! to AudioStateEpoch(pair[1].jsonPrimitive.content)
                }
            val expected = row["expect_accepted"]!!.jsonArray.map { it.jsonPrimitive.booleanOrNull!! }
            assertEquals(offers.size, expected.size, "$name malformed row")
            for ((index, offer) in offers.withIndex()) {
                val (revision, epoch) = offer
                assertEquals(
                    expected[index],
                    inbox.accept(sampleMessage(revision, epoch)),
                    "$name offer $index (revision $revision epoch $epoch)",
                )
            }
            val current = row["expect_current"]!!.jsonArray
            assertEquals(current[0].jsonPrimitive.longOrNull, inbox.current?.revision, "$name final revision")
            assertEquals(
                AudioStateEpoch(current[1].jsonPrimitive.content),
                inbox.current?.revisionEpoch,
                "$name final lifetime — a revision alone would pass for the wrong one",
            )
            // Every refusal is one or the other, never both and never uncounted: a stale revision
            // within the held lifetime, or a straggler from a lifetime already replaced.
            val retiredEpoch = row.nullableLong("expect_dropped_retired_epoch")?.toInt()
            val stale = row.nullableLong("expect_dropped_stale")?.toInt()
            if (retiredEpoch != null) assertEquals(retiredEpoch, inbox.droppedRetiredEpoch, "$name retired-epoch count")
            if (stale != null) assertEquals(stale, inbox.droppedStale, "$name stale count")
            assertEquals(
                expected.count { !it },
                inbox.droppedStale + inbox.droppedRetiredEpoch,
                "$name every refusal must be counted exactly once",
            )
        }
    }

    // --- decoding ----------------------------------------------------------------------------

    private fun sampleMessage(
        revision: Long = 1,
        epoch: AudioStateEpoch = AudioStateEpoch(VECTOR_EPOCH_A),
    ): AudioStateMessage =
        AudioStateMessage(
            revision = revision,
            revisionEpoch = epoch,
            endpointClass = EndpointClass.BLUETOOTH,
            microphoneOpen = true,
            effectiveOutputProfile = AudioProfile.DUPLEX_WIDEBAND,
            effectiveInputProfile = AudioProfile.DUPLEX_WIDEBAND,
            effectiveOutputSampleRateHz = 16_000,
            effectiveInputSampleRateHz = 16_000,
            mediaQuality = MediaQuality.REDUCED,
            routeState = RouteState.STABLE,
            intercomMode = IntercomMode.PTT,
            confidence = AudioConfidence.ASSUMED,
        )

    private fun snapshot(spec: JsonObject): AudioRouteSnapshot =
        AudioRouteSnapshot(
            endpointClass = EndpointClass.parse(spec.string("endpoint_class")),
            microphoneOpen = spec.bool("microphone_open"),
            effectiveOutputProfile = AudioProfile.parse(spec.string("effective_output_profile")),
            effectiveInputProfile = AudioProfile.parse(spec.string("effective_input_profile")),
            effectiveOutputSampleRateHz = spec.nullableInt("effective_output_sample_rate_hz"),
            effectiveInputSampleRateHz = spec.nullableInt("effective_input_sample_rate_hz"),
            routeState = RouteState.parse(spec.string("route_state")),
            profileCoupling = ProfileCoupling.parse(spec.string("profile_coupling")),
            confidence = AudioConfidence.parse(spec.string("confidence")),
            interrupted = spec.bool("interrupted"),
            lastChangeReason = AudioRouteChangeReason.valueOf(spec.string("last_change_reason")),
            lastTransitionDurationUs = spec.nullableLong("last_transition_duration_us"),
        )

    private fun message(spec: JsonObject): AudioStateMessage =
        AudioStateMessage(
            revision = spec.long("revision"),
            revisionEpoch = AudioStateEpoch(spec.string("revision_epoch")),
            endpointClass = EndpointClass.parse(spec.string("endpoint_class")),
            microphoneOpen = spec.bool("microphone_open"),
            effectiveOutputProfile = AudioProfile.parse(spec.string("effective_output_profile")),
            effectiveInputProfile = AudioProfile.parse(spec.string("effective_input_profile")),
            effectiveOutputSampleRateHz = spec.nullableInt("effective_output_sample_rate_hz"),
            effectiveInputSampleRateHz = spec.nullableInt("effective_input_sample_rate_hz"),
            mediaQuality = MediaQuality.parse(spec.string("media_quality")),
            routeState = RouteState.parse(spec.string("route_state")),
            intercomMode = IntercomMode.parse(spec.string("intercom_mode")),
            confidence = AudioConfidence.parse(spec.string("confidence")),
        )

    /**
     * Both sides of an encode comparison reduced to `key -> string` so a JSON number and a Kotlin
     * [Long] compare by value rather than by representation. The alternative — comparing
     * `JsonElement`s — would fail on `16000` versus `16000.0` for reasons that have nothing to do
     * with the protocol.
     */
    private fun canonical(payload: JsonObject): Map<String, String?> =
        payload.mapValues { (_, value) -> if (value is JsonNull) null else value.jsonPrimitive.content }

    private fun canonical(encoded: Map<String, Any?>): Map<String, String?> = encoded.mapValues { (_, value) -> value?.toString() }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.booleanOrNull!!

    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.longOrNull!!

    private fun JsonObject.nullableInt(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

    private fun JsonObject.nullableLong(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull

    private companion object {
        /**
         * The same fabricated values `tools/generate_audio_state_vectors.py` uses. Readable runs of
         * one hex digit, deliberately not CSPRNG output, so nothing here could be mistaken for a
         * captured value (CLAUDE.md: vectors carry fabricated test values only).
         */
        const val VECTOR_EPOCH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val VECTOR_EPOCH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
