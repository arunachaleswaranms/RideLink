package com.ridelink.core.audiopolicy

import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/intercom/intercom_vectors.json` against [IntercomTransmission].
 *
 * The mirror is `RideLinkCoreTests.IntercomVectorTests`, running the **same file**. What the table
 * encodes is exactly what would otherwise be discovered on a ride: that PTT gates transmission and
 * never the hardware, that mute and an interruption both win over an open gate, that a policy switch
 * cannot inherit a button nobody is holding, and that full duplex remains the no-gate case.
 */
class IntercomVectorTest {
    private val doc = Vectors.load("intercom/intercom_vectors.json").jsonObject

    @Test
    fun `every row of the shared intercom table holds`() {
        var checked = 0
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val before = state(row["state"]!!.jsonObject)
            val outcome = IntercomTransmission.reduce(before, input(row["input"]!!.jsonObject))
            val expect = row["expect"]!!.jsonObject

            assertEquals(state(expect["state"]!!.jsonObject), outcome.state, "vector $name resulting state")
            assertEquals(
                expect["actions"]!!.jsonArray.map { actionLabel(it.jsonObject) },
                outcome.actions.map(::actionLabel),
                "vector $name actions",
            )
            assertEquals(expect.bool("transmitting"), outcome.state.transmitting, "vector $name transmitting")
            checked += 1
        }
        assertTrue(checked >= EXPECTED_MINIMUM_ROWS, "expected at least $EXPECTED_MINIMUM_ROWS rows, ran $checked")
    }

    /**
     * The five REQUIREMENTS §8 modes, field for field, against the file — including the two wire
     * vocabularies, which differ by one value on purpose (PROTOCOL §4.4 versus §7.4, ADR-021 §3).
     */
    @Test
    fun `the five mode presets match the shared file exactly`() {
        var checked = 0
        for (element in doc["presets"]!!.jsonArray) {
            val spec = element.jsonObject
            val id = IntercomModeId.valueOf(spec.string("id"))
            val policy = requireNotNull(IntercomPolicy.byId(id)) { "no policy for $id" }

            assertEquals(spec.bool("mic_always_open"), policy.micAlwaysOpen, "$id mic_always_open")
            assertEquals(gate(spec["gate"]!!.jsonObject), policy.gate, "$id gate")
            assertEquals(onSpeech(spec["on_speech"]!!.jsonObject), policy.onSpeech, "$id on_speech")
            assertEquals(
                MusicQualityPriority.valueOf(spec.string("music_quality_priority")),
                policy.musicQualityPriority,
                "$id music_quality_priority",
            )
            assertEquals(VoiceMode.valueOf(spec.string("voice_wire_mode")), policy.voiceWireMode, "$id voice_wire_mode")
            assertEquals(
                IntercomMode.valueOf(spec.string("intercom_wire_mode")),
                policy.intercomWireMode,
                "$id intercom_wire_mode",
            )
            assertEquals(spec.bool("full_duplex"), policy.fullDuplex, "$id full_duplex")
            assertEquals(spec.bool("intercom_enabled"), policy.intercomEnabled, "$id intercom_enabled")
            checked += 1
        }
        assertEquals(IntercomPolicy.ALL.size, checked, "every preset must appear in the shared file")
    }

    /**
     * **The default is an architecture default, not a measurement.** ARCHITECTURE §6.3 and ADR-008 §4
     * name Mode C until `docs/PHASE0_RESULTS.md` is filled in, so this asserts the file and the code
     * agree about which mode that is — and the assertion is what changes when a real measurement
     * exists, exactly as the route mappers' `confidence: assumed` assertions are.
     */
    @Test
    fun `the default policy is the one the shared file names`() {
        assertEquals(
            IntercomModeId.valueOf(doc.string("default_policy_id")),
            IntercomPolicy.DEFAULT.id,
            "the default policy must match the shared file",
        )
        assertEquals(IntercomModeId.MODE_C, IntercomPolicy.DEFAULT.id, "ARCHITECTURE §6.3's documented default")
    }

    /** The VOX starting points are shared too, so a tuning change cannot land on one platform only. */
    @Test
    fun `the vox defaults match the shared file`() {
        val defaults = doc["vox_defaults"]!!.jsonObject
        val gate = IntercomPolicy.MODE_B.gate
        assertTrue(gate is TransmissionGate.Vox, "Mode B must be a VOX gate")
        assertEquals(defaults.double("threshold_dbfs"), gate.thresholdDbfs, "vox threshold")
        assertEquals(defaults.long("hangover_ms"), gate.hangoverMs, "vox hangover")
    }

    /**
     * ARCHITECTURE §6.3's central invariant, asserted as a property of the whole file rather than row
     * by row: **the transmission gate has no capture action to emit.** TEST_PLAN A-10 is the hardware
     * test of the same property; this is the one that can run on a laptop.
     */
    @Test
    fun `no action in the whole file touches the capture device`() {
        val permitted = setOf("SetTransmitting", "AnnounceVoiceMode", "PublishAudioState")
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            for (action in row["expect"]!!.jsonObject["actions"]!!.jsonArray) {
                assertTrue(
                    action.jsonObject.string("kind") in permitted,
                    "row ${row.string("name")} emitted a non-transmission action",
                )
            }
        }
    }

    /** Transmission can never precede an open capture path, from any row (ARCHITECTURE §6.4). */
    @Test
    fun `no row transmits without an open capture path, a mute, or through an interruption`() {
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val after = state(row["expect"]!!.jsonObject["state"]!!.jsonObject)
            if (!after.transmitting) continue
            assertTrue(after.captureOpen, "row ${row.string("name")} transmits with capture closed")
            assertTrue(!after.userMuted, "row ${row.string("name")} transmits while muted")
            assertTrue(!after.interrupted, "row ${row.string("name")} transmits through an interruption")
            assertTrue(
                after.policy.gate != TransmissionGate.Disabled,
                "row ${row.string("name")} transmits in Mode E",
            )
        }
    }

    /** A `SetTransmitting` action is a diff: present iff the value changed, and equal to the new value. */
    @Test
    fun `every SetTransmitting action agrees with the resulting state and is emitted only on change`() {
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val before = state(row["state"]!!.jsonObject)
            val expect = row["expect"]!!.jsonObject
            val after = state(expect["state"]!!.jsonObject)
            val emitted =
                expect["actions"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it.string("kind") == "SetTransmitting" }
            if (before.transmitting == after.transmitting) {
                assertTrue(emitted.isEmpty(), "row ${row.string("name")} restated an unchanged value")
            } else {
                assertEquals(1, emitted.size, "row ${row.string("name")} must emit exactly one SetTransmitting")
                assertEquals(
                    after.transmitting,
                    emitted.single().bool("transmitting"),
                    "row ${row.string("name")} SetTransmitting disagrees with the state",
                )
            }
        }
    }

    /** A policy switch must never inherit a gate that was open under the previous policy. */
    @Test
    fun `no policy switch leaves a held button or an open vox gate`() {
        var covered = 0
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            if (row["input"]!!.jsonObject.string("kind") != "PolicySelected") continue
            val after = state(row["expect"]!!.jsonObject["state"]!!.jsonObject)
            assertTrue(!after.pttHeld, "row ${row.string("name")} inherited a held PTT button")
            assertTrue(!after.voxOpen, "row ${row.string("name")} inherited an open VOX gate")
            assertNull(after.voxHangoverUntilMonoUs, "row ${row.string("name")} inherited a hangover deadline")
            covered += 1
        }
        assertTrue(covered > 0, "the file must contain PolicySelected rows for this to mean anything")
    }

    // --- vector decoding ----------------------------------------------------------------------

    private fun state(spec: JsonObject): TransmissionState =
        TransmissionState(
            policy = requireNotNull(IntercomPolicy.byId(IntercomModeId.valueOf(spec.string("policy_id")))),
            captureOpen = spec.bool("capture_open"),
            userMuted = spec.bool("user_muted"),
            pttHeld = spec.bool("ptt_held"),
            voxOpen = spec.bool("vox_open"),
            voxHangoverUntilMonoUs = spec.nullableLong("vox_hangover_until_mono_us"),
            interrupted = spec.bool("interrupted"),
        )

    private fun input(spec: JsonObject): IntercomInput =
        when (val kind = spec.string("kind")) {
            "PolicySelected" ->
                IntercomInput.PolicySelected(
                    requireNotNull(IntercomPolicy.byId(IntercomModeId.valueOf(spec.string("policy_id")))),
                )
            "UserMuted" -> IntercomInput.UserMuted(spec.bool("muted"))
            "PttHeld" -> IntercomInput.PttHeld(spec.bool("held"))
            "CaptureOpen" -> IntercomInput.CaptureOpen(spec.bool("open"))
            "Interrupted" -> IntercomInput.Interrupted(spec.bool("interrupted"))
            "SpeechLevel" -> IntercomInput.SpeechLevel(spec.double("level_dbfs"), spec.long("at_mono_us"))
            "VoxTick" -> IntercomInput.VoxTick(spec.long("at_mono_us"))
            else -> error("unknown input kind in vectors: $kind")
        }

    private fun gate(spec: JsonObject): TransmissionGate =
        when (val kind = spec.string("kind")) {
            "none" -> TransmissionGate.None
            "vox" -> TransmissionGate.Vox(spec.double("threshold_dbfs"), spec.long("hangover_ms"))
            "ptt" -> TransmissionGate.Ptt
            "disabled" -> TransmissionGate.Disabled
            else -> error("unknown gate kind in vectors: $kind")
        }

    private fun onSpeech(spec: JsonObject): OnSpeech =
        when (val kind = spec.string("kind")) {
            "duck" -> OnSpeech.Duck(spec.int("to_percent"))
            "pause" -> OnSpeech.Pause
            else -> error("unknown on_speech kind in vectors: $kind")
        }

    private fun actionLabel(spec: JsonObject): String =
        when (val kind = spec.string("kind")) {
            "SetTransmitting" -> "SetTransmitting(${spec.bool("transmitting")})"
            "AnnounceVoiceMode" -> "AnnounceVoiceMode(${spec.string("mode")})"
            "PublishAudioState" -> "PublishAudioState"
            else -> error("unknown action kind in vectors: $kind")
        }

    private fun actionLabel(action: IntercomAction): String =
        when (action) {
            is IntercomAction.SetTransmitting -> "SetTransmitting(${action.transmitting})"
            is IntercomAction.AnnounceVoiceMode -> "AnnounceVoiceMode(${action.mode.name})"
            IntercomAction.PublishAudioState -> "PublishAudioState"
        }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.booleanOrNull!!

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.intOrNull!!

    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.longOrNull!!

    private fun JsonObject.double(key: String): Double = this[key]!!.jsonPrimitive.doubleOrNull!!

    private fun JsonObject.nullableLong(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull

    private companion object {
        const val EXPECTED_MINIMUM_ROWS = 58
    }
}
