package com.ridelink.network.voice

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
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 50 — semantic `VOICE_*` work that was admitted by one control lifetime, and is
 * still queued when that lifetime ends.
 *
 * This is **not** ADR-025 frame provenance. Every frame here was read while its control generation
 * was genuinely live, and `VoiceSignalRelay` was right to admit it. The question this file settles
 * is the one that comes *after* admission: once `ControlLinkLost` has been applied, may semantic
 * work that the retired lifetime queued still **begin or advance** a voice negotiation?
 *
 * The interleaving is reachable because [VoiceController.offer] is non-blocking and its consumer is
 * a suspending coroutine: a `VOICE_*` frame can be queued while the consumer is mid-effect, the
 * control link can drop immediately after, and [com.ridelink.core.voice.VoiceInputMailbox] then
 * quite deliberately drains `TEARDOWN` **first**. [ManualDispatcher] makes that ordering exact
 * rather than a race a fast machine wins either way.
 */
class VoiceControllerLinkLossOrderingTest {
    /**
     * P50-A — a remote `VOICE_OFFER` queued before the link is lost.
     *
     * The answerer is the side that may legally receive an offer, so it is the side where this
     * matters. After `ControlLinkLost` has stopped the media transport, the stale offer must not
     * rebuild the peer connection or answer a peer that is no longer reachable.
     */
    @Test
    fun `a VOICE_OFFER queued before ControlLinkLost cannot begin a negotiation after it`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()
            assertTrue(answerer.diagnostics.value.localAudioOpen, "the answerer's capture must be open for this to be the real case")

            // Admitted while the control lifetime was genuinely live (ADR-025 is satisfied), but
            // not yet drained by the consumer.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP))
            // ...and now that lifetime ends. TEARDOWN outranks CRITICAL, so this applies first.
            answerer.onControlLinkLost()
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            val stopAt = calls.indexOf("stop")
            assertTrue(stopAt >= 0, "ControlLinkLost must have stopped the media transport; calls=$calls")
            val afterTeardown = calls.drop(stopAt + 1)

