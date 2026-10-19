package com.ridelink.network.control

import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 125, reproduced four times on two physical Android phones (Phase 9B-A):
 * **a reconnect that the peer completes by dialling back in leaves this phone's own ladder
 * running inside the live session.**
 *
 * `promote` called `ReconnectController.reset()` and never stopped the ladder. So when this phone
 * was `RECONNECTING` and the *peer's* dial was promoted (an inbound promotion), the ladder kept
 * dialling the remembered — often stale — address. `reset()` had zeroed its spent budget, so it
 * ran a fresh 120 s, then wrote `DISCONNECTED` into the diagnostics of a connected session and
 * emitted `ReconnectBudgetExhausted` (which the FSM rejects from `CONNECTED`). It also left the
 * budget spent, so the **next** genuine link loss in that process got zero attempts: `RECONNECTING
 * -> DISCONNECTED` in 19–160 ms on the phones.
 *
 * Real managers over real loopback TLS 1.3, the phones' sequence: the dialler drops the link, this
 * side's ladder runs against a dead (stale) address, and the dialler's redial is what reconnects.
 * Time is real; only the ladder's own waits are accelerated, through the manager's
 * `reconnectDelayMs` seam, and only once the promotion has happened — so an orphaned ladder would
 * burn its 120 s within a fraction of a second, and nothing else about the session is sped up.
 */
class ReconnectLadderOrphanTest {
    /**
     * Against unmodified `promote` this fails on the exhaustion: the orphaned ladder runs a full
     * fresh budget during the live session and emits `ReconnectBudgetExhausted`.
     */
    @Test
    fun `an inbound promotion during RECONNECTING ends this side's own ladder`() =
        withInboundReconnect { pair ->
            val countAtPromotion = pair.manager.reconnectCount

            pair.ladderSpeed.set(FAST)
            delay(ORPHAN_BUDGET_WINDOW_MS)

            assertEquals(0, pair.session.countOf { it == ControlEvent.ReconnectBudgetExhausted }, "no exhaustion in a live session")
            assertEquals(ControlState.CONNECTED, pair.manager.diagnostics.value.controlState, "diagnostics still say CONNECTED")
            assertEquals(countAtPromotion, pair.manager.reconnectCount, "no ladder attempt after the promotion")
            assertEquals(SessionStatus.CONNECTED, pair.session.status)
        }

    /**
     * Against unmodified `promote` this fails on the attempt count: the orphan has spent the
     * budget, so the next ladder makes no attempt at all before giving up.
     */
    @Test
    fun `the next genuine link loss gets a full fresh budget`() =
        withInboundReconnect { pair ->
            pair.ladderSpeed.set(FAST)
            delay(ORPHAN_BUDGET_WINDOW_MS) // whatever an orphaned ladder would do, it has done now

            pair.dropDiallerConnection()
            pair.session.awaitStatus(SessionStatus.RECONNECTING)
            val countBefore = pair.manager.reconnectCount
            pair.manager.beginReconnect(pair.localPeer.local, "127.0.0.1", pair.deadPort)
            pair.session.awaitStatus(SessionStatus.DISCONNECTED)

            val attempts = pair.manager.reconnectCount - countBefore
            assertTrue(
                attempts >= MIN_ATTEMPTS_IN_A_FULL_BUDGET,
                "a fresh 120 s ladder makes a full run of attempts before giving up, not $attempts",
            )
            assertEquals(1, pair.session.countOf { it == ControlEvent.ReconnectBudgetExhausted }, "exactly one exhaustion: this ladder's")
            assertEquals(attempts, pair.manager.diagnostics.value.reconnectCount - countBefore, "the card shows every attempt")
        }

    private class Pair(
        val manager: ControlSessionManager,
        val session: FsmSession,
        val port: Int,
        val dialler: ControlSessionManager,
        val diallerPeer: TestPeer,
        val localPeer: TestPeer,
        val ladderSpeed: AtomicLong,
        val deadPort: Int,
    ) {
        suspend fun connectAndAwait(expectedConnections: Int) {
            withTimeout(FsmSession.TIMEOUT_MS) { while (dialler.currentReadBinding() != null) delay(POLL_MS) }
            dialler.connectTo("127.0.0.1", port, diallerPeer.freshLocal())
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (session.countOf { it is ControlEvent.Connected } < expectedConnections) delay(POLL_MS)
            }
        }

