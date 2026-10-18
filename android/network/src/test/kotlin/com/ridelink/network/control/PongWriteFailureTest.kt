package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * STATUS §4 problem 123, found on two physical phones (Phase 9B-A): **a `PONG` that cannot be
 * written crashed the app.**
 *
 * `handlePing` answered every `PING` with an unguarded `socket.writeFrame`, and it runs on the read
 * loop, which production launches on the application scope (`SupervisorJob() + Dispatchers.Main`, no
 * exception handler). On the phones, the peer's screen turned off mid-pairing, Android destroyed the
 * peer app's sockets, and the reset arrived between this side reading a `PING` and writing its
 * answer: `sun.net.ConnectionResetException` escaped the read loop and killed the process. Every
 * other control write was already wrapped.
 *
 * The write has to fail on a connection that is still **live** when the `PING` is dispatched —
 * exactly the phones' ordering. Closing the socket would end the connection first, and ADR-025 then
 * refuses the `PING` before any write is attempted, which would make this test pass with or without
 * the fix. So only the live socket's output stream is replaced, with one that fails the way a reset
 * connection does. Reads and the connection's liveness are untouched.
 *
 * iOS is not affected: its `PONG` write is `try?`, and Swift does not let an unhandled throw compile.
 */
class PongWriteFailureTest {
    /** Against the unfixed `handlePing`, `handleFrame` throws `IOException("Connection reset")` here. */
    @Test
    fun `a PONG that cannot be written does not escape the read path`() =
        runBlocking {
            val (a, b) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = a.manager(scope, MONOTONIC)
            val dialler = b.manager(scope, MONOTONIC)
            try {
                val session = FsmSession(a, manager)
                session.collectInto(scope)
                val port = manager.startListening(a.local)
                dialler.startListening(b.local)
                dialler.connectTo("127.0.0.1", port, b.local)
                session.awaitEvent { it is ControlEvent.Connected }
                val live = assertNotNull(manager.currentReadBinding(), "the trusted connection must be live")

                live.socket.failEveryWrite()
                manager.handleFrame(live, ping())

                assertSame(
                    live.socket,
                    manager.currentReadBinding()?.socket,
                    "the PING was dispatched on the live connection, so its PONG write really was attempted",
                )
                assertEquals(
                    0,
                    manager.retiredConnectionFrames,
                    "ADR-025 refused nothing: the write failed, the frame was not dropped before it",
                )
            } finally {
                dialler.shutdown()
                manager.shutdown()
                scope.cancel()
            }
        }

    private fun ping() =
        FrameReadResult.Frame(
            rawEnvelope(PEER_B, "PING") { put("t1_mono_us", 1L) },
            versionOk = true,
        )

    /**
     * Replaces the socket's private output stream with one that fails as a reset connection does,
     * leaving its input — and so the read loop and the connection's liveness — alone.
     */
    private fun ControlSocket.failEveryWrite() {
        val field = ControlSocket::class.java.getDeclaredField("output")
        field.isAccessible = true
        field.set(
            this,
            DataOutputStream(
                object : OutputStream() {
                    override fun write(b: Int): Unit = throw IOException("Connection reset")
                },
            ),
        )
    }

    private companion object {
        val PEER_B = PeerId("bbbbbbbbbbbbbbbb")
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
    }
}
