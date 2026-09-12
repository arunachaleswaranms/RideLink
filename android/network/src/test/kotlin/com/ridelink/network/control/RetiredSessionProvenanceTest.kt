package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.protocol.AudioStateMessageTypes
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.ManifestMessageTypes
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.core.protocol.TransferMessageTypes
import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.transfer.TransferSink
import com.ridelink.network.voice.VoiceSignalSpy
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The regression **ADR-025** exists for: an inbound frame's authority comes from the connection it
 * was read from, and no subsystem downstream of [ControlSessionManager.handleFrame] may throw that
 * away and reconstruct authority from live session state.
 *
 * **What ADR-024 Amendment A7 left.** A7 fixed the *origin* of the number — `readLoop` now builds a
 * [ReadFrameBinding] the instant the read returns — and threaded it to the Phase 5 sinks. It
 * explicitly did **not** thread it anywhere else, and recorded that omission as `docs/STATUS.md` §4
 * problem 44. Everything below that line kept doing exactly what A7 had just disproved:
 *
 * - `MANIFEST_*`/`TRANSFER_*`: [com.ridelink.network.manifest.ManifestRelay] and
 *   [com.ridelink.network.transfer.TransferRelay] discarded the generation, and
 *   `SharedLibraryCoordinator`'s sink lambdas — which run **synchronously inside `handleFrame`** —
 *   read `currentAuthGeneration` *there*. A Session A `MANIFEST_PAGE` dispatched after Session B
 *   activated therefore arrived carrying B's number, its own re-check passed, and it mutated B's
 *   catalogue.
 * - `VOICE_*`: the generation never reached [com.ridelink.network.voice.VoiceSignalRelay] at all.
 *   `VoiceController` is deliberately kept across a control reconnect (the capture device stays open
 *   for the ride segment), and `VoiceNegotiation`'s `voice_session_id` guards answer a different
 *   question, so a stale `VOICE_STATE { state: "closed" }` could tear down the successor's live
 *   media and a stale `VOICE_OFFER` could start a negotiation on the successor's connection.
 * - `AUDIO_STATE`: same discarded generation, into an inbox that is reset per *discovery* session
 *   rather than per control session.
 * - The **pre-authentication family** (`PING`/`PONG`/`PAIR_*`/`BYE`/`ERROR`) is allowed past the
 *   generation gate by design and so was bound to nothing at all: `handlePong` took a payload and no
 *   connection, and `handlePairingFrame` reached for whatever exchange was live.
 *
 * **How this test produces the park** is A7's construction, unchanged and for its reason: nothing a
 * test controls can suspend a coroutine between `ControlSocket.readFrame()` returning and the
 * dispatch that follows it. So the two halves of that one step are called as two statements with a
 * **real** session boundary in between — [ControlSessionManager.currentReadBinding] is the capture
 * `readLoop` performs, and [ControlSessionManager.handleFrame] is the very function it calls.
 * Everything under test is production: two real TLS 1.3 sessions on one real
 * [ControlSessionManager], the real trust gate, the real allowlist and the real codecs.
 *
 * The mirror is `RideLinkPlatformTests.RetiredSessionProvenanceTests`.
 */
class RetiredSessionProvenanceTest {
    // --- MANIFEST_* (PROTOCOL §8.1) ---------------------------------------------------------------

