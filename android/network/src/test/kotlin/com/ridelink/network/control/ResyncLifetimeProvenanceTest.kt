package com.ridelink.network.control

import com.ridelink.core.resync.ResyncMessage
import com.ridelink.network.resync.ResyncSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent-review Blocker 1, at the real `ControlSessionManager`/`ResyncRelay` seam —
 * `VoiceLifetimeProvenanceTest`'s `a VOICE frame authorised by a retired generation is never
 * written on the successor's socket`, mirrored exactly for PROTOCOL §10's resync plane.
 *
 * Before the fix, `ResyncRelay.send` resolved the writer live (`authenticatedWriter()`) rather
 * than from the one immutable `AuthenticatedConnection` record bound to the authorising
 * generation, so a `STATE_SNAPSHOT`/`STATE_REQUEST` decided under generation 1 could be written
 * through generation 2's socket if the two suspended across a reconnect. This proves the fixed
 * relay refuses that, against a real second `ControlSessionManager` connection — not a fake.
 */
class ResyncLifetimeProvenanceTest {
    @Test
    fun `a STATE_SNAPSHOT authorised by a retired generation is never written on the successor's socket`() =
        twoPeers { a, b, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)

            val first = connect(manager, session, a, b, scope)
            assertEquals(1L, manager.liveAuthenticatedGeneration)
            first.shutdown()
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != null) delay(POLL_MS)
            }

            val second = connect(manager, session, a, b, scope)
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != 2L) delay(POLL_MS)
            }
            assertEquals(2L, manager.liveAuthenticatedGeneration, "generation 2 owns the surviving connection")
            val peerSpy = ResyncSinkSpy()
            second.resync.sink = peerSpy

            assertFalse(manager.resync.send(ResyncMessage.StateRequest, 1L), "a retired lifetime's frame must fail closed")
            assertEquals(1, manager.resync.droppedRetiredGenerationOutbound, "and be counted, never silent")

            assertTrue(manager.resync.send(ResyncMessage.StateRequest, 2L), "the live lifetime's own frame still goes")
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

    @Test
    fun `a STATE_REQUEST authorised by a retired generation is never written on the successor's socket`() =
        twoPeers { a, b, scope ->
            val manager = a.manager(scope, MONOTONIC)
            val session = FsmSession(a, manager)
            session.collectInto(scope)

            val first = connect(manager, session, a, b, scope)
            first.shutdown()
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != null) delay(POLL_MS)
            }
            val second = connect(manager, session, a, b, scope)
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.liveAuthenticatedGeneration != 2L) delay(POLL_MS)
            }

            assertFalse(manager.resync.send(ResyncMessage.StateRequest, 1L), "generation 1's own STATE_REQUEST must fail closed too")
            assertEquals(1, manager.resync.droppedRetiredGenerationOutbound)
            // Unlike VOICE_*, a resync frame is always constructed with a real, known authorising
            // generation (`ResyncCoordinator.triggerRequest`'s own `generation: Long` parameter,
            // `Outbound.generation: Long`) -- there is no equivalent "consent recorded but no
            // control lifetime" state, so `ResyncRelay.send`'s generation is deliberately
            // non-nullable rather than mirroring Voice's `Long?`.
            assertFalse(manager.resync.send(ResyncMessage.StateRequest, 99L), "an unrelated generation must fail closed too")
            assertEquals(2, manager.resync.droppedRetiredGenerationOutbound)

            manager.shutdown()
            second.shutdown()
        }

    // --- harness (mirrors VoiceLifetimeProvenanceTest.connect/twoPeers exactly) ---------------------

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

    private class ResyncSinkSpy : ResyncSink {
        private val log = CopyOnWriteArrayList<ResyncMessage>()
        private val generationLog = CopyOnWriteArrayList<Long>()

        val received: List<ResyncMessage> get() = log.toList()
        val generations: List<Long> get() = generationLog.toList()

        override fun submit(
            message: ResyncMessage,
            generation: Long,
        ) {
            log.add(message)
            generationLog.add(generation)
        }
    }

    private companion object {
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 5L
    }
}
