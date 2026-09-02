package com.ridelink.network.voice

import com.ridelink.core.protocol.VoiceBounds
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VoiceController] driven through the real [com.ridelink.core.voice.VoiceInputMailbox] under
 * flooding conditions an authenticated-but-compromised peer could actually produce.
 *
 * [VoiceInputMailboxTest] already exhausts the mailbox's own bound/coalesce/priority logic in
 * isolation. This file is the half that only shows up once the mailbox is wired into a live
 * controller: that flooding cannot wedge or crash it, that `stop`/`onControlLinkLost` always get
 * through, and that a forced degrade leaves the controller in exactly the same safe state a real
 * control-link blip would.
 *
 * Most tests here run the controller on [ManualDispatcher] rather than a real one. That is
 * deliberate, not a convenience: [VoiceInputMailbox.offer] bounds the mailbox synchronously on
 * whichever thread calls [VoiceController.submit], so the bound itself never races — but whether a
 * flood actually *overflows* the critical lane, as opposed to being drained just as fast as it
 * arrives, depends on how the consumer happens to be scheduled relative to the producer. A real
 * dispatcher makes that a coin flip a fast machine can win either way; [ManualDispatcher] makes it
 * deterministic by guaranteeing nothing is consumed until the test says so. One real-dispatcher test
 * at the end covers the thing [ManualDispatcher] cannot: that the lock is actually safe under
 * genuine concurrent producers.
 */
