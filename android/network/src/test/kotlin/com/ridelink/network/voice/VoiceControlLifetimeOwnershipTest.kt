package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.voice.VoiceSignalDropReason
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **STATUS §4 problem 61 — the *state* half of a control-lifetime boundary.**
 *
 * Problem 60 (ADR-020 Amendment A7) closed the queue half: a retired lifetime can no longer discard
 * or refuse a successor's **inputs**. This file is about what happens once a successor's input has
 * already been *reduced* — at which point it is no longer an input at all, but ordinary negotiation
 * state, with nothing on it to say which control lifetime it belongs to.
 *
 * The ordering needs no race and is production's own. `ControlEvent.LinkLost` reaches
 * `VoiceController` through `SessionCoordinator`'s event consumer, while
 * `ControlSessionManager.promote` authenticates a successor and admits its frames without waiting on
 * that consumer at all (`VoiceLifetimeProvenanceTest` pins exactly that over two real TLS sessions).
 * So a successor's `VOICE_OFFER` can be admitted, drained and applied while the predecessor's
 * boundary is still sitting unconsumed — and before ADR-020 Amendment A8, applying it then returned
 * the successor's live negotiation to `IDLE`.
 *
 * **Why the obvious fix is not the fix.** Suppressing a boundary that a newer generation appears to
 * have superseded was implemented, mirrored, tested and rejected, because *admission is not
 * application*: a successor's admitted offer can be dropped by `offerReceived`'s
 * `GENERATION_MISMATCH` against a still-live predecessor negotiation, so "a newer generation
 * admitted something" does not imply its negotiation is live. Suppressing on that premise leaves a
 * **dead** lifetime's negotiation standing, which then refuses every offer the successor sends.
 * Both orderings wedge; only an owner recorded on the state itself tells them apart. [`P61-B`] is
 * that ordering, and it is the test the suppression fails.
 *
 * [ManualDispatcher] makes "already reduced" a fact rather than a race a fast machine wins anyway.
 */
