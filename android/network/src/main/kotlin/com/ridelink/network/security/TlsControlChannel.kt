package com.ridelink.network.security

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.Sas
import com.ridelink.core.security.IdentityCertificate
import com.ridelink.network.control.ChannelSecurity
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/** PROTOCOL §1 / ADR-007: TLS 1.3 and nothing else. Not a preference — the only enabled protocol. */
private const val TLS_1_3 = "TLSv1.3"

/**
 * The three TLS 1.3 cipher suites. Used to assert the negotiated connection really is TLS 1.3.
 *
 * That check is deliberately not `SSLSession.getProtocol() == "TLSv1.3"`: the Phase 1b spike
 * measured Conscrypt's **server-side** session reporting `TLSv1.2` on a connection where only
 * TLS 1.3 was ever enabled and a TLS-1.3-only suite was negotiated. Asserting on the protocol
 * string would reject good sessions. The suite name cannot lie in the same way, because these
 * three exist only in TLS 1.3 (ADR-018 §3, `docs/test-results/phase1b-security-spike-20260827.md`).
 */
private val TLS_1_3_CIPHER_SUITES =
    setOf("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")

/** How long a TLS handshake may take before the socket is abandoned. */
private const val HANDSHAKE_TIMEOUT_MS = 5_000

/** Matches the Phase 1a `ControlSocket.connect` timeout, and iOS's `connectTimeoutMs`. */
private const val CONNECT_TIMEOUT_MS = 5_000

/**
 * Which TLS 1.3 implementation provides the sockets, and how its keying-material exporter
 * (RFC 8446 §7.5) is reached.
 *
 * **One interface, one production implementation, and a specific reason for the seam.** Android's
 * default JSSE provider *is* Conscrypt-over-BoringSSL and reaches the exporter through
 * `android.net.ssl.SSLSockets` — a class that does not exist on a plain JVM. A desktop JVM's
 * default provider (SunJSSE) speaks TLS 1.3 but exposes **no exporter at all**, which is a gap in
 * the JDK, not in this design. Without this seam the entire secure control plane would be testable
 * only on a phone, and the SAS — the one thing a user is asked to check with their own eyes —
 * would have no laptop test.
 *
 * The test implementation supplies Conscrypt directly, so what the JVM suite exercises is the same
 * TLS stack Android ships and the same `exportKeyingMaterial` the platform class delegates to, one
 * call frame lower. That substitution is stated plainly in
 * `docs/test-results/phase1b-security-spike-20260827.md` and is closed by the real-device gate,
 * not by this comment.
 */
interface TlsProvider {
    fun sslContext(
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        random: SecureRandom,
    ): SSLContext

    /** Returns null when the exporter is unavailable; callers must treat that as a hard failure. */
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray?
}

/**
 * The production provider: the platform's own TLS 1.3 (Conscrypt on Android) plus
 * `android.net.ssl.SSLSockets.exportKeyingMaterial`, public SDK since **API 31** — exactly the
 * ADR-011 `minSdk`, verified against `api-versions.xml` rather than assumed (ADR-018 §1).
 */
object AndroidTlsProvider : TlsProvider {
    override fun sslContext(
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        random: SecureRandom,
    ): SSLContext = SSLContext.getInstance(TLS_1_3).apply { init(keyManagers, trustManagers, random) }

    override fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray? =
        runCatching { android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, context, length) }
            .getOrNull()
}

/**
 * The channel-security facts for one completed TLS 1.3 handshake.
 *
 * Note what is *not* here: no method returns the peer's certificate, its subject, or anything else
 * a caller could accidentally start trusting. Trust is [peerIdentitySpkiSha256] and nothing else
 * (ADR-012).
 */
internal class TlsChannelSecurity(
    private val socket: SSLSocket,
    peerCertificate: X509Certificate,
    override val peerCertificateStructurallyValid: Boolean,
    private val provider: TlsProvider,
) : ChannelSecurity {
    override val peerIdentitySpkiSha256: SpkiHash =
        IdentityCertificate.spkiHashOfEncoded(peerCertificate.publicKey.encoded)

    override val negotiatedCipherSuite: String = socket.session.cipherSuite

    /**
     * PROTOCOL §4.5.1 / ADR-018. The exporter output never leaves this method except as six
     * decimal digits, and neither the digits nor the 32 bytes have any log path
     * (ARCHITECTURE §11).
     */
    override fun deriveSas6(): String? {
        val exported = provider.exportKeyingMaterial(socket, Sas.EXPORTER_LABEL, EMPTY_CONTEXT, Sas.EXPORTER_LENGTH_BYTES)
        return exported?.let(Sas::deriveSas6)
    }

    private companion object {
        /**
         * ADR-018 §2: TLS 1.3 always hashes a context value, so an absent context and an empty one
         * are the same input — measured equal on Conscrypt, and the only form Apple's public API
         * can express. Written as an explicit empty array rather than `null` so the choice reads
         * as deliberate.
         */
        val EMPTY_CONTEXT = ByteArray(0)
    }
}