        /** Closes the dialler's end of the live connection without a `BYE`: a network loss. */
        fun dropDiallerConnection() {
            assertNotNull(dialler.currentReadBinding(), "the dialler's connection must be live").socket.close()
        }
    }

    /**
     * Leaves the pair connected by an **inbound** promotion that happened while this side's own
     * ladder was running against a stale address — the state every phone run was in at
     * `RECONNECTING -> CONNECTED`.
     */
    private fun withInboundReconnect(body: suspend (Pair) -> Unit) =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("cccccccccccccccc", "dddddddddddddddd")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ladderSpeed = AtomicLong(REAL_TIME)
            val manager =
                ControlSessionManager(
                    scope = scope,
                    monotonicNowUs = MONOTONIC,
                    localPeerId = a.peerId,
                    channel = a.channel(),
                    trustedPeers = a.trustedPeers,
                    nowEpochSeconds = { com.ridelink.network.security.TestTlsSupport.NOW_EPOCH_SECONDS },
                    reconnectDelayMs = { ms -> scaledDelay(ms, ladderSpeed) },
                )
            val dialler = b.manager(scope, MONOTONIC)
            try {
                val session = FsmSession(a, manager)
                session.collectInto(scope)
                val port = manager.startListening(a.local)
                dialler.startListening(b.local)
                assertTrue(session.apply(SessionEvent.StartDiscovery) && session.apply(SessionEvent.PeerSelected))
                val pair = Pair(manager, session, port, dialler, b, a, ladderSpeed, deadPort())

                pair.connectAndAwait(expectedConnections = 1)
                pair.session.awaitStatus(SessionStatus.CONNECTED)

                // The link drops; as `SessionCoordinator` does, this side starts its ladder — here
                // against an address nothing answers, like a remembered port from an earlier session.
                pair.dropDiallerConnection()
                pair.session.awaitStatus(SessionStatus.RECONNECTING)
                manager.beginReconnect(a.local, "127.0.0.1", pair.deadPort)
                withTimeout(FsmSession.TIMEOUT_MS) { while (manager.reconnectCount < 1) delay(POLL_MS) }

                // The peer dials back in: an inbound promotion completes the reconnect.
                pair.connectAndAwait(expectedConnections = 2)
                pair.session.awaitStatus(SessionStatus.CONNECTED)

                body(pair)
            } finally {
                dialler.shutdown()
                manager.shutdown()
                scope.cancel()
            }
        }

    /**
     * A port that is bound and immediately released, so a connect to it is refused at once. The
     * plaintext fixture, because nothing here ever completes a handshake.
     */
    private suspend fun deadPort(): Int =
        PlaintextControlChannelFixture().bind().let { listener ->
            val port = listener.localPort
            listener.close()
            port
        }

    private companion object {
        val CLOCK = AtomicLong(1_000_000L)
        val MONOTONIC: () -> Long = { CLOCK.addAndGet(1_000) }
        const val POLL_MS = 10L
        const val STEP_MS = 10L
        const val REAL_TIME = 1L

        /** One real 10 ms step stands for 10 s of ladder time: a whole budget in well under a second. */
        const val FAST = 1_000L

        /** Long enough for an orphaned ladder to finish its parked wait and spend 120 s at [FAST]. */
        const val ORPHAN_BUDGET_WINDOW_MS = 3_000L

        /**
         * PROTOCOL §10's ladder spends 7.5 s on its first four rungs and then 8 s (±20%) per attempt,
         * so a 120 s budget holds at least 16 attempts; 10 leaves room and still rules out "zero".
         */
        const val MIN_ATTEMPTS_IN_A_FULL_BUDGET = 10

        /**
         * Waits [ms] of ladder time in real steps whose worth is read each step, so a wait already
         * in progress speeds up the moment [speed] changes.
         */
        suspend fun scaledDelay(
            ms: Long,
            speed: AtomicLong,
        ) {
            var remaining = ms
            while (remaining > 0) {
                delay(STEP_MS)
                remaining -= STEP_MS * speed.get()
            }
        }
    }
}
