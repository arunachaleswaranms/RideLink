package com.ridelink.network.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ridelink.core.security.UtcTime
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * The device identity, held in **Android Keystore** (ADR-017 §1).
 *
 * The private key is generated inside the Keystore and is non-exportable: there is no API that
 * returns its bytes, so there is no code path — accidental or otherwise — that writes it to a
 * file, a log or the network. What this class hands out is an opaque `PrivateKey` handle that only
 * JSSE ever dereferences.
 *
 * **Lifetime, by platform, stated because it differs and users notice:**
 *
 * | Event | Key survives? |
 * |---|---|
 * | App restart / device reboot | Yes |
 * | App upgrade (same signing key) | Yes |
 * | Uninstall / reinstall | **No** — Android deletes an app's Keystore entries on uninstall |
 * | "Clear app data" | Yes; the Keystore entry is not app data. But the trusted-peer store is, so the peer becomes unknown from this side |
 *
 * A reinstall therefore produces a new identity key, a new SPKI, and a deliberate `pin_mismatch`
 * on the peer — which is correct, not a bug: a different key *is* a different identity (ADR-012).
 * Recovery is the documented one, forget-and-re-pair with a fresh SAS on both screens.
 *
 * The only part of Phase 1b that cannot be exercised without a device. Everything it composes —
 * the certificate encoding, the signing call, the point extraction, the TLS handshake — is
 * covered by JVM tests through [IdentityIssuer] and [TlsControlChannel].
 */
class AndroidKeystoreIdentityStore(
    private val alias: String = DEFAULT_ALIAS,
) {
    /**
     * Returns the existing identity, or creates one on first run.
     *
     * If the stored certificate is outside its validity window, a fresh one is issued **around the
     * same key**: the SPKI is unchanged, so peers stay trusted and no SAS is shown (ADR-012,
     * PROTOCOL §4.5.3). This is exactly the operation Android's auto-issued certificate cannot
     * perform, and the reason RideLink encodes its own (ADR-017 §3).
     */
    @Suppress("ReturnCount") // three distinct outcomes: existing-and-valid, existing-but-reissued, fresh
    fun loadOrCreate(now: UtcTime): DeviceIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            val certificate = existing.certificate as X509Certificate
            if (isWithinValidity(certificate, now)) {
                return DeviceIdentity(certificate, keyStore, null)
            }
            return DeviceIdentity(reissue(keyStore, existing, now), keyStore, null)
        }
        return DeviceIdentity(generate(keyStore, now), keyStore, null)
    }

    /**
     * Generates a fresh P-256 key in the Keystore and replaces the placeholder certificate the
     * Keystore mints at generation time with RideLink's own.
     *
     * `setCertificateSubject`/`setCertificateSerialNumber` are deliberately *not* used to shape
     * that placeholder: its encoding is outside our control, cannot be pinned by a shared vector,
     * and cannot be re-issued later — see ADR-017 §3.
     */
    private fun generate(
        keyStore: KeyStore,
        now: UtcTime,
    ): X509Certificate {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(DeviceIdentity.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No setUserAuthenticationRequired: the identity has to be usable while the screen
                // is locked, which is the normal state of a phone in a tank bag mid-ride
                // (ARCHITECTURE §6.4).
                .build(),
        )
        val keyPair = generator.generateKeyPair()
        val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(keyPair.private) }
        val certificate = IdentityIssuer.issue(keyPair.public, signer, now)
        keyStore.setKeyEntry(alias, keyPair.private, null, arrayOf(certificate))
        return certificate
    }

    private fun reissue(
        keyStore: KeyStore,
        entry: KeyStore.PrivateKeyEntry,
        now: UtcTime,
    ): X509Certificate {
        val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(entry.privateKey) }
        val certificate = IdentityIssuer.issue(entry.certificate.publicKey, signer, now)
        keyStore.setKeyEntry(alias, entry.privateKey, null, arrayOf(certificate))
        return certificate
    }

    private fun isWithinValidity(
        certificate: X509Certificate,
        now: UtcTime,
    ): Boolean =
        now.isWithin(
            UtcTime(certificate.notBefore.time / MILLIS_PER_SECOND),
            UtcTime(certificate.notAfter.time / MILLIS_PER_SECOND),
        )

    companion object {
        const val DEFAULT_ALIAS: String = "ridelink.identity.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MILLIS_PER_SECOND = 1000L
    }
}
