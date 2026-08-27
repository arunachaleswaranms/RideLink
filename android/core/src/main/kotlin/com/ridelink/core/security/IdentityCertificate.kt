package com.ridelink.core.security

import com.ridelink.core.model.SpkiHash
import java.security.MessageDigest

/**
 * The RideLink device identity: a P-256 public key, its `identity_spki_sha256`, and the
 * self-signed X.509 certificate that carries it on the wire (ADR-012, ADR-017).
 *
 * Everything here is pure byte arrangement. Key generation, signing and storage belong to the
 * platform — Android Keystore or the iOS Keychain — and no private key ever reaches this layer.
 * `RideLinkCore.Security.IdentityCertificate` is the line-for-line mirror; both are pinned by
 * `protocol/vectors/identity/identity_vectors.json`.
 */
object IdentityCertificate {
    /** Uncompressed X9.63 point: `0x04 ‖ X(32) ‖ Y(32)`. The only public-key form v1 accepts. */
    const val P256_UNCOMPRESSED_POINT_BYTES: Int = 65
    private const val UNCOMPRESSED_POINT_TAG: Byte = 0x04

    /** SubjectPublicKeyInfo for a P-256 key is always exactly this long. */
    const val P256_SPKI_BYTES: Int = 91

    /**
     * ADR-017 §2: identical on every device that will ever run this, so it carries no information
     * and therefore leaks none. Identity is the SPKI hash, never the subject text.
     */
    const val SUBJECT_COMMON_NAME: String = "RideLink Device"

    /** ADR-012: expiry must never masquerade as key rotation, so the window is generous. */
    const val VALIDITY_YEARS: Int = 10

    /** Backdate to absorb modest clock skew between the two phones (ADR-017 §2). */
    const val NOT_BEFORE_BACKDATE_SECONDS: Long = 24 * 60 * 60

    /** X.509 v3 encodes as INTEGER 2. */
    private const val X509_VERSION_V3 = 2

    /** 16 CSPRNG bytes, high bit cleared by the caller so it encodes as a positive INTEGER. */
    const val SERIAL_BYTES: Int = 16

    /** keyUsage = digitalSignature: BIT STRING, 7 unused bits, bit 0 set. */
    private val KEY_USAGE_DIGITAL_SIGNATURE = byteArrayOf(0x03, 0x02, 0x07, 0x80.toByte())

    /**
     * Builds the 91-byte SubjectPublicKeyInfo for a P-256 key from its raw uncompressed point.
     *
     * Android never needs this for a *peer* — `PublicKey.getEncoded()` already returns these
     * exact bytes — but it does need it to build its own certificate, and iOS needs it for both,
     * because `SecKeyCopyExternalRepresentation` hands back the bare point (ADR-017 §4). Having
     * one implementation on each platform, checked against the same vector, is what keeps the two
     * `identity_spki_sha256` values equal.
     */
    fun subjectPublicKeyInfo(uncompressedPoint: ByteArray): ByteArray {
        requireValidPoint(uncompressedPoint)
        return Der.sequence(
            Der.sequence(
                Der.objectIdentifier(Oid.EC_PUBLIC_KEY),
                Der.objectIdentifier(Oid.PRIME256V1),
            ),
            Der.bitString(uncompressedPoint),
        )
    }

    /** `identity_spki_sha256` for a P-256 key: `"sha256:"` ‖ lowercase hex of SHA-256(SPKI). */
    fun identitySpkiSha256(uncompressedPoint: ByteArray): SpkiHash = spkiHashOfEncoded(subjectPublicKeyInfo(uncompressedPoint))

