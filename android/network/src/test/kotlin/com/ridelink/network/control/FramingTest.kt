package com.ridelink.network.control

import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.EnvelopeCodec
import com.ridelink.core.protocol.FrameLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.DataOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PROTOCOL §1 framing over real loopback sockets: `uint32` BE length prefix, 262144-byte cap,
 * enforced by inspecting the length **before** allocating the payload buffer (this session's
 * brief §8) — proven here by declaring a huge length with no body and confirming
 * [FrameReadResult.FrameTooLarge] returns promptly instead of hanging or allocating.
 */
class FramingTest {
    private fun envelope(payload: JsonObject = JsonObject(emptyMap())) =
        Envelope(
            v = 1,
            type = "PING",
            sessionId = "s1",
            senderId = "0123456789abcdef",
            msgId = "m1",
            seq = 1,
            sentAtMonoUs = 1000,
            payload = payload,
        )

    @Test
    fun `frame at exactly the cap round-trips`() =
        runBlocking {
            withListenerAndConnection { serverSocket, clientSocket ->
                // protocol/README.md "Frame-size boundary vectors" recipe: measure the base
                // encoded size with an empty pad, then pad exactly to the target byte length.
                val base = envelope(JsonObject(mapOf("pad" to kotlinx.serialization.json.JsonPrimitive(""))))
                val baseSize = EnvelopeCodec.encode(base).size
                val padding = "a".repeat(FrameLimits.MAX_CONTROL_FRAME_BYTES - baseSize)
                val big = envelope(JsonObject(mapOf("pad" to kotlinx.serialization.json.JsonPrimitive(padding))))
                val encodedSize = EnvelopeCodec.encode(big).size
                assertTrue(encodedSize == FrameLimits.MAX_CONTROL_FRAME_BYTES, "test fixture must land exactly on the cap: $encodedSize")

                val writer = async(Dispatchers.IO) { clientSocket.writeFrame(big) }
                val result = withTimeout(5_000) { serverSocket.readFrame() }
                writer.await()

                assertIs<FrameReadResult.Frame>(result)
            }
        }

    @Test
    fun `declared length over the cap is rejected without reading or hanging`() =
        runBlocking {
            withRawLoopback { rawClientOut, serverSocket ->
                // Declare a length far beyond the cap and never send a body — if the
                // implementation allocated ByteArray(declaredLength) first this would either
                // throw OutOfMemoryError or block forever waiting for bytes that never arrive.
                val out = DataOutputStream(rawClientOut)
                out.writeInt(FrameLimits.MAX_CONTROL_FRAME_BYTES + 1)
                out.flush()

                val result = withTimeout(5_000) { serverSocket.readFrame() }
                assertIs<FrameReadResult.FrameTooLarge>(result)
                assertTrue((result as FrameReadResult.FrameTooLarge).declaredLength > FrameLimits.MAX_CONTROL_FRAME_BYTES)
            }
        }

    @Test
    fun `negative declared length is rejected`() =
        runBlocking {
            withRawLoopback { rawClientOut, serverSocket ->
                val out = DataOutputStream(rawClientOut)
                out.writeInt(-1)
                out.flush()

                val result = withTimeout(5_000) { serverSocket.readFrame() }
                assertIs<FrameReadResult.FrameTooLarge>(result)
            }
        }

    /**
     * Framing is transport-neutral, and these tests are about the `uint32` length prefix and the
     * 256 KiB cap alone — no handshake, no identity, nothing a TLS session would contribute. The
     * plaintext fixture (test sources only; see [PlaintextControlChannelFixture]) keeps them fast
     * and keeps what they assert unambiguous.
     */
    private val plaintext = PlaintextControlChannelFixture()

    private suspend fun withListenerAndConnection(block: suspend (ControlSocket, ControlSocket) -> Unit) {
        val listener = plaintext.bind()
        val clientDeferred =
            kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) { plaintext.connect("127.0.0.1", listener.localPort) }
        val server = listener.accept()
        val client = clientDeferred.await()
        try {
            block(server, client)
        } finally {
            server.close()
            client.close()
            listener.close()
        }
    }

    private suspend fun withRawLoopback(block: suspend (java.io.OutputStream, ControlSocket) -> Unit) {
        val listener = plaintext.bind()
        val rawClient = Socket()
        rawClient.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort))
        val server = listener.accept()
        try {
            block(rawClient.getOutputStream(), server)
        } finally {
            server.close()
            rawClient.close()
            listener.close()
        }
    }
}
