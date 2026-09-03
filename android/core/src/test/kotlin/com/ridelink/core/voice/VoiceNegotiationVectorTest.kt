package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/voice-fsm/voice_fsm_vectors.json` against [VoiceNegotiation].
 *
 * The mirror is `RideLinkCoreTests.VoiceNegotiationVectorTests`, running the **same file**. What the
 * table encodes is exactly what would otherwise be discovered on a ride: which side offers, that two
 * simultaneous Start Voice presses produce one negotiation, that a stale callback cannot touch the
 * next session, and that a link blip does not close a microphone Android would not let us reopen.
 */
class VoiceNegotiationVectorTest {
    private val doc = Vectors.load("voice-fsm/voice_fsm_vectors.json").jsonObject

    @Test
    fun `every row of the shared negotiation table holds`() {
        var checked = 0
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val before = state(row["state"]!!.jsonObject)
            val outcome = VoiceNegotiation.reduce(before, input(row["input"]!!.jsonObject))
            val expect = row["expect"]!!.jsonObject

            assertEquals(
                expect["actions"]!!.jsonArray.map { actionLabel(it.jsonObject) },
                outcome.actions.map(::actionLabel),
                "vector $name actions",
            )
            assertEquals(state(expect["state"]!!.jsonObject), outcome.state, "vector $name resulting state")
            checked += 1
        }
        assertTrue(checked >= EXPECTED_MINIMUM_ROWS, "expected at least $EXPECTED_MINIMUM_ROWS rows, ran $checked")
    }

    /**
     * The §7.2 generation guard, as a property over the whole file rather than row by row: whenever
     * an input names a generation this side does not hold, the **only** permitted action is recording
     * the drop. Anything else would be a path by which a stale frame or callback reaches the media
     * stack.
     */
    @Test
    fun `an input naming a foreign generation can only ever be dropped`() {
        var covered = 0
        for (element in doc["rows"]!!.jsonArray) {
            val row = element.jsonObject
            val before = state(row["state"]!!.jsonObject)
            val inputSpec = row["input"]!!.jsonObject
            val inputId =
                inputSpec.nullableString("voice_session_id")
                    ?: (inputSpec["signal"] as? JsonObject)?.nullableString("voice_session_id")
            val held = before.voiceSessionId?.value
            if (inputId == null || held == null || inputId == held) continue

            val outcome = VoiceNegotiation.reduce(before, input(inputSpec))
            val only = outcome.actions.singleOrNull()
            assertTrue(
                only is VoiceAction.RecordDroppedSignal && only.reason in GENERATION_GUARD_REASONS,
                "row ${row.string("name")} must drop a foreign generation and do nothing else, got ${outcome.actions}",
            )
            assertEquals(before, outcome.state, "row ${row.string("name")} must not change state")
            covered += 1
        }
        assertTrue(covered > 0, "the file must contain generation-mismatch rows for this to mean anything")
    }

    /**
     * ARCHITECTURE §6.3/§6.4 as a property: a control-plane blip may drop the media transport but
     * must never release the capture device, because on Android there is no second legal opportunity
     * to open a microphone once the screen is locked.
     */
    @Test
    fun `no control link loss ever releases local audio`() {
        for (role in VoiceRole.entries) {
            for (status in VoiceStatus.entries) {
                val before =
                    VoiceNegotiationState(
                        role = role,
                        status = status,
                        voiceSessionId = if (status == VoiceStatus.IDLE) null else VoiceSessionId(VSID_A),
                        localAudioOpen = true,
                        remoteDescriptionApplied = status == VoiceStatus.ACTIVE,
                    )
                val outcome = VoiceNegotiation.reduce(before, VoiceInput.ControlLinkLost)
                assertTrue(
                    outcome.actions.none { it is VoiceAction.ReleaseLocalAudio },
                    "$role/$status released capture on a link loss",
                )
                assertTrue(
                    outcome.actions.none { it is VoiceAction.SendVoiceState },
                    "$role/$status tried to send on a link that is gone",
                )
                assertTrue(outcome.state.localAudioOpen, "$role/$status forgot the user's consent")
            }
        }
    }

    /** PROTOCOL §7.3, exhaustively: an answerer never authors an offer, from any status. */
    @Test
    fun `an answerer never offers, from any status`() {
        for (status in VoiceStatus.entries) {
            val before =
                VoiceNegotiationState(
                    role = VoiceRole.ANSWERER,
                    status = status,
                    voiceSessionId = if (status == VoiceStatus.IDLE) null else VoiceSessionId(VSID_A),
                    localAudioOpen = true,
                )
            val inputs =
                listOf(
                    VoiceInput.StartRequested(VoiceSessionId(VSID_FRESH)),
                    VoiceInput.SignalReceived(
                        VoiceSignal.State(null, VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS),
                        VoiceSessionId(VSID_FRESH),
                    ),
                    VoiceInput.SignalReceived(
                        VoiceSignal.State(VoiceSessionId(VSID_A), VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS),
                        VoiceSessionId(VSID_FRESH),
                    ),
                )
            for (input in inputs) {
                val outcome = VoiceNegotiation.reduce(before, input)
                assertTrue(
                    outcome.actions.none { it is VoiceAction.CreateOffer || it is VoiceAction.SendOffer },
                    "answerer in $status offered on $input",
                )
            }
        }
    }

    /**
     * PROTOCOL §7.3 glare, as a property rather than one row: whichever order the two presses and the
     * peer's intent arrive in, exactly **one** `CreateOffer` is produced and it names one generation.
     */
    @Test
    fun `simultaneous start on both sides produces exactly one offer`() {
        val fresh = VoiceSessionId(VSID_FRESH)
        val peerIntent =
            VoiceInput.SignalReceived(
                VoiceSignal.State(null, VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS),
                fresh,
            )
        val orders =
            listOf(
                listOf(VoiceInput.StartRequested(fresh), peerIntent),
                listOf(peerIntent, VoiceInput.StartRequested(fresh)),
                listOf(peerIntent, peerIntent, VoiceInput.StartRequested(fresh), peerIntent),
            )
        for (order in orders) {
            var state = VoiceNegotiationState(role = VoiceRole.OFFERER, localAudioOpen = true)
            val offers = mutableListOf<VoiceSessionId>()
            for (input in order) {
                val outcome = VoiceNegotiation.reduce(state, input)
                state = outcome.state
                outcome.actions.filterIsInstance<VoiceAction.CreateOffer>().forEach { offers += it.voiceSessionId }
            }
            assertEquals(1, offers.size, "order $order produced ${offers.size} offers")
            assertEquals(state.voiceSessionId, offers.single(), "the offer must name the live generation")
        }
    }

    // --- vector decoding ----------------------------------------------------------------------

    private fun state(spec: JsonObject): VoiceNegotiationState =
        VoiceNegotiationState(
            role = VoiceRole.valueOf(spec.string("role")),
            status = VoiceStatus.valueOf(spec.string("status")),
            voiceSessionId = spec.nullableString("voice_session_id")?.let { VoiceSessionId(it) },
            localAudioOpen = spec.bool("local_audio_open"),
            remoteDescriptionApplied = spec.bool("remote_description_applied"),
            peerVoiceEnabled = spec.bool("peer_voice_enabled"),
            peerReportedState = VoiceWireState.valueOf(spec.string("peer_reported_state")),
            heldRemoteOffer =
                (spec["held_remote_offer"] as? JsonObject)?.let {
                    HeldRemoteOffer(VoiceSessionId(it.string("voice_session_id")), it.string("sdp"))
                },
            micMuted = spec.bool("mic_muted"),
            mode = VoiceMode.valueOf(spec.string("mode")),
        )

    private fun input(spec: JsonObject): VoiceInput =
        when (val kind = spec.string("kind")) {
            "StartRequested" -> VoiceInput.StartRequested(VoiceSessionId(spec.string("fresh_voice_session_id")))
            "StopRequested" -> VoiceInput.StopRequested
            "ControlLinkLost" -> VoiceInput.ControlLinkLost
            "MuteRequested" -> VoiceInput.MuteRequested(spec.bool("muted"))
            "ModeSelected" -> VoiceInput.ModeSelected(VoiceMode.valueOf(spec.string("mode")))
            "SignalReceived" ->
                VoiceInput.SignalReceived(
                    signal(spec["signal"]!!.jsonObject),
                    VoiceSessionId(spec.string("fresh_voice_session_id")),
                )
            "LocalOfferCreated" ->
                VoiceInput.LocalOfferCreated(VoiceSessionId(spec.string("voice_session_id")), spec.string("sdp"))
            "LocalAnswerCreated" ->
                VoiceInput.LocalAnswerCreated(VoiceSessionId(spec.string("voice_session_id")), spec.string("sdp"))
            "LocalCandidateGathered" ->
                VoiceInput.LocalCandidateGathered(
                    VoiceSessionId(spec.string("voice_session_id")),
                    spec.string("candidate"),
                    spec.nullableString("sdp_mid"),
                    spec.int("sdp_mline_index"),
                )
            "RemoteTrackChanged" ->
                VoiceInput.RemoteTrackChanged(VoiceSessionId(spec.string("voice_session_id")), spec.bool("present"))
            "MediaConnectivityChanged" ->
                VoiceInput.MediaConnectivityChanged(
                    VoiceSessionId(spec.string("voice_session_id")),
                    connected = spec.bool("connected"),
                    failed = spec.bool("failed"),
                )
            else -> error("unknown input kind in vectors: $kind")
        }

    private fun signal(spec: JsonObject): VoiceSignal =
        when (val kind = spec.string("kind")) {
            "Offer" -> VoiceSignal.Offer(VoiceSessionId(spec.string("voice_session_id")), spec.string("sdp"))
            "Answer" -> VoiceSignal.Answer(VoiceSessionId(spec.string("voice_session_id")), spec.string("sdp"))
            "IceCandidate" ->
                VoiceSignal.IceCandidate(
                    VoiceSessionId(spec.string("voice_session_id")),
                    spec.string("candidate"),
                    spec.nullableString("sdp_mid"),
                    spec.int("sdp_mline_index"),
                )
            "State" ->
                VoiceSignal.State(
                    spec.nullableString("voice_session_id")?.let { VoiceSessionId(it) },
                    VoiceWireState.valueOf(spec.string("state")),
                    spec.bool("mic_muted"),
                    VoiceMode.valueOf(spec.string("mode")),
                )
            else -> error("unknown signal kind in vectors: $kind")
        }

    /**
     * Compares actions as a canonical label rather than by constructing an expected object per kind.
     * A label keeps the failure message readable — `SendVoiceState(voice:5e2a9c…, connecting, …)`
     * says what went wrong; a structural diff of two sealed-class instances does not.
     */
    private fun actionLabel(spec: JsonObject): String =
        when (val kind = spec.string("kind")) {
            "StartLocalAudio", "DrainQueuedCandidates", "StopMediaTransport",
            "ReleaseLocalAudio", "SurfacePeerVoiceRequest",
            -> kind
            "CreateOffer", "CreateAnswer" -> "$kind(${spec.string("voice_session_id")})"
            "ApplyRemoteOffer", "ApplyRemoteAnswer", "SendOffer", "SendAnswer" ->
                "$kind(${spec.string("voice_session_id")},${spec.string("sdp")})"
            "SendVoiceState" ->
                "SendVoiceState(${spec.nullableString("voice_session_id")}," +
                    "${spec.string("state")},${spec.bool("mic_muted")},${spec.string("mode")})"
            "ApplyRemoteCandidate", "QueueRemoteCandidate", "SendCandidate" ->
                "$kind(${spec.string("voice_session_id")},${spec.string("candidate")}," +
                    "${spec.nullableString("sdp_mid")},${spec.int("sdp_mline_index")})"
            "SetMicrophoneMuted" -> "SetMicrophoneMuted(${spec.bool("muted")})"
            "RecordDroppedSignal" -> "RecordDroppedSignal(${spec.string("reason")})"
            else -> error("unknown action kind in vectors: $kind")
        }

    private fun actionLabel(action: VoiceAction): String =
        when (action) {
            VoiceAction.StartLocalAudio -> "StartLocalAudio"
            VoiceAction.DrainQueuedCandidates -> "DrainQueuedCandidates"
            VoiceAction.StopMediaTransport -> "StopMediaTransport"
            VoiceAction.ReleaseLocalAudio -> "ReleaseLocalAudio"
            VoiceAction.SurfacePeerVoiceRequest -> "SurfacePeerVoiceRequest"
            is VoiceAction.CreateOffer -> "CreateOffer(${action.voiceSessionId.value})"
            is VoiceAction.CreateAnswer -> "CreateAnswer(${action.voiceSessionId.value})"
            is VoiceAction.ApplyRemoteOffer -> "ApplyRemoteOffer(${action.voiceSessionId.value},${action.sdp})"
            is VoiceAction.ApplyRemoteAnswer -> "ApplyRemoteAnswer(${action.voiceSessionId.value},${action.sdp})"
            is VoiceAction.SendOffer -> "SendOffer(${action.voiceSessionId.value},${action.sdp})"
            is VoiceAction.SendAnswer -> "SendAnswer(${action.voiceSessionId.value},${action.sdp})"
            is VoiceAction.SendVoiceState ->
                "SendVoiceState(${action.voiceSessionId?.value}," +
                    "${action.state.wire},${action.micMuted},${action.mode.name})"
            is VoiceAction.ApplyRemoteCandidate ->
                "ApplyRemoteCandidate(${action.voiceSessionId.value},${action.candidate}," +
                    "${action.sdpMid},${action.sdpMlineIndex})"
            is VoiceAction.QueueRemoteCandidate ->
                "QueueRemoteCandidate(${action.voiceSessionId.value},${action.candidate}," +
                    "${action.sdpMid},${action.sdpMlineIndex})"
            is VoiceAction.SendCandidate ->
                "SendCandidate(${action.voiceSessionId.value},${action.candidate}," +
                    "${action.sdpMid},${action.sdpMlineIndex})"
            is VoiceAction.SetMicrophoneMuted -> "SetMicrophoneMuted(${action.muted})"
            is VoiceAction.RecordDroppedSignal -> "RecordDroppedSignal(${action.reason.name})"
        }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.nullableString(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.booleanOrNull!!

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.intOrNull!!

    private companion object {
        /**
         * Both are the PROTOCOL §7.2 generation guard, and the distinction between them is
         * deliberate rather than incidental: a foreign generation arriving on the **wire** is a peer
         * talking about a negotiation we no longer have, while the same from the **media stack's own
         * callback** is a delegate call from a peer connection we already closed. They are diagnosed
         * separately because they point at different faults.
         */
        val GENERATION_GUARD_REASONS =
            setOf(VoiceSignalDropReason.GENERATION_MISMATCH, VoiceSignalDropReason.STALE_ENGINE_CALLBACK)

        const val EXPECTED_MINIMUM_ROWS = 59
        const val VSID_A = "5e2a9c40b7f13d86e0a4c95b28f7d613"
        const val VSID_FRESH = "ffeeddccbbaa99887766554433221100"
    }
}
