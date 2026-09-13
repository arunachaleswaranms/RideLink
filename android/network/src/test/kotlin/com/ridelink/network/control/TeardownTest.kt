package com.ridelink.network.control

import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import com.ridelink.network.transfer.TransferSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * This session's brief §9/§10: on shutdown, every task the session owns must actually stop —
 * reconnect, the active socket, pending PING waiters — and a fresh session started afterward on
 * the same (reused) [ControlSessionManager] instance must work cleanly, with nothing left over
 * from the torn-down one.
 */
class TeardownTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    /** A bound-then-released port, so a connect to it is refused. See PingRaceAndReconnectTest. */
    private suspend fun deadPort(): Int =
        PlaintextControlChannelFixture().bind().let { listener ->
            val port = listener.localPort
            listener.close()
            port
        }

    @Test
    fun `shutdown stops the reconnect ladder -- no further attempts afterward`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val a = TestSessions.unpairedPeer("1010101010101010", "A")
                val peer = a.manager(scope, monotonicNowUs)

                peer.beginReconnect(a.local, "127.0.0.1", deadPort())
                delay(200) // let the ladder get going
                peer.shutdown()

                val countAtShutdown = peer.reconnectCount
                delay(2_000)
                assertEquals(countAtShutdown, peer.reconnectCount, "no reconnect attempt may run after shutdown")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `shutdown closes the active socket -- the peer observes connection closed`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (sutPeer, fakePeer) = TestSessions.pairedPeers("2020202020202020", "3030303030303030", "SUT", "fake")
                val sut = sutPeer.manager(scope, monotonicNowUs)
                val port = sut.startListening(sutPeer.local)

                val fake = fakePeer.channel().connect("127.0.0.1", port)
                val outcome =
                    ControlHandshake.performAsInitiator(
                        fake,
                        fakePeer.peerId,
                        SeqCounter(),
                        monotonicNowUs,
                        fakePeer.local,
                        fakePeer.trustedPeers,
                    )
                check(outcome is HandshakeOutcome.Success)

                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                sut.shutdown()

                // The SUT's own keepalive/clock-sync loop may still be sending its own PING/PONG
                // traffic on this socket in the instant before shutdown's cancellation takes
                // effect; skip over those and look for the actual teardown signal — BYE followed
                // by (or coinciding with) the socket closing.
                var closedCleanly = false
                withTimeout(5_000) {
                    while (!closedCleanly) {
                        when (val result = fake.readFrame()) {
                            is FrameReadResult.ConnectionClosed -> closedCleanly = true
                            is FrameReadResult.Frame -> if (result.envelope.type == "BYE") closedCleanly = true
                            else -> Unit
                        }
                    }
                }
                assert(closedCleanly)

                fake.close()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new session on the same reused manager starts cleanly after shutdown`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sutPeer = TestSessions.unpairedPeer("4040404040404040", "SUT")
                val b1Peer = TestSessions.unpairedPeer("5050505050505050", "B1")
                val b2Peer = TestSessions.unpairedPeer("6060606060606060", "B2")
                // The SUT already knows both partners, so each session is a trusted silent connect
                // and this test stays about teardown/reuse rather than about pairing.
                listOf(b1Peer, b2Peer).forEach { partner ->
                    sutPeer.trust(partner)
                    partner.trust(sutPeer)
                }

                val sut = sutPeer.manager(scope, monotonicNowUs)
                val peerB1 = b1Peer.manager(scope, monotonicNowUs)

                val portB1 = peerB1.startListening(b1Peer.local)
                val portSut1 = sut.startListening(sutPeer.local)
                sut.connectTo("127.0.0.1", portB1, sutPeer.local)
                peerB1.connectTo("127.0.0.1", portSut1, b1Peer.local)
                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                sut.shutdown()
                peerB1.shutdown()
                assertEquals(ControlState.ENDED, sut.diagnostics.value.controlState)

                // Reuse the SAME sut instance for a fresh session, matching how
                // SessionCoordinator holds one ControlSessionManager for the app's lifetime.
                val peerB2 = b2Peer.manager(scope, monotonicNowUs)
                val portB2 = peerB2.startListening(b2Peer.local)
                val portSut2 = sut.startListening(sutPeer.freshLocal())
                sut.connectTo("127.0.0.1", portB2, sutPeer.freshLocal())
                peerB2.connectTo("127.0.0.1", portSut2, b2Peer.local)

                withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }
                assertEquals(ControlState.CONNECTED, sut.diagnostics.value.controlState)

                sut.shutdown()
                peerB2.shutdown()
            } finally {
                scope.cancel()
            }
        }

    /**
     * **`docs/STATUS.md` §4 problem 54.** `shutdown()` used to call `relays.reset()`, which nulled
     * every relay sink — including the three installed **once per process** by
     * `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`. Nothing ever re-installs those, so a
     * single Stop Discovery silently disabled Phase 4 and Phase 5 for the rest of the process.
     *
     * The rule now kept is the narrow one that was always true: a sink belongs to whoever installed
     * it. The two per-session families (`voice`, `audioState`) are detached by `SessionCoordinator`,
     * synchronously, when it retires the session — see `SessionLifecycleRestartTest`; the three
     * process-lifetime families are guarded by ADR-025's per-frame generation, which is what lets
     * them legitimately span a boundary.
     *
     * Unreachable-as-a-bug before this pass only in the sense that it could not be *recovered* from:
     * `ENDING -> IDLE` never opened, so an app that had stopped discovering could not start a second
     * session in which to notice the loss.
     */
    @Test
    fun `shutdown detaches only the sinks the session owned, never a process-lifetime one`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val peer = TestSessions.unpairedPeer("7070707070707070", "SUT")
                val sut = peer.manager(scope, monotonicNowUs)

                val manifestSink = ManifestSink { _, _ -> }
                val transferSink = TransferSink { _, _ -> }
                val playbackSink = PlaybackSink { _, _ -> }
                val queueSink = QueueSink { _, _ -> }
                sut.manifest.sink = manifestSink
                sut.transfer.sink = transferSink
                sut.playback.playbackSink = playbackSink
                sut.playback.queueSink = queueSink
                // The two per-session families, attached exactly as SessionCoordinator attaches them.
                sut.voice.sink =
                    object : VoiceSignalSink {
                        override fun submit(
                            signal: VoiceSignal,
                            controlGeneration: Long,
                        ) = Unit
                    }
                sut.audioState.sink = AudioStateSink { }

                sut.startListening(peer.local)
                sut.shutdown()

                assertSame(manifestSink, sut.manifest.sink, "Phase 4's manifest sink is not the session's to remove")
                assertSame(transferSink, sut.transfer.sink, "nor its transfer sink")
                assertSame(playbackSink, sut.playback.playbackSink, "nor Phase 5's playback sink")
                assertSame(queueSink, sut.playback.queueSink, "nor its queue sink")

                // …and a second session on the same manager still has all four.
                sut.startListening(peer.freshLocal())
                assertSame(manifestSink, sut.manifest.sink)
                assertSame(playbackSink, sut.playback.playbackSink)
                sut.shutdown()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `shutdown mid clock burst leaves no stale pending ping to collide with the next session`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (a1, b1) = TestSessions.pairedPeers("7070707070707070", "8080808080808080")
                val peerA1 = a1.manager(scope, monotonicNowUs)
                val peerB1 = b1.manager(scope, monotonicNowUs)
                val portA1 = peerA1.startListening(a1.local)
                val portB1 = peerB1.startListening(b1.local)
                peerA1.connectTo("127.0.0.1", portB1, a1.local)
                peerB1.connectTo("127.0.0.1", portA1, b1.local)
                withTimeout(5_000) { peerA1.diagnostics.first { it.controlState == ControlState.CONNECTED } }

                // Tear down immediately, almost certainly while the first 11-sample clock burst
                // is still in flight.
                peerA1.shutdown()
                peerB1.shutdown()

                // A fresh pair, reusing the same clock (so t1 values can collide with the old
                // session's), must still converge cleanly.
                val (a2, b2) = TestSessions.pairedPeers("7070707070707070", "8080808080808080")
                val peerA2 = a2.manager(scope, monotonicNowUs)
                val peerB2 = b2.manager(scope, monotonicNowUs)
                val portA2 = peerA2.startListening(a2.local)
                val portB2 = peerB2.startListening(b2.local)
                peerA2.connectTo("127.0.0.1", portB2, a2.local)
                peerB2.connectTo("127.0.0.1", portA2, b2.local)
                withTimeout(5_000) { peerA2.diagnostics.first { it.controlState == ControlState.CONNECTED } }
                withTimeout(2_500) { peerA2.diagnostics.first { it.clockOffsetUs != null } }

                peerA2.shutdown()
                peerB2.shutdown()
            } finally {
                scope.cancel()
            }
        }
}
