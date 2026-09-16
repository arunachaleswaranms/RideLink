package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.network.voice.AuthenticatedFrameWriter
import com.ridelink.network.voice.VoiceSignalRelay
import com.ridelink.network.voice.VoiceSignalSpy
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **STATUS §4 problem 60, at the two production seams the pure tables cannot see.**
 *
 * `VoiceMailboxLifetimeIdentityTest` proves the *policy* — a semantic `VOICE_*` input may affect the
 * negotiation only while the control generation that admitted it is unretired. This file proves the
 * three facts that policy depends on, against real `ControlSessionManager` code:
 *
 * 1. **The relay hands the sink the frame's own generation**, not whatever is live when it looks.
 *    That is ADR-025's rule applied one layer further down, and it is what makes the mailbox's
 *    decision about *provenance* rather than about liveness a second time.
 * 2. **A control generation is strictly increasing and never reset** — including across a
 *    `shutdown()`/`startListening()` cycle. That is what makes a monotonic retired floor exact
 *    rather than a heuristic, and the mailbox's own doc cites it.
 * 3. **A successor lifetime is authenticated, and its frames are admitted, without waiting for the
 *    predecessor's `LinkLost` to be consumed by anything.** This is Window 2, and it is not a race:
 *    with the event consumer deliberately held, the successor still authenticates and its own
 *    `VOICE_OFFER` still reaches the voice sink. Before the fix, the link loss that arrived
 *    afterwards discarded exactly that frame.
 *
 * The mirror is `RideLinkPlatformTests.VoiceLifetimeProvenanceTests`.
 */
class VoiceLifetimeProvenanceTest {
    // --- 1. the relay passes provenance, never a live read ------------------------------------------

    /**
     * The discriminator is a `liveGeneration` supplier that **changes between calls**. The gate reads
     * it once and finds a match; anything that read it a second time to label the frame would see the
     * successor's number instead. The sink must be told 7.
     *
     * This is the same architectural claim as `ReadFrameBinding`'s, one layer lower: a value that has
     * already authorised a read is the frame's for as long as the frame exists.
     */
    @Test
    fun `deliver passes the frame's own generation to the sink, never a re-read live one`() {
        val liveReads = AtomicLong(0)
        val spy = VoiceSignalSpy()
        val relay = relayWith(spy) { if (liveReads.getAndIncrement() == 0L) FRAME_GENERATION else SUCCESSOR_GENERATION }

        relay.deliver(VoiceMessageTypes.OFFER, offerPayload(), FRAME_GENERATION)

        assertEquals(1, spy.received.size, "the frame's own generation was live, so it is delivered")
        assertEquals(
            listOf(FRAME_GENERATION),
            spy.generations,
            "the sink must be handed the generation that authorised the read, not the one live afterwards",
        )
    }

    /** And the liveness half is unchanged: a frame whose own generation is not live is refused. */
    @Test
    fun `deliver still refuses a frame whose own generation is no longer live`() {
        val spy = VoiceSignalSpy()
        val relay = relayWith(spy) { SUCCESSOR_GENERATION }

        relay.deliver(VoiceMessageTypes.OFFER, offerPayload(), FRAME_GENERATION)

        assertEquals(emptyList(), spy.received)
        assertEquals(1, relay.droppedRetiredGeneration)
    }

    // --- 2. generations are monotonic, and never reset ----------------------------------------------

    /**
     * The producer-side half of P60-6. `VoiceInputMailbox`'s retired floor is a single monotonic
     * number, and that is only exact because `activateAuthenticatedSession` never reuses or resets
     * one. `shutdown()` un-latches the manager for reuse (`isShutDown = false` in `startListening`),
     * which is exactly the path a "full new session" takes — and the counter must survive it.
     */
    @Test
    fun `an authentication generation strictly increases and survives a shutdown and restart`() =
        twoPeers { a, b, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)
            assertEquals(0L, manager.currentAuthGeneration, "nothing has authenticated yet")

            val first = connect(manager, session, a, b, scope)
            assertEquals(1L, manager.currentAuthGeneration)
            assertEquals(1L, manager.liveAuthenticatedGeneration)

            manager.shutdown()
            first.shutdown()
            withTimeout(FsmSession.TIMEOUT_MS) { while (manager.liveAuthenticatedGeneration != null) delay(POLL_MS) }
            assertEquals(
                1L,
                manager.currentAuthGeneration,
                "a shutdown un-latches the manager for reuse and must not reset the counter",
            )