    /**
     * **STATUS §4 problem 44, at the seam that contained it.** Against unmodified `326a145`
     * production sources the message below reaches the sink, and `SharedLibraryCoordinator`'s lambda
     * — running right here, inside `handleFrame` — reads generation **2** for it.
     */
    @Test
    fun `a MANIFEST frame bound to Session A never reaches Session B's sink`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding(), "Session A must have a connection")
            assertEquals(1L, parked.generation, "Session A is generation 1")

            sut.boundaryToSecondSession()
            assertEquals(2L, sut.manager.liveAuthenticatedGeneration, "Session B is generation 2")

            sut.manager.handleFrame(parked, manifestPage())

            assertEquals(emptyList(), sut.manifest.received, "a retired session's MANIFEST_PAGE reaches no sink")
            assertEquals(1, sut.manager.manifest.droppedRetiredGeneration, "and is counted as the refusal it is")
            assertEquals(emptyMap(), sut.manager.manifest.rejectionCounts, "it was refused by the gate, not the codec")
        }

    /**
     * The half a naive fix breaks: **being late is not being stale.** A frame whose own session is
     * still live is still delivered, carrying that session's generation.
     */
    @Test
    fun `a MANIFEST frame dispatched late within its own live session is still delivered`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            delay(SETTLE_MS) // other work runs, which is what every ride produces

            sut.manager.handleFrame(parked, manifestPage())

            assertEquals(1, sut.manifest.received.size, "the live session's own frame is delivered")
            assertEquals(listOf(1L), sut.manifest.generations, "tagged its own generation, not looked up")
            assertEquals(0, sut.manager.manifest.droppedRetiredGeneration)
        }

    /** Session B's own ingress is untouched, through the same production path. */
    @Test
    fun `Session B's own MANIFEST still works after a Session A frame was refused`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()
            sut.manager.handleFrame(parked, manifestPage())

            val live = assertNotNull(sut.manager.currentReadBinding(), "Session B must have a connection")
            sut.manager.handleFrame(live, manifestPage())

            assertEquals(1, sut.manifest.received.size, "B's own frame is delivered")
            assertEquals(listOf(2L), sut.manifest.generations, "as B's, and only B's")
        }

    // --- TRANSFER_* (PROTOCOL §8.2) ---------------------------------------------------------------

    /**
     * `TRANSFER_OFFER` is the state-changing one that matters most to a requester: it is what
     * satisfies a pending request, and it carries the bulk port and token a fetch is then made
     * against (ADR-023). A retired session's offer must not be able to satisfy the successor's.
     */
    @Test
    fun `a TRANSFER_OFFER bound to Session A never reaches Session B's sink`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            sut.manager.handleFrame(parked, transferOffer())

            assertEquals(emptyList(), sut.transfer.received, "a retired session's TRANSFER_OFFER reaches no sink")
            assertEquals(1, sut.manager.transfer.droppedRetiredGeneration)
            assertEquals(emptyMap(), sut.manager.transfer.rejectionCounts, "refused by the gate, not the codec")
        }

    /**
     * The other three state-changing `TRANSFER_*` messages, through the same gate: a stale `CANCEL`
     * must not cancel the successor's bulk operation, a stale `REQUEST` must not be served under the
     * successor's peer identity, and a stale `RESULT { ok: true }` must not mark content the
     * successor's peer has verified (ADR-024 §7's availability input).
     */
    @Test
    fun `every state-changing TRANSFER type bound to Session A is refused`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            sut.manager.handleFrame(parked, transferRequest())
            sut.manager.handleFrame(parked, transferCancel())
            sut.manager.handleFrame(parked, transferResult())

            assertEquals(emptyList(), sut.transfer.received)
            assertEquals(3, sut.manager.transfer.droppedRetiredGeneration)

            val live = assertNotNull(sut.manager.currentReadBinding())
            sut.manager.handleFrame(live, transferRequest())
            assertEquals(1, sut.transfer.received.size, "B's own TRANSFER_REQUEST still works")
            assertEquals(listOf(2L), sut.transfer.generations)
        }

    // --- VOICE_* (PROTOCOL §7) --------------------------------------------------------------------

    /**
     * The harmful one. `VOICE_STATE { state: "closed" }` with no `voice_session_id` carries no
     * generation claim, so `VoiceNegotiation.peerStateReceived` does **not** treat it as a mismatch
     * — it is `teardownFromPeer`, which stops the media transport. Delivered to the retained
     * `VoiceController` after a reconnect, a Session A frame therefore tears down **Session B's**
     * live voice. Only the control-session generation distinguishes the two, and it was discarded.
     */
    @Test
    fun `a VOICE_STATE closed bound to Session A never reaches Session B's voice sink`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            sut.manager.handleFrame(parked, voiceStateClosed())

            assertEquals(emptyList(), sut.voice.received, "a retired session's VOICE_STATE reaches no sink")
            assertEquals(1, sut.manager.voice.droppedRetiredGeneration)
            assertEquals(emptyMap(), sut.manager.voice.rejectionCounts, "refused by the gate, not the codec")
        }

    /**
     * The other harmful one, and the reason `voice_session_id` cannot stand in for this: after
     * `ControlLinkLost` the reducer resets to `IDLE` with `voiceSessionId = null`, which is exactly
     * the state in which `offerReceived` **accepts** an offer naming any generation. A stale offer
     * would then start a negotiation whose answer is written to the successor's connection.
     */
    @Test
    fun `a VOICE_OFFER bound to Session A never reaches Session B's voice sink`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            sut.manager.handleFrame(parked, voiceOffer())

            assertEquals(emptyList(), sut.voice.received)
            assertEquals(1, sut.manager.voice.droppedRetiredGeneration)

            val live = assertNotNull(sut.manager.currentReadBinding())
            sut.manager.handleFrame(live, voiceOffer())
            assertEquals(1, sut.voice.received.size, "B's own VOICE_OFFER still works")
            assertTrue(sut.voice.received.single() is VoiceSignal.Offer)
        }

    // --- AUDIO_STATE (PROTOCOL §4.4) --------------------------------------------------------------

    /**
     * Reachable, and therefore fixed rather than argued away. `AudioStateInboxHolder` is reset per
     * **discovery** session (PROTOCOL §4.4's `revision` is "per sender per session"), not per
     * control session, so it survives a reconnect on purpose — which means a stale Session A message
     * whose `revision` exceeds the held one is accepted by the revision rule and published as the
     * successor session's peer audio state.
     */
    @Test
    fun `an AUDIO_STATE bound to Session A never reaches Session B's sink`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            sut.manager.handleFrame(parked, audioState(revision = 5))

            assertEquals(emptyList(), sut.audioState.received, "a retired session's AUDIO_STATE reaches no sink")
            assertEquals(1, sut.manager.audioState.droppedRetiredGeneration)

            val live = assertNotNull(sut.manager.currentReadBinding())
            sut.manager.handleFrame(live, audioState(revision = 6))
            val delivered = sut.audioState.received
            assertEquals(1, delivered.size, "B's own AUDIO_STATE still works")
            assertEquals(6, delivered.single().revision, "and it is B's revision, not A's")
        }

    // --- PONG (PROTOCOL §6) -----------------------------------------------------------------------

    /**
     * `PING`/`PONG` are in the pre-authentication allowlist by design, so they never reach the
     * generation gate — and before ADR-025 nothing else bound them to a connection either.
     * `handlePong` took a payload and no socket, and every one of its effects is **manager-level**:
     * `lastPongAtMonoUs` (which is what `keepaliveLoop` measures the link's liveness against),
     * `clock.recordRtt` (unconditional — it does not depend on a matching pending ping) and the
     * `rttMs` diagnostic. `promote` calls `clock.reset()`, so a retired connection's round trip
     * lands in the **successor's fresh** RTT window, which is what ARCHITECTURE §7.2's
     * `LEAD = max(120 ms, 4 × rtt_p95)` is computed from.
     *
     * A full `RTT_WINDOW_CAPACITY` of them is injected so the assertion is on the window's *whole*
     * contents rather than on where one sample happens to fall in a p95 — deterministic either way,
     * and without that a real loopback sample could mask the injected one.
     */
    @Test
    fun `a PONG read from a retired connection cannot touch the successor's clock`() =
        twoSessionsOnOneManager { sut ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()

            repeat(RTT_WINDOW_CAPACITY) { i -> sut.manager.handleFrame(parked, pong(ABSURD_RTT_US + i)) }

            assertEquals(RTT_WINDOW_CAPACITY, sut.manager.retiredConnectionFrames, "each one refused and counted")
            val p95 = sut.manager.clock.rttP95Us
            assertTrue(
                p95 == null || p95 < UNREACHABLE_BY_A_REAL_SAMPLE_US,
                "Session B's RTT window must hold only Session B's own round trips, was $p95",
            )
        }

    /** The other half: Session B's own `PONG` still measures Session B's link. */
    @Test
    fun `Session B's own PONG still records`() =
        twoSessionsOnOneManager { sut ->
            sut.boundaryToSecondSession()
            val live = assertNotNull(sut.manager.currentReadBinding())

            sut.manager.handleFrame(live, pong(ABSURD_RTT_US))

            assertEquals(0, sut.manager.retiredConnectionFrames, "nothing was refused")
            // `t4` is production's own `monotonicNowUs()`, so the recorded round trip is
            // `ABSURD_RTT_US` plus however long the dispatch took — asserted as a floor rather than
            // an equality, because an equality would be asserting the test's own scheduling.
            val p95 = assertNotNull(sut.manager.clock.rttP95Us, "the live connection's PONG is a measurement")
            assertTrue(p95 >= ABSURD_RTT_US, "a PONG on the live connection still reaches the window, was $p95")
        }

    // --- harness ------------------------------------------------------------------------------------

    /**
     * One [ControlSessionManager] under test, kept alive across a session boundary — which is the
     * whole point, since every defect here is about state moving *underneath* a live manager.
     *
     * The same shape `StaleReadGenerationTest` uses for Phase 5, with the four non-Phase-5 sinks
     * attached instead.
     */
    private class Sinks(
        val manifest: ManifestSpy = ManifestSpy(),
        val transfer: TransferSpy = TransferSpy(),
        val voice: VoiceSignalSpy = VoiceSignalSpy(),
        val audioState: AudioStateSpy = AudioStateSpy(),
    )

    private class Sut(
        val manager: ControlSessionManager,
        val session: FsmSession,
        private val sinks: Sinks,
        private val peer: TestPeer,
        private val scope: CoroutineScope,
        private val port: Int,
        private val counterpart: TestPeer,
    ) {
        val manifest: ManifestSpy get() = sinks.manifest
        val transfer: TransferSpy get() = sinks.transfer
        val voice: VoiceSignalSpy get() = sinks.voice
        val audioState: AudioStateSpy get() = sinks.audioState

        private val managers = mutableListOf<ControlSessionManager>()

        suspend fun connectFirstSession() = connectSession()

        /**
         * Ends Session A with a real `BYE` and brings a second real TLS session up on the same
         * manager, so the authentication generation advances 1 -> 2 exactly as a reconnect does.
         */
        suspend fun boundaryToSecondSession() {
            managers.last().shutdown()
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.currentReadBinding() != null) delay(POLL_MS)
            }
            connectSession()
        }

        private suspend fun connectSession() {
            val target = counterpart.manager(scope, MONOTONIC)
            managers.add(target)
            val before = session.countOf { it is ControlEvent.Connected }
            val targetPort = target.startListening(counterpart.local)
            manager.connectTo("127.0.0.1", targetPort, peer.local)
            target.connectTo("127.0.0.1", port, counterpart.freshLocal())
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (session.countOf { it is ControlEvent.Connected } <= before) delay(POLL_MS)
            }
        }

        suspend fun shutdownAll() {
            manager.shutdown()
            managers.forEach { it.shutdown() }
        }
    }

    private fun twoSessionsOnOneManager(body: suspend (Sut) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = a.manager(scope, MONOTONIC)
                val session = FsmSession(a, manager)
                session.collectInto(scope)

                val sinks = Sinks()
                manager.manifest.sink = sinks.manifest
                manager.transfer.sink = sinks.transfer
                manager.voice.sink = sinks.voice
                manager.audioState.sink = sinks.audioState

                val port = manager.startListening(a.local)
                val sut = Sut(manager, session, sinks, a, scope, port, b)
                sut.connectFirstSession()
                try {
                    body(sut)
                } finally {
                    sut.shutdownAll()
                }
            } finally {
                scope.cancel()
            }
        }

    /** Records **which generation** each message arrived with — the fact this amendment is about. */
    private class ManifestSpy : ManifestSink {
        private val log = CopyOnWriteArrayList<ManifestMessage>()
        private val generationLog = CopyOnWriteArrayList<Long>()

        val received: List<ManifestMessage> get() = log.toList()
        val generations: List<Long> get() = generationLog.toList()

        override fun submit(
            message: ManifestMessage,
            generation: Long,
        ) {
            log.add(message)
            generationLog.add(generation)
        }
    }

    private class TransferSpy : TransferSink {
        private val log = CopyOnWriteArrayList<TransferMessage>()
        private val generationLog = CopyOnWriteArrayList<Long>()

        val received: List<TransferMessage> get() = log.toList()
        val generations: List<Long> get() = generationLog.toList()

        override fun submit(
            message: TransferMessage,
            generation: Long,
        ) {
            log.add(message)
            generationLog.add(generation)
        }
    }

    private class AudioStateSpy : AudioStateSink {
        private val log = CopyOnWriteArrayList<AudioStateMessage>()

        val received: List<AudioStateMessage> get() = log.toList()

        override fun submit(message: AudioStateMessage) {
            log.add(message)
        }
    }

    // --- frames (valid by the shared vectors, so a refusal can only ever be the gate) ---------------

    private fun frame(
        type: String,
        build: JsonObjectBuilder.() -> Unit,
    ) = FrameReadResult.Frame(rawEnvelope(PEER_B, type, build), versionOk = true)

    private fun manifestPage() =
        frame(ManifestMessageTypes.PAGE) {
            put("manifest_id", MANIFEST_ID)
            put("manifest_revision", 7)
            put("page_index", 0)
            put("entries", buildJsonArray { })
            put("removed", buildJsonArray { })
        }

    private fun transferOffer() =
        frame(TransferMessageTypes.OFFER) {
            put("transfer_id", TRANSFER_ID)
            put("size_bytes", 1_024)
            put("chunk_size", 65_536)
            put("chunk_count", 1)
            put("bulk_port", 45_001)
            put("bulk_token", BULK_TOKEN)
        }

    private fun transferRequest() =
        frame(TransferMessageTypes.REQUEST) {
            put("content_hash", CONTENT_HASH)
            put("transfer_id", TRANSFER_ID)
        }

    private fun transferCancel() =
        frame(TransferMessageTypes.CANCEL) {
            put("transfer_id", TRANSFER_ID)
            put("reason", "user_cancelled")
        }

    private fun transferResult() =
        frame(TransferMessageTypes.RESULT) {
            put("transfer_id", TRANSFER_ID)
            put("ok", true)
            put("sha256", CONTENT_HASH)
        }

    private fun voiceStateClosed() =
        frame(VoiceMessageTypes.STATE) {
            // `voice_session_id` absent, which the codec reads as null and `VoiceNegotiation` reads
            // as "carries no generation claim" — the shape that is *not* a generation mismatch and
            // therefore the one only the control-session generation can refuse.
            put("state", "closed")
            put("mic_muted", false)
            put("mode", "continuous")
        }

    private fun voiceOffer() =
        frame(VoiceMessageTypes.OFFER) {
            put("voice_session_id", VOICE_SESSION_ID)
            put("sdp", MINIMAL_SDP)
        }

    private fun audioState(revision: Int) =
        frame(AudioStateMessageTypes.AUDIO_STATE) {
            put("revision", revision)
            put("endpoint_class", "bluetooth")
            put("microphone_open", true)
            put("effective_output_profile", "duplex_wideband")
            put("effective_input_profile", "duplex_wideband")
            put("effective_output_sample_rate_hz", 16_000)
            put("effective_input_sample_rate_hz", 16_000)
            put("media_quality", "reduced")
            put("route_state", "stable")
            put("intercom_mode", "ptt")
            put("confidence", "assumed")
        }

    /**
     * A §6-valid `PONG` whose round trip is [rttUs]. `t2`/`t3` are equal, so the sample's rtt is
     * exactly `t4 - t1` and `isPlausibleClockSample` accepts it — a real peer with a slow link
     * produces precisely this shape.
     */
    private fun pong(rttUs: Long) =
        frame("PONG") {
            put("t1_mono_us", MONOTONIC() - rttUs)
            put("t2_mono_us", 1_000L)
            put("t3_mono_us", 1_000L)
        }

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
        const val SETTLE_MS = 100L

        /** `ClockSync.RTT_WINDOW_CAPACITY`, named here so the assertion says why it is that number. */
        const val RTT_WINDOW_CAPACITY = 64

        /** Absurd on purpose: ~1.4 hours of round trip, which no loopback sample can be confused with. */
        const val ABSURD_RTT_US = 5_000_000_000L

        /**
         * 100 seconds — deliberately **not** "a plausible loopback RTT". Production bounds every
         * sample it can record by its own ping timeout (`PING_TIMEOUT_MS` 3 s for the §7.1 burst,
         * `KEEPALIVE_INTERVAL_MS` 2 s for keepalive), so no real sample can reach this however
         * loaded the machine is — while [ABSURD_RTT_US] exceeds it fifty-fold. Asserting against a
         * ceiling a *real* sample could approach under load would be asserting the build agent's
         * scheduling, not the gate.
         */
        const val UNREACHABLE_BY_A_REAL_SAMPLE_US = 100_000_000L

        const val MANIFEST_ID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
        const val TRANSFER_ID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5D"
        const val CONTENT_HASH = "sha256:1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f"
        const val BULK_TOKEN = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val VOICE_SESSION_ID = "5e2a9c40b7f13d86e0a4c95b28f7d613"
        const val MINIMAL_SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    }
}
