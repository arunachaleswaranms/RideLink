package com.ridelink.network.playback

import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.protocol.PlaybackMessageTypes
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.FrameReadResult
import com.ridelink.network.control.FsmSession
import com.ridelink.network.control.ReadFrameBinding
import com.ridelink.network.control.TestPeer
import com.ridelink.network.control.TestSessions
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The regression ADR-024 **Amendment A7** — the seventh Phase 5 closure audit — exists for.
 *
 * **A6 bound every Phase 5 *loss* to the generation that caused it. A7 is the observation that the
 * generation a frame arrives with was itself read from live state, one layer above.**
 *
 * Every consumer of an inbound Phase 5 frame already took the generation as a *value*:
 * `ControlRelays.deliver`, `PlaybackRelay.deliverPlayback`, `PlaybackSink.submit` (whose own
 * doc comment says the value is "the authentication generation that was live **when the frame was
 * read off the wire**"), the coordinator's guard and `Phase5FrameQueue`'s loss ledger. None of them
 * looked one up. But the value they were handed came from `handleFrame` reading the manager's live
 * `authenticationGeneration` field at *dispatch* time — so the contract the whole chain was built
 * on was never actually met at its origin.
 *
 * **The interleaving, before the fix.** `ControlSocket.readFrame()` is a suspend function whose
 * body runs on `Dispatchers.IO`; returning from it resumes the read-loop coroutine on the scope's
 * own dispatcher, which is a scheduling point. `endConnection` is reachable concurrently — the
 * keepalive loop is a separate coroutine and a pong timeout calls it — and it does **not** cancel
 * the read loop. So:
 *
 * 1. Session A is authenticated as generation 1;
 * 2. Session A's read loop reads a valid `PAUSE` off socket A and its continuation is queued;
 * 3. the keepalive loop times out, `endConnection` runs, socket A is closed and the session ends;
 * 4. a reconnect completes and Session B authenticates as generation 2;
 * 5. *only then* does the read-loop continuation run — and read `authenticationGeneration` as **2**.
 *
 * Session A's frame is now, to everything downstream, Session B's authority. A6's retired-loss
 * accounting cannot help: the frame was relabelled before it ever reached `Phase5FrameQueue`.
 *
 * **Why A6's own suite could not see it.** Every A6 regression supplies the generation itself
 * (`session.deliver(message, generation = 1)` against a `FakeSyncSession`), which is exactly right
 * for asserting what the coordinator does with a generation — and exactly blind to where that
 * number comes from. The defect is entirely above that seam.
 *
 * **What the fix is.** `readLoop` captures a `ReadFrameBinding` — the connection the frame was read
 * from, its `session_id`, and the generation that owned *that connection* — and `handleFrame` is
 * given it. The generation is bound to the connection once, at
 * `activateAuthenticatedSession`, and is discarded whole at the boundary; no later transition can
 * give socket A a newer one.
 *
 * **How this test produces the park.** Nothing a test controls can suspend a coroutine between
 * `ControlSocket.readFrame()` returning and the dispatch that follows it. So the two halves of that
 * one step are called as two statements with a real session boundary in between —
 * `currentReadBinding()` is the capture `readLoop` performs, and `handleFrame(binding, frame)` is
 * the very function it calls. Everything under test is production: two real TLS 1.3 sessions on one
 * real `ControlSessionManager`, the real trust gate, the real allowlist, the real `PlaybackCodec`
 * and the real `PlaybackRelay`.
 *
 * The mirror is `RideLinkPlatformTests.StaleReadGenerationTests`.
 */
class StaleReadGenerationTest {
    /**
     * **The defect, in full.** A frame bound to Session A and dispatched after Session B is live
     * must still be Session A's — and socket A must never be readable as Session B's generation.
     *
     * Pre-fix, against unmodified `a0b81c1` production sources, the `PAUSE` below is delivered to
     * the sink with generation **2**.
     */
    @Test
    fun `a frame bound to Session A is never delivered as Session B's generation`() =
        twoSessionsOnOneManager { sut, spy ->
            // Captured while Session A is live: exactly what its read loop holds for a frame it has
            // just read off socket A.
            val parked = assertNotNull(sut.manager.currentReadBinding(), "Session A must have a connection")
            assertEquals(1L, parked.generation, "Session A is generation 1")

            sut.boundaryToSecondSession()

            assertEquals(2L, sut.manager.currentAuthGeneration, "Session B is generation 2")
            // The core invariant, asked of the production function that answers it: socket A's
            // authorisation went to *nothing*, never to Session B's number.
            assertNull(
                ReadFrameBinding.of(sut.manager.authenticatedRecord, parked.socket, parked.sessionId).generation,
                "socket A must never be readable as an authenticated connection again",
            )

            // The parked read-loop continuation finally runs.
            sut.manager.handleFrame(parked, frame(PlaybackMessageTypes.PAUSE) { pauseBody() })

            assertEquals(
                listOf(1L),
                spy.generations,
                "a Session A frame must arrive as Session A's generation or not at all — never as Session B's",
            )
            assertTrue(spy.messages.single() is PlaybackMessage.Pause, "and it is still the frame that was read")
        }

    /**
     * The other half, and the one a naive fix breaks (requirement 3 of this amendment's brief):
     * **a frame whose own session is still live is still delivered, with that session's
     * generation** — being late is not being stale. Binding to the connection must not turn every
     * scheduling delay into a dropped command.
     */
    @Test
    fun `a frame dispatched late within the same live session is still delivered normally`() =
        twoSessionsOnOneManager { sut, spy ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            // No boundary: only other work running, which is the ordinary case every ride produces.
            delay(SETTLE_MS)

            sut.manager.handleFrame(parked, frame(PlaybackMessageTypes.PAUSE) { pauseBody() })

            assertEquals(listOf(1L), spy.generations, "the live session's own frame is delivered, tagged its own")
            assertTrue(spy.messages.single() is PlaybackMessage.Pause)
        }

    /**
     * Session B's own ingress is untouched by any of this: its next frame carries its own
     * generation, through the same production path, with no trace of Session A in the counters.
     */
    @Test
    fun `Session B's own Phase 5 authority is unaffected`() =
        twoSessionsOnOneManager { sut, spy ->
            val parked = assertNotNull(sut.manager.currentReadBinding())
            sut.boundaryToSecondSession()
            sut.manager.handleFrame(parked, frame(PlaybackMessageTypes.PAUSE) { pauseBody() })

            val live = assertNotNull(sut.manager.currentReadBinding(), "Session B must have a connection")
            sut.manager.handleFrame(live, frame(PlaybackMessageTypes.PAUSE) { pauseBody() })

            assertEquals(listOf(1L, 2L), spy.generations, "each frame carries the generation of the connection it came from")
            assertEquals(
                0,
                sut.manager.playback.droppedPreAuthentication,
                "neither frame was refused — both connections were authenticated when their frame was read",
            )
            assertEquals(emptyMap(), sut.manager.playback.playbackRejectionCounts, "and neither was malformed")
        }

    /**
     * The complementary outcome the amendment's brief also accepts: a frame *read* from a
     * connection whose session has already ended carries no authorisation at all, so the
     * pre-authentication gate refuses it — for the same reason and by the same construction that
     * refuses an unpaired peer's `PAUSE` (PROTOCOL §5's absence from the allowlist).
     *
     * This is the read-loop iteration that follows the boundary rather than the one that precedes
     * it: Android's `endConnection` does not cancel the read loop, so it genuinely runs once more.
     */
    @Test
    fun `a frame read from a connection whose session has ended is refused, not relabelled`() =
        twoSessionsOnOneManager { sut, spy ->
            val socketA = assertNotNull(sut.manager.currentReadBinding()).socket
            sut.boundaryToSecondSession()

            // What `readLoop` would capture on socket A's next iteration, produced by `bindRead`
            // itself rather than hand-built.
            val retired =
                ReadFrameBinding.of(
                    sut.manager.authenticatedRecord,
                    socketA,
                    sut.manager.currentReadBinding()!!.sessionId,
                )
            sut.manager.handleFrame(retired, frame(PlaybackMessageTypes.PAUSE) { pauseBody() })

            assertEquals(emptyList(), spy.generations, "a retired connection's frame reaches no sink")
            assertEquals(1, sut.manager.playback.droppedPreAuthentication, "and is counted as the refusal it is")
        }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * One `ControlSessionManager` under test, kept alive across a session boundary — which is the
     * whole point, since the defect is about a generation moving *underneath* a live manager.
     */
    private class Sut(
        val manager: ControlSessionManager,
        val session: FsmSession,
        private val peer: TestPeer,
        private val scope: CoroutineScope,
        private val port: Int,
        private val counterpart: TestPeer,
    ) {
        private val managers = mutableListOf<ControlSessionManager>()

        suspend fun connectFirstSession() = connectSession()

        /**
         * Ends Session A with a real `BYE` and brings a second real TLS session up on the same
         * manager, so `authenticationGeneration` advances 1 -> 2 exactly as a reconnect does.
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

    private fun twoSessionsOnOneManager(body: suspend (Sut, Phase5Spy) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = a.manager(scope, MONOTONIC)
                val session = FsmSession(a, manager)
                session.collectInto(scope)

                val spy = Phase5Spy()
                manager.playback.playbackSink = PlaybackSink { message, generation -> spy.record(message, generation) }

                val port = manager.startListening(a.local)
                val sut = Sut(manager, session, a, scope, port, b)
                sut.connectFirstSession()
                try {
                    body(sut, spy)
                } finally {
                    sut.shutdownAll()
                }
            } finally {
                scope.cancel()
            }
        }

    /** Records **which generation** each message arrived with — the only fact this amendment is about. */
    private class Phase5Spy {
        val messages = CopyOnWriteArrayList<PlaybackMessage>()
        val generations = CopyOnWriteArrayList<Long>()

        fun record(
            message: PlaybackMessage,
            generation: Long,
        ) {
            messages.add(message)
            generations.add(generation)
        }
    }

    private fun frame(
        type: String,
        build: JsonObjectBuilder.() -> Unit,
    ) = FrameReadResult.Frame(rawEnvelope(PEER_B, type, build), versionOk = true)

    /** A valid PROTOCOL §5 `PAUSE`, so a rejection can only ever be the gate, never the codec. */
    private fun JsonObjectBuilder.pauseBody() {
        put("command_seq", 1)
        put("effective_at_session_us", 90_210_500_000)
        put("issued_by", PEER_B.value)
        put("queue_revision", 0)
        put("position_ms", 1_000)
    }

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
        const val SETTLE_MS = 100L
    }
}
