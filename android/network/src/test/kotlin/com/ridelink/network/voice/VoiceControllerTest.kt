package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.ProfileCoupling
import com.ridelink.core.protocol.VoiceBounds
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.PendingCandidates
import com.ridelink.core.voice.RemoteCandidate
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceRole
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VoiceController] driven entirely by fakes: no WebRTC, no microphone, no network.
 *
 * The negotiation *decisions* are pinned by `protocol/vectors/voice-fsm/` on both platforms
 * ([com.ridelink.core.voice.VoiceNegotiation]). What these tests are about is the half a pure table
 * cannot express — that the effects actually happen, in the right order, and that a torn-down
 * generation's callbacks cannot touch the next one.
 *
 * **None of this is evidence that voice works.** A fake engine proves the controller. Real Opus over
 * real DTLS-SRTP is proven on the Apple side by `VoiceEngineLoopbackTests` (real WebRTC, real media)
 * and on Android not at all yet — `PeerConnectionFactory.initialize` needs an Android `Context`, so
 * `WebRtcVoiceEngine` is **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7).
 */
class VoiceControllerTest {
    // --- start / offer / answer ----------------------------------------------------------------

    @Test
    fun `the leader offers on Start Voice and the follower only states intent`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            assertEquals(listOf("open"), fakes.audio.calls, "the capture path is opened exactly once")
            assertTrue(fakes.engine.calls.contains("start(${GEN_1})"), "the peer connection names the generation")
            // Phase 2b: the intercom gate is the single source of `VOICE_STATE.mic_muted`, so opening the
            // capture path changes that field and sends a second `negotiating` frame saying so. Both
            // frames are truthful — before capture opened this side genuinely was transmitting silence —
            // and PROTOCOL §7.4 sends `VOICE_STATE` on change, so the count is not the invariant. What
            // this test is about is that the **offerer offers and the follower does not**, so it asserts
            // the state values rather than how many frames carried them.
            assertEquals(
                setOf(VoiceWireState.NEGOTIATING),
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .map { it.state }
                    .toSet(),
            )
            assertEquals(
                listOf(true, false),
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .map { it.micMuted },
                "mic_muted goes true (capture not yet open) then false (open, full duplex)",
            )
            assertEquals(VoiceStatus.NEGOTIATING, leader.diagnostics.value.status)
            assertEquals(VoiceRole.OFFERER, leader.diagnostics.value.role)
        }

    @Test
    fun `the follower never creates an offer`() =
        withController(isLocalLeader = false) { follower, fakes ->
            follower.start()
            fakes.awaitEngineCall("open", onAudio = true)
            delay(SETTLE_MS)

            assertFalse(fakes.engine.calls.contains("createOffer"), "PROTOCOL §7.3: only the leader offers")
            assertTrue(fakes.transport.sent.isNotEmpty(), "but it must still state its intent")
            val intent =
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .single()
            assertEquals(VoiceWireState.NEGOTIATING, intent.state)
            assertNull(intent.voiceSessionId, "the answerer holds no generation yet")
        }

    @Test
    fun `an offer created by the engine is sent, and the answer completes the exchange`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            fakes.engine.emit(VoiceEngineEvent.OfferCreated(VoiceSessionId(GEN_1), OFFER_SDP))
            fakes.awaitSent { it is VoiceSignal.Offer }
            assertEquals(
                OFFER_SDP,
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.Offer>()
                    .single()
                    .sdp,
            )

            leader.submit(VoiceSignal.Answer(VoiceSessionId(GEN_1), ANSWER_SDP))
            fakes.awaitEngineCall("applyRemote(ANSWER)")
            assertEquals(VoiceStatus.CONNECTING, leader.diagnostics.value.status)

            fakes.engine.emit(
                VoiceEngineEvent.TransportStateChanged(VoiceSessionId(GEN_1), MediaTransportState.CONNECTED),
            )
            fakes.awaitStatus(leader, VoiceStatus.ACTIVE)
            assertTrue(
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .any { it.state == VoiceWireState.ACTIVE },
                "the peer is told when voice goes active",
            )
        }

    // --- trickle ICE ---------------------------------------------------------------------------

    @Test
    fun `a candidate arriving before the answer is queued and applied on the answer`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(VoiceSessionId(GEN_1), OFFER_SDP))
            fakes.awaitSent { it is VoiceSignal.Offer }

            leader.submit(VoiceSignal.IceCandidate(VoiceSessionId(GEN_1), CANDIDATE, "0", 0))
            fakes.awaitQueued(leader, 1)
            assertFalse(
                fakes.engine.calls.any { it.startsWith("addRemoteCandidate") },
                "nothing may be applied before the remote description",
            )

            leader.submit(VoiceSignal.Answer(VoiceSessionId(GEN_1), ANSWER_SDP))
            fakes.awaitEngineCall("addRemoteCandidate(0)")
            assertEquals(0, leader.diagnostics.value.queuedCandidates, "the queue is drained, not merely read")
        }

    @Test
    fun `a candidate arriving after the answer is applied immediately`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(VoiceSessionId(GEN_1), OFFER_SDP))
            leader.submit(VoiceSignal.Answer(VoiceSessionId(GEN_1), ANSWER_SDP))
            fakes.awaitEngineCall("applyRemote(ANSWER)")

            leader.submit(VoiceSignal.IceCandidate(VoiceSessionId(GEN_1), CANDIDATE, null, 3))
            fakes.awaitEngineCall("addRemoteCandidate(3)")
            assertEquals(0, leader.diagnostics.value.queuedCandidates)
        }

    @Test
    fun `a locally gathered candidate is trickled to the peer`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            fakes.engine.emit(
                VoiceEngineEvent.LocalCandidateGathered(VoiceSessionId(GEN_1), CANDIDATE, "0", 0),
            )
            fakes.awaitSent { it is VoiceSignal.IceCandidate }
            val sent =
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.IceCandidate>()
                    .single()
            assertEquals(CANDIDATE, sent.candidate)
            assertEquals(GEN_1, sent.voiceSessionId.value)
        }

    /** PROTOCOL §7.6: an `srflx` candidate cannot legitimately occur, so it is surfaced, not ignored. */
    @Test
    fun `a non-host candidate type is reported in diagnostics`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            assertFalse(leader.diagnostics.value.unexpectedCandidateTypeSeen)

            fakes.engine.emit(
                VoiceEngineEvent.LocalCandidateGathered(
                    VoiceSessionId(GEN_1),
                    "candidate:1 1 udp 1 198.51.100.9 3478 typ srflx raddr 192.0.2.11 rport 51234",
                    "0",
                    0,
                ),
            )
            withTimeout(TIMEOUT_MS) {
                while (!leader.diagnostics.value.unexpectedCandidateTypeSeen) delay(POLL_MS)
            }
        }

    // --- mute -----------------------------------------------------------------------------------

    @Test
    fun `mute disables the sender and unmute restores it, and the peer is told both times`() =
        withController(isLocalLeader = true) { leader, fakes ->
            // Phase 2b: mute now flows through the intercom gate, so the policy decides what unmuting
            // *means*. The harness runs these under full duplex (see `withController`), where it means
            // exactly what it meant in Phase 2a — the sender is re-enabled. Under PTT it does not, and
            // `VoiceControllerIntercomTest` states that separately.
            leader.start()
            fakes.awaitEngineCall("createOffer")

            // Awaited on the observable state rather than on an engine call name. Phase 2b tells the
            // engine the gate's value whenever a peer connection is built (so the track's enabled state
            // is a consequence of the policy, not of a constructor default), which means both call names
            // are already in the log by this point and a name-based await would prove nothing.
            leader.setMicrophoneMuted(true)
            fakes.await { leader.diagnostics.value.micMuted }
            assertEquals(true, fakes.engine.muted)
            assertTrue(
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .any { it.micMuted },
            )

            leader.setMicrophoneMuted(false)
            fakes.await { !leader.diagnostics.value.micMuted }
            assertEquals(false, fakes.engine.muted)
        }

    // --- teardown, and the difference between the two kinds -------------------------------------

    @Test
    fun `End Voice closes the peer connection, releases the media stack and releases capture`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            leader.stop()
            fakes.awaitEngineCall("release")

            assertTrue(fakes.engine.calls.contains("stop"), "the peer connection is closed")
            assertEquals(listOf("open", "close"), fakes.audio.calls, "capture is released on a deliberate stop")
            assertTrue(
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .any { it.state == VoiceWireState.CLOSED },
                "PROTOCOL §7.4: `closed` is the teardown signal, and the peer is told before the socket goes",
            )
            assertEquals(VoiceStatus.IDLE, leader.diagnostics.value.status)
        }

    /**
     * ARCHITECTURE §6.3/§6.4, and the reason `VoiceEngine.stop` and `release` are separate calls: a
     * control blip must not close a microphone Android would not let us reopen from the background,
     * and must not renegotiate the Bluetooth endpoint's profile either.
     */
    @Test
    fun `a control link loss drops the media transport but never the capture device`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            val sentBefore = fakes.transport.sent.size

            leader.onControlLinkLost()
            fakes.awaitEngineCall("stop")
            delay(SETTLE_MS)

            assertFalse(fakes.engine.calls.contains("release"), "the media factory and capture path must survive")
            assertEquals(listOf("open"), fakes.audio.calls, "the audio session must not be closed by a link blip")
            assertTrue(fakes.audio.isOpen)
            assertEquals(sentBefore, fakes.transport.sent.size, "there is no link to send a VOICE_STATE on")
            assertTrue(leader.diagnostics.value.localAudioOpen, "the user's consent for this segment survives")
        }

    @Test
    fun `voice can be rebuilt after a link loss with a fresh generation`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            leader.onControlLinkLost()
            fakes.awaitEngineCall("stop")

            leader.start()
            fakes.awaitEngineCall("start(${GEN_2})")
            assertTrue(
                leader.diagnostics.value.voiceSessionPrefix
                    ?.contains(GEN_2.take(6)) == true,
                "a rebuild is a fresh negotiation (PROTOCOL §7.8), not a resumed one",
            )
            assertTrue(leader.diagnostics.value.rebuildCount >= 1)
        }

    // --- the generation guard, applied to callbacks ---------------------------------------------

    /**
     * PROTOCOL §7.8's stale-callback rule, as a behavioural test rather than a table row: a callback
     * from a peer connection that has already been torn down must not be able to send anything or
     * move the next negotiation.
     */
    @Test
    fun `a stale engine callback cannot activate the next generation`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")
            leader.onControlLinkLost()
            fakes.awaitEngineCall("stop")
            leader.start()
            fakes.awaitEngineCall("start(${GEN_2})")
            val sentBefore = fakes.transport.sent.size

            // The old generation's peer connection reports an offer and then a connection.
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(VoiceSessionId(GEN_1), OFFER_SDP))
            fakes.engine.emit(
                VoiceEngineEvent.TransportStateChanged(VoiceSessionId(GEN_1), MediaTransportState.CONNECTED),
            )
            delay(SETTLE_MS)

            assertEquals(sentBefore, fakes.transport.sent.size, "a stale callback must send nothing")
            assertEquals(
                VoiceStatus.NEGOTIATING,
                leader.diagnostics.value.status,
                "and must not mark the new generation active",
            )
            assertTrue(
                (leader.diagnostics.value.droppedSignals[VoiceSignalDropReason.STALE_ENGINE_CALLBACK] ?: 0) >= 2,
                "each stale callback is counted, not silently discarded",
            )
        }

    @Test
    fun `a signal for a foreign generation is dropped and counted`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            leader.submit(VoiceSignal.Answer(VoiceSessionId(FOREIGN_GEN), ANSWER_SDP))
            withTimeout(TIMEOUT_MS) {
                while ((leader.diagnostics.value.droppedSignals[VoiceSignalDropReason.GENERATION_MISMATCH] ?: 0) == 0) {
                    delay(POLL_MS)
                }
            }
            assertFalse(fakes.engine.calls.contains("applyRemote(ANSWER)"))
            assertEquals(VoiceStatus.NEGOTIATING, leader.diagnostics.value.status)
        }

    /** PROTOCOL §7.3: the answerer must refuse to answer an offer it should never have received. */
    @Test
    fun `an offer sent to the offerer is refused as a role violation`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            leader.submit(VoiceSignal.Offer(VoiceSessionId(GEN_1), OFFER_SDP))
            withTimeout(TIMEOUT_MS) {
                while ((leader.diagnostics.value.droppedSignals[VoiceSignalDropReason.ROLE_VIOLATION] ?: 0) == 0) {
                    delay(POLL_MS)
                }
            }
            assertFalse(fakes.engine.calls.contains("applyRemote(OFFER)"))
        }

    // --- graceful degradation -------------------------------------------------------------------

    /** FR-025: a denied microphone must not crash and must not silently pretend to have one. */
    @Test
    fun `a refused audio session does not crash and is visible in diagnostics`() =
        withController(isLocalLeader = true, audioOpens = false) { leader, fakes ->
            leader.start()
            delay(SETTLE_MS)

            assertEquals(listOf("open"), fakes.audio.calls)
            assertFalse(fakes.audio.isOpen)
            assertFalse(leader.diagnostics.value.localAudioOpen, "the UI must be able to say the mic is unavailable")
        }

    @Test
    fun `the audio route is published to diagnostics as the platform reports it`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            fakes.audio.publish(
                AudioRouteSnapshot(
                    endpointClass = EndpointClass.BLUETOOTH,
                    microphoneOpen = true,
                    effectiveOutputProfile = AudioProfile.DUPLEX_WIDEBAND,
                    effectiveInputProfile = AudioProfile.DUPLEX_WIDEBAND,
                    profileCoupling = ProfileCoupling.INPUT_FORCES_OUTPUT,
                ),
            )
            withTimeout(TIMEOUT_MS) {
                while (leader.diagnostics.value.route.endpointClass != EndpointClass.BLUETOOTH) delay(POLL_MS)
            }
            // ADR-016's whole point: with the mic open on Bluetooth the honest answer is "reduced".
            assertEquals(
                com.ridelink.core.audiopolicy.MediaQuality.REDUCED,
                leader.diagnostics.value.route.mediaQuality,
            )
        }

    // --- the bounded queue ----------------------------------------------------------------------

    @Test
    fun `the candidate queue is bounded and every drop is counted`() {
        val queue = PendingCandidates(capacity = 4)
        val id = VoiceSessionId(GEN_1)
        repeat(4) { queue.offer(RemoteCandidate(id, "c$it", null, 0)) }
        assertEquals(4, queue.size)
        assertEquals(0, queue.droppedCount)

        repeat(3) { queue.offer(RemoteCandidate(id, "over$it", null, 0)) }
        assertEquals(4, queue.size, "capacity is a hard bound")
        assertEquals(3, queue.droppedCount, "silent truncation would read as 'we saw everything'")

        // The oldest go first, so the newest — most likely still reachable — survive.
        assertEquals(listOf("c3", "over0", "over1", "over2"), queue.drain(id).map { it.candidate })
    }

    @Test
    fun `draining discards candidates queued for another generation`() {
        val queue = PendingCandidates()
        val mine = VoiceSessionId(GEN_1)
        val theirs = VoiceSessionId(FOREIGN_GEN)
        queue.offer(RemoteCandidate(theirs, "stale", null, 0))
        queue.offer(RemoteCandidate(mine, "current", null, 0))

        assertEquals(listOf("current"), queue.drain(mine).map { it.candidate })
        assertEquals(0, queue.size, "a foreign-generation candidate is discarded, never left to be drained later")
    }

    @Test
    fun `the queue's default capacity is the protocol bound`() {
        val queue = PendingCandidates()
        val id = VoiceSessionId(GEN_1)
        repeat(VoiceBounds.MAX_QUEUED_CANDIDATES + 5) { queue.offer(RemoteCandidate(id, "c$it", null, 0)) }
        assertEquals(VoiceBounds.MAX_QUEUED_CANDIDATES, queue.size)
        assertEquals(5, queue.droppedCount)
    }

    // --- harness ---------------------------------------------------------------------------------

    private class Fakes(
        val engine: FakeVoiceEngine,
        val audio: FakeVoiceAudioSession,
        val transport: RecordingVoiceTransport,
    ) {
        suspend fun awaitEngineCall(
            call: String,
            onAudio: Boolean = false,
        ) {
            val calls = { if (onAudio) audio.calls else engine.calls }
            withTimeout(TIMEOUT_MS) {
                while (!calls().contains(call)) delay(POLL_MS)
            }
        }

        suspend fun awaitSent(predicate: (VoiceSignal) -> Boolean) {
            withTimeout(TIMEOUT_MS) {
                while (transport.sent.none(predicate)) delay(POLL_MS)
            }
        }

        /**
         * Awaits an observable condition rather than an engine call name.
         *
         * Needed wherever the same call name can legitimately appear more than once — Phase 2b tells the
         * engine the intercom gate's value whenever a peer connection is built, so
         * `setMicrophoneMuted(true)` is in the log before a test's own mute ever runs, and a name-based
         * await there would return immediately and assert nothing.
         */
        suspend fun await(condition: () -> Boolean) {
            withTimeout(TIMEOUT_MS) {
                while (!condition()) delay(POLL_MS)
            }
        }

        suspend fun awaitQueued(
            controller: VoiceController,
            count: Int,
        ) {
            withTimeout(TIMEOUT_MS) {
                while (controller.diagnostics.value.queuedCandidates < count) delay(POLL_MS)
            }
        }

        suspend fun awaitStatus(
            controller: VoiceController,
            status: VoiceStatus,
        ) {
            withTimeout(TIMEOUT_MS) {
                while (controller.diagnostics.value.status != status) delay(POLL_MS)
            }
        }
    }

    /**
     * **[policy] defaults to Mode A (full duplex), not to the production default.**
     *
     * These tests are about the negotiation table's *wiring* — who offers, what reaches the engine,
     * what a stale callback cannot do — and full duplex is the policy in which "Start Voice, then
     * talk" has exactly the shape Phase 2a's assertions describe: capture opens and outbound audio
     * flows, so `VOICE_STATE.mic_muted` never changes and the frame counts below are literal.
     *
     * Under the production default (Mode C, PTT — ARCHITECTURE §6.3) capture opening leaves this side
     * transmitting nothing, so the gate correctly sends one more `VOICE_STATE` to say so. That is
     * Phase 2b behaviour and `VoiceControllerIntercomTest` is where it is asserted, rather than
     * blurring it into these.
     */
    private fun withController(
        isLocalLeader: Boolean,
        audioOpens: Boolean = true,
        policy: IntercomPolicy = IntercomPolicy.MODE_A,
        body: suspend (VoiceController, Fakes) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val engine = FakeVoiceEngine()
            val audio = FakeVoiceAudioSession()
            if (!audioOpens) audio.openResult = Result.failure(IllegalStateException("RECORD_AUDIO denied"))
            val transport = RecordingVoiceTransport()
            val ids = SequencedVoiceSessionIds(GEN_1, GEN_2, GEN_3)
            val controller =
                VoiceController(
                    scope = scope,
                    engine = engine,
                    audioSession = audio,
                    transport = transport,
                    isLocalLeader = isLocalLeader,
                    localTrackId = "ridelink-voice",
                    newVoiceSessionId = ids::next,
                )
            // Offered before the body runs, and the mailbox drains intercom commands ahead of voice
            // inputs, so the policy is in force before the body's first `start()` is reduced.
            controller.selectPolicy(policy)
            body(controller, Fakes(engine, audio, transport))
            controller.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val GEN_1 = "11111111111111111111111111111111"
        const val GEN_2 = "22222222222222222222222222222222"
        const val GEN_3 = "33333333333333333333333333333333"
        const val FOREIGN_GEN = "abababababababababababababababab"
        const val OFFER_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=setup:actpass\r\n"
        const val ANSWER_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=setup:active\r\n"
        const val CANDIDATE = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 150L
    }
}
