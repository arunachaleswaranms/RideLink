package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for this session's brief §2 (PING/PONG ordering review — Android was
 * already correct, but write-failure cleanup was missing) and §3 (reconnect re-entrancy). Both
 * only manifest against real sockets and real scheduling, so these use real loopback TCP
 * [ControlSessionManager] pairs, the same pattern as [DuplicateConnectionResolutionTest].
 */
class PingRaceAndReconnectTest {
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

    // MARK: - §2 clock burst / PING-PONG

    @Test
    fun `clock burst completes quickly with no dropped pongs`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peerA = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("1111111111111111"))
                val peerB = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("2222222222222222"))

                val portA = peerA.startListening(localIdentity("A"))
                val portB = peerB.startListening(localIdentity("B"))

                peerA.connectTo("127.0.0.1", portB, localIdentity("A"))
                peerB.connectTo("127.0.0.1", portA, localIdentity("B"))

                withTimeout(5_000) {
                    peerA.diagnostics.first { it.controlState == ControlState.CONNECTED }
                }
                // First clock burst (11 samples) runs immediately on CONNECTED (ARCHITECTURE §7.1).
                // If any PONG were dropped by a registration race, the affected sample would wait
                // out the full 3s PING_TIMEOUT_MS, so a 2.5s deadline is well under the failure
                // case and comfortably over the success case.
                withTimeout(2_500) {
                    peerA.diagnostics.first { it.clockOffsetUs != null }
                }

                peerA.shutdown()
                peerB.shutdown()
            } finally {
                scope.cancel()
            }
        }

    // MARK: - §3 reconnect re-entrancy

    @Test
    fun `failed reconnect attempts never emit an event`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peer = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("5555555555555555"))
                val recorded = mutableListOf<ControlEvent>()
                val collectJob = launch { peer.events.collect { recorded.add(it) } }

                // Bind and immediately release a port so nothing is listening there.
                val deadListener = ControlListener.bind()
                val deadPort = deadListener.localPort
                deadListener.close()

                peer.beginReconnect(localIdentity("A"), "127.0.0.1", deadPort)

                // Ladder: attempt 1 at ~0.4-0.6s, attempt 2 at ~1.2-1.8s cumulative. 3s leaves
                // comfortable margin for connect-refused overhead on a loaded CI machine.
                delay(3_000)

                assertTrue(
                    recorded.isEmpty(),
                    "a failed reconnect attempt must never emit a ControlEvent (would re-trigger beginReconnect): $recorded",
                )
                assertTrue(peer.reconnectCount >= 2, "the ladder must keep advancing across failed attempts: ${peer.reconnectCount}")

                collectJob.cancel()
                peer.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `reconnect count grows monotonically and never resets`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peer = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("6666666666666666"))
                val deadListener = ControlListener.bind()
                val deadPort = deadListener.localPort
                deadListener.close()

                peer.beginReconnect(localIdentity("A"), "127.0.0.1", deadPort)

                val samples = mutableListOf<Int>()
                repeat(6) {
                    delay(400)
                    samples.add(peer.reconnectCount)
                }

                for (i in 1 until samples.size) {
                    assertTrue(samples[i] >= samples[i - 1], "reconnectCount must never go backwards: $samples")
                }
                assertTrue((samples.lastOrNull() ?: 0) > 0, "the ladder must have made progress: $samples")

                peer.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `successful reconnect stops further attempts`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peerA = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("7777777777777777"))
                val peerB = ControlSessionManager(scope, monotonicNowUs, localPeerId = PeerId("8888888888888888"))

                val portB = peerB.startListening(localIdentity("B"))

                val awaitConnected =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        peerA.events.first { it is ControlEvent.Connected }
                    }

                peerA.beginReconnect(localIdentity("A"), "127.0.0.1", portB)

                withTimeout(5_000) { awaitConnected.await() }

                val countAtSuccess = peerA.reconnectCount
                delay(1_500)
                val countAfterWaiting = peerA.reconnectCount
                assertEquals(countAtSuccess, countAfterWaiting, "no further attempts should occur once reconnect succeeded")

                peerA.shutdown()
                peerB.shutdown()
            } finally {
                scope.cancel()
            }
        }
}
