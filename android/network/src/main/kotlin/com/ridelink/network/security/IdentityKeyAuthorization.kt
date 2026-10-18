package com.ridelink.network.security

/**
 * Which digests a RideLink identity key must be authorised for, and whether an existing key is
 * (ADR-017 Amendment A1, STATUS §4 problem 122).
 *
 * A Keystore key's digest authorizations are fixed when it is generated and enforced by Keystore
 * itself, so they are as much a part of the identity as the curve. The key needs **two**:
 *
 * - `SHA-256`, because RideLink signs its own certificate with `SHA256withECDSA` (ADR-017 §3),
 *   for the first issue and for every re-issue around the same key (ADR-012);
 * - `NONE`, because Conscrypt signs the TLS 1.3 `CertificateVerify` for a Keystore EC key by
 *   hashing the handshake transcript itself and calling back with `NONEwithECDSA` over that
 *   digest (`CryptoUpcalls.ecSignDigestWithPrivateKey`). Without it Keystore refuses with
 *   `INCOMPATIBLE_DIGEST` and no handshake can complete, as client or as server — which is what
 *   every key generated before this amendment does.
 *
 * Kept free of `android.*` so the rule is a plain JVM-tested function; the constants equal
 * `KeyProperties.DIGEST_SHA256` and `KeyProperties.DIGEST_NONE`, which the Keystore-backed
 * instrumented test asserts on a device.
 */
object IdentityKeyAuthorization {
    const val DIGEST_SHA256: String = "SHA-256"
    const val DIGEST_NONE: String = "NONE"

    val REQUIRED_DIGESTS: Set<String> = setOf(DIGEST_SHA256, DIGEST_NONE)

    /**
     * True when a key authorised for exactly [authorizedDigests] can both issue RideLink's
     * certificate and sign a TLS 1.3 handshake. Missing either is incompatible — `NONE` alone
     * would leave the certificate impossible to re-issue around the same key.
     */
    fun isTlsCapable(authorizedDigests: Collection<String>): Boolean = authorizedDigests.containsAll(REQUIRED_DIGESTS)
}
