package com.ridelink.network.security

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.IdentityCertificate
import com.ridelink.core.security.UtcTime
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/**
 * The device's long-term RideLink identity, in the shape the TLS layer needs it (ADR-012, ADR-017).
 *
 * The private key is **never** here — it stays behind an opaque `PrivateKey` handle whose material
 * lives in Android Keystore. [keyManagers] hands that handle to JSSE, which is the only thing that
 * ever uses it, and it is never serialised, never written to a file and never logged.
 */
class DeviceIdentity internal constructor(
    val certificate: X509Certificate,
    private val keyStore: KeyStore,
    private val alias: String,
    private val keyPassword: CharArray?,
) {
    /**
     * `identity_spki_sha256`. Computed from `PublicKey.getEncoded()`, which on the JVM/Android
     * **is** the DER SubjectPublicKeyInfo — so this is a hash of the exact bytes, not a
     * reconstruction (ADR-017 §4).
     */
    val identitySpkiSha256: SpkiHash = IdentityCertificate.spkiHashOfEncoded(certificate.publicKey.encoded)

    fun keyManagers(): Array<KeyManager> {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, keyPassword)
        return factory.keyManagers
    }

    companion object {
        /** RideLink's key/signature pair, fixed for both platforms by ADR-017 §1. */
        const val KEY_ALGORITHM: String = "EC"
        const val CURVE: String = "secp256r1"
        const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"
    }
}

/**
 * Turns a P-256 keypair into the RideLink identity certificate, using
 * [com.ridelink.core.security.IdentityCertificate] for the encoding and the platform's own
 * `Signature` for the signing (ADR-017 §3 — we arrange bytes, the platform does the cryptography).
 *
 * Kept free of `android.*` on purpose: the encoding, the signing call and the extraction below are
 * then all exercisable by a plain JVM unit test against a real X.509 parser, instead of only on a
 * phone. [AndroidKeystoreIdentityStore] is the thin part that is not.
 */
object IdentityIssuer {
    /**
     * Issues (or re-issues) a self-signed certificate around [publicKey].
     *
     * Re-issuance around an **unchanged** key is a first-class operation, not an afterthought:
     * ADR-012 says the pin is the key, so a fresh certificate for an old key is a non-event that
     * must not prompt the user. It is also the reason RideLink does not use Android's
     * `KeyGenParameterSpec` auto-issued certificate, which can only be minted when the key is
     * created (ADR-017 §3).
     *
     * @param signer already initialised for signing with the matching private key.
     * @param issuedAt wall-clock time, supplied by the caller. X.509 validity is defined in
     *   wall-clock terms by RFC 5280 and has no monotonic alternative — see
     *   [com.ridelink.core.security.UtcTime] for why this is the one permitted exception.
     */
    fun issue(
        publicKey: PublicKey,
        signer: Signature,
        issuedAt: UtcTime,
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        val point = uncompressedPoint(publicKey)
        val (notBefore, notAfter) = IdentityCertificate.validityWindow(issuedAt)
        val tbs = IdentityCertificate.tbsCertificate(point, freshSerial(random), notBefore, notAfter)
        signer.update(tbs)
        val der = IdentityCertificate.certificate(tbs, signer.sign())
        return parseCertificate(der)
    }

    /**
     * 16 CSPRNG bytes with the top bit cleared, so the DER INTEGER is unambiguously positive
     * without a leading pad byte (ADR-017 §2).
     */
    fun freshSerial(random: SecureRandom = SecureRandom()): ByteArray {
        val serial = ByteArray(IdentityCertificate.SERIAL_BYTES)
        random.nextBytes(serial)
        serial[0] = (serial[0].toInt() and 0x7F).toByte()
        return serial
    }

    /** Round-trips our own DER through the platform's X.509 parser, so a malformed encoding fails here. */
    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /**
     * The raw uncompressed X9.63 point (`0x04 ‖ X(32) ‖ Y(32)`) for a P-256 public key.
     *
     * The curve check is done by *rebuilding* the SubjectPublicKeyInfo from the extracted point
     * and requiring it to equal `publicKey.encoded`. That is stronger than inspecting curve
     * parameters and cheaper to be sure of: it rejects every non-P-256 key, and it simultaneously
     * proves at runtime that the two routes to `identity_spki_sha256` — Android's
     * hash-the-encoded-SPKI and iOS's rebuild-from-the-point (ADR-017 §4) — agree on this key.
     */
    fun uncompressedPoint(publicKey: PublicKey): ByteArray {
        require(publicKey is ECPublicKey) { "RideLink identity keys are EC P-256 (ADR-017)" }
        val point =
            byteArrayOf(UNCOMPRESSED_TAG) +
                fixedWidth(publicKey.w.affineX.toByteArray()) +
                fixedWidth(publicKey.w.affineY.toByteArray())
        require(IdentityCertificate.subjectPublicKeyInfo(point).contentEquals(publicKey.encoded)) {
            "public key is not P-256 in the canonical SubjectPublicKeyInfo encoding (ADR-017 §1)"
        }
        return point
    }

    private const val UNCOMPRESSED_TAG: Byte = 0x04
    private const val COORDINATE_BYTES = 32

    /**
     * `BigInteger.toByteArray()` is signed and variable width: it drops leading zero bytes and
     * prepends one when the top bit is set. A P-256 coordinate must be exactly 32 bytes, so both
     * cases have to be corrected — silently getting this wrong shifts the whole point and produces
     * a valid-looking but different `identity_spki_sha256`.
     */
    private fun fixedWidth(signedMagnitude: ByteArray): ByteArray {
        val stripped =
            if (signedMagnitude.size > COORDINATE_BYTES) {
                require(signedMagnitude.size == COORDINATE_BYTES + 1 && signedMagnitude[0] == 0x00.toByte()) {
                    "EC coordinate wider than $COORDINATE_BYTES bytes"
                }
                signedMagnitude.copyOfRange(1, signedMagnitude.size)
            } else {
                signedMagnitude
            }
        if (stripped.size == COORDINATE_BYTES) return stripped
        return ByteArray(COORDINATE_BYTES - stripped.size) + stripped
    }
}
