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
 *
 * **Every `@Test` here is written `(): Unit =` on purpose (ADR-023 Amendment A5).** These are
 * expression-bodied functions, so Kotlin infers the return type from `runBlocking`'s last
 * expression — and JUnit 5 silently *does not discover* a `@Test` method whose return type is not
 * `void`. Two cases in this file (the wrong-SPKI rejection, and A1's own `cancelActive` proof)
 * ended in `serveResult.await()`, inferred `BulkServeOutcome`, and had therefore never executed
 * once — a whole audit's worth of green runs reported them as passing while JUnit had skipped them
 * without a word. Found by comparing declared `@Test` counts against the JUnit XML's `tests=`
 * attribute, then confirming with `javap`. Do not drop the explicit `: Unit`.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BulkTransportManagerTest {
    private val alice = TestTlsSupport.freshIdentity()
    private val bob = TestTlsSupport.freshIdentity()
    private val mallory = TestTlsSupport.freshIdentity() // a third identity, never the expected peer

    private fun manager(identity: TestTlsSupport.TestIdentity) =
        BulkTransportManager(
            channel =
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
                            client.fetch(transferId, "127.0.0.1", port, token, alice.identity.identitySpkiSha256, 2, sink)
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

    // The explicit `: Unit` on every test below is load-bearing, not decoration — see the class
    // KDoc's ADR-023 Amendment A5 note on why an inferred non-Unit return hides a test from JUnit 5.
    @Test
    fun `client rejects a provider presenting the wrong SPKI`(): Unit =
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
                            client.fetch(transferId, "127.0.0.1", port, token, alice.identity.identitySpkiSha256, 1, ChunkSink { _, _ -> })
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
                            client.fetch(
                                transferId,
                                "127.0.0.1",
                                port,
                                wrongToken,
                                alice.identity.identitySpkiSha256,
                                1,
                                ChunkSink { _, _ -> },
                            )
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
                            client.fetch(
                                transferId,
                                "127.0.0.1",
                                port,
                                staleToken,
                                alice.identity.identitySpkiSha256,
                                1,
                                ChunkSink { _, _ -> },
                            )
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
                                transferId,
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
                                RAW_FRAME_TRANSFER_ID,
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
                                RAW_FRAME_TRANSFER_ID,
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
                                RAW_FRAME_TRANSFER_ID,
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
                                RAW_FRAME_TRANSFER_ID,
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
    fun `cancelActive unblocks a fetch genuinely stuck waiting for chunks that will never arrive`(): Unit =
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

                // ADR-023 Amendment A5: this waits on a real state transition, not on a fixed
                // sleep. The sleep it replaces was the one piece of timing guesswork left in this
                // suite, and it mattered here more than anywhere else: if the cancel fires before
                // the fetch has actually connected, `cancelActive` is *correctly* a no-op (there is
                // nothing to cancel yet), the fetch then parks for the full 10 s bound below, and
                // the server's `serve` — blocked in a real, non-cancellable `accept()`/read — holds
                // this `coroutineScope` open for its own 30 s bound. That produces exactly the
                // "one failure, ~32 s run" signature observed once in 220 stress runs of this
                // suite. Chunk 0 arriving is the precise precondition the test name claims:
                // connected, authorised, and genuinely parked waiting for more.
                val firstChunkArrived = CompletableDeferred<Unit>()
                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) { server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 5L, source) }
                    val fetchResult =
                        async(Dispatchers.IO) {
                            client.fetch(
                                transferId,
                                "127.0.0.1",
                                port,
                                token,
                                alice.identity.identitySpkiSha256,
                                5,
                                ChunkSink { _, _ -> firstChunkArrived.complete(Unit) },
                            )
                        }
                    withTimeout(TIMEOUT_MS) { firstChunkArrived.await() }
                    assertEquals(
                        transferId,
                        client.connectedTransferIdForTesting,
                        "the fetch must really own the transport's connected phase before the cancel is meaningful",
                    )
                    client.cancelActive(transferId)

                    withTimeout(TIMEOUT_MS) {
                        assertTrue(
                            fetchResult.await() in setOf(BulkFetchOutcome.CONNECTION_LOST, BulkFetchOutcome.IO_ERROR),
                            "a force-closed fetch must return promptly with a failure outcome, never hang",
                        )
                    }
                    neverSendsSecondChunk.complete(Unit)
                    withTimeout(TIMEOUT_MS) { serveResult.await() }
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
                                transferId,
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
     * ADR-023 Amendment A4 Finding W's other half, still true and still needed: a `serve` nobody
     * ever dials and nobody ever cancels is ended by the session boundary's `close()`. A5 does not
     * change this — it adds the *explicit-cancel* path below, and leaves the 30 s bound
     * (`ControlListenerAcceptTimeoutTest` covers it firing, with a short bound) in place for the
     * cases where no cancel ever arrives at all.
     */
    @Test
    fun `a serve nobody ever dials is ended by closing the transport`() =
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
                    awaitPendingAccept(server, transferId)
                    assertTrue(serveResult.isActive, "serve must still be parked in accept() -- nobody has dialled")

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
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5N")
            try {
                manager.cancelActive(transferId)
                manager.cancelActive(transferId)
            } finally {
                manager.close()
            }
        }

    // --- ADR-023 Amendment A5 Finding A: explicit cancellation while parked in accept() ----------

    /**
     * Spins until [manager] reports [transferId] as the transfer parked in `accept()`. Deterministic
     * on a real state hook rather than a guessed sleep: the `serve` coroutine is genuinely blocked
     * on a real `ServerSocket.accept()` on another thread, so there is no scheduler to advance —
     * only a real transition to observe. Bounded so a regression fails the test instead of hanging
     * it.
     */
    private suspend fun awaitPendingAccept(
        manager: BulkTransportManager,
        transferId: TransferId,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (manager.pendingAcceptTransferIdForTesting != transferId) delay(POLL_MS)
        }
    }

    /**
     * **The A5 Finding A regression.** PROTOCOL §8.2 allows `TRANSFER_CANCEL` from either side at
     * any time. Before A5, a cancel arriving while the provider was still parked in `accept()` —
     * the requester was cancelled between taking the offer and dialling, or its connect failed —
     * had nothing to act on, because the socket a cancel closes does not exist until `accept()`
     * *returns*. The call then sat there holding the one-active-transfer slot (and the
     * coordinator's `BulkOperationGate` above it) until A4's 30 s bound expired.
     *
     * This asserts the causality directly, not the bound: the production accept timeout is
     * unchanged at 30 s, and `serve` must return in a small fraction of that because the *cancel*
     * ended it. A regression that reverted to waiting the bound out would blow [TIMEOUT_MS] (10 s)
     * and fail here, rather than passing slowly.
     */
    @Test
    fun `an explicit cancel ends a serve parked in accept, without waiting out the 30 s bound`() =
        runBlocking {
            val server = manager(alice)
            try {
                server.ensureListening()
                val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5P")
                val generation = 1L
                val token = server.issueToken(transferId, generation)

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferId, bob.identity.identitySpkiSha256, { generation }, 1L, chunksOf(ByteArray(10)))
                        }
                    awaitPendingAccept(server, transferId)

                    val startedAtNs = System.nanoTime()
                    server.cancelActive(transferId)
                    assertEquals(BulkServeOutcome.IO_ERROR, withTimeout(TIMEOUT_MS) { serveResult.await() })
                    val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000
                    assertTrue(
                        elapsedMs < PROMPT_MS,
                        "the cancel itself must have ended the accept ($elapsedMs ms) -- not A4's 30 s bound",
                    )
                }

                assertEquals(null, server.pendingAcceptTransferIdForTesting, "the cancelled transfer must no longer own the accept")
                assertEquals(null, server.listenerPortForTesting, "the listener the accept was parked on must be gone")
                // Section 5: the offer's token dies with the transfer it authorised, rather than
                // staying live for the rest of its 30 s TTL.
                assertTrue(
                    !server.tokenTable.validateAndConsume(transferId, token, generation),
                    "a cancelled pre-accept transfer's token must no longer authorise anything",
                )
            } finally {
                server.close()
            }
        }

    /**
     * The other half of A5 Finding A's invariant (brief §2/§18): cancellation is `transfer_id`-
     * scoped in **both** phases, so a cancel naming some other transfer — a stale one, a queued
     * one, a peer's mistake — must leave the pending accept exactly where it is. Only the cancel
     * that actually names it may end it.
     */
    @Test
    fun `a cancel naming a different transfer leaves a pending accept untouched`() =
        runBlocking {
            val server = manager(alice)
            try {
                val port = server.ensureListening()
                val transferA = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5Q")
                val transferB = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5R")
                val generation = 1L
                server.issueToken(transferA, generation)

                coroutineScope {
                    val serveResult =
                        async(Dispatchers.IO) {
                            server.serve(transferA, bob.identity.identitySpkiSha256, { generation }, 1L, chunksOf(ByteArray(10)))
                        }
                    awaitPendingAccept(server, transferA)

                    server.cancelActive(transferB)
                    // A real sleep is correct here and nowhere else in this suite: this asserts the
                    // *absence* of an effect, and there is no state transition to wait on when the
                    // whole claim is that nothing transitions. Bounded, and the accept it is
                    // proving still-parked is ended by the cancel below rather than by any timeout.
                    withContext(Dispatchers.IO) { Thread.sleep(SETTLE_MS) }
                    assertTrue(serveResult.isActive, "a cancel for B must not end A's accept")
                    assertEquals(transferA, server.pendingAcceptTransferIdForTesting)
                    assertEquals(port, server.listenerPortForTesting, "a wrong-transfer cancel must not close the shared listener")

                    server.cancelActive(transferA)
                    assertEquals(BulkServeOutcome.IO_ERROR, withTimeout(TIMEOUT_MS) { serveResult.await() })
                }
            } finally {
                server.close()
            }
        }

    /**
     * Brief §6/§20: cancelling a pending accept closes the listener it was parked on, which is only
     * acceptable if the session is not left wedged. The next transfer must bind a fresh listener,
     * mint a fresh token, and complete normally — and the cancelled transfer's old token must not
     * work against that new listener.
     */
    @Test
    fun `a fresh transfer works normally after a pending accept is cancelled`() =
        runBlocking {
            val server = manager(alice)
            val client = manager(bob)
            try {
                val cancelledPort = server.ensureListening()
                val transferA = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5S")
                val generation = 1L
                val staleToken = server.issueToken(transferA, generation)

                coroutineScope {
                    val serveA =
                        async(Dispatchers.IO) {
                            server.serve(transferA, bob.identity.identitySpkiSha256, { generation }, 1L, chunksOf(ByteArray(10)))
                        }
                    awaitPendingAccept(server, transferA)
                    server.cancelActive(transferA)
                    assertEquals(BulkServeOutcome.IO_ERROR, withTimeout(TIMEOUT_MS) { serveA.await() })
                }

                // The abandoned offer's port is genuinely gone: presenting the stale token there
                // cannot reach anything (the connect itself fails -- there is nothing listening).
                assertEquals(
                    BulkFetchOutcome.CONNECTION_LOST,
                    withTimeout(TIMEOUT_MS) {
                        client.fetch(
                            transferA,
                            "127.0.0.1",
                            cancelledPort,
                            staleToken,
                            alice.identity.identitySpkiSha256,
                            1,
                            ChunkSink { _, _ -> },
                        )
                    },
                )

                val freshPort = server.ensureListening()
                val transferB = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5V")
                val freshToken = server.issueToken(transferB, generation)
                val payload = ByteArray(64) { it.toByte() }
                val received = mutableListOf<ByteArray>()

                coroutineScope {
                    val serveB =
                        async(Dispatchers.IO) {
                            server.serve(transferB, bob.identity.identitySpkiSha256, { generation }, 1L, chunksOf(payload))
                        }
                    val fetchB =
                        withTimeout(TIMEOUT_MS) {
                            client.fetch(
                                transferB,
                                "127.0.0.1",
                                freshPort,
                                freshToken,
                                alice.identity.identitySpkiSha256,
                                1,
                                ChunkSink { _, bytes -> received.add(bytes) },
                            )
                        }
                    assertEquals(BulkFetchOutcome.OK, fetchB)
                    assertEquals(BulkServeOutcome.OK, serveB.await())
                }
                assertEquals(1, received.size)
                assertTrue(payload.contentEquals(received[0]))
            } finally {
                server.close()
                client.close()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 5L

        /** Generously above any real cancellation cost, and far below A4's 30 s accept bound — the
         *  gap between them is what makes the assertion about causality rather than about timing. */
        const val PROMPT_MS = 3_000L

        /** The requester-side `transfer_id` for the raw-frame harnesses below, which drive `fetch`
         *  against a hand-written provider that never issues a token — the id is only what makes
         *  `fetch`'s own cancellation scoping work, and no test here cancels them. */
        val RAW_FRAME_TRANSFER_ID = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B60")
    }
}
