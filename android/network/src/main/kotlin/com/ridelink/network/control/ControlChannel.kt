package com.ridelink.network.control

import com.ridelink.core.model.SpkiHash

/**
 * What one established control connection knows about the peer at the other end, once the
 * transport's own handshake has completed.
 *
 * This is the seam between the framing layer and the security layer: [ControlSessionManager] never
 * imports a TLS type, and the pin check ([com.ridelink.core.security.PeerTrust]) never imports a
 * socket type. Only the facts below cross.
 */
interface ChannelSecurity {
    /** `identity_spki_sha256` computed from the peer's certificate. The **only** trustworthy identity input. */
    val peerIdentitySpkiSha256: SpkiHash

    /**
     * The platform's verdict on the peer certificate's structure, self-signature and validity
     * window — not on any PKI chain, which RideLink deliberately does not use (ARCHITECTURE §4.3).
     */
    val peerCertificateStructurallyValid: Boolean

    /** Negotiated cipher suite, used to assert TLS 1.3 (ADR-018 §3) and shown in diagnostics. */
    val negotiatedCipherSuite: String

    /**
     * The six-digit pairing code for **this** handshake (PROTOCOL §4.5.1, ADR-018).
     *
     * Never logged, never persisted, never sent — there is no log path for it at all
     * (ARCHITECTURE §11). Returns null if the exporter is unavailable, which is a hard failure:
     * pairing must not proceed without a channel binding.
     */
    fun deriveSas6(): String?
}

/**
 * How control-plane bytes get from one phone to the other.
 *
 * **Production has exactly one implementation, and it is TLS 1.3.** PROTOCOL §1 and NFR-06 do not
 * admit a plaintext production path, so there is no runtime switch here and no fallback: a
 * `ControlChannel` that is not secure exists only as a test fixture, lives only in `src/test`, and
 * therefore cannot be linked into any app build — debug or release — at all.
 */
interface ControlChannel {
    /** Shown verbatim in the diagnostics UI. Must never claim security the channel does not have. */
    val transportLabel: String

    /** False only for the test-only plaintext fixture. [ControlSessionManager] refuses to pair over a false. */
    val isSecure: Boolean

    /** Binds an OS-selected dynamic port (PROTOCOL §1) and starts accepting. */
    suspend fun bind(): ControlListener

    /** Dials a peer and completes the transport handshake before returning. */
    suspend fun connect(
        host: String,
        port: Int,
    ): ControlSocket
}
