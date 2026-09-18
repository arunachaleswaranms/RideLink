package com.ridelink.core.audiopolicy

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the same Phase 6 coexistence scenarios as `CoexistenceVectorTests` on Apple. */
class CoexistenceVectorTest {
    private val document = Vectors.load("coexistence/coexistence_vectors.json").jsonObject

    @Test
    fun `both platforms implement the shared coexistence table`() {
        var checked = 0
        for (element in document["scenarios"]!!.jsonArray) {
            val scenario = element.jsonObject
            val name = scenario.string("name")
            var state =
                CoexistenceState(
                    baseVolumePermille = scenario.int("base_volume_permille"),
                    targetVolumePermille = scenario.int("base_volume_permille"),
                )
            state =
                IntercomMusicCoexistence
                    .reduce(
                        state,
                        CoexistenceInput.LifetimeStarted(GENERATION, policy(scenario.string("policy_id"))),
                    ).state
            state =
                IntercomMusicCoexistence
                    .reduce(
                        state,
                        CoexistenceInput.MusicChanged(GENERATION, true, TRACK, true, false),
                    ).state

            val actions = mutableListOf<String>()
            for (event in scenario["events"]!!.jsonArray) {
                val outcome = IntercomMusicCoexistence.reduce(state, input(event.jsonObject, state.generation))
                state = outcome.state
                actions += outcome.actions.map(::label)
            }

            assertEquals(scenario.strings("expect_actions"), actions, "vector $name actions")
            assertEquals(scenario.int("expect_target_permille"), state.targetVolumePermille, "vector $name target")
            assertEquals(scenario.int("expect_base_permille"), state.baseVolumePermille, "vector $name base")
            assertEquals(CoexistenceFallback.valueOf(scenario.string("expect_fallback")), state.fallback, "vector $name fallback")
            assertEquals(scenario.int("expect_stale_count"), state.staleInputCount, "vector $name stale count")
            checked += 1
        }
        assertTrue(checked >= EXPECTED_MINIMUM_SCENARIOS, "shared coverage unexpectedly shrank")
        assertEquals(CoexistenceAction.RAMP_DURATION_MS, document.long("ramp_duration_ms"))
    }

    private fun input(
        spec: JsonObject,
        currentGeneration: Long,
    ): CoexistenceInput {
        val generation = spec.optionalLong("generation") ?: currentGeneration
        return when (val kind = spec.string("kind")) {
            "LifetimeStarted" -> CoexistenceInput.LifetimeStarted(generation, policy(spec.string("policy_id")))
            "LifetimeEnded" -> CoexistenceInput.LifetimeEnded(generation)
            "PolicySelected" -> CoexistenceInput.PolicySelected(generation, policy(spec.string("policy_id")))
            "VoiceChanged" ->
                CoexistenceInput.VoiceChanged(
                    generation,
                    spec.bool("available"),
                    spec.bool("local_speech_active"),
                    spec.bool("peer_speech_active"),
                    spec.bool("speech_activity_available"),
                )
            "MusicChanged" ->
                CoexistenceInput.MusicChanged(
                    generation,
                    spec.bool("available"),
                    spec.optionalString("track_token"),
                    spec.bool("playing"),
                    spec.bool("ended"),
                )
            "UserPlaybackIntent" -> CoexistenceInput.UserPlaybackIntent(generation, spec.bool("playing"))
            "RouteChanged" ->
                CoexistenceInput.RouteChanged(
                    generation,
                    RouteState.valueOf(spec.string("route_state")),
                    spec.bool("interrupted"),
                    spec.bool("transition_timed_out"),
                )
            "SyncAvailabilityChanged" -> CoexistenceInput.SyncAvailabilityChanged(generation, spec.bool("available"))
            else -> error("unknown coexistence vector input $kind")
        }
    }

    private fun policy(raw: String): IntercomPolicy = requireNotNull(IntercomPolicy.byId(IntercomModeId.valueOf(raw)))

    private fun label(action: CoexistenceAction): String =
        when (action) {
            is CoexistenceAction.RampMusicVolume -> "Ramp(${action.targetPermille},${action.durationMs})"
            is CoexistenceAction.PauseMusicForVoice -> "Pause(${action.trackToken})"
            is CoexistenceAction.ResumeMusicAfterVoice -> "Resume(${action.trackToken})"
        }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalString(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int = requireNotNull(getValue(key).jsonPrimitive.intOrNull)

    private fun JsonObject.long(key: String): Long = requireNotNull(getValue(key).jsonPrimitive.longOrNull)

    private fun JsonObject.optionalLong(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject.bool(key: String): Boolean = requireNotNull(getValue(key).jsonPrimitive.booleanOrNull)

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        const val GENERATION = 1L
        const val TRACK = "track-a"
        const val EXPECTED_MINIMUM_SCENARIOS = 23
    }
}