class VoiceControllerMailboxTest {
    /**
     * Acceptance criterion B: ICE buffering stays bounded end to end. The mailbox's own capacity
     * is exhausted directly in [VoiceInputMailboxTest]; what only shows up with a live controller
     * is that the live backlog *surfaced in diagnostics* — the thing an operator or a future bug
     * would actually look at — never exceeds the protocol bound either, before or after the answer
     * lets the queue drain.
     */
    @Test
    fun `flooding VOICE_ICE before the answer cannot bypass the protocol's queued-candidate bound`() =
        withControllerManual(isLocalLeader = true) { leader, fakes, dispatcher ->
            leader.start()
            dispatcher.runAll()
            assertTrue(fakes.engine.calls.contains("createOffer"))

            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(1), SDP))
            dispatcher.runAll()
            assertTrue(fakes.transport.sent.any { it is VoiceSignal.Offer })

            // Flooded well before the answer, so every one of these would have queued for trickle
            // ICE under the old, unbounded design.
            repeat(FLOOD_COUNT) { i -> leader.submit(VoiceSignal.IceCandidate(genAt(1), "candidate:$i typ host", null, 0)) }
            dispatcher.runAll()

            assertTrue(
                leader.diagnostics.value.queuedCandidates <= VoiceBounds.MAX_QUEUED_CANDIDATES,
                "PendingCandidates' own bound must hold even after a pre-reducer mailbox flood",
            )

            leader.submit(VoiceSignal.Answer(genAt(1), SDP))
            dispatcher.runAll()

            assertTrue(fakes.engine.calls.any { it.startsWith("addRemoteCandidate") })
            assertTrue(
                fakes.engine.calls.count { it.startsWith("addRemoteCandidate") } <= VoiceBounds.MAX_QUEUED_CANDIDATES,
                "the mailbox's ICE lane must not have let more than the protocol bound ever reach the engine",
            )
            assertEqualsInt(0, leader.diagnostics.value.queuedCandidates, "the queue is drained, not merely read")
        }

    /** Acceptance criteria C and D: the safety valve always gets through, and it never kills capture. */
    @Test
    fun `stop remains processable while VOICE_STATE floods in`() =
        withControllerManual(isLocalLeader = false) { follower, fakes, dispatcher ->
            follower.start()
            dispatcher.runAll()
            assertTrue(fakes.audio.calls.contains("open"))

            repeat(FLOOD_COUNT) {
                follower.submit(VoiceSignal.State(genAt(1), VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS))
            }
            follower.stop()
            dispatcher.runAll()

            assertTrue(fakes.audio.calls.contains("close"), "stop must not be starved by a VOICE_STATE flood")
            assertTrue(
                follower.diagnostics.value.status == VoiceStatus.IDLE,
                "capture was released, so the reducer must have actually run the deliberate-stop path",
            )
        }

    /**
     * ADR-020 Amendment A3, acceptance criterion G: a peer's terminal `VOICE_STATE` must reach the
     * real [VoiceNegotiation] reducer -- not be lost to a later ordinary update coalescing over it in
     * the mailbox -- and must go through the reducer's *remote*-teardown path
     * (`teardownFromPeer`), which is distinct from a deliberate local [VoiceController.stop]: capture
     * survives either way, but only a local stop's [com.ridelink.core.voice.VoiceAction.ReleaseLocalAudio]
     * can ever release it.
     */
    @Test
    fun `a remote CLOSED survives a flood of ordinary ACTIVE updates and tears the session down to IDLE`() =
        withControllerManual(isLocalLeader = true) { leader, fakes, dispatcher ->
            leader.start()
            dispatcher.runAll()
            assertTrue(fakes.engine.calls.contains("createOffer"))

            leader.submit(VoiceSignal.State(genAt(1), VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS))
            repeat(FLOOD_COUNT) {
                leader.submit(VoiceSignal.State(genAt(1), VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS))
            }
            dispatcher.runAll()

            assertTrue(
                leader.diagnostics.value.status == VoiceStatus.IDLE,
                "the terminal CLOSED must have reached the reducer despite the ACTIVE flood behind it",
            )
            assertFalse(
                fakes.engine.calls.contains("release"),
                "a remote CLOSED must go through teardownFromPeer, never the local stop's capture-release action",
            )
            assertTrue(fakes.audio.isOpen, "capture survives a remote teardown exactly as it survives a link loss")
        }

    /** The FAILED half of the same property, with an ordinary CONNECTING flood behind it instead. */
    @Test
    fun `a remote FAILED survives a flood of ordinary CONNECTING updates and yields FAILED`() =
        withControllerManual(isLocalLeader = true) { leader, fakes, dispatcher ->
            leader.start()
            dispatcher.runAll()

            leader.submit(VoiceSignal.State(genAt(1), VoiceWireState.FAILED, false, VoiceMode.CONTINUOUS))
            repeat(FLOOD_COUNT) {
                leader.submit(VoiceSignal.State(genAt(1), VoiceWireState.CONNECTING, false, VoiceMode.CONTINUOUS))
            }
            dispatcher.runAll()

            assertTrue(
                leader.diagnostics.value.status == VoiceStatus.FAILED,
                "the terminal FAILED must have reached the reducer despite the CONNECTING flood behind it",
            )
            assertFalse(fakes.engine.calls.contains("release"))
            assertTrue(fakes.audio.isOpen)
        }

    /**
     * Acceptance criterion H: a flood of terminal peer states, on its own, cannot grow the mailbox
     * without bound -- it forces the same safe, already-proven degrade a critical-lane overflow does,
     * never releases capture, and never kills the control session.
     */
    @Test
    fun `a flood of terminal peer states beyond the lane's capacity forces a safe degrade, never releasing capture`() =
        withControllerManual(isLocalLeader = false) { follower, fakes, dispatcher ->
            follower.start()
            dispatcher.runAll()
            assertTrue(fakes.audio.calls.contains("open"))

            // Every one of these is submitted before the dispatcher is pumped again, so nothing can
            // drain and free terminal-lane space between submissions -- deterministic overflow, well
            // past VoiceInputMailbox.TERMINAL_PEER_STATE_CAPACITY (8).
            repeat(TERMINAL_OVERFLOW_FLOOD_COUNT) { i ->
                val wire = if (i % 2 == 0) VoiceWireState.CLOSED else VoiceWireState.FAILED
                follower.submit(VoiceSignal.State(genAt(1), wire, false, VoiceMode.CONTINUOUS))
            }
            dispatcher.runAll()

            val overflow = follower.diagnostics.value.droppedSignals[VoiceSignalDropReason.INPUT_MAILBOX_OVERFLOW] ?: 0
            assertTrue(overflow > 0, "flooding $TERMINAL_OVERFLOW_FLOOD_COUNT terminal states past a capacity of 8 must overflow")
            assertFalse(
                fakes.engine.calls.contains("release"),
                "a terminal-lane overflow must degrade like a link loss, never release capture",
            )
            assertTrue(fakes.audio.isOpen, "this user's consent for the ride segment must survive the degrade")
        }

    /** Acceptance criteria A, D and E, together: overflow degrades safely, capture and control both survive. */
    @Test
    fun `an authenticated flood of distinct offers triggers a safe degrade, not a crash, and never releases capture`() =
        withControllerManual(isLocalLeader = false) { follower, fakes, dispatcher ->
            follower.start()
            dispatcher.runAll()
            assertTrue(fakes.audio.calls.contains("open"))

            // Every one of these is submitted before the dispatcher is pumped again, so nothing can
            // drain and free critical-lane space between submissions: the flood genuinely outruns
            // the consumer, which is exactly the scenario an authenticated-but-compromised peer
            // flooding faster than this controller can consume would produce.
            repeat(OVERFLOW_FLOOD_COUNT) { i -> follower.submit(VoiceSignal.Offer(genAt(OFFSET + i), SDP)) }
            dispatcher.runAll()

            val overflow = follower.diagnostics.value.droppedSignals[VoiceSignalDropReason.INPUT_MAILBOX_OVERFLOW] ?: 0
            assertTrue(overflow > 0, "flooding $OVERFLOW_FLOOD_COUNT offers past a capacity of 32 must overflow")
            assertFalse(
                fakes.engine.calls.contains("release"),
                "a mailbox overflow must degrade like a link loss, never release capture",
            )
            assertTrue(fakes.audio.isOpen, "this user's consent for the ride segment must survive the degrade")
            assertTrue(follower.diagnostics.value.localAudioOpen)

            // The controller is still alive and useful afterward -- the degrade was not a wedge.
            follower.start()
            dispatcher.runAll()
            assertTrue(fakes.audio.calls.count { it == "open" } >= 1)
        }

    /** Acceptance criterion: teardown completes even when every lane was full at the moment it arrives. */
    @Test
    fun `teardown completes even when the mailbox was completely full`() =
        withControllerManual(isLocalLeader = true) { leader, fakes, dispatcher ->
            leader.start()
            dispatcher.runAll()

            repeat(OVERFLOW_FLOOD_COUNT) { i -> leader.submit(VoiceSignal.Offer(genAt(OFFSET + i), SDP)) }
            repeat(FLOOD_COUNT) { i -> leader.submit(VoiceSignal.IceCandidate(genAt(1), "c$i", null, 0)) }
            repeat(FLOOD_COUNT) {
                leader.submit(VoiceSignal.State(genAt(1), VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS))
            }
            leader.stop()
            dispatcher.runAll()

            assertTrue(fakes.audio.calls.contains("close"), "teardown must complete even under a fully saturated mailbox")
        }

    /** A fresh voice session after a flood-induced degrade must start clean, not corrupted. */
    @Test
    fun `restarting after an overflow-induced degrade begins a genuinely fresh negotiation`() =
        withControllerManual(isLocalLeader = true) { leader, fakes, dispatcher ->
            leader.start()
            dispatcher.runAll()

            repeat(OVERFLOW_FLOOD_COUNT) { i -> leader.submit(VoiceSignal.Offer(genAt(OFFSET + i), SDP)) }
            dispatcher.runAll()
            assertTrue((leader.diagnostics.value.droppedSignals[VoiceSignalDropReason.INPUT_MAILBOX_OVERFLOW] ?: 0) > 0)

            leader.start()
            dispatcher.runAll()

            assertTrue(leader.diagnostics.value.status == VoiceStatus.NEGOTIATING, "a fresh Start Voice must still work after the degrade")
            assertTrue(leader.diagnostics.value.rebuildCount >= 1)
            assertTrue(
                fakes.engine.calls.contains("createOffer"),
                "the new negotiation must genuinely reach the engine, not just the state",
            )
        }

    /**
     * The one test that keeps a real dispatcher: [ManualDispatcher] proves the mailbox's *policy*
     * deterministically, but says nothing about whether [VoiceController.offer]'s lock is actually
     * safe when producers really do run concurrently. Several real threads submit at once; the only
     * claims are the ones that must hold regardless of how the race resolves — no crash, no lost
     * teardown, and the mailbox never reports more entries than either bound allows.
     */
    @Test
    fun `concurrent producers from real threads cannot corrupt or exceed the mailbox`() =
        withController(isLocalLeader = true) { leader, fakes ->
            leader.start()
            fakes.awaitEngineCall("createOffer")

            val threads =
                (0 until CONCURRENT_THREADS).map { t ->
                    Thread {
                        repeat(FLOOD_COUNT) { i ->
                            leader.submit(VoiceSignal.IceCandidate(genAt(1), "t$t-c$i", null, 0))
                        }
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join(TIMEOUT_MS) }
            leader.stop()

            withTimeout(TIMEOUT_MS) {
                while (!fakes.audio.calls.contains("close")) delay(POLL_MS)
            }
            assertTrue(
                leader.diagnostics.value.queuedCandidates <= VoiceBounds.MAX_QUEUED_CANDIDATES,
                "the bound must hold even when many real threads race to fill it",
            )
        }

    // --- harness ---------------------------------------------------------------------------------

    private class Fakes(
        val engine: FakeVoiceEngine,
        val audio: FakeVoiceAudioSession,
        val transport: RecordingVoiceTransport,
    ) {
        suspend fun awaitEngineCall(call: String) {
            withTimeout(TIMEOUT_MS) {
                while (!engine.calls.contains(call)) delay(POLL_MS)
            }
        }
    }

    /**
     * Queues every dispatch instead of running it. A coroutine launched on this dispatcher does not
     * execute a single line until [runAll] is called, which is what lets a test flood the mailbox
     * with a guarantee that nothing has been consumed yet.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        /** Runs every queued task, including ones a task queues while this call is still running. */
        fun runAll() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
                next?.run() ?: break
            }
        }
    }

    private fun withControllerManual(
        isLocalLeader: Boolean,
        body: (VoiceController, Fakes, ManualDispatcher) -> Unit,
    ) {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = FakeVoiceEngine()
        val audio = FakeVoiceAudioSession()
        val transport = RecordingVoiceTransport()
        // Local to this call, so the very first id -- the one `start()` installs as the live
        // generation -- is deterministically genAt(1) in every test, the way the manual emit()
        // calls below expect.
        val freshIds =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val controller =
            VoiceController(
                scope = scope,
                engine = engine,
                audioSession = audio,
                transport = transport,
                isLocalLeader = isLocalLeader,
                localTrackId = "ridelink-voice",
                newVoiceSessionId = { genAt(freshIds.incrementAndGet()) },
            )
        try {
            body(controller, Fakes(engine, audio, transport), dispatcher)
        } finally {
            scope.cancel()
        }
    }

    private fun withController(
        isLocalLeader: Boolean,
        body: suspend (VoiceController, Fakes) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val engine = FakeVoiceEngine()
            val audio = FakeVoiceAudioSession()
            val transport = RecordingVoiceTransport()
            val freshIds =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val controller =
                VoiceController(
                    scope = scope,
                    engine = engine,
                    audioSession = audio,
                    transport = transport,
                    isLocalLeader = isLocalLeader,
                    localTrackId = "ridelink-voice",
                    newVoiceSessionId = { genAt(freshIds.incrementAndGet()) },
                )
            body(controller, Fakes(engine, audio, transport))
            controller.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private fun assertEqualsInt(
        expected: Int,
        actual: Int,
        message: String,
    ) {
        assertTrue(expected == actual, "$message (expected $expected, was $actual)")
    }

    private companion object {
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        const val FLOOD_COUNT = 500
        const val OVERFLOW_FLOOD_COUNT = 200

        /** Well past VoiceInputMailbox.TERMINAL_PEER_STATE_CAPACITY (8); deterministic overflow. */
        const val TERMINAL_OVERFLOW_FLOOD_COUNT = 20
        const val CONCURRENT_THREADS = 8

        /** Well clear of anything [genAt] or a harness's own fresh-id counter could otherwise produce. */
        const val OFFSET = 10_000_000

        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L

        /** A distinct, deterministic 32-hex-digit id for test index [n] -- never random, never reused. */
        fun genAt(n: Int): VoiceSessionId = VoiceSessionId(n.toString().padStart(32, '0'))
    }
}