            val second = connect(manager, session, a, b, scope)
            assertEquals(2L, manager.currentAuthGeneration, "the successor's generation is strictly greater")
            assertEquals(2L, manager.liveAuthenticatedGeneration)
            manager.shutdown()
            second.shutdown()
        }

    // --- 3. Window 2, with no race in it ------------------------------------------------------------

    /**
     * **The Window-2 production trace.** A consumer of `ControlEvent` is held on the `LinkLost` it is
     * given — a `SessionCoordinator` legitimately takes its time there (it awaits
     * `SharedLibraryCoordinator.handleLinkLost()` and `SyncPlaybackCoordinator.handleLinkLost()`, and
     * on iOS it defers the voice call into a further `Task`). Nothing in the control plane waits for
     * it: `promote` requires only that `activeSocket` be null, which `endConnection` has already done.
     *
     * So while that consumer is still holding generation 1's link loss, generation 2 authenticates and
     * **its own** `VOICE_OFFER` is admitted and reaches the voice sink. The link loss that is released
     * afterwards names generation 1, and it is that name — not the order the two arrived in — that
     * keeps generation 2's offer.
     */
    @Test
    fun `a successor's VOICE_OFFER is admitted while the predecessor's LinkLost is still unconsumed`() =
        twoPeers { a, b, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)
            val spy = VoiceSignalSpy()
            manager.voice.sink = spy

            val first = connect(manager, session, a, b, scope)
            assertEquals(1L, manager.liveAuthenticatedGeneration)

            // A consumer that behaves exactly as `SessionCoordinator`'s does — one ordered stream,
            // side effects awaited — and that this test holds on the first link loss it is handed.
            val heldLinkLost = CompletableDeferred<ControlEvent.LinkLost>()
            val release = CompletableDeferred<Unit>()
            val consumed = CopyOnWriteArrayList<ControlEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                manager.events.collect { event ->
                    if (event is ControlEvent.LinkLost && !heldLinkLost.isCompleted) {
                        heldLinkLost.complete(event)
                        release.await()
                    }
                    consumed.add(event)
                }
            }

            // Generation 1 ends, the way a ride does: the *peer* goes away and this manager's read
            // loop notices, so the production `endConnection` -> `LinkLost` path runs here.
            first.shutdown()
            val linkLost = withTimeout(FsmSession.TIMEOUT_MS) { heldLinkLost.await() }
            assertEquals(1L, linkLost.retiredAuthGeneration, "the event names the lifetime that actually ended")
            assertTrue(consumed.none { it is ControlEvent.LinkLost }, "and the consumer is still holding it")

            // Generation 2 authenticates anyway — nothing about `promote` waits on that consumer.
            val second = connect(manager, session, a, b, scope)
            assertEquals(2L, manager.liveAuthenticatedGeneration)

            // ...and generation 2's own VOICE_OFFER is admitted, still with the link loss unconsumed.
            val live = assertNotNull(manager.currentReadBinding(), "session B must have a connection")
            manager.handleFrame(live, FrameReadResult.Frame(offerEnvelope(), versionOk = true))

            assertTrue(consumed.none { it is ControlEvent.LinkLost }, "the predecessor's loss is *still* unconsumed")
            assertEquals(1, spy.received.size, "the successor's offer reaches the voice sink regardless")
            assertEquals(
                listOf(2L),
                spy.generations,
                "and it is labelled as the successor's own work, which is what lets a later LinkLost(1) spare it",
            )

            release.complete(Unit)
            manager.shutdown()
            second.shutdown()
        }

    /**
     * **STATUS §4 problem 64 — the same rule pointing outwards, over two real TLS sessions on one
     * real manager** (ADR-020 Amendment A9).
     *
     * The three tests above prove a frame's *inbound* authority is the connection it was read from.
     * This one proves the outbound half, which had no guard at all: `send` resolved "the
     * authenticated writer" at the moment of the write, so a `VOICE_*` frame authorised by
     * generation 1 was written to generation 2's socket — and the peer on that socket received it as
     * current work.
     *
     * Deliberately built on the same two-session machinery rather than on a fake: what is under test
     * is that `ControlSessionManager`'s writer supplier resolves the socket **and** the generation
     * from the one immutable `AuthenticatedConnection` record, which no fake could get wrong for it.
     */
    @Test
    fun `a VOICE frame authorised by a retired generation is never written on the successor's socket`() =
        twoPeers { a, b, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)

            val first = connect(manager, session, a, b, scope)
            assertEquals(1L, manager.liveAuthenticatedGeneration)
            first.shutdown()
            // Wait for *this* manager to have observed the loss before dialling again. `promote`
            // requires `activeSocket` to be null, so reconnecting while the first connection is still
            // being torn down can leave the successor unauthenticated — which is a fact about this
            // test's setup, not about the rule under test. Gated on an observable, never on a sleep.
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != null) delay(POLL_MS)
            }

            val second = connect(manager, session, a, b, scope)
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != 2L) delay(POLL_MS)
            }
            assertEquals(2L, manager.liveAuthenticatedGeneration, "generation 2 owns the surviving connection")
            // The successor's own peer is the only observer that matters: it is the socket the
            // pre-fix `send` would have written generation 1's frame to.
            val peerSpy = VoiceSignalSpy()
            second.voice.sink = peerSpy

            val offer = VoiceSignal.Offer(VoiceSessionId(VOICE_SESSION_ID), SDP)

            assertFalse(manager.voice.send(offer, 1L), "a retired lifetime's frame must fail closed")
            assertEquals(1, manager.voice.droppedRetiredGenerationOutbound, "and be counted, never silent")
            assertFalse(manager.voice.send(offer, null), "and so must a frame authorised by nobody")
            assertEquals(2, manager.voice.droppedRetiredGenerationOutbound)

            assertTrue(manager.voice.send(offer, 2L), "the live lifetime's own frame still goes")
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (peerSpy.received.isEmpty()) delay(POLL_MS)
            }
            assertEquals(
                1,
                peerSpy.received.size,
                "exactly one frame reached the successor's peer -- generation 1's never did",
            )
            assertEquals(listOf(1L), peerSpy.generations, "and the peer read it under its own first generation")

            manager.shutdown()
            second.shutdown()
        }

    /**
     * The other half of the same event: a connection that never passed the trust gate retires no
     * generation, because it never admitted a `VOICE_*` frame for one to own. `connectTo` to a port
     * nothing is listening on is the production path that emits exactly that.
     */
    @Test
    fun `a LinkLost from a dial that never authenticated names no generation`() =
        twoPeers { a, _, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)

            manager.connectTo("127.0.0.1", UNUSED_PORT, a.local)

            val linkLost =
                withTimeout(FsmSession.TIMEOUT_MS) {
                    var found: ControlEvent.LinkLost? = null
                    while (found == null) {
                        found = session.events.filterIsInstance<ControlEvent.LinkLost>().firstOrNull()
                        if (found == null) delay(POLL_MS)
                    }
                    found
                }
            assertNull(linkLost.retiredAuthGeneration, "nothing authenticated, so nothing is retired")
            manager.shutdown()
        }

    // --- harness ------------------------------------------------------------------------------------

    private fun relayWith(
        sink: VoiceSignalSpy,
        liveGeneration: () -> Long?,
    ): VoiceSignalRelay =
        VoiceSignalRelay(
            localPeerId = PeerId("aaaaaaaaaaaaaaaa"),
            monotonicNowUs = MONOTONIC,
            nextSeq = { 1L },
            activeSessionId = { SessionId("00000000000000000000000000000000") },
            authenticatedWriterFor = { expected ->
                if (expected == liveGeneration()) AuthenticatedFrameWriter { } else null
            },
            liveGeneration = liveGeneration,
        ).also { it.sink = sink }

    private fun offerEnvelope() =
        rawEnvelope(PeerId("bbbbbbbbbbbbbbbb"), VoiceMessageTypes.OFFER) {
            put("voice_session_id", VOICE_SESSION_ID)
            put("sdp", SDP)
        }

    private fun offerPayload() = offerEnvelope().payload

    private suspend fun connect(
        manager: ControlSessionManager,
        session: FsmSession,
        peer: TestPeer,
        counterpart: TestPeer,
        scope: CoroutineScope,
    ): ControlSessionManager {
        val target = counterpart.manager(scope, MONOTONIC)
        val before = session.countOf { it is ControlEvent.Connected }
        val targetPort = target.startListening(counterpart.local)
        val ownPort = manager.startListening(peer.local)
        manager.connectTo("127.0.0.1", targetPort, peer.local)
        target.connectTo("127.0.0.1", ownPort, counterpart.freshLocal())
        withTimeout(FsmSession.TIMEOUT_MS) {
            while (session.countOf { it is ControlEvent.Connected } <= before) delay(POLL_MS)
        }
        return target
    }

    private fun twoPeers(body: suspend (TestPeer, TestPeer, CoroutineScope) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                body(a, b, scope)
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 5L

        /** A generation a frame was read under, and the one that replaced it. */
        const val FRAME_GENERATION = 7L
        const val SUCCESSOR_GENERATION = 9L

        const val VOICE_SESSION_ID = "abababababababababababababababab"
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

        /** Nothing binds here; the dial fails and the emitted `LinkLost` carries no generation. */
        const val UNUSED_PORT = 1
    }
}
