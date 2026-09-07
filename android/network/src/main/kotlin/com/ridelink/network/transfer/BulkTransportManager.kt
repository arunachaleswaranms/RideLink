package com.ridelink.network.transfer

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.transfer.BulkFraming
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSocket
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
 * ADR-023 — at most one **live** bulk TLS listener per authenticated session (never one per
 * transfer), the same identity as the control connection, SPKI-pinned, single-use-token-authorised
 * per transfer, and bounded to one active transfer at a time (brief §20). A listener never outlives
 * its own session; Amendment A5 lets a session open a *replacement* within its own life, when
 * cancelling a pending accept ends the current one — see [cancelActive].
 *
 * Reuses [com.ridelink.network.security.TlsControlChannel] wholesale for the bulk connection's TLS
 * setup rather than duplicating it — same mutual TLS 1.3, same accept-then-pin-one-layer-up shape
 * (ADR-007, ADR-012, ADR-017) — and [ControlSocket]'s raw byte I/O (`writeRawBytes`/`readRawBytes`)
 * instead of its JSON envelope framing, which the bulk plane never uses.
 */
class BulkTransportManager(
    /**
     * Production always wires [com.ridelink.network.security.TlsControlChannel] here — PROTOCOL §1
     * and CLAUDE.md rule 14 admit no plaintext production transport, and `AppContainer` is the one
     * call site. Declared as the [ControlChannel] interface — exactly as
     * [com.ridelink.network.control.ControlSessionManager] already declares its own — so ADR-023
     * Amendment A5's bind-versus-close publication race can be driven by a test double whose
     * `bind()` suspends on demand, rather than by hoping a real TLS bind happens to be slow.
     */
    private val channel: ControlChannel,
    monotonicNowUs: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    val tokenTable = BulkTokenTable(monotonicNowUs)

    /**
     * ADR-023 Amendment A5 — which transfer owns this transport right now, and how far along it is.
     * A4 tracked only the socket, which exists only *after* `accept()`/`connect()` returns, so a
     * `TRANSFER_CANCEL` arriving while [serve] was still parked in `accept()` had nothing to act on
     * (that amendment's Finding W recorded the gap and bounded it with a 30 s timeout rather than
     * closing it). Naming the owner from the moment the wait begins is what makes an explicit
     * cancellation able to end that wait, and what makes a cancellation naming a *different*
     * transfer a no-op instead of a way to disturb this one.
     */
    private sealed class Operation {
        abstract val transferId: TransferId

        /** [serve] is parked in `accept()`; no socket exists yet, and the listener is what a cancel must break. */
        class WaitingForAccept(
            override val transferId: TransferId,
        ) : Operation()

        /** A real socket is open — the A4 state, now carrying the `transfer_id` it belongs to on both platforms. */
        class Connected(
            override val transferId: TransferId,
            val socket: ControlSocket,
        ) : Operation()
    }

    /**
     * Guards [listener], [listenerEpoch] and [operation]. A plain monitor, not [listenerMutex]:
     * [close] and [cancelActive] are ordinary non-suspending functions called from a session
     * boundary and from `TRANSFER_CANCEL` routing, so they cannot take a coroutine [Mutex] at all —
     * which is precisely how A5's Finding B arose (a suspended `bind()` could publish its listener
     * after a `close()` that had already run and found nothing to close).
     */
    private val lifecycleLock = Any()

    /** Guarded by [lifecycleLock]. */
    private var listener: ControlListener? = null

    /**
     * ADR-023 Amendment A5 Finding B — the bulk listener's lifetime, bumped whenever a listener is
     * torn down ([close], or [cancelActive] abandoning a pending accept). [ensureListening] reads
     * it before suspending in `bind()` and re-reads it before publishing: a bind belonging to an
     * already-ended lifetime closes what it bound and fails, instead of resurrecting a listener for
     * a session that is over. Guarded by [lifecycleLock].
     */
    private var listenerEpoch = 0L

    /** Guarded by [lifecycleLock]. */
    private var operation: Operation? = null

    /** Brief §20: one active transfer per session — additional requests queue above this manager. */
    private val activeTransferMutex = Mutex()

    /** Serialises concurrent [ensureListening] callers so only one `bind()` is ever in flight. Publication
     *  safety is [listenerEpoch]'s job, not this lock's — see [lifecycleLock]. */
    private val listenerMutex = Mutex()

    /** Test-only: the port currently published, or `null` if no listener is published at all. */
    internal val listenerPortForTesting: Int? get() = synchronized(lifecycleLock) { listener?.localPort }

    /** Test-only: the transfer parked in `accept()`, if any — the deterministic signal a cancel-before-accept
     *  test waits on instead of guessing with a sleep. */
    internal val pendingAcceptTransferIdForTesting: TransferId?
        get() = synchronized(lifecycleLock) { (operation as? Operation.WaitingForAccept)?.transferId }

    /** Test-only: the transfer holding a real open socket, if any. */
    internal val connectedTransferIdForTesting: TransferId?
        get() = synchronized(lifecycleLock) { (operation as? Operation.Connected)?.transferId }

    /**
     * Opens the listener on first need; a later call just returns the already-bound port.
     *
     * Amendment A5 Finding B: `bind()` suspends, and [close] can run inside that suspension. The
     * epoch captured before the bind and re-checked at publication is what stops the resumed call
     * publishing a listener into a session that has already been torn down.
     *
     * @throws IOException if the bind itself fails, or if the transport's listener lifetime ended
     *   while this call was binding.
     */
    suspend fun ensureListening(): Int =
        listenerMutex.withLock {
            val started = synchronized(lifecycleLock) { Lifetime(listener, listenerEpoch) }
            started.listener?.let { return@withLock it.localPort }
            val bound = channel.bind()
            val outcome =
                synchronized(lifecycleLock) {
                    when {
                        listenerEpoch != started.epoch -> BindOutcome(port = null, publishedBound = false)
                        // A concurrent caller in the same lifetime already published one; take theirs.
                        listener != null -> BindOutcome(port = listener?.localPort, publishedBound = false)
                        else -> {
                            listener = bound
                            BindOutcome(port = bound.localPort, publishedBound = true)
                        }
                    }
                }
            if (!outcome.publishedBound) runCatching { bound.close() }
            outcome.port ?: throw IOException("the bulk listener lifetime ended while it was binding")
        }

    private class Lifetime(
        val listener: ControlListener?,
        val epoch: Long,
    )

    private class BindOutcome(
        val port: Int?,
        val publishedBound: Boolean,
    )

    fun issueToken(
        transferId: TransferId,
        generation: Long,
    ): String = tokenTable.issue(transferId, generation)

    /** Finding M: see [BulkTokenTable.tryIssue] — `null` if [transferId] already has a live,
     *  unconsumed token, rather than silently invalidating it. */
    fun tryIssueToken(
        transferId: TransferId,
        generation: Long,
    ): String? = tokenTable.tryIssue(transferId, generation)

    /** Call on every fresh authentication (ADR-023 §3) — sweeps tokens from any earlier generation. */
    fun onNewGeneration(generation: Long) {
        tokenTable.sweepBelow(generation)
    }

    /**
     * ADR-023 §1: the listener never outlives the session that opened it.
     *
     * Amendment A5 Finding B: the epoch bump comes **first**, before anything is closed, so an
     * [ensureListening] call already suspended inside `bind()` can never publish its result into
     * this now-dead lifetime — no matter when it resumes.
     */
    override fun close() {
        synchronized(lifecycleLock) {
            // A WaitingForAccept operation needs no separate action: the listener this just closed
            // is exactly what its parked accept() is blocked on.
            endListenerLifetime()
            (operation as? Operation.Connected)?.let { runCatching { it.socket.close() } }
            operation = null
        }
        tokenTable.clear()
    }

    /**
     * Closure-audit Finding C/D/N, made phase-aware by ADR-023 Amendment A5: forcibly terminates
     * the in-flight [serve]/[fetch] call **if and only if** it is the one [transferId] owns — a user
     * cancellation, or a peer's `TRANSFER_CANCEL` for the transfer this manager is actively serving
     * or fetching. A cancel naming a stale, foreign or already-finished transfer is a no-op, never a
     * way to disturb an unrelated operation (the manager-wide `cancelActive()` this replaces on
     * Android could not tell the difference; iOS gained the same guard in A3).
     *
     * Both phases terminate, which is the whole of A5's Finding A:
     * - **Connected** — close the socket. Closing it, rather than merely requesting coroutine
     *   cancellation, is what actually unblocks a blocking read/write.
     * - **WaitingForAccept** — there is no socket yet, so the *listener* is what the parked
     *   `accept()` is blocked on: end its lifetime and close it. `ensureListening()` binds a fresh
     *   one for the next transfer, and the bumped epoch stops any bind suspended right now from
     *   resurrecting the old one.
     *
     * Either way [transferId]'s bulk token is dropped: an offer the peer has just cancelled must not
     * stay authorised for the remainder of its 30 s TTL (ADR-023 §2).
     *
     * Idempotent and safe to call when nothing matching is active.
     */
    fun cancelActive(transferId: TransferId) {
        val terminated =
            synchronized(lifecycleLock) {
                val current = operation
                if (current == null || current.transferId != transferId) {
                    false
                } else {
                    when (current) {
                        is Operation.Connected -> runCatching { current.socket.close() }
                        is Operation.WaitingForAccept -> endListenerLifetime()
                    }
                    operation = null
                    true
                }
            }
        if (terminated) tokenTable.remove(transferId)
    }

    /** Ends the current listener's lifetime: nothing bound under the old epoch may be published afterwards.
     *  Must be called while holding [lifecycleLock]. */
    private fun endListenerLifetime() {
        listenerEpoch += 1
        runCatching { listener?.close() }
        listener = null
    }

    /**
     * Provider side: accept exactly one bulk connection, verify its SPKI and single-use token, then
     * stream [source]'s chunks to it. SPKI is checked **before** the token is even read (ADR-023 §4
     * — two independent checks, neither standing in for the other).
     *
     * [expectedChunkCount] is the `chunk_count` the caller already promised in its `TRANSFER_OFFER`
     * (closure-audit Amendment A4 Finding T) — this method will never write more frames than that,
     * mirroring the same bound [fetch] enforces from the receiving side.
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
        expectedChunkCount: Long,
        source: ChunkSource,
    ): BulkServeOutcome =
        activeTransferMutex.withLock {
            val l =
                synchronized(lifecycleLock) {
                    val bound = listener ?: return BulkServeOutcome.IO_ERROR
                    // Amendment A5 Finding A: claim the pending accept *before* parking in it, so a
                    // TRANSFER_CANCEL naming this transfer has something to act on. Set under the
                    // same lock that cancelActive/close read, with no suspension between the claim
                    // and the accept below.
                    operation = Operation.WaitingForAccept(transferId)
                    bound
                }
            val socket =
                try {
                    // Amendment A4 Finding W: bounded by the bulk token's own 30 s TTL (ADR-023
                    // §2). This bound stays, as defence in depth for the cases no cancellation ever
                    // arrives for — the peer crashed, the negotiation was simply abandoned, or the
                    // TRANSFER_CANCEL never reached us. An explicit cancel no longer waits it out:
                    // Amendment A5 ends this accept promptly by closing the listener it is parked
                    // on. Without the bound, an abandoned negotiation would hold the single
                    // activeTransferMutex slot — and the coordinator's BulkOperationGate above it —
                    // against every transfer in both directions until the next session boundary.
                    l.acceptWithin(ACCEPT_TIMEOUT_MS)
                } catch (io: IOException) {
                    clearOperation(transferId)
                    return BulkServeOutcome.IO_ERROR
                }
            // Amendment A5: a cancel can land in the instant between accept() returning a socket
            // and this claim. Promote only if this call still owns the pending accept — otherwise
            // the operation was cancelled (or the session closed) and this socket must not be
            // served. Belt-and-braces with the token removal cancelActive already did, which would
            // fail the authorisation below anyway; this makes it structural rather than incidental.
            val promoted =
                synchronized(lifecycleLock) {
                    val current = operation
                    if (current is Operation.WaitingForAccept && current.transferId == transferId) {
                        operation = Operation.Connected(transferId, socket)
                        true
                    } else {
                        false
                    }
                }
            if (!promoted) {
                socket.close()
                return BulkServeOutcome.NOT_AUTHORIZED
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
                    // Closure-audit Amendment A4 Finding T: the provider already declared
                    // `chunk_count` in its TRANSFER_OFFER (PROTOCOL §8.2) — emitting more frames
                    // than that is the provider violating its own offer, and the requester's
                    // Finding K index check would (correctly) reject the whole transfer. Refuse
                    // here instead, so a [source] that outruns its declared count — a short-reading
                    // stream, or a local file that grew between the size check and the open — fails
                    // as this side's own IO_ERROR rather than as the peer's PROTOCOL_ERROR.
                    if (index >= expectedChunkCount) return BulkServeOutcome.IO_ERROR
                    socket.writeRawBytes(BulkFraming.encodeFrame(index, chunk))
                    index += 1
                }
                BulkServeOutcome.OK
            } catch (io: IOException) {
                BulkServeOutcome.IO_ERROR
            } finally {
                socket.close()
                clearOperation(transferId)
            }
        }

    /**
     * Requester side: dial the provider's bulk port, present the token, stream chunks into [sink].
     *
     * [transferId] is ADR-023 Amendment A5's addition on Android — it is what makes
     * [cancelActive] operation-aware for the requester role exactly as it already is for [serve]'s
     * provider role (iOS gained the same parameter in A3).
     */
    @Suppress("ReturnCount", "SwallowedException", "LongParameterList")
    suspend fun fetch(
        transferId: TransferId,
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
                    channel.connect(host, port)
                } catch (io: IOException) {
                    return BulkFetchOutcome.CONNECTION_LOST
                }
            synchronized(lifecycleLock) { operation = Operation.Connected(transferId, socket) }
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
                                // Closure-audit Finding K: PROTOCOL §8.2's explicit chunk_index only
                                // means something if it is checked. Reject anything but the exact
                                // expected next index — duplicate, skipped, out-of-order, or a frame
                                // beyond the offer's own declared chunk_count — rather than merely
                                // counting frames and trusting the final whole-file hash to catch it.
                                if (received >= expectedChunkCount || frame.chunkIndex != received) {
                                    return BulkFetchOutcome.PROTOCOL_ERROR
                                }
                                sink.onChunk(frame.chunkIndex, frame.payload)
                                received += 1
                            }
                            buffer = result.leftover
                        }
                        BulkFraming.ParseResult.Incomplete -> Unit
                        is BulkFraming.ParseResult.Invalid -> return BulkFetchOutcome.PROTOCOL_ERROR
                    }
                }
                // Closure-audit Finding K (section 12): satisfying `expectedChunkCount` is not by
                // itself proof that nothing more was sent. `buffer` here is whatever [BulkFraming]
                // could not yet fully parse when the loop above stopped reading — a non-empty
                // leftover is the start of an extra frame already received but never counted. A
                // well-behaved provider ([serve]) closes its socket immediately after its last
                // chunk, so one more read is expected to see EOF; anything else — more bytes, not a
                // clean close — means the provider sent past its own declared chunk_count.
                if (buffer.isNotEmpty()) return BulkFetchOutcome.PROTOCOL_ERROR
                val trailing = withContext(ioDispatcher) { socket.readRawBytes(readBuf, 0, readBuf.size) }
                if (trailing > 0) return BulkFetchOutcome.PROTOCOL_ERROR
                BulkFetchOutcome.OK
            } catch (io: IOException) {
                BulkFetchOutcome.IO_ERROR
            } finally {
                socket.close()
                clearOperation(transferId)
            }
        }

    /** Clears the operation slot only if [transferId] still owns it — a late cleanup from an
     *  operation a [cancelActive]/[close] already ended never clears a fresher one. */
    private fun clearOperation(transferId: TransferId) {
        synchronized(lifecycleLock) {
            if (operation?.transferId == transferId) operation = null
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

        /** ADR-023 §2's `bulk_token` TTL — see [BulkTokenTable.TTL_US], the same bound in µs. */
        const val ACCEPT_TIMEOUT_MS = 30_000
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
