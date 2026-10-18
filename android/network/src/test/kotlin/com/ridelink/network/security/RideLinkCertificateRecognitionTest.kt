package com.ridelink.network.security

import com.ridelink.core.security.Der
import com.ridelink.core.security.IdentityCertificate
import com.ridelink.core.security.Oid
import com.ridelink.core.security.UtcTime
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-017 Amendment A1: [IdentityIssuer.isRideLinkIdentityCertificate] is what lets
 * `AndroidKeystoreIdentityStore` tell a finished identity from the residue of an interrupted
 * generation, whose Keystore entry still carries the platform's placeholder certificate. It must
 * accept every certificate RideLink issues, and nothing else.
 */
class RideLinkCertificateRecognitionTest {
    private val now = UtcTime(TestTlsSupport.NOW_EPOCH_SECONDS)

    private fun keyPair(): KeyPair =
        KeyPairGenerator
            .getInstance(DeviceIdentity.KEY_ALGORITHM)
            .apply { initialize(ECGenParameterSpec(DeviceIdentity.CURVE)) }
            .generateKeyPair()

    private fun signer(keyPair: KeyPair) = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(keyPair.private) }

    @Test
    fun `a certificate RideLink issued is recognised, and so is a re-issue around the same key`() {
        val keyPair = keyPair()
        assertTrue(IdentityIssuer.isRideLinkIdentityCertificate(IdentityIssuer.issue(keyPair.public, signer(keyPair), now)))
        assertTrue(
            IdentityIssuer.isRideLinkIdentityCertificate(
                IdentityIssuer.issue(keyPair.public, signer(keyPair), UtcTime(now.epochSeconds + TestTlsSupport.ONE_HOUR_SECONDS)),
            ),
        )
    }

    @Test
    fun `a RideLink-shaped certificate signed by a different key is not`() {
        val subject = keyPair()
        val impostor = keyPair()
        assertFalse(IdentityIssuer.isRideLinkIdentityCertificate(IdentityIssuer.issue(subject.public, signer(impostor), now)))
    }

    @Test
    fun `a validly self-signed certificate RideLink did not encode — the shape of Keystore's placeholder — is not`() {
        val keyPair = keyPair()
        val name = Der.sequence(Der.set(Der.sequence(Der.objectIdentifier(Oid.COMMON_NAME), Der.utf8String("fake"))))
        val algorithm = Der.sequence(Der.objectIdentifier(Oid.ECDSA_WITH_SHA256))
        // A v1 certificate with no extensions and a foreign subject, around the very same key.
        val tbs =
            Der.sequence(
                Der.integer(byteArrayOf(0x01)),
                algorithm,
                name,
                Der.sequence(Der.utcTime(UtcTime(0).utcTimeString), Der.utcTime(now.utcTimeString)),
                name,
                keyPair.public.encoded,
            )
        val signature = signer(keyPair).apply { update(tbs) }.sign()
        val foreign = IdentityIssuer.parseCertificate(IdentityCertificate.certificate(tbs, signature))
        foreign.verify(keyPair.public) // genuinely self-signed: only the encoding gives it away

        assertFalse(IdentityIssuer.isRideLinkIdentityCertificate(foreign))
    }
}
