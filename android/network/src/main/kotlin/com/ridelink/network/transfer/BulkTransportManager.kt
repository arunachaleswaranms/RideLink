package com.ridelink.network.transfer

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.transfer.BulkFraming
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.security.TlsControlChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Supplies chunk bytes to send, in order, starting at index 0. Returns null when exhausted. */
fun interface ChunkSource {
    suspend fun nextChunk(): ByteArray?
}

/** Consumes chunk bytes as they arrive, in order. Hashing/disk-writing is the caller's concern. */
fun interface ChunkSink {
    suspend fun onChunk(
        index: Long,
        bytes: ByteArray,
    )
}

enum class BulkServeOutcome { OK, NOT_AUTHORIZED, CONNECTION_LOST, IO_ERROR }

enum class BulkFetchOutcome { OK, NOT_AUTHORIZED, CONNECTION_LOST, IO_ERROR, PROTOCOL_ERROR }

/**
 * ADR-023 — one bulk TLS listener per authenticated **session** (not per transfer), the same
 * identity as the control connection, SPKI-pinned, single-use-token-authorised per transfer, and
 * bounded to one active transfer at a time (brief §20).
 *
 * Reuses [TlsControlChannel] wholesale for the bulk connection's TLS setup rather than
 * duplicating it — same mutual TLS 1.3, same accept-then-pin-one-layer-up shape (ADR-007,
 * ADR-012, ADR-017) — and [ControlSocket]'s raw byte I/O (`writeRawBytes`/`readRawBytes`) instead
 * of its JSON envelope framing, which the bulk plane never uses.
 */
class BulkTransportManager(
    private val tlsChannel: TlsControlChannel,
    monotonicNowUs: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    val tokenTable = BulkTokenTable(monotonicNowUs)

    private var listener: ControlListener? = null
    private val listenerMutex = Mutex()

    /** Brief §20: one active transfer per session — additional requests queue above this manager. */
    private val activeTransferMutex = Mutex()

    /** Opens the listener on first need; a later call just returns the already-bound port. */
    suspend fun ensureListening(): Int =
        listenerMutex.withLock {
            listener?.localPort ?: tlsChannel.bind().also { listener = it }.localPort
        }

    fun issueToken(
        transferId: TransferId,
        generation: Long,
    ): String = tokenTable.issue(transferId, generation)

    /** Call on every fresh authentication (ADR-023 §3) — sweeps tokens from any earlier generation. */
    fun onNewGeneration(generation: Long) {
        tokenTable.sweepBelow(generation)
    }

    /** ADR-023 §1: the listener never outlives the session that opened it. */
    override fun close() {
        runCatching { listener?.close() }
        listener = null
        tokenTable.clear()
    }

    /**
     * Provider side: accept exactly one bulk connection, verify its SPKI and single-use token, then
     * stream [source]'s chunks to it. SPKI is checked **before** the token is even read (ADR-023 §4
     * — two independent checks, neither standing in for the other).
     *
     * The IOException's message never reaches a caller that could act on it differently — every
     * catch site here already reduces to one of the small [BulkServeOutcome] values, exactly like
     * `readFrame`'s `ConnectionClosed` result elsewhere in this module.
     */
    @Suppress("SwallowedException")
    suspend fun serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: () -> Long,
        source: ChunkSource,
    ): BulkServeOutcome =
        activeTransferMutex.withLock {
            val l = listener ?: return BulkServeOutcome.IO_ERROR
            val socket =
                try {
                    l.accept()
                } catch (io: IOException) {
                    return BulkServeOutcome.IO_ERROR
                }
            try {
                val peerSpki = socket.security?.peerIdentitySpkiSha256
                if (peerSpki == null || peerSpki != expectedPeerSpki) return BulkServeOutcome.NOT_AUTHORIZED
                val tokenBytes = ByteArray(TOKEN_BYTES)
                if (!readExactly(socket, tokenBytes)) return BulkServeOutcome.CONNECTION_LOST
                val presented = tokenBytes.hex()
                if (!tokenTable.validateAndConsume(transferId, presented, currentGeneration())) {
                    return BulkServeOutcome.NOT_AUTHORIZED
                }
                var index = 0L
                while (true) {
                    val chunk = source.nextChunk() ?: break
                    socket.writeRawBytes(BulkFraming.encodeFrame(index, chunk))
                    index += 1
                }
                BulkServeOutcome.OK
            } catch (io: IOException) {
                BulkServeOutcome.IO_ERROR
            } finally {
                socket.close()
            }
        }

    /** Requester side: dial the provider's bulk port, present the token, stream chunks into [sink]. */
    @Suppress("ReturnCount", "SwallowedException")
    suspend fun fetch(
        host: String,
        port: Int,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Long,
        sink: ChunkSink,
    ): BulkFetchOutcome =
        activeTransferMutex.withLock {
            val socket =
                try {
                    tlsChannel.connect(host, port)
                } catch (io: IOException) {
                    return BulkFetchOutcome.CONNECTION_LOST
                }
            try {
                val peerSpki = socket.security?.peerIdentitySpkiSha256
                if (peerSpki == null || peerSpki != expectedPeerSpki) return BulkFetchOutcome.NOT_AUTHORIZED
                socket.writeRawBytes(token.hexToBytes())
                var buffer = ByteArray(0)
                var received = 0L
                val readBuf = ByteArray(READ_BUFFER_BYTES)
                while (received < expectedChunkCount) {
                    val n = withContext(ioDispatcher) { socket.readRawBytes(readBuf, 0, readBuf.size) }
                    if (n < 0) return BulkFetchOutcome.CONNECTION_LOST
                    buffer += readBuf.copyOf(n)
                    when (val result = BulkFraming.parseAll(buffer)) {
                        is BulkFraming.ParseResult.Parsed -> {
                            for (frame in result.frames) {
                                sink.onChunk(frame.chunkIndex, frame.payload)
                                received += 1
                            }
                            buffer = result.leftover
                        }
                        BulkFraming.ParseResult.Incomplete -> Unit
                        is BulkFraming.ParseResult.Invalid -> return BulkFetchOutcome.PROTOCOL_ERROR
                    }
                }
                BulkFetchOutcome.OK
            } catch (io: IOException) {
                BulkFetchOutcome.IO_ERROR
            } finally {
                socket.close()
            }
        }

    private suspend fun readExactly(
        socket: ControlSocket,
        into: ByteArray,
    ): Boolean {
        var offset = 0
        while (offset < into.size) {
            val n = socket.readRawBytes(into, offset, into.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val READ_BUFFER_BYTES = 16_384
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray {
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(this[2 * i], HEX_RADIX) shl HEX_SHIFT) + Character.digit(this[2 * i + 1], HEX_RADIX)).toByte()
    }
    return out
}

private const val HEX_RADIX = 16
private const val HEX_SHIFT = 4
