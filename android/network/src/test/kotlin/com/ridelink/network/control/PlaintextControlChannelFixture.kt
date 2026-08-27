package com.ridelink.network.control

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A plaintext [ControlChannel], **for unit tests only**.
 *
 * This file lives in `src/test`. That is the whole isolation mechanism, and it is stronger than
 * the `BuildConfig.DEBUG` gate it replaces: a class in the test source set is not compiled into
 * the library at all, so no app build — debug or release — contains these bytes, and no amount of
 * reflection, configuration or R8 accident can reach them. NFR-06 / PROTOCOL §1 say production
 * control traffic is TLS 1.3; there is no runtime switch here to get wrong.
 *
 * It exists because the framing, duplicate-connection resolution, reconnect ladder and teardown
 * suites want a real socket without a TLS handshake's keys and timing in the way. Anything that
 * concerns *security* — pinning, the exporter, the SAS — is tested against the real
 * [com.ridelink.network.security.TlsControlChannel] instead, in `TlsControlChannelTest`.
 *
 * Its [security] is null, which is what makes the difference visible in the type system:
 * `ControlHandshake` refuses a socket with no [ChannelSecurity] outright, so a test that wants a
 * handshake must use the TLS channel.
 */
class PlaintextControlChannelFixture(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ControlChannel {
    override val transportLabel: String = "PLAINTEXT TEST FIXTURE / NOT SECURE"
    override val isSecure: Boolean = false

    override suspend fun bind(): ControlListener =
        withContext(ioDispatcher) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            ControlListener(server) { accept(it) }
        }

    private suspend fun accept(server: ServerSocket): ControlSocket =
        withContext(ioDispatcher) { wrap(server.accept(), isInitiator = false) }

    override suspend fun connect(
        host: String,
        port: Int,
    ): ControlSocket =
        withContext(ioDispatcher) {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            wrap(socket, isInitiator = true)
        }

    private fun wrap(
        socket: Socket,
        isInitiator: Boolean,
    ): ControlSocket {
        ControlSocket.configureTcp(socket)
        return ControlSocket(socket, isInitiator, ioDispatcher, security = null)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