    /**
     * `identity_spki_sha256` for an already-encoded SubjectPublicKeyInfo — the Android path, where
     * `X509Certificate.getPublicKey().getEncoded()` *is* the SPKI DER and re-deriving it from the
     * point would be a pointless second chance to differ.
     */
    fun spkiHashOfEncoded(subjectPublicKeyInfoDer: ByteArray): SpkiHash {
        val digest = MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfoDer)
        return SpkiHash("sha256:" + digest.joinToString("") { "%02x".format(it) })
    }

    /**
     * The TBSCertificate — everything that gets signed. Deterministic: the same inputs produce the
     * same bytes on both platforms, which is what the shared vector asserts.
     *
     * @param serial [SERIAL_BYTES] CSPRNG bytes with the high bit already cleared.
     */
    fun tbsCertificate(
        uncompressedPoint: ByteArray,
        serial: ByteArray,
        notBefore: UtcTime,
        notAfter: UtcTime,
    ): ByteArray {
        requireValidPoint(uncompressedPoint)
        require(serial.isNotEmpty()) { "certificate serial must not be empty" }
        require(notBefore.epochSeconds < notAfter.epochSeconds) { "notBefore must precede notAfter" }
        val name =
            Der.sequence(
                Der.set(
                    Der.sequence(
                        Der.objectIdentifier(Oid.COMMON_NAME),
                        Der.utf8String(SUBJECT_COMMON_NAME),
                    ),
                ),
            )
        return Der.sequence(
            Der.explicit(0, Der.integer(X509_VERSION_V3)),
            Der.integer(serial),
            signatureAlgorithm(),
            name, // issuer — identical to subject, because it is self-signed
            Der.sequence(Der.utcTime(notBefore.utcTimeString), Der.utcTime(notAfter.utcTimeString)),
            name, // subject
            subjectPublicKeyInfo(uncompressedPoint),
            extensions(),
        )
    }

    /**
     * Wraps a signed TBSCertificate into the final X.509 Certificate.
     *
     * @param signature the platform's ECDSA output over SHA-256 of [tbsCertificate], already in
     *   X9.62 `SEQUENCE { r, s }` DER — which is what both `Signature("SHA256withECDSA")` and
     *   `SecKeyCreateSignature(.ecdsaSignatureMessageX962SHA256)` return.
     */
    fun certificate(
        tbsCertificate: ByteArray,
        signature: ByteArray,
    ): ByteArray {
        require(signature.isNotEmpty()) { "signature must not be empty" }
        return Der.sequence(tbsCertificate, signatureAlgorithm(), Der.bitString(signature))
    }

    /**
     * The ADR-017 validity window for a certificate issued at [issuedAt]: backdated a day to
     * absorb clock skew, then [VALIDITY_YEARS] calendar years wide.
     */
    fun validityWindow(issuedAt: UtcTime): Pair<UtcTime, UtcTime> {
        val notBefore = issuedAt.minusSeconds(NOT_BEFORE_BACKDATE_SECONDS)
        return notBefore to notBefore.plusYears(VALIDITY_YEARS)
    }

    private fun signatureAlgorithm(): ByteArray = Der.sequence(Der.objectIdentifier(Oid.ECDSA_WITH_SHA256))

    private fun extensions(): ByteArray =
        Der.explicit(
            3,
            Der.sequence(
                // basicConstraints, critical, CA:FALSE (an empty SEQUENCE — cA defaults to FALSE)
                Der.sequence(
                    Der.objectIdentifier(Oid.BASIC_CONSTRAINTS),
                    Der.boolean(true),
                    Der.octetString(Der.sequence()),
                ),
                // keyUsage, critical, digitalSignature only
                Der.sequence(
                    Der.objectIdentifier(Oid.KEY_USAGE),
                    Der.boolean(true),
                    Der.octetString(KEY_USAGE_DIGITAL_SIGNATURE),
                ),
            ),
        )

    /**
     * v1 accepts exactly one key shape. Rejecting anything else here removes algorithm confusion
     * as a class of bug (ADR-017 §1) — a compressed point, a different curve or a different
     * algorithm never reaches the hash, so it can never become somebody's pinned identity.
     */
    private fun requireValidPoint(point: ByteArray) {
        require(point.size == P256_UNCOMPRESSED_POINT_BYTES) {
            "P-256 public key must be $P256_UNCOMPRESSED_POINT_BYTES bytes, was ${point.size}"
        }
        require(point[0] == UNCOMPRESSED_POINT_TAG) {
            "P-256 public key must be in uncompressed X9.63 form (leading 0x04)"
        }
    }
}
