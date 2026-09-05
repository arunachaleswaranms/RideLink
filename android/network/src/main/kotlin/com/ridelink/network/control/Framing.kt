package com.ridelink.network.control

import com.ridelink.core.protocol.DecodeResult
import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.EnvelopeCodec
import com.ridelink.core.protocol.FrameLimits
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Control-plane framing (PROTOCOL §1): `uint32` big-endian byte length, then that many UTF-8 JSON
 * bytes. [FrameLimits.MAX_CONTROL_FRAME_BYTES] is 262 144, and the length prefix is read and
 * validated **before** the payload buffer is allocated — [ControlSocket.readFrame] returns
 * [FrameReadResult.FrameTooLarge] without ever calling `ByteArray(length)` for an oversized or
 * negative length.
 *
 * Transport-neutral by design. Whether the bytes underneath are TLS records (production, the only
 * option — see [ControlChannel]) or plaintext (a test fixture) is decided by the [ControlChannel]
 * that produced the socket, and nothing in this file knows or cares.
 */
sealed class FrameReadResult {
    data class Frame(
        val envelope: Envelope,
        val versionOk: Boolean,
    ) : FrameReadResult()

    /** The 4-byte length prefix exceeded the cap (or was negative). No body was read. */
    data class FrameTooLarge(
        val declaredLength: Int,
    ) : FrameReadResult()

    /** The body was read in full but failed to decode (PROTOCOL §2 `malformed_frame`). */
    data class Malformed(
        val errorCode: String,
    ) : FrameReadResult()

    /** EOF or an I/O error mid-read: the peer is gone. */
    object ConnectionClosed : FrameReadResult()
}

/**
 * One control-plane socket, framed per the rules above. Blocking I/O confined to [ioDispatcher]
 * (CLAUDE.md "Kotlin coroutines / Dispatchers.IO are appropriate"). Safe for one concurrent reader
 * and one concurrent writer (the read-loop + write-on-demand shape the session manager uses);
 * concurrent writers are serialised by [writeLock].
 *
 * Construct these through a [ControlChannel], never directly — that is what guarantees a
 * production socket has completed a TLS 1.3 handshake and carries a non-null [security].
 */
class ControlSocket internal constructor(
    private val socket: Socket,
    val isInitiator: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    /** Non-null on every production socket. Null only on the test-only plaintext fixture. */
    val security: ChannelSecurity?,
) : AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Mutex()

    val remoteHost: String get() = socket.inetAddress?.hostAddress ?: "unknown"
    val remotePort: Int get() = socket.port
    val isClosed: Boolean get() = socket.isClosed

    suspend fun writeFrame(envelope: Envelope): Unit =
        withContext(ioDispatcher) {
            val bytes = EnvelopeCodec.encode(envelope)
            check(bytes.size <= FrameLimits.MAX_CONTROL_FRAME_BYTES) {
                "refusing to send an outgoing frame (${bytes.size} bytes) over the cap"
            }
            writeLock.withLock {
                output.writeInt(bytes.size) // DataOutputStream.writeInt is big-endian (PROTOCOL §1)
                output.write(bytes)
                output.flush()
            }
        }

    @Suppress("ReturnCount", "SwallowedException")
    suspend fun readFrame(): FrameReadResult =
        withContext(ioDispatcher) {
            val length =
                try {
                    input.readInt()
                } catch (eof: EOFException) {
                    return@withContext FrameReadResult.ConnectionClosed
                } catch (io: IOException) {
                    return@withContext FrameReadResult.ConnectionClosed
                }

            // The length is validated BEFORE any payload buffer is allocated — an
            // attacker-specified oversized length never causes an oversized allocation.
            if (length < 0 || length > FrameLimits.MAX_CONTROL_FRAME_BYTES) {
                return@withContext FrameReadResult.FrameTooLarge(length)
            }

            val buffer = ByteArray(length)
            try {
                input.readFully(buffer)
            } catch (io: IOException) {
                return@withContext FrameReadResult.ConnectionClosed
            }

            when (val decoded = EnvelopeCodec.decode(buffer)) {
                is DecodeResult.Success -> FrameReadResult.Frame(decoded.envelope, decoded.versionOk)
                is DecodeResult.Failure -> FrameReadResult.Malformed(decoded.errorCode)
            }
        }

    /**
     * PROTOCOL §8.2's bulk plane — raw byte I/O that bypasses the JSON envelope framing entirely.
     * Never called from the control read loop; used only by the bulk transport (ADR-023), which
     * reuses this same TLS+SPKI-pinned socket machinery for its own, differently-framed (RLB1)
     * connection rather than duplicating the TLS setup.
     */
    suspend fun writeRawBytes(bytes: ByteArray): Unit =
        withContext(ioDispatcher) {
            writeLock.withLock {
                output.write(bytes)
                output.flush()
            }
        }

    /** @return the number of bytes actually read (may be less than [length]), or -1 at EOF. */
    suspend fun readRawBytes(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        withContext(ioDispatcher) {
            input.read(buffer, offset, length)
        }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /**
         * CLAUDE.md rule 10 / PROTOCOL §1: `TCP_NODELAY` so clock-sync PING/PONG is not held by
         * Nagle. OS-level keepalive is best-effort; the application PING/PONG (2 s / 6 s,
         * PROTOCOL §1) remains authoritative for session health, never this.
         */
        internal fun configureTcp(socket: Socket) {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }
    }
}

/**
 * An accepting control-plane listener on an OS-selected dynamic TCP port (PROTOCOL §1). The port
 * is what discovery advertises, so exactly one socket is bound per session.
 *
 * [acceptOne] is supplied by the owning [ControlChannel]: it is where a TLS server handshake runs
 * and where a socket acquires its [ChannelSecurity], so that neither this class nor
 * [ControlSessionManager] has to know a transport exists.
 */
class ControlListener internal constructor(
    private val serverSocket: ServerSocket,
    private val acceptOne: suspend (ServerSocket) -> ControlSocket,
) : AutoCloseable {
    val localPort: Int get() = serverSocket.localPort

    suspend fun accept(): ControlSocket = acceptOne(serverSocket)

    override fun close() {
        runCatching { serverSocket.close() }
    }
}
