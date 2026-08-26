package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOCOL §4.2 / ADR-015 duplicate-connection resolution wired to real loopback TCP sockets —
 * this session's brief §11: "candidate sockets must NOT enter SessionCoordinator as the active
 * session until duplicate resolution completes" and "exactly one control connection survives".
 *
 * Two independent [ControlSessionManager]s (distinct `peer_id`s, distinct `conn_tiebreak`s) stand
 * in for the two phones, both listening and both dialling each other at once — the "normal case
 * on every reconnect" PROTOCOL §4.2 describes, not an exotic race.
 */
class DuplicateConnectionResolutionTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    private fun localIdentity(name: String) =
        LocalHandshakeIdentity(
            displayName = name,
            platform = "android",
            osVersion = "test",
            appVersion = "test",
            connTiebreak = ConnTiebreakGenerator.generate(),
        )

    @Test
    fun `simultaneous mutual connect leaves exactly one survivor on both sides`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peerA = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("aaaaaaaaaaaaaaaa"))
                val peerB = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("bbbbbbbbbbbbbbbb"))

                val portA = peerA.startListening(localIdentity("A"))
                val portB = peerB.startListening(localIdentity("B"))

                // Subscribe BEFORE triggering the connects: `events` is a zero-replay hot
                // SharedFlow, so a `.first{}` started after the race resolves would wait forever
                // for an emission that already happened.
                val awaitConnectedA =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        peerA.events.first { it is ControlEvent.Connected } as ControlEvent.Connected
                    }
                val awaitConnectedB =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        peerB.events.first { it is ControlEvent.Connected } as ControlEvent.Connected
                    }

                // Both peers dial each other at (as close as this JVM can manage to) the same
                // instant — the simultaneous-connect race PROTOCOL §4.2 exists to resolve.
                val connectJobs =
                    listOf(
                        async(Dispatchers.IO) { peerA.connectTo("127.0.0.1", portB, localIdentity("A")) },
                        async(Dispatchers.IO) { peerB.connectTo("127.0.0.1", portA, localIdentity("B")) },
                    )
                connectJobs.awaitAll()

                val eventA = withTimeout(5_000) { awaitConnectedA.await() }
                val eventB = withTimeout(5_000) { awaitConnectedB.await() }

                // Exactly one session per side, and both sides agree on who leads (ADR-010) —
                // independent of which side's outbound connection happened to survive (ADR-015
                // Amendment A2): no assumption that initiator == leader or acceptor == leader.
                assertEquals(PeerId("bbbbbbbbbbbbbbbb"), eventA.remotePeerId)
                assertEquals(PeerId("aaaaaaaaaaaaaaaa"), eventB.remotePeerId)
                assertEquals(eventA.sessionId, eventB.sessionId)
                assertTrue(eventA.isLocalLeader != eventB.isLocalLeader, "exactly one side must be leader")
                // ADR-010: the lexicographically smaller peer_id leads, always.
                assertTrue(eventA.isLocalLeader, "aaaa... < bbbb... lexicographically, so A must lead")

                assertEquals(ControlState.CONNECTED, peerA.diagnostics.value.controlState)
                assertEquals(ControlState.CONNECTED, peerB.diagnostics.value.controlState)

                // No duplicate close should have touched reconnect_count on either side.
                assertEquals(0, peerA.reconnectCount)
                assertEquals(0, peerB.reconnectCount)

                peerA.shutdown()
                peerB.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `duplicate close does not increment reconnect count or emit a link-lost event`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peerA = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("cccccccccccccccc"))
                val peerB = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("dddddddddddddddd"))

                val portA = peerA.startListening(localIdentity("A"))
                val portB = peerB.startListening(localIdentity("B"))

                // Subscribe before triggering the connects (zero-replay hot SharedFlow — see the
                // sibling test's comment).
                val awaitConnected =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        peerA.events.first { it is ControlEvent.Connected }
                    }
                val awaitDuplicateClosed =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        peerA.events.first { it is ControlEvent.DuplicateConnectionClosed }
                    }

                listOf(
                    async(Dispatchers.IO) { peerA.connectTo("127.0.0.1", portB, localIdentity("A")) },
                    async(Dispatchers.IO) { peerB.connectTo("127.0.0.1", portA, localIdentity("B")) },
                ).awaitAll()

                withTimeout(5_000) { awaitConnected.await() }
                withTimeout(5_000) { awaitDuplicateClosed.await() }

                assertEquals(0, peerA.reconnectCount, "duplicate_connection must never increment reconnect_count")

                peerA.shutdown()
                peerB.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private suspend fun <T> List<kotlinx.coroutines.Deferred<T>>.awaitAll() = forEach { it.await() }
}
