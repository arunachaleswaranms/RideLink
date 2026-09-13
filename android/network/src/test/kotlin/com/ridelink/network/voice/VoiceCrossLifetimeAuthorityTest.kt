package com.ridelink.network.voice

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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **STATUS §4 problems 63 and 64 — the two ways work authorised by one control lifetime could still
 * reach another one's wire** (ADR-020 Amendment A9).
 *
 * Amendment A8 (problem 61) gave a *negotiation* an owner, so a predecessor's delayed boundary can
 * no longer retire a successor's reduced state. It did not ask the two questions this file asks,
 * and both have production paths:
 *
 * - **Problem 63 — a held remote offer could cross lifetimes.** A `VOICE_OFFER` admitted under A and
 *   held for want of local consent (§7.3) was answered by whatever `StartRequested` arrived next,
 *   and the answer branch then set the owner to the **press's** lifetime. Consent under a successor
 *   therefore adopted a dead lifetime's SDP, reused its `voice_session_id` — which the offerer had
 *   already discarded when *its* copy of that link died — and moved ownership to B, leaving A's own
 *   boundary inert. PROTOCOL §7.8 wants a reconnect to rebuild voice as a **fresh** negotiation; this
 *   was the one path that quietly did the opposite.
 *
 * - **Problem 64 — an authorised send could be written on a successor's socket.** Everything between
 *   the press and the write suspends: the mailbox's single consumer, `createOffer`'s engine
 *   callback, the dispatcher hop, the write lock, the flush. `VoiceSignalRelay.send` asked for "the
 *   authenticated writer" at the moment of the *write*, so a `VOICE_OFFER` authorised by A was
 *   written to B's connection, where the peer accepted it as current — and A's boundary, arriving
 *   afterwards, then tore this side's media down while the peer was still negotiating.
 *
 * Neither is a race. [ManualDispatcher] makes "already reduced" and "drained after the successor
 * authenticated" facts rather than orderings a fast machine happens to win, and [liveWire] is
 * production's own rule — a frame is written to the connection its authorising lifetime owns, or to
 * none at all.
 */
class VoiceCrossLifetimeAuthorityTest {
    // --- problem 63: a held offer may not cross a control lifetime ------------------------------

    /**
     * **P63-A — the defect itself, and the whole of the recovery after it.**
     *
     * A's offer is held (no consent yet). A dies, B authenticates, and — before A's delayed boundary
     * is consumed — this user taps Start. A's SDP must not be applied, A's `voice_session_id` must
     * not be answered, and what goes out on B must be §7.3's intent-to-talk. B's own fresh offer is
     * then answered normally, under a `voice_session_id` that is not A's.
     */
    @Test
    fun `a held offer from a predecessor is never answered by a successor's consent`() =
        withController(isLocalLeader = false) { answerer, fakes, dispatcher, live ->
            answerer.submit(VoiceSignal.Offer(genAt(A_OFFER), SDP_A), CONTROL_A)
            dispatcher.runAll()
            assertFalse(fakes.audio.isOpen, "precondition: a peer's offer never opens the microphone")
            assertTrue(fakes.engine.calls.isEmpty(), "precondition: the offer is held, not applied")

            // A dies; B authenticates; A's `ControlLinkLost` has not been consumed yet.
            live.set(CONTROL_B)
            answerer.start(CONTROL_B)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertFalse(calls.contains("applyRemote(OFFER)"), "a dead lifetime's SDP was applied; calls=$calls")
            assertFalse(calls.contains("createAnswer"), "and answered; calls=$calls")
            assertTrue(
                fakes.transport.sent.none { it is VoiceSignal.Answer },
                "no answer may go out at all; sent=${fakes.transport.sent}",
            )
            assertTrue(
                fakes.transport.sent.none { it.namesSession(genAt(A_OFFER)) },
                "and nothing may name the retired lifetime's voice_session_id; sent=${fakes.transport.sent}",
            )
            assertEquals(
                VoiceSignal.State(null, VoiceWireState.NEGOTIATING, false, MODE),
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .first(),
                "§7.3's intent-to-talk is the answerer's whole negotiation effect here",
            )
            assertTrue(
                fakes.transport.sentGenerations.all { it == CONTROL_B },
                "and everything written went out on B; generations=${fakes.transport.sentGenerations}",
            )
            assertEquals(
                1,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.RETIRED_HELD_OFFER],
                "the discarded held offer is surfaced, never silent",
            )
            assertEquals(1, fakes.audio.openCaptureCount, "consent still opened capture")
            assertEquals(0, fakes.audio.closeCaptureCount, "and never closed it")