            assertFalse(
                afterTeardown.any { it.startsWith("start(") },
                "a retired lifetime's offer must not rebuild the peer connection; after stop=$afterTeardown",
            )
            assertFalse(
                afterTeardown.contains("applyRemote(OFFER)"),
                "a retired lifetime's offer must not be applied; after stop=$afterTeardown",
            )
            assertFalse(
                afterTeardown.contains("createAnswer"),
                "a retired lifetime's offer must not be answered; after stop=$afterTeardown",
            )
            assertEquals(
                VoiceStatus.IDLE,
                answerer.diagnostics.value.status,
                "the controller must not believe it is negotiating with a peer it has no link to",
            )
            assertTrue(
                fakes.transport.sent.none { it is VoiceSignal.Answer },
                "no answer may be produced for a retired lifetime's offer",
            )
        }

    /**
     * P50-E — the same question for the coalesced lane. A peer `VOICE_STATE { negotiating }` is
     * §7.3's intent-to-talk, and on the **offerer** it starts a whole negotiation of its own. It
     * drains below `TEARDOWN` exactly as the offer does.
     */
    @Test
    fun `a peer negotiating intent queued before ControlLinkLost cannot start a negotiation after it`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            assertTrue(offerer.diagnostics.value.localAudioOpen)
            val beforeStarts = fakes.engine.calls.count { it.startsWith("start(") }

            offerer.submit(VoiceSignal.State(null, VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS))
            offerer.onControlLinkLost()
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            val stopAt = calls.indexOf("stop")
            assertTrue(stopAt >= 0, "ControlLinkLost must have stopped the media transport; calls=$calls")
            val afterTeardown = calls.drop(stopAt + 1)

            assertFalse(
                afterTeardown.any { it.startsWith("start(") },
                "a retired lifetime's peer intent must not rebuild the peer connection; after stop=$afterTeardown",
            )
            assertFalse(
                afterTeardown.contains("createOffer"),
                "a retired lifetime's peer intent must not create an offer; after stop=$afterTeardown",
            )
            assertEquals(
                VoiceStatus.IDLE,
                offerer.diagnostics.value.status,
                "the controller must not believe it is negotiating with a peer it has no link to",
            )
            assertTrue(beforeStarts >= 0)
        }

    /**
     * P50-B — the answer lane, which the existing generation guard already covers. Kept as a
     * regression so a future change to the offer rule cannot quietly weaken this one: an answer
     * names a generation, and after teardown there is no generation for it to name.
     */
    @Test
    fun `a VOICE_ANSWER queued before ControlLinkLost cannot advance a retired negotiation`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            assertEquals(VoiceStatus.NEGOTIATING, offerer.diagnostics.value.status)
            // The harness's first fresh id is the one `start()` installed as the live generation.
            val liveId = genAt(1)

            offerer.submit(VoiceSignal.Answer(liveId, SDP))
            offerer.onControlLinkLost()
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            val afterTeardown = calls.drop(calls.indexOf("stop") + 1)
            assertFalse(
                afterTeardown.contains("applyRemote(ANSWER)"),
                "a retired lifetime's answer must not be applied; after stop=$afterTeardown",
            )
            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status)
        }

    /** P50-C — the ICE lane. The existing `voiceSessionId` guard is claimed to make this inert. */
    @Test
    fun `a VOICE_ICE queued before ControlLinkLost cannot reach the engine after it`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            val liveId = genAt(1)

            offerer.submit(VoiceSignal.IceCandidate(liveId, "candidate:1 typ host", null, 0))
            offerer.onControlLinkLost()
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            val afterTeardown = calls.drop(calls.indexOf("stop") + 1)
            assertFalse(
                afterTeardown.any { it.startsWith("addRemoteCandidate") },
                "a retired lifetime's candidate must not reach the engine; after stop=$afterTeardown",
            )
        }

    /** P50-D — a terminal peer state around the boundary must not resurrect or mis-tear anything. */
    @Test
    fun `a terminal VOICE_STATE queued before ControlLinkLost leaves the controller idle`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            val liveId = genAt(1)

            offerer.submit(VoiceSignal.State(liveId, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS))
            offerer.onControlLinkLost()
            dispatcher.runAll()

            assertEquals(VoiceStatus.IDLE, offerer.diagnostics.value.status)
            val calls = fakes.engine.calls.toList()
            val afterTeardown = calls.drop(calls.indexOf("stop") + 1)
            assertFalse(
                afterTeardown.any { it.startsWith("start(") },
                "nothing may rebuild the peer connection after teardown; after stop=$afterTeardown",
            )
        }

    /**
     * The other half of the invariant, and the one that stops the fix from being "refuse everything":
     * a genuinely fresh offer, arriving after the control link is back, is still answered normally.
     */
    @Test
    fun `a fresh VOICE_OFFER after the link is restored is still answered`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            val openedBefore = fakes.audio.openCaptureCount
            val closedBefore = fakes.audio.closeCaptureCount

            // PROTOCOL §7.8: the control ladder reconnected and voice is rebuilt. The offer below is
            // the **successor** lifetime's, so it names `CONTROL_B` — that is what distinguishes it
            // from the retired one above, and saying so is the whole of STATUS §4 problem 60.
            answerer.start()
            dispatcher.runAll()
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            dispatcher.runAll()

            assertTrue(
                fakes.engine.calls.contains("applyRemote(OFFER)"),
                "a fresh offer after reconnect must still be applied; calls=${fakes.engine.calls}",
            )
            assertTrue(fakes.engine.calls.contains("createAnswer"), "a fresh offer after reconnect must still be answered")
            assertEquals(
                openedBefore,
                fakes.audio.openCaptureCount,
                "an ordinary control-link blip must not reopen the capture device (ARCHITECTURE §6.3/§6.4)",
            )
            assertEquals(
                closedBefore,
                fakes.audio.closeCaptureCount,
                "an ordinary control-link blip must not close the capture device (ARCHITECTURE §6.3/§6.4)",
            )
        }

    /**
     * A second, distinct defect found while tracing problem 50, and it needs **no scheduling race
     * at all** — see STATUS §4 problem 56.
     *
     * `VoiceController.perform` discards the `Boolean` that `VoiceSignalTransport.send` returns. So
     * an offer created while the control link is down is "sent" into a `null` writer, the send
     * silently fails, and the table still advances to `NEGOTIATING`. `VoiceNegotiation.start` is
     * idempotent against a live negotiation — deliberately, so two Start presses make one offer —
     * so when `SessionCoordinator.attachVoice` rebuilds voice on the reconnect, its `start()` is a
     * **no-op**. The peer never sees an offer, its own `negotiating` intent hits the same
     * idempotence on the way back, and voice is wedged for the rest of the ride segment.
     */
    @Test
    fun `an offer that could not be sent does not wedge voice for the rest of the segment`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(1), SDP))
            dispatcher.runAll()
            assertTrue(fakes.transport.sent.any { it is VoiceSignal.Offer }, "the healthy case must really send an offer")

            offerer.onControlLinkLost()
            dispatcher.runAll()

            // The link is down: `VoiceSignalRelay.send` finds no authenticated writer and returns
            // false. The user presses Start Voice again while the ladder is still reconnecting.
            fakes.transport.accept = false
            offerer.start()
            dispatcher.runAll()
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(2), SDP))
            dispatcher.runAll()

            // The ladder reconnects. `attachVoice` rebuilds voice as a fresh negotiation (§7.8).
            fakes.transport.accept = true
            val offersBefore = fakes.transport.sent.count { it is VoiceSignal.Offer }
            offerer.start()
            dispatcher.runAll()
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(3), SDP))
            dispatcher.runAll()

            assertTrue(
                fakes.transport.sent.count { it is VoiceSignal.Offer } > offersBefore,
                "after a reconnect the rebuild must put a new offer on the wire; sent=${fakes.transport.sent.map { it.kindName() }}",
            )
        }

    /**
     * Problem 55 reached the other way — the interleaving that first exposed it. A user presses
     * Start Voice at the moment the link drops, so `StartRequested` and `ControlLinkLost` are queued
     * together and the teardown lane applies the link loss first. `controlLinkLost` is a no-op from
     * `IDLE`, so the Start then builds a negotiation belonging to a lifetime that has already gone,
     * with a transport that can no longer carry its offer.
     */
    @Test
    fun `a Start pressed as the link drops still leaves voice rebuildable after reconnect`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            // The link is already gone by the time either of these is applied.
            fakes.transport.accept = false
            offerer.start()
            offerer.onControlLinkLost()
            dispatcher.runAll()
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(1), SDP))
            dispatcher.runAll()

            fakes.transport.accept = true
            val offersBefore = fakes.transport.sent.count { it is VoiceSignal.Offer }
            offerer.start()
            dispatcher.runAll()
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(2), SDP))
            dispatcher.runAll()

            assertTrue(
                fakes.transport.sent.count { it is VoiceSignal.Offer } > offersBefore,
                "after a reconnect the rebuild must put a new offer on the wire; sent=${fakes.transport.sent.map { it.kindName() }}",
            )
            assertTrue(
                fakes.audio.closeCaptureCount == 0,
                "no part of this degrade may close the capture device (ARCHITECTURE §6.3/§6.4)",
            )
        }

    /**
     * A-1 — STATUS §4 problem 57. **A send that failed is not a control lifetime that ended.**
     *
     * Problem 56's fix turned `transport.send(...) == false` into `VoiceInput.ControlLinkLost`,
     * which is the input problem 50 gave *lifetime-boundary* semantics: offering it discards every
     * queued `SignalReceived`, because the lifetime that admitted them has gone. A send failure is
     * not that event. `VoiceSignalRelay.send` suspends — `withContext(ioDispatcher)`, then
     * `ControlSocket.writeFrame`'s write lock and `flush()` — and reports `false` for a write that
     * threw, so its `Boolean` can arrive after the §10 ladder has already authenticated a
     * **successor** generation and that successor's own `VOICE_OFFER` has been admitted.
     *
     * The consumer is the single thread that both parks inside `perform` and runs `degradeIfUnsent`
     * on resume, so the successor's frame is necessarily already queued when the degrade offers its
     * teardown — no race is needed, only a send that outlives its lifetime.
     */
    @Test
    fun `a send failing after a successor lifetime is live must not discard the successor's queued offer`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()
            assertTrue(answerer.diagnostics.value.localAudioOpen)

            // Lifetime A: the peer offered and this side is answering.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            dispatcher.runAll()
            fakes.transport.parkWhen = { it is VoiceSignal.Answer }
            fakes.engine.emit(VoiceEngineEvent.AnswerCreated(genAt(OFFER_ID), SDP))
            dispatcher.runAll()
            assertTrue(fakes.transport.parked, "the answer's write must really be in flight for this to be the case")

            // Lifetime A ends. The consumer is parked, so nothing drains yet.
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            // The ladder reconnects, lifetime B authenticates, and B's peer offers. `VoiceSignalRelay`
            // admitted this frame against a live generation, so ADR-025 is satisfied: it is genuinely
            // the successor's work — and since problem 60 it *says* so, rather than being
            // indistinguishable from A's.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)

            // Only now does lifetime A's write report that it failed.
            fakes.transport.release(false)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            val afterTeardown = calls.drop(calls.lastIndexOf("stop") + 1)
            assertTrue(
                afterTeardown.contains("applyRemote(OFFER)"),
                "the successor lifetime's offer must survive a retired lifetime's send failure; after stop=$afterTeardown",
            )
            assertTrue(
                afterTeardown.contains("createAnswer"),
                "the successor lifetime's offer must still be answered; after stop=$afterTeardown",
            )
            assertEquals(
                VoiceStatus.NEGOTIATING,
                answerer.diagnostics.value.status,
                "the successor's negotiation must be live; a retired send failure may not retire it",
            )
            assertTrue(
                afterTeardown.contains("start(${genAt(OFFER_ID + 1).value})"),
                "the rebuilt peer connection must belong to the successor's generation; after stop=$afterTeardown",
            )
        }

    /**
     * A-2 — the same problem reaching rule 21 rather than voice. [VoiceMailboxLane.TEARDOWN] is one
     * slot, latest wins, so a degrade offered from the consumer's own resume **replaces** a
     * `StopRequested` that `shutdown()`/`stopAndAwaitRelease()` is waiting on. Nothing then ever
     * applies that stop: capture is never released, `pendingStopCompletions` is never resolved, and
     * `SessionCoordinator.retireSession` — which awaits `shutdown()` with no timeout of its own by
     * design (ADR-021 Amendment A4) — can never emit `TeardownComplete`, so the session can never
     * reach `IDLE` (ADR-026).
     */
    @Test
    fun `a send failing while a stop is pending must not erase the stop`() =
        withControllerManual(isLocalLeader = true) { offerer, fakes, dispatcher ->
            offerer.start()
            dispatcher.runAll()
            fakes.transport.parkWhen = { it is VoiceSignal.Offer }
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(genAt(1), SDP))
            dispatcher.runAll()
            assertTrue(fakes.transport.parked)

            // The ride is ending: `retireSession` asks for the release it must prove happened.
            offerer.stop()

            fakes.transport.release(false)
            dispatcher.runAll()

            assertEquals(
                1,
                fakes.audio.closeCaptureCount,
                "the pending StopRequested must still be applied; a failed send may not replace it",
            )
        }

    /**
     * P56-1 from the **answerer's** side — STATUS §4 problem 59, and the half of problem 56 its own
     * fix left open.
     *
     * An answerer never offers (PROTOCOL §7.3). Its `start()` produces exactly one wire effect: a
     * `VOICE_STATE { negotiating }` with **no** `voice_session_id`, which is the whole of its
     * intent-to-talk — and the table advances to `NEGOTIATING` regardless of whether that frame
     * reached anything. Problem 56's fix degrades a lost `SendOffer`/`SendAnswer` and deliberately
     * exempts `SendVoiceState` because "a lost state update is carried by the next one". That is true
     * of every `VOICE_STATE` except this one: there is no next one, `VoiceNegotiation.start` is
     * idempotent against the live `NEGOTIATING` it just entered, so `attachVoice`'s reconnect rebuild
     * is a no-op — and if the leader has not itself consented, `attachVoice` does not call `start()`
     * there either, so nothing on either side ever asks again. Voice is wedged for the ride segment,
     * which is problem 56's exact failure mode reached down the other role's path.
     */
    @Test
    fun `an answerer's intent that could not be sent does not wedge voice for the rest of the segment`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            // The link is down: `VoiceSignalRelay.send` finds no authenticated writer, exactly as in
            // the window between a link loss and the §10 ladder reconnecting.
            fakes.transport.accept = false
            answerer.start()
            dispatcher.runAll()

            // The ladder reconnects and `attachVoice` rebuilds voice as a fresh negotiation (§7.8).
            fakes.transport.accept = true
            answerer.start()
            dispatcher.runAll()

            val intents =
                fakes.transport.sent.filterIsInstance<VoiceSignal.State>().filter {
                    it.state == VoiceWireState.NEGOTIATING
                }
            assertTrue(
                intents.isNotEmpty(),
                "after a reconnect the answerer must ask for voice again; sent=${fakes.transport.sent.map { it.kindName() }}",
            )
            assertTrue(
                fakes.audio.closeCaptureCount == 0,
                "no part of this degrade may close the capture device (ARCHITECTURE §6.3/§6.4)",
            )
        }

    /**
     * **P60-1 — STATUS §4 problem 60, Window 1.** A retired lifetime's signal *admitted after* its own
     * link loss has already been applied.
     *
     * Reachable because nothing spans `VoiceSignalRelay.deliver`'s liveness check and its
     * `sink.submit`: `endConnection` clears the authenticated record from another coroutine, so a
     * frame can pass the check and be overtaken by the entire teardown — the link loss included —
     * before it is queued. Problem 50's discard runs at **offer** time and so cannot see it, and the
     * table is by then in `IDLE` with `voiceSessionId = null`, which is exactly the state
     * `offerReceived` accepts any generation in.
     *
     * This is the same failure problem 50 closed, reached by the one route its fix left open, and it
     * must now be refused by identity: A is retired, so A's work is inert whenever it arrives.
     */
    @Test
    fun `a retired lifetime's offer submitted after its link loss cannot begin a negotiation`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()
            assertTrue(answerer.diagnostics.value.localAudioOpen, "the answerer's capture must be open for this to be the real case")

            // Lifetime A ends and the teardown is fully applied — the consumer is not starved here.
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()
            val calls = fakes.engine.calls.toList()
            val stopAt = calls.indexOf("stop")
            assertTrue(stopAt >= 0, "ControlLinkLost must have stopped the media transport; calls=$calls")

            // ...and only now does A's in-flight frame reach the mailbox.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            dispatcher.runAll()

            val afterTeardown =
                fakes.engine.calls
                    .toList()
                    .drop(stopAt + 1)
            assertFalse(
                afterTeardown.any { it.startsWith("start(") },
                "a retired lifetime's offer must not rebuild the peer connection; after stop=$afterTeardown",
            )
            assertFalse(
                afterTeardown.contains("applyRemote(OFFER)"),
                "a retired lifetime's offer must not be applied; after stop=$afterTeardown",
            )
            assertFalse(
                afterTeardown.contains("createAnswer"),
                "a retired lifetime's offer must not be answered; after stop=$afterTeardown",
            )
            assertEquals(VoiceStatus.IDLE, answerer.diagnostics.value.status)
            assertTrue(
                fakes.transport.sent.none { it is VoiceSignal.Answer },
                "no answer may be produced for a retired lifetime's offer",
            )
            assertEquals(
                1,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.RETIRED_CONTROL_LIFETIME],
                "and the refusal is surfaced, not silent",
            )
        }

    /**
     * **P60-2 — STATUS §4 problem 60, Window 2.** A successor lifetime's offer, queued *before* the
     * predecessor's link loss is delivered.
     *
     * `VoiceLifetimeProvenanceTest` proves the ordering is production's and needs no race:
     * `ControlEvent.LinkLost` reaches this controller through `SessionCoordinator`'s event consumer,
     * while `ControlSessionManager.promote` authenticates a successor without waiting on that
     * consumer at all — so with the loss still unconsumed, generation 2's own `VOICE_OFFER` is
     * admitted and reaches the sink. Before this fix the loss then discarded it, and since a peer
     * never re-sends an offer, voice was wedged for the ride segment exactly as in problem 56.
     */
    @Test
    fun `a successor's queued offer survives a delayed link loss for the predecessor`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()
            val startsBefore = fakes.engine.calls.count { it.startsWith("start(") }

            // Lifetime B is already authenticated and its peer has offered. Nothing has told this
            // controller that lifetime A ended yet.
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            // ...and only now is A's link loss delivered, out of the coordinator's event queue.
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertTrue(
                calls.contains("applyRemote(OFFER)"),
                "the successor lifetime's offer must survive the predecessor's link loss; calls=$calls",
            )
            assertTrue(calls.contains("createAnswer"), "and must still be answered; calls=$calls")
            assertTrue(
                calls.count { it.startsWith("start(") } > startsBefore,
                "the peer connection is rebuilt for the successor; calls=$calls",
            )
            assertEquals(
                VoiceStatus.NEGOTIATING,
                answerer.diagnostics.value.status,
                "a retired lifetime's boundary may not retire the successor's negotiation",
            )
            assertEquals(
                null,
                answerer.diagnostics.value.droppedSignals[VoiceSignalDropReason.RETIRED_CONTROL_LIFETIME],
                "nothing belonging to the retired lifetime was queued, so nothing may be discarded",
            )
        }

    /**
     * P60-2's harder sibling: **both** lifetimes have queued work when the predecessor's loss lands.
     * A's must go and B's must stay, from one `offer` call, with no ordering to appeal to.
     */
    @Test
    fun `a delayed link loss discards only the retired lifetime's queued offer`() =
        withControllerManual(isLocalLeader = false) { answerer, fakes, dispatcher ->
            answerer.start()
            dispatcher.runAll()

            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID), SDP), CONTROL_A)
            answerer.submit(VoiceSignal.Offer(genAt(OFFER_ID + 1), SDP), CONTROL_B)
            answerer.onControlLinkLost(CONTROL_A)
            dispatcher.runAll()

            val calls = fakes.engine.calls.toList()
            assertTrue(calls.contains("createAnswer"), "B's offer is answered; calls=$calls")
            assertTrue(
                calls.contains("start(${genAt(OFFER_ID + 1).value})"),
                "and the rebuilt peer connection belongs to B's offer, not A's; calls=$calls",
            )
            assertFalse(
                calls.contains("start(${genAt(OFFER_ID).value})"),
                "A's retired offer must never have started anything; calls=$calls",
            )
            assertEquals(VoiceStatus.NEGOTIATING, answerer.diagnostics.value.status)
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
         * Two **control authentication** generations, which are a different identity from the
         * `voice_session_id`s above: `ControlSessionManager.activateAuthenticatedSession` allocates
         * these, strictly increasing, one per trust-gate pass (STATUS §4 problem 60).
         */
        const val CONTROL_A = 1L
        const val CONTROL_B = 2L

        fun genAt(n: Int): VoiceSessionId = VoiceSessionId(n.toString().padStart(32, '0'))
    }
}
