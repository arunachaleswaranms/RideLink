package com.ridelink.network.transfer

import com.ridelink.core.model.TransferId
import com.ridelink.core.transfer.BulkFraming
import com.ridelink.network.security.TestTlsSupport
import com.ridelink.network.security.TlsControlChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bulk transport (ADR-023), end to end over **real loopback TCP with a real TLS 1.3
 * handshake** — same discipline as `TlsControlChannelTest`: what a bulk connection actually does
 * is what a laptop test must prove, not what the design doc says it should do.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BulkTransportManagerTest {
    private val alice = TestTlsSupport.freshIdentity()
    private val bob = TestTlsSupport.freshIdentity()
    private val mallory = TestTlsSupport.freshIdentity() // a third identity, never the expected peer

    private fun manager(identity: TestTlsSupport.TestIdentity) =
        BulkTransportManager(
            tlsChannel =
                TlsControlChannel(
                    identity = identity.identity,
                    ioDispatcher = Dispatchers.IO,
                    provider = TestTlsSupport.ConscryptTlsProvider,
                    secureRandom = SecureRandom(),
                ),
            monotonicNowUs = { System.nanoTime() / 1000 },
        )

    private fun chunksOf(vararg data: ByteArray): ChunkSource {
        val index = AtomicLong(0)
        return ChunkSource {
            val i = index.getAndIncrement().toInt()
            if (i >= data.size) null else data[i]
        }
    }

    @Test
    fun `happy path transfers every chunk in order`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
                val generation = 1L
                val token = server.issueToken(transferId, generation)

                val chunk0 = ByteArray(100) { it.toByte() }
                val chunk1 = ByteArray(200) { (it * 3).toByte() }
                val source = chunksOf(chunk0, chunk1)

                val received = mutableListOf<Pair<Long, ByteArray>>()
                val sink = ChunkSink { index, bytes -> received.add(index to bytes) }

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 2L, source)
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch("127.0.0.1", port, token, alice.identity.identitySpkiSha256, 2, sink)
                        }
                    assertEquals(BulkFetchOutcome.OK, fetchResult)
                    assertEquals(BulkServeOutcome.OK, serveResult.await())
                }

                assertEquals(2, received.size)
                assertEquals(0L, received[0].first)
                assertTrue(chunk0.contentEquals(received[0].second))
                assertEquals(1L, received[1].first)
                assertTrue(chunk1.contentEquals(received[1].second))
            } finally {
                server.close()
                client.close()
            }
        }

    @Test
    fun `client rejects a provider presenting the wrong SPKI`() =
        runBlocking {
            val server = manager(mallory) // not the peer bob expects
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5D")
                val token = server.issueToken(transferId, 1L)
                val source = chunksOf(ByteArray(10))

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) { server.serve(transferId, bob.identity.identitySpkiSha256, { 1L }, 1L, source) }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch("127.0.0.1", port, token, alice.identity.identitySpkiSha256, 1, ChunkSink { _, _ -> })
                        }
                    assertEquals(BulkFetchOutcome.NOT_AUTHORIZED, fetchResult)
                    serveResult.await()
                }
            } finally {
                server.close()
                client.close()
            }
        }

    @Test
    fun `server rejects a connection whose token does not match`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5E")
                server.issueToken(transferId, 1L) // real token minted, but the client below never learns it
                val wrongToken = "ab".repeat(32)
                val source = chunksOf(ByteArray(10))

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) { server.serve(transferId, bob.identity.identitySpkiSha256, { 1L }, 1L, source) }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch("127.0.0.1", port, wrongToken, alice.identity.identitySpkiSha256, 1, ChunkSink { _, _ -> })
                        }
                    // The client's connection succeeds at the TLS/SPKI layer and it dutifully sends the
                    // wrong token; the server closes without ever streaming a chunk, so the client's read
                    // loop sees EOF before satisfying expectedChunkCount.
                    assertEquals(BulkFetchOutcome.CONNECTION_LOST, fetchResult)
                    assertEquals(BulkServeOutcome.NOT_AUTHORIZED, serveResult.await())
                }
            } finally {
                server.close()
                client.close()
            }
        }

    @Test
    fun `a token from a superseded generation is rejected`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5F")
                val staleToken = server.issueToken(transferId, 1L) // minted under generation 1
                server.onNewGeneration(2L) // a reconnect re-authenticates: generation moves to 2
                val source = chunksOf(ByteArray(10))

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) { server.serve(transferId, bob.identity.identitySpkiSha256, { 2L }, 1L, source) }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch("127.0.0.1", port, staleToken, alice.identity.identitySpkiSha256, 1, ChunkSink { _, _ -> })
                        }
                    assertEquals(BulkFetchOutcome.CONNECTION_LOST, fetchResult)
                    assertEquals(BulkServeOutcome.NOT_AUTHORIZED, serveResult.await())
                }
            } finally {
                server.close()
                client.close()
            }
        }

    @Test
    fun `a multi-chunk file larger than one read buffer still reassembles correctly in order`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5G")
                val generation = 1L
                val token = server.issueToken(transferId, generation)
                // A file bigger than BulkTransportManager's internal 16 KiB read buffer, split into
                // chunks at the RLB1 payload bound (64 KiB) exactly as a real disk-backed chunker
                // would (ChunkSource is one wire frame's payload per call, never a whole file) —
                // forcing several reads-and-reassemble cycles through the same code path a real
                // large file would.
                val big = ByteArray(200_000) { (it % 251).toByte() }
                val pieces = big.toList().chunked(BulkFraming.MAX_CHUNK_PAYLOAD_BYTES).map { it.toByteArray() }
                val source = chunksOf(*pieces.toTypedArray())
                val received = mutableListOf<ByteArray>()

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, pieces.size.toLong(), source)
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                port,
                                token,
                                alice.identity.identitySpkiSha256,
                                pieces.size.toLong(),
                                ChunkSink { _, bytes -> received.add(bytes) },
                            )
                        }
                    assertEquals(BulkFetchOutcome.OK, fetchResult)
                    assertEquals(BulkServeOutcome.OK, serveResult.await())
                }

                assertEquals(pieces.size, received.size)
                val reassembled = received.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                assertTrue(big.contentEquals(reassembled))
            } finally {
                server.close()
                client.close()
            }
        }

    // --- Closure-audit Finding K: frame ordering/count validation --------------------------------

    /** Writes raw, deliberately malformed RLB1 frames directly to a socket — bypassing
     *  [BulkTransportManager.serve]'s own always-sequential [ChunkSource] loop entirely, since that
     *  API has no way to construct an out-of-order/duplicate/extra frame. Consumes and discards
     *  the token bytes exactly like a real provider would, without validating them — this harness
     *  is testing the *requester*'s ([BulkTransportManager.fetch]) framing validation, not the
     *  provider's authorization. */
    private fun rawFrameServer(identity: TestTlsSupport.TestIdentity) =
        TlsControlChannel(
            identity = identity.identity,
            ioDispatcher = Dispatchers.IO,
            provider = TestTlsSupport.ConscryptTlsProvider,
            secureRandom = SecureRandom(),
        )

    @Test
    fun `a duplicate chunk index is rejected as a protocol error, not merely counted`() =
        runBlocking {
            val client = manager(bob)
            val rawServer = rawFrameServer(alice)
            val listener = rawServer.bind()
            try {
                coroutineScope {
                    val serverJob =
                        async(Dispatchers.IO) {
                            val socket = listener.accept()
                            discardToken(socket)
                            socket.writeRawBytes(BulkFraming.encodeFrame(0, ByteArray(10)))
                            socket.writeRawBytes(BulkFraming.encodeFrame(0, ByteArray(10))) // duplicate, not index 1
                            socket.close()
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                listener.localPort,
                                "00".repeat(32),
                                alice.identity.identitySpkiSha256,
                                2,
                                ChunkSink { _, _ -> },
                            )
                        }
                    assertEquals(BulkFetchOutcome.PROTOCOL_ERROR, fetchResult)
                    serverJob.await()
                }
            } finally {
                listener.close()
                client.close()
            }
        }

    @Test
    fun `a skipped chunk index is rejected as a protocol error`() =
        runBlocking {
            val client = manager(bob)
            val rawServer = rawFrameServer(alice)
            val listener = rawServer.bind()
            try {
                coroutineScope {
                    val serverJob =
                        async(Dispatchers.IO) {
                            val socket = listener.accept()
                            discardToken(socket)
                            socket.writeRawBytes(BulkFraming.encodeFrame(0, ByteArray(10)))
                            socket.writeRawBytes(BulkFraming.encodeFrame(2, ByteArray(10))) // skips index 1
                            socket.close()
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                listener.localPort,
                                "00".repeat(32),
                                alice.identity.identitySpkiSha256,
                                3,
                                ChunkSink { _, _ -> },
                            )
                        }
                    assertEquals(BulkFetchOutcome.PROTOCOL_ERROR, fetchResult)
                    serverJob.await()
                }
            } finally {
                listener.close()
                client.close()
            }
        }

    @Test
    fun `an out-of-order chunk index is rejected as a protocol error`() =
        runBlocking {
            val client = manager(bob)
            val rawServer = rawFrameServer(alice)
            val listener = rawServer.bind()
            try {
                coroutineScope {
                    val serverJob =
                        async(Dispatchers.IO) {
                            val socket = listener.accept()
                            discardToken(socket)
                            socket.writeRawBytes(BulkFraming.encodeFrame(1, ByteArray(10))) // index 1 first, not 0
                            socket.writeRawBytes(BulkFraming.encodeFrame(0, ByteArray(10)))
                            socket.close()
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                listener.localPort,
                                "00".repeat(32),
                                alice.identity.identitySpkiSha256,
                                2,
                                ChunkSink { _, _ -> },
                            )
                        }
                    assertEquals(BulkFetchOutcome.PROTOCOL_ERROR, fetchResult)
                    serverJob.await()
                }
            } finally {
                listener.close()
                client.close()
            }
        }

    @Test
    fun `an extra frame beyond the offer's declared chunk count is rejected`() =
        runBlocking {
            val client = manager(bob)
            val rawServer = rawFrameServer(alice)
            val listener = rawServer.bind()
            try {
                coroutineScope {
                    val serverJob =
                        async(Dispatchers.IO) {
                            val socket = listener.accept()
                            discardToken(socket)
                            socket.writeRawBytes(BulkFraming.encodeFrame(0, ByteArray(10)))
                            // expectedChunkCount below is 1 — this second, in-sequence frame is still
                            // one frame too many and must be rejected, not silently accepted because
                            // the earlier count was already satisfied by a *different* code path.
                            socket.writeRawBytes(BulkFraming.encodeFrame(1, ByteArray(10)))
                            socket.close()
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                listener.localPort,
                                "00".repeat(32),
                                alice.identity.identitySpkiSha256,
                                1,
                                ChunkSink { _, _ -> },
                            )
                        }
                    assertEquals(BulkFetchOutcome.PROTOCOL_ERROR, fetchResult)
                    serverJob.await()
                }
            } finally {
                listener.close()
                client.close()
            }
        }

    private suspend fun discardToken(socket: com.ridelink.network.control.ControlSocket) {
        val tokenBytes = ByteArray(32)
        var offset = 0
        while (offset < tokenBytes.size) {
            val n = socket.readRawBytes(tokenBytes, offset, tokenBytes.size - offset)
            if (n < 0) break
            offset += n
        }
    }

    // --- Closure-audit Findings C/D/N: cancelActive force-closes a stuck in-flight operation ------

    @Test
    fun `cancelActive unblocks a fetch genuinely stuck waiting for chunks that will never arrive`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5J")
                val generation = 1L
                val token = server.issueToken(transferId, generation)
                // The server sends one chunk, then hangs (never sends chunk 2, never closes) — the
                // socket stays genuinely open with the client's read loop blocked in a real blocking
                // socket read, exactly the state a user-cancelled or session-lost transfer leaves
                // behind if nothing ever force-closes the connection.
                val neverSendsSecondChunk = CompletableDeferred<Unit>()
                var chunkIndex = 0
                val source =
                    ChunkSource {
                        when (chunkIndex++) {
                            0 -> ByteArray(10)
                            else -> {
                                neverSendsSecondChunk.await()
                                null
                            }
                        }
                    }

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) { server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 5L, source) }
                    val fetchResult =
                        async(Dispatchers.IO) {
                            client.fetch("127.0.0.1", port, token, alice.identity.identitySpkiSha256, 5, ChunkSink { _, _ -> })
                        }
                    // Give the real loopback connection time to actually deliver the one chunk the
                    // server does send, so the client is genuinely parked waiting for more.
                    delay(SETTLE_MS)
                    client.cancelActive()

                    withTimeout(TIMEOUT_MS) {
                        assertTrue(
                            fetchResult.await() in setOf(BulkFetchOutcome.CONNECTION_LOST, BulkFetchOutcome.IO_ERROR),
                            "a force-closed fetch must return promptly with a failure outcome, never hang",
                        )
                    }
                    neverSendsSecondChunk.complete(Unit)
                    serveResult.await()
                }
            } finally {
                server.close()
                client.close()
            }
        }

    /**
     * ADR-023 Amendment A4 Finding T: `chunk_count` in a `TRANSFER_OFFER` is a promise (PROTOCOL
     * §8.2), and the requester enforces it — a frame past the declared count is a `PROTOCOL_ERROR`.
     * The provider must therefore refuse to emit one at all, so a [ChunkSource] that outruns its
     * own declared count (a short-reading stream before Amendment A4's [InputStreamChunkSource], or
     * a local file that grew between the size check and the open) fails as this side's own
     * `IO_ERROR` rather than as the peer's protocol violation.
     */
    @Test
    fun `provider refuses to write more frames than the chunk_count it declared`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val port = server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5K")
                val generation = 1L
                val token = server.issueToken(transferId, generation)
                // Four frames available, but the offer promised two.
                val source = chunksOf(ByteArray(10), ByteArray(10), ByteArray(10), ByteArray(10))
                val received = mutableListOf<Long>()

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 2L, source)
                        }
                    val fetchResult =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                "127.0.0.1",
                                port,
                                token,
                                alice.identity.identitySpkiSha256,
                                2,
                                ChunkSink { i, _ -> received.add(i) },
                            )
                        }
                    assertEquals(
                        BulkServeOutcome.IO_ERROR,
                        serveResult.await(),
                        "the provider must stop itself, not be stopped by the peer",
                    )
                    // The requester got exactly the two frames it was promised. It then expects a
                    // clean provider close; the cap above makes `serve` return (closing the socket)
                    // rather than write a third frame, so this is a clean EOF, not a trailing byte.
                    assertEquals(listOf(0L, 1L), received)
                    assertEquals(BulkFetchOutcome.OK, fetchResult)
                }
            } finally {
                server.close()
                client.close()
            }
        }

    /**
     * ADR-023 Amendment A4 Finding W: `serve()`'s `accept()` must be bounded by the `bulk_token`'s
     * own 30 s TTL (ADR-023 §2). Unbounded, a requester that takes a `TRANSFER_OFFER` and then
     * never dials — cancelled between offer and fetch, or its connect failed — parks `serve()`
     * inside `accept()` for the rest of the session, holding [BulkTransportManager]'s single
     * `activeTransferMutex` (and, one layer up, the coordinator's `BulkOperationGate`) against
     * every other transfer in **both** directions. `cancelActive()` cannot rescue it either:
     * `activeSocket` is still null at that point, so there is nothing for it to close.
     *
     * The bound itself is 30 s, far too long to sit in a unit test, so this proves the two halves
     * separately: that `cancelActive()` genuinely cannot unblock a not-yet-accepted `serve` (the
     * reason a timeout is needed at all), and that closing the listener does end it promptly (the
     * session-boundary escape hatch that limited the blast radius to one session rather than
     * forever). `ControlListenerAcceptTimeoutTest` covers the timeout firing, with a short bound.
     */
    @Test
    fun `a serve nobody ever dials is not rescued by cancelActive, only by closing the listener`() =
        runBlocking {
            val server = manager(alice)
            try {
                server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5M")
                val generation = 1L
                server.issueToken(transferId, generation)

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 1L, chunksOf(ByteArray(10)))
                        }
                    // Give the serve call time to actually reach accept() and park there.
                    withContext(Dispatchers.IO) { Thread.sleep(SETTLE_MS) }
                    assertTrue(serveResult.isActive, "serve must still be parked in accept() -- nobody has dialled")

                    server.cancelActive()
                    withContext(Dispatchers.IO) { Thread.sleep(SETTLE_MS) }
                    assertTrue(
                        serveResult.isActive,
                        "cancelActive() cannot unblock an accept() that has not produced a socket yet -- " +
                            "this is exactly why the accept needs its own bound",
                    )

                    // Closing the listener makes the parked accept() throw, which serve() reports.
                    server.close()
                    assertEquals(BulkServeOutcome.IO_ERROR, withTimeout(TIMEOUT_MS) { serveResult.await() })
                }
            } finally {
                server.close()
            }
        }

    @Test
    fun `cancelActive is a safe no-op when nothing is active`() =
        runBlocking {
            val manager = manager(alice)
            try {
                manager.cancelActive()
                manager.cancelActive()
            } finally {
                manager.close()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 500L
    }
}
