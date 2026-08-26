package com.ridelink.network.control

import com.ridelink.core.protocol.DecodeResult
import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.EnvelopeCodec
import com.ridelink.core.protocol.FrameLimits
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * PHASE 1a PLAINTEXT CONTROL TRANSPORT.
 *
 * **`PlainControlTransportPhase1a` — not secure, debug/development builds only.** PROTOCOL §1
 * specifies TCP over **TLS 1.3**; Phase 1b has not started (CLAUDE.md rule 28), so this transport
 * carries the same length-prefixed JSON framing over **plain TCP**. Every type in this file is
 * named or documented so a reviewer cannot mistake it for the production transport, and callers
 * must gate its use behind a debug-build check (wired in `app` — release builds must not
 * construct a [ControlListener] or [ControlSocket] at all).
 *
 * Framing (PROTOCOL §1): `uint32` big-endian byte length, then that many UTF-8 JSON bytes.
 * [FrameLimits.MAX_CONTROL_FRAME_BYTES] is 262 144. The length prefix is read and validated
 * **before** the payload buffer is allocated — [readFrame] returns [FrameReadResult.FrameTooLarge]
 * without ever calling `ByteArray(length)` for an oversized or negative length.
 */
object PlainControlTransportPhase1a

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
 * One control-plane TCP socket, plain (Phase 1a), framed per [PlainControlTransportPhase1a].
 * Blocking I/O confined to [ioDispatcher] (CLAUDE.md "Kotlin coroutines / Dispatchers.IO are
 * appropriate"). A single [ControlSocket] is safe for one concurrent reader and one concurrent
 * writer (typical read-loop + write-on-demand usage); concurrent writers are serialised by
 * [writeLock].
 */
class ControlSocket private constructor(
    private val socket: Socket,
    val isInitiator: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
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

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
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

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        suspend fun connect(
            host: String,
            port: Int,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            connectTimeoutMs: Int = 5000,
        ): ControlSocket =
            withContext(ioDispatcher) {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                configure(socket)
                ControlSocket(socket, isInitiator = true, ioDispatcher = ioDispatcher)
            }

        fun fromAccepted(
            socket: Socket,
            ioDispatcher: CoroutineDispatcher,
        ): ControlSocket {
            configure(socket)
            return ControlSocket(socket, isInitiator = false, ioDispatcher = ioDispatcher)
        }

        private fun configure(socket: Socket) {
            // CLAUDE.md rule 10 / PROTOCOL §1: TCP_NODELAY so clock-sync PING/PONG isn't held by
            // Nagle. OS-level keepalive is best-effort; the application PING/PONG (2s/6s,
            // PROTOCOL §1) remains authoritative for session health, never this.
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }
    }
}

/** Binds an OS-selected dynamic TCP port (PROTOCOL §1) and accepts inbound [ControlSocket]s. */
class ControlListener private constructor(
    private val serverSocket: ServerSocket,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    val localPort: Int get() = serverSocket.localPort

    suspend fun accept(): ControlSocket =
        withContext(ioDispatcher) {
            val socket = serverSocket.accept()
            ControlSocket.fromAccepted(socket, ioDispatcher)
        }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    companion object {
        suspend fun bind(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ControlListener =
            withContext(ioDispatcher) {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(0)) // dynamic port; advertised via mDNS by the caller
                ControlListener(server, ioDispatcher)
            }
    }
}