            // A's delayed boundary finally arrives. B owns the intent, so it is inert.
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)

            // The offerer, rebuilt under B, offers again. A fresh generation, answered normally.
            answerer.submit(VoiceSignal.Offer(genAt(B_OFFER), SDP_B), CONTROL_B)
            dispatcher.runAll()
            answerer.emitAnswer(fakes, genAt(B_OFFER))
            dispatcher.runAll()

            val answer =
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.Answer>()
                    .single()
            assertEquals(genAt(B_OFFER), answer.voiceSessionId, "B's rebuild answers B's own fresh generation")
            assertEquals(VoiceStatus.CONNECTING, answerer.diagnostics.value.status)
            assertEquals(1, fakes.audio.openCaptureCount, "and capture was never reopened across any of it")
            assertEquals(0, fakes.audio.closeCaptureCount)
        }

    /**
     * **P63-B — the opposite ordering, which the same rule has to get right in the other direction.**
     *
     * The press is the stale thing: the user tapped Start while A was live, the tap sat in the
     * mailbox, and B's offer was admitted and reduced first. Answering B's offer *under A* would send
     * an answer no link could carry and destroy the only copy of that offer. Consent is honoured;
     * the negotiation is not started; B's held offer survives for B's own consent to answer.
     */
    @Test
    fun `a start authorised by a retired lifetime keeps a newer lifetime's held offer intact`() =
        withController(isLocalLeader = false) { answerer, fakes, dispatcher, live ->
            answerer.submit(VoiceSignal.Offer(genAt(B_OFFER), SDP_B), CONTROL_B)
            dispatcher.runAll()

            // The tap was authorised by A and is only now drained. B is live.
            live.set(CONTROL_B)
            answerer.start(CONTROL_A)
            dispatcher.runAll()

            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status, "a dead lifetime starts no negotiation")
            assertTrue(fakes.transport.sent.isEmpty(), "and sends nothing; sent=${fakes.transport.sent}")
            // The capture gate's own `setMicrophoneMuted` is not a negotiation effect: opening the
            // device is what the consent *did*, and ADR-021 §4 routes the gate's absolute value
            // through the table on every intercom input. Nothing that touches WebRTC may appear.
            assertTrue(
                fakes.engine.calls.none { it.startsWith("start(") || it.startsWith("applyRemote") || it == "createAnswer" },
                "and touches no media; calls=${fakes.engine.calls}",
            )
            assertEquals(1, fakes.audio.openCaptureCount, "but consent is still consent (ARCHITECTURE §6.4)")
            assertEquals(
                1,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.SUPERSEDED_START_LIFETIME],
                "and the refusal is surfaced",
            )

            // B's own consent answers B's own held offer — the only copy, still there.
            answerer.start(CONTROL_B)
            dispatcher.runAll()
            val calls = fakes.engine.calls.toList()
            assertTrue(calls.contains("applyRemote(OFFER)"), "calls=$calls")
            assertTrue(calls.contains("createAnswer"), "calls=$calls")
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)
            assertEquals(1, fakes.audio.openCaptureCount, "capture opened once across both presses")
        }

    /** A held offer answered by **its own** lifetime's consent is untouched — A8's behaviour, kept. */
    @Test
    fun `a held offer is still answered by the lifetime that delivered it`() =
        withController(isLocalLeader = false) { answerer, fakes, dispatcher, _ ->
            answerer.submit(VoiceSignal.Offer(genAt(A_OFFER), SDP_A), CONTROL_A)
            dispatcher.runAll()
            answerer.start(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertTrue(calls.contains("applyRemote(OFFER)"), "calls=$calls")
            assertTrue(calls.contains("createAnswer"), "calls=$calls")
            assertNull(
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.RETIRED_HELD_OFFER],
                "nothing was discarded",
            )
        }

    // --- problem 64: an authorised send may not be written on a successor's socket ---------------

    /**
     * **P64-A — the defect itself, offerer side.** The press is authorised by A and drained after B
     * has authenticated. Every frame it produces names A, so every one is refused; the negotiation
     * degrades through `NegotiationSendFailed`; and **nothing reaches B**.
     */
    @Test
    fun `an offerer's start authorised by a retired lifetime puts nothing on the successor's wire`() =
        withController(isLocalLeader = true) { offerer, fakes, dispatcher, live ->
            offerer.start(CONTROL_A)
            // A dies and B authenticates before the queued press is ever consumed.
            live.set(CONTROL_B)
            dispatcher.runAll()
            offerer.emitOffer(fakes, genAt(FRESH))
            dispatcher.runAll()

            assertTrue(fakes.transport.sent.isEmpty(), "A's work reached B's wire; sent=${fakes.transport.sent}")
            assertTrue(
                fakes.transport.attempted.all { it.second == CONTROL_A },
                "every attempt named its own authorising lifetime; attempted=${fakes.transport.attempted}",
            )
            assertTrue(
                fakes.transport.attempted.any { it.first is VoiceSignal.Offer },
                "the offer was attempted and refused, not silently skipped",
            )
            assertEquals(
                VoiceStatus.IDLE,
                offerer.diagnostics.value.status,
                "a negotiation whose offer cannot be placed degrades rather than wedging",
            )
            assertTrue(fakes.engine.calls.contains("stop"), "media goes")
            assertEquals(1, fakes.audio.openCaptureCount, "capture does not (ARCHITECTURE §6.3/§6.4)")
            assertEquals(0, fakes.audio.closeCaptureCount)

            // And the rebuild under B is a **fresh** negotiation, on B's wire.
            offerer.start(CONTROL_B)
            dispatcher.runAll()
            offerer.emitOffer(fakes, genAt(FRESH + 1))
            dispatcher.runAll()

            val offer =
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.Offer>()
                    .single()
            assertEquals(genAt(FRESH + 1), offer.voiceSessionId, "B gets a fresh voice_session_id")
            assertTrue(
                fakes.transport.sentGenerations.all { it == CONTROL_B },
                "and everything written went out on B; generations=${fakes.transport.sentGenerations}",
            )
            assertEquals(1, fakes.audio.openCaptureCount, "capture was never reopened")
        }

    /**
     * **P64-B — the answerer's half**, which problem 59 already showed is the one `VOICE_STATE` no
     * later one replaces. Its refusal has to degrade, or the table sits in `NEGOTIATING` forever and
     * `attachVoice`'s rebuild finds a live negotiation and does nothing.
     */
    @Test
    fun `an answerer's intent-to-talk authorised by a retired lifetime degrades rather than wedging`() =
        withController(isLocalLeader = false) { answerer, fakes, dispatcher, live ->
            answerer.start(CONTROL_A)
            live.set(CONTROL_B)
            dispatcher.runAll()

            assertTrue(fakes.transport.sent.isEmpty(), "sent=${fakes.transport.sent}")
            assertEquals(
                listOf<Long?>(CONTROL_A),
                fakes.transport.attempted
                    .filter { it.first is VoiceSignal.State }
                    .map { it.second },
                "the intent was attempted under A and refused",
            )
            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status, "problem 59's wedge stays closed")
            assertEquals(1, fakes.audio.openCaptureCount)

            answerer.start(CONTROL_B)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)
            assertEquals(listOf<Long?>(CONTROL_B), fakes.transport.sentGenerations, "B's intent goes out on B")
        }

    /**
     * **P64-C — the send parked at the transport, released after the boundary.** This is the shape
     * problem 57 established: name the instant rather than race for it. The offer is suspended
     * *inside* `perform`, the lifetime is replaced underneath it, and the write then fails closed.
     *
     * It also pins the two things that failure must **not** do: speak as `ControlLinkLost` (which
     * would discard a successor's queued work and erase a pending stop, problems 57 and 59), and
     * displace the `StopRequested` waiting in the one-slot teardown lane.
     */
    @Test
    fun `a send parked across a lifetime boundary fails closed without speaking for the lifetime`() =
        withController(isLocalLeader = true) { offerer, fakes, dispatcher, live ->
            offerer.start(CONTROL_A)
            dispatcher.runAll()
            assertTrue(
                fakes.transport.sentGenerations.isNotEmpty() && fakes.transport.sentGenerations.all { it == CONTROL_A },
                "A's negotiating state went out on A; generations=${fakes.transport.sentGenerations}",
            )

            fakes.transport.parkWhen = { it is VoiceSignal.Offer }
            offerer.emitOffer(fakes, genAt(FRESH))
            dispatcher.runAll()
            assertTrue(fakes.transport.parked, "the offer's write is suspended, which is the instant under test")

            // The lifetime is replaced while the write is parked, and a stop is queued behind it.
            live.set(CONTROL_B)
            offerer.stop()
            fakes.transport.release(true)
            dispatcher.runAll()

            assertTrue(
                fakes.transport.sent.none { it is VoiceSignal.Offer },
                "the parked offer must not land on the successor even though the write itself succeeded",
            )
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status)
            assertTrue(fakes.engine.calls.contains("release"), "the queued StopRequested was never erased")
            assertEquals(1, fakes.audio.closeCaptureCount, "a deliberate stop is the one thing that may close capture")
        }

    /**
     * **P64-D — an engine continuation created by A cannot produce a B-authorised wire effect.**
     * `createOffer` was dispatched under A; its callback resumes after B is live. The
     * `voice_session_id` guard alone would have let it through — the negotiation it names *is* still
     * the live one — so what refuses it is the owner the `SendOffer` carries.
     */
    @Test
    fun `a local offer callback from a retired lifetime cannot be sent on the successor`() =
        withController(isLocalLeader = true) { offerer, fakes, dispatcher, live ->
            offerer.start(CONTROL_A)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, offerer.diagnostics.value.status)
            val before = fakes.transport.sent.size

            // The engine answers late: A is gone, B is live, and A's boundary has not arrived.
            live.set(CONTROL_B)
            offerer.emitOffer(fakes, genAt(FRESH))
            dispatcher.runAll()

            assertEquals(before, fakes.transport.sent.size, "sent=${fakes.transport.sent}")
            assertEquals(
                CONTROL_A,
                fakes.transport.attempted
                    .last { it.first is VoiceSignal.Offer }
                    .second,
                "the offer named A, which is the only lifetime entitled to carry it",
            )
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status, "and its loss degrades the negotiation")
        }

    /**
     * **P64-E — a refused send degrades, and the rebuild that follows is clean.**
     *
     * A's offer is parked at the transport, the lifetime is replaced underneath it, and the write is
     * then refused. What follows is the whole recovery: the degrade, a boundary that finds nothing
     * left to retire, and a rebuild under B with a **fresh** `voice_session_id`.
     *
     * **What this test deliberately does *not* claim**, because building it proved the ordering
     * unreachable: a stale `NegotiationSendFailed` cannot be applied *after* a successor's rebuild
     * has been reduced. `VoiceMailboxLane.SEND_FAILURE` outranks `VoiceMailboxLane.CRITICAL` by
     * design (problem 57), and the controller has one consumer — which is parked inside `perform`
     * for as long as the write is — so the failure is always reduced **before** any rebuild queued
     * behind it. The reducer's guard against the other ordering is real and is pinned where it
     * belongs, in `protocol/vectors/voice-fsm/`'s
     * `negotiation-send-failed-from-a-retired-generation-is-inert`; asserting it here would have been
     * a test of a state no production ordering can produce.
     */
    @Test
    fun `a refused send degrades and the successor's rebuild is clean`() =
        withController(isLocalLeader = true) { offerer, fakes, dispatcher, live ->
            offerer.start(CONTROL_A)
            dispatcher.runAll()
            fakes.transport.parkWhen = { it is VoiceSignal.Offer }
            offerer.emitOffer(fakes, genAt(FRESH))
            dispatcher.runAll()
            assertTrue(fakes.transport.parked, "the offer's write is suspended, which is the instant under test")

            // The lifetime is replaced while the write is parked; the write then reports.
            live.set(CONTROL_B)
            fakes.transport.release(true)
            dispatcher.runAll()
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status, "the refused offer degraded the table")
            assertTrue(fakes.engine.calls.contains("stop"))

            // A's boundary arrives to find nothing left to retire, and B rebuilds fresh.
            offerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            offerer.start(CONTROL_B)
            dispatcher.runAll()
            offerer.emitOffer(fakes, genAt(FRESH + 1))
            dispatcher.runAll()

            assertEquals(
                VoiceStatus.NEGOTIATING,
                offerer.diagnostics.value.status,
                "B's rebuild must survive everything A left behind",
            )
            val offers = fakes.transport.sent.filterIsInstance<VoiceSignal.Offer>()
            assertEquals(listOf(genAt(FRESH + 1)), offers.map { it.voiceSessionId }, "only B's offer was ever written")
            // A's own `negotiating` state did go out, on A's link, while A was live -- that is correct
            // and is not what is under test. What may never appear is an SDP authorised by A at all.
            assertTrue(
                fakes.transport.sentGenerations.zip(fakes.transport.sent).none { (gen, signal) ->
                    gen == CONTROL_A && (signal is VoiceSignal.Offer || signal is VoiceSignal.Answer)
                },
                "an SDP authorised by A was written; generations=${fakes.transport.sentGenerations}",
            )
            assertEquals(1, fakes.audio.openCaptureCount)
            assertEquals(0, fakes.audio.closeCaptureCount)
        }

    // --- harness ---------------------------------------------------------------------------------

    private fun VoiceSignal.namesSession(id: VoiceSessionId): Boolean =
        when (this) {
            is VoiceSignal.Offer -> voiceSessionId == id
            is VoiceSignal.Answer -> voiceSessionId == id
            is VoiceSignal.IceCandidate -> voiceSessionId == id
            is VoiceSignal.State -> voiceSessionId == id
        }

    private fun VoiceController.emitOffer(
        fakes: Fakes,
        id: VoiceSessionId,
    ) = fakes.engine.emit(VoiceEngineEvent.OfferCreated(id, SDP_A))

    private fun VoiceController.emitAnswer(
        fakes: Fakes,
        id: VoiceSessionId,
    ) = fakes.engine.emit(VoiceEngineEvent.AnswerCreated(id, SDP_B))

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
                next?.run() ?: break
            }
        }
    }

    private class Fakes(
        val engine: FakeVoiceEngine,
        val audio: FakeVoiceAudioSession,
        val transport: RecordingVoiceTransport,
    )

    /**
     * A controller whose transport enforces production's outbound rule: a frame is written to the
     * connection its authorising lifetime owns, or to none at all. [liveWire] is what a test moves to
     * say "A ended and B authenticated" — the one fact `ControlSessionManager` would supply.
     */
    private fun withController(
        isLocalLeader: Boolean,
        body: (VoiceController, Fakes, ManualDispatcher, AtomicLong) -> Unit,
    ) {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = FakeVoiceEngine()
        val audio = FakeVoiceAudioSession()
        val liveWire = AtomicLong(CONTROL_A)
        val transport = RecordingVoiceTransport().apply { liveGeneration = { liveWire.get() } }
        val fresh =
            java.util.concurrent.atomic
                .AtomicInteger(FRESH - 1)
        val controller =
            VoiceController(
                scope = scope,
                engine = engine,
                audioSession = audio,
                transport = transport,
                isLocalLeader = isLocalLeader,
                localTrackId = "ridelink-voice",
                newVoiceSessionId = { genAt(fresh.incrementAndGet()) },
            )
        try {
            body(controller, Fakes(engine, audio, transport), dispatcher, liveWire)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val SDP_A = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=x:A\r\n"
        const val SDP_B = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=x:B\r\n"

        val MODE = com.ridelink.core.protocol.VoiceMode.CONTINUOUS

        const val A_OFFER = 910
        const val B_OFFER = 920
        const val FRESH = 930

        const val CONTROL_A = 1L
        const val CONTROL_B = 2L

        fun genAt(n: Int): VoiceSessionId = VoiceSessionId(n.toString().padStart(32, '0'))
    }
}