class VoiceControlLifetimeOwnershipTest {
    /**
     * **P61-A — the defect itself.** B authenticates, offers, and its offer is *fully reduced*; only
     * then does A's boundary arrive. B's negotiation must survive it untouched.
     */
    @Test
    fun `a delayed boundary for a predecessor cannot retire a successor's reduced negotiation`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            dispatcher.runAll()

            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()
            assertEquals(
                VoiceStatus.NEGOTIATING,
                answerer.diagnostics.value.status,
                "precondition: B's offer is not merely admitted but applied",
            )
            val callsBefore = fakes.engine.calls.toList()
            assertTrue(callsBefore.contains("applyRemote(OFFER)") && callsBefore.contains("createAnswer"))

            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertEquals(
                VoiceStatus.NEGOTIATING,
                answerer.diagnostics.value.status,
                "a predecessor's boundary may not retire the successor's negotiation; calls=$calls",
            )
            assertEquals(callsBefore, calls, "and may not touch the media transport at all; calls=$calls")
            assertEquals(
                1,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME],
                "the preserved boundary is surfaced, never silent",
            )
        }

    /**
     * **P61-B — the ordering that killed the naïve suppression, and the reason this fix is about
     * ownership rather than about which generation was seen last.**
     *
     * B is authenticated and its offer *is* admitted — but the reducer refuses it, because A's
     * negotiation is still live and names a different `voice_session_id`. B therefore never becomes
     * the owner, so A's boundary must still tear A's negotiation down. A rule keyed on "has a newer
     * generation been admitted?" cannot distinguish this from P61-A and leaves a dead lifetime's
     * negotiation standing forever.
     */
    @Test
    fun `a boundary still retires a predecessor a successor only ever tried to take over from`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)

            // B's own offer, admitted by a live lifetime and reduced — and refused, because A's
            // negotiation is live and names a different generation.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()
            assertEquals(
                1,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.GENERATION_MISMATCH],
                "precondition: B was admitted and then refused, so it never became the owner",
            )

            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertEquals(
                VoiceStatus.IDLE,
                answerer.diagnostics.value.status,
                "A still owns the negotiation, so A's boundary must still retire it; calls=$calls",
            )
            assertTrue(calls.contains("stop"), "and the media transport must actually stop; calls=$calls")
            assertEquals(
                null,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME],
                "nothing was superseded: admission is not application",
            )
            assertTrue(fakes.audio.isOpen, "capture survives a link loss (ARCHITECTURE §6.3/§6.4)")
        }

    /**
     * **P61-C — more than an A/B pair.** Two reconnects on, a boundary for the long-dead first
     * lifetime is no less inert. The rule is a comparison against the owner, not a memory of the
     * previous generation.
     */
    @Test
    fun `a boundary for a long-dead lifetime cannot retire a third generation's negotiation`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 2), SDP), CONTROL_C)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)

            // Drained one at a time on purpose: the TEARDOWN lane is a single latest-wins slot, so
            // offering both before either is polled would coalesce them and only ever test B's.
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            answerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertEquals(
                VoiceStatus.NEGOTIATING,
                answerer.diagnostics.value.status,
                "neither dead lifetime owns C's negotiation; calls=$calls",
            )
            assertFalse(calls.contains("stop"), "and neither may stop C's media; calls=$calls")
            assertEquals(
                2,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME],
                "and both were genuinely applied rather than coalescing into one; calls=$calls",
            )

            // ...and C's own boundary still works, which is what stops this being inertness.
            answerer.onControlLinkLost(CONTROL_C)
            dispatcher.runAll()
            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status)
            assertTrue(fakes.engine.calls.contains("stop"))
        }

    /**
     * **P61-D — the fix must not make link losses inert.** The owning lifetime's own boundary is
     * PROTOCOL §7.8 in full: media stops, capture stays, nothing is retried.
     */
    @Test
    fun `the owning lifetime's own boundary still performs the whole of PROTOCOL 7_8`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_B)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()
            val sentBefore = fakes.transport.sent.size

            answerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status, "calls=$calls")
            assertTrue(calls.contains("stop"), "media transport stops; calls=$calls")
            assertFalse(calls.contains("release"), "capture is NOT released by a link loss; calls=$calls")
            assertTrue(fakes.audio.isOpen, "and the audio session stays open for the ride segment")
            assertEquals(
                sentBefore,
                fakes.transport.sent.size,
                "nothing is sent on a link that is gone, and nothing is retried (§7.8)",
            )
            // Nothing follows the teardown: `VoiceNegotiation` never retries by itself, and §10's
            // control ladder is the app's only reconnect loop (§7.8). Asserted as "stop is the last
            // thing that happened" rather than by re-inspecting a prefix, which asserts nothing.
            assertEquals(
                "stop",
                calls.last(),
                "the teardown must be the last thing the engine was asked to do; calls=$calls",
            )
        }

    /**
     * **P61-E — a locally started negotiation is owned too.** The press carries the lifetime it was
     * authorised by, so a predecessor's boundary cannot retire it and its own can.
     *
     * The offerer is the side that matters here: a local Start is what authors its offer.
     */
    @Test
    fun `a locally started negotiation is owned by the lifetime that authorised the press`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start(CONTROL_B)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, offerer.diagnostics.value.status)
            val callsBefore = fakes.engine.calls.toList()

            offerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            assertEquals(
                VoiceStatus.NEGOTIATING,
                offerer.diagnostics.value.status,
                "a predecessor cannot retire a negotiation a later lifetime's press started",
            )
            assertEquals(callsBefore, fakes.engine.calls.toList(), "and touches nothing")

            offerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()
            assertEquals(
                VoiceStatus.IDLE,
                offerer.diagnostics.value.status,
                "its own lifetime's boundary still retires it",
            )
            assertTrue(fakes.engine.calls.contains("stop"))
            assertTrue(fakes.audio.isOpen, "capture still survives it")
        }

    /**
     * **P61-F — Start pressed in the gap between two control lifetimes.**
     *
     * A user can press Start Intercom after one link has died and before PROTOCOL §10's ladder has
     * restored the next. The press must not be refused — ARCHITECTURE §6.4 requires capture to be
     * opened while the app is foreground-visible, and this may be the last such moment — but it must
     * not create a negotiation either, because there is no link to negotiate over and, worse, no
     * lifetime to own one. A negotiation owned by nobody is the single state no boundary can retire.
     *
     * So the press records consent and stops there, and `SessionCoordinator.attachVoice` starts the
     * negotiation under the successor the moment one authenticates — which is exactly the rebuild it
     * already performs for any segment whose capture is open. Deterministic, and no wedge.
     */
    @Test
    fun `a Start pressed between two lifetimes opens capture and is rebuilt by the successor`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start(null)
            dispatcher.runAll()

            assertTrue(fakes.audio.isOpen, "consent opens capture while the app is still foreground-visible")
            assertTrue(offerer.diagnostics.value.localAudioOpen, "and the coordinator can see that it did")
            assertEquals(
                VoiceStatus.IDLE,
                offerer.diagnostics.value.status,
                "but no negotiation exists, because no lifetime could own one",
            )
            assertFalse(
                fakes.engine.calls.any { it == "createOffer" },
                "and nothing was offered on a link that is not there; calls=${fakes.engine.calls}",
            )
            assertTrue(fakes.transport.sent.isEmpty(), "nor sent: sent=${fakes.transport.sent}")

            // A boundary arriving in the gap finds nothing to retire and must not disturb consent.
            offerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            assertTrue(fakes.audio.isOpen, "a boundary in the gap may not close capture")

            // The ladder reconnects: `attachVoice` rebuilds voice for a segment whose capture is open.
            offerer.start(CONTROL_B)
            dispatcher.runAll()
            assertEquals(
                VoiceStatus.NEGOTIATING,
                offerer.diagnostics.value.status,
                "the successor rebuilds what the gap press could not start",
            )
            assertTrue(fakes.engine.calls.contains("createOffer"), "calls=${fakes.engine.calls}")

            // ...and it is genuinely the successor's, not a negotiation owned by nobody.
            offerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, offerer.diagnostics.value.status, "A cannot retire B's rebuild")
            offerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status, "B's own boundary can")
        }

    /**
     * A held remote offer is negotiation state too — it is what a later consent answers (§7.3) — so
     * it is owned by the lifetime that delivered it, and a predecessor's boundary may not discard it.
     *
     * This matters more than it looks: the held offer is the *only* copy. A peer never re-sends one,
     * so discarding a successor's held offer wedges voice for the ride segment exactly as problem 56
     * did.
     */
    @Test
    fun `a predecessor's boundary cannot discard a successor's held remote offer`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            // No local consent yet, so the offer is held rather than answered (ARCHITECTURE §6.4).
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()
            assertFalse(fakes.audio.isOpen, "precondition: a peer's offer never opens the microphone")

            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            // Consent arrives under the successor: the held offer must still be there to answer.
            answerer.start(CONTROL_B)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertTrue(calls.contains("applyRemote(OFFER)"), "the held offer survived and was answered; calls=$calls")
            assertTrue(calls.contains("createAnswer"), "calls=$calls")
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)
        }

    /**
     * The degrade `VoiceController` forces when a bounded lane overflows names **no** lifetime, and
     * must therefore retire whatever is live regardless of who owns it: it is a local safety valve,
     * not a statement about a control lifetime.
     *
     * Driven through the production input rather than by overflowing a lane, because what is under
     * test is the reducer's response to a `null` generation — the shape both the degrade and a
     * connection that died before authenticating produce.
     */
    @Test
    fun `a boundary naming no lifetime retires whatever is live`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 2), SDP), CONTROL_C)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)

            answerer.onControlLinkLost(null)
            dispatcher.runAll()

            assertEquals(
                VoiceStatus.IDLE,
                answerer.diagnostics.value.status,
                "the safe degrade has to work whoever owns the negotiation",
            )
            assertTrue(fakes.engine.calls.contains("stop"))
            assertTrue(fakes.audio.isOpen, "and it is still only a media teardown")
        }

    /**
     * Problem 60's mailbox rule, unchanged by all of the above: a retirement still discards the work
     * the retired lifetime queued and still preserves the successor's, in the same `offer` call.
     *
     * Kept here because the two halves are easy to confuse. The mailbox decides which *inputs* a
     * boundary owns; the table decides which *state* it owns. This fix adds the second and must not
     * weaken the first.
     */
    @Test
    fun `the mailbox still discards only the retired lifetime's queued work`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            dispatcher.runAll()

            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertTrue(
                calls.contains("start(${genAt(OFFER_ID + 1).value})"),
                "B's queued offer is still preserved and answered; calls=$calls",
            )
            assertFalse(
                calls.contains("start(${genAt(OFFER_ID).value})"),
                "A's queued offer is still discarded; calls=$calls",
            )
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)
        }

    /**
     * A mode change is not a lifetime event and must not become one: `ModeSelected` leaves the owner
     * exactly where it was, so a later predecessor boundary is still inert and the owner's is not.
     */
    @Test
    fun `an intercom mode change does not move negotiation ownership`() =
        withControllerManual(isLocalLeader = true) { offerer, _, dispatcher ->
            offerer.start(CONTROL_B)
            dispatcher.runAll()
            offerer.selectPolicy(IntercomPolicy.MODE_C)
            dispatcher.runAll()

            offerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, offerer.diagnostics.value.status)

            offerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status)
        }

    /** A peer's own `closed` returns the table to a state owning nothing, whoever owned it before. */
    @Test
    fun `a terminal peer state clears ownership along with the negotiation`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start(CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)

            answerer.submit(
                VoiceSignal.State(genAt(OFFER_ID + 1), VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS),
                CONTROL_B,
            )
            dispatcher.runAll()
            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status)
            assertTrue(fakes.engine.calls.contains("stop"))

            // Nothing is owned now, so a boundary for *any* lifetime is the pre-existing no-op.
            val callsBefore = fakes.engine.calls.toList()
            answerer.onControlLinkLost(CONTROL_B)
            dispatcher.runAll()
            assertEquals(callsBefore, fakes.engine.calls.toList())
            assertEquals(
                null,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME],
                "an empty table is a no-op, not a supersession",
            )
        }

    // --- harness ---------------------------------------------------------------------------------

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

    private fun withControllerManual(
        isLocalLeader: Boolean,
        body: (VoiceController, Fakes, ManualDispatcher) -> Unit,
    ) {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
        try {
            body(controller, Fakes(engine, audio, transport), dispatcher)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

        /** Far clear of the harness's own fresh-id counter, so a collision cannot mask a result. */
        const val OFFER_ID = 900

        /**
         * Three **control authentication** generations — a different identity from the
         * `voice_session_id`s above, and from each other only in being strictly increasing, which is
         * the one property `ControlSessionManager.activateAuthenticatedSession` guarantees.
         */
        const val CONTROL_A = 1L
        const val CONTROL_B = 2L
        const val CONTROL_C = 3L

        fun genAt(n: Int): VoiceSessionId = VoiceSessionId(n.toString().padStart(32, '0'))
    }
}