/**
 * **The production control channel.** TCP + TLS 1.3, mutually authenticated with the device's own
 * self-signed identity certificate (ADR-007, ADR-017).
 *
 * Both ends require a peer certificate. A one-sided handshake would leave the connecting side's
 * identity unbound, which would make the SPKI pin on that side meaningless — the peer would be
 * pinning a key nobody proved possession of.
 *
 * PKI validation is deliberately absent: there is no CA, no hostname to check, and
 * [PinningTrustManager] therefore accepts every certificate at the *transport* layer and records
 * it. Trust is applied one layer up, by [com.ridelink.core.security.PeerTrust], against the
 * pinned `identity_spki_sha256`. That split is what ARCHITECTURE §4.3 describes, and it is why
 * accepting here is not a hole: nothing is trusted until the pin says so, and a `TrustManager`
 * cannot express "ask the user" anyway.
 */
class TlsControlChannel(
    private val identity: DeviceIdentity,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val provider: TlsProvider = AndroidTlsProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : ControlChannel {
    override val transportLabel: String = "TLS 1.3 / MUTUAL / SPKI-PINNED"
    override val isSecure: Boolean = true

    private val sslContext: SSLContext by lazy {
        provider.sslContext(identity.keyManagers(), arrayOf(PinningTrustManager()), secureRandom)
    }

    override suspend fun bind(): ControlListener =
        withContext(ioDispatcher) {
            val server = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.reuseAddress = true
            server.enabledProtocols = arrayOf(TLS_1_3)
            server.needClientAuth = true // both peers prove key possession, or neither pin means anything
            server.bind(InetSocketAddress(0)) // dynamic port; advertised via mDNS by the caller
            ControlListener(server) { socket -> accept(socket) }
        }

    private suspend fun accept(server: ServerSocket): ControlSocket =
        withContext(ioDispatcher) {
            val socket = server.accept() as SSLSocket
            finishHandshake(socket, isInitiator = false)
        }

    override suspend fun connect(
        host: String,
        port: Int,
    ): ControlSocket =
        withContext(ioDispatcher) {
            // Connect the TCP layer with an explicit timeout first, then start TLS on top: an
            // SSLSocketFactory.createSocket(host, port) would connect with no timeout at all.
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val socket =
                runCatching {
                    sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                }.getOrElse { failure ->
                    runCatching { plain.close() }
                    throw failure
                }
            socket.useClientMode = true
            finishHandshake(socket, isInitiator = true)
        }

    private fun finishHandshake(
        socket: SSLSocket,
        isInitiator: Boolean,
    ): ControlSocket {
        ControlSocket.configureTcp(socket)
        socket.enabledProtocols = arrayOf(TLS_1_3)
        try {
            // soTimeout bounds the handshake so an unresponsive peer cannot pin this coroutine
            // forever; it is cleared afterwards because the read loop must block indefinitely and
            // let the application PING/PONG (PROTOCOL §1) decide liveness instead.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 0

            check(socket.session.cipherSuite in TLS_1_3_CIPHER_SUITES) {
                "negotiated ${socket.session.cipherSuite}, which is not a TLS 1.3 cipher suite"
            }
            val peerCertificate =
                socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: error("peer presented no X.509 certificate")

            return ControlSocket(
                socket = socket,
                isInitiator = isInitiator,
                ioDispatcher = ioDispatcher,
                security =
                    TlsChannelSecurity(
                        socket = socket,
                        peerCertificate = peerCertificate,
                        peerCertificateStructurallyValid = isStructurallyValid(peerCertificate),
                        provider = provider,
                    ),
            )
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    /**
     * The structural half of PROTOCOL §4.1's certificate check: well-formed, self-signature
     * verifies, inside its validity window. Explicitly **not** a chain or hostname check — there
     * is no CA and no name to check.
     *
     * A false here becomes `ERROR/certificate_invalid`, which is a distinct code from
     * `pin_mismatch` precisely so a phone with a wrong clock is not reported to its owner as an
     * attack (ADR-012).
     */
    private fun isStructurallyValid(certificate: X509Certificate): Boolean =
        runCatching {
            certificate.checkValidity()
            certificate.verify(certificate.publicKey) // self-signed: it is its own issuer
            true
        }.getOrDefault(false)

    /**
     * Accepts every certificate at the transport layer and lets the SPKI pin decide, one layer up.
     *
     * `X509ExtendedTrustManager` rather than the plain interface: JSSE calls the socket/engine
     * overloads when they exist, and a subclass that only overrides the two-argument forms would
     * silently not be consulted.
     */
    private class PinningTrustManager : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
