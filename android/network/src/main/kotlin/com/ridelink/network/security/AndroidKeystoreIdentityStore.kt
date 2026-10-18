package com.ridelink.network.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.IdentityCertificate
import com.ridelink.core.security.UtcTime
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * What [AndroidKeystoreIdentityStore.resolve] found (ADR-017 Amendment A1).
 */
sealed interface IdentityResolution {
    /** A usable identity: its key can issue RideLink's certificate and sign a TLS 1.3 handshake. */
    class Ready(
        val identity: DeviceIdentity,
    ) : IdentityResolution

    /**
     * An identity exists, but its key was generated without `DIGEST_NONE` and so can never sign a
     * TLS handshake (STATUS §4 problem 122). Keystore cannot add an authorization to an existing
     * key, so the only remedy is a **new** key — a new SPKI, which ADR-012 says is a new identity.
     * That is the user's decision, never the store's: it is offered through
     * [AndroidKeystoreIdentityStore.replaceIncompatibleIdentity] and nothing calls it implicitly.
     *
     * [incompatibleSpki] is the identity being replaced, for display (redacted, 6 hex).
     */
    class ReplacementRequired(
        val incompatibleSpki: SpkiHash,
    ) : IdentityResolution
}

/**
 * The device identity, held in **Android Keystore** (ADR-017 §1).
 *
 * The private key is generated inside the Keystore and is non-exportable: there is no API that
 * returns its bytes, so there is no code path — accidental or otherwise — that writes it to a
 * file, a log or the network. What this class hands out is an opaque `PrivateKey` handle that only
 * JSSE ever dereferences.
 *
 * **Two aliases (ADR-017 Amendment A1).** Keys this build generates live under [alias] and are
 * authorised for `SHA-256` *and* `NONE` ([IdentityKeyAuthorization]). Every key generated before
 * the amendment lives under [legacyAlias] and is authorised for `SHA-256` only, which Conscrypt's
 * TLS signing cannot use. An incompatible legacy entry is read, inspected and reported, and
 * **never modified or deleted** — not by [resolve], and not by [replaceIncompatibleIdentity], which
 * writes only [alias]. (Re-issue writes back to whichever alias holds a *capable* key; no build has
 * ever put one under the legacy alias.) Which one TLS presents is pinned by [DeviceIdentity]'s alias.
 *
 * **No half-made identity is ever Ready.** Keystore writes a key, with its own placeholder
 * certificate, the moment the key is generated; RideLink's certificate replaces it only afterwards.
 * [generate] is therefore all-or-nothing: it runs only on an empty [alias], and any failure after the
 * key exists removes that entry before rethrowing. A process death between the two steps cannot run
 * that cleanup, so [resolve] and [replaceIncompatibleIdentity] first discard an entry under [alias]
 * that does not carry a RideLink certificate ([IdentityIssuer.isRideLinkIdentityCertificate]).
 * Such an entry was never returned by this store, so it was never presented in a handshake and no
 * peer can have pinned it: it is not an identity, and removing it restores the state from before
 * that generation began. A working identity always carries a RideLink certificate, so it can never
 * match; [legacyAlias] is never examined for this and never deleted, whatever it holds.
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
 * Recovery is the documented one, forget-and-re-pair with a fresh SAS on both screens. An explicit
 * [replaceIncompatibleIdentity] has exactly the same consequence and the same recovery.
 *
 * The part of Phase 1b that cannot be exercised without Android Keystore. The certificate
 * encoding, the signing call and the point extraction are covered by JVM tests through
 * [IdentityIssuer]; the Keystore authorizations and a real Keystore-backed TLS 1.3 handshake are
 * covered by `KeystoreTlsIdentityTest`, an instrumented test.
 */
class AndroidKeystoreIdentityStore internal constructor(
    private val alias: String,
    private val legacyAlias: String,
    // The two steps after key generation that can fail, injectable so a test can make them fail on a
    // real Keystore. Production always uses the defaults below.
    private val issueCertificate: (PublicKey, Signature, UtcTime) -> X509Certificate,
    private val commitEntry: (KeyStore, String, PrivateKey, X509Certificate) -> Unit,
) {
    constructor(
        alias: String = DEFAULT_ALIAS,
        legacyAlias: String = LEGACY_ALIAS,
    ) : this(
        alias,
        legacyAlias,
        issueCertificate = { key, signer, now -> IdentityIssuer.issue(key, signer, now) },
        commitEntry = { keyStore, entryAlias, key, certificate -> keyStore.setKeyEntry(entryAlias, key, null, arrayOf(certificate)) },
    )

    init {
        require(alias != legacyAlias) { "the current and legacy identity aliases must differ" }
    }

    /**
     * Returns the existing identity, creates one on first run, or reports that the existing one
     * cannot be used for TLS.
     *
     * If the stored certificate is outside its validity window, a fresh one is issued **around the
     * same key**: the SPKI is unchanged, so peers stay trusted and no SAS is shown (ADR-012,
     * PROTOCOL §4.5.3). This is exactly the operation Android's auto-issued certificate cannot
     * perform, and the reason RideLink encodes its own (ADR-017 §3). An incompatible identity is
     * **not** re-issued: a new certificate would not make its key usable.
     *
     * A new key is generated only when no identity exists at all. In particular, an incompatible
     * identity is never silently replaced here — that would change this phone's SPKI without the
     * user knowing, which ADR-012 forbids.
     */
    @Suppress("ReturnCount") // three distinct outcomes: fresh, incompatible, existing (re-issued or not)
    fun resolve(now: UtcTime): IdentityResolution {
        val keyStore = openKeyStore()
        discardUnfinishedGeneration(keyStore)
        val existing =
            existingIdentity(keyStore)
                ?: return IdentityResolution.Ready(DeviceIdentity(generate(keyStore, now), keyStore, null, alias))
        if (!existing.tlsCapable) {
            return IdentityResolution.ReplacementRequired(spkiOf(existing.certificate))
        }
        val certificate =
            if (isWithinValidity(existing.certificate, now)) {
                existing.certificate
            } else {
                reissue(keyStore, existing, now)
            }
        return IdentityResolution.Ready(DeviceIdentity(certificate, keyStore, null, existing.alias))
    }

    /**
     * The explicit, user-confirmed replacement of a TLS-incompatible identity (ADR-017 Amendment
     * A1). Generates a new key under [alias] — a **new SPKI** — and leaves the incompatible entry
     * exactly as it was.
     *
     * This is deliberately not a general "rotate my key": it refuses unless [resolve] would return
     * [IdentityResolution.ReplacementRequired], so it can never replace a working identity, and it
     * is not certificate re-issuance (which keeps the SPKI). Peers that pinned the old SPKI will see
     * `pin_mismatch`; recovery is forget-and-re-pair with a fresh SAS on both phones. Trusted-peer
     * records are not touched: they pin the *peers'* keys, which have not changed.
     */
    fun replaceIncompatibleIdentity(now: UtcTime): DeviceIdentity {
        val keyStore = openKeyStore()
        discardUnfinishedGeneration(keyStore)
        val existing = existingIdentity(keyStore)
        check(existing != null && !existing.tlsCapable) {
            "refusing to replace an identity that is absent or already TLS-capable"
        }
        // The current alias is only ever written by generate(), which always authorises NONE, so
        // an incompatible key there is not one this app made. Refuse rather than delete it.
        check(existing.alias != alias) { "refusing to overwrite the current identity alias" }
        return DeviceIdentity(generate(keyStore, now), keyStore, null, alias)
    }

    private class ExistingIdentity(
        val alias: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val tlsCapable: Boolean,
    )

    /**
     * Removes the residue of a [generate] that did not finish — an entry under [alias] whose
     * certificate is not RideLink's — and nothing else. See the class comment for why that entry is
     * not an identity. Only [alias] is looked at; [legacyAlias] is never deleted here or anywhere.
     */
    private fun discardUnfinishedGeneration(keyStore: KeyStore) {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return
        if (!IdentityIssuer.isRideLinkIdentityCertificate(entry.certificate as X509Certificate)) {
            keyStore.deleteEntry(alias)
        }
    }

    /** The current alias wins over the legacy one: after a replacement both exist. */
    private fun existingIdentity(keyStore: KeyStore): ExistingIdentity? =
        listOf(alias, legacyAlias).firstNotNullOfOrNull { candidate ->
            (keyStore.getEntry(candidate, null) as? KeyStore.PrivateKeyEntry)?.let { entry ->
                ExistingIdentity(
                    alias = candidate,
                    privateKey = entry.privateKey,
                    certificate = entry.certificate as X509Certificate,
                    tlsCapable = IdentityKeyAuthorization.isTlsCapable(authorizedDigests(entry.privateKey)),
                )
            }
        }

    /**
     * Generates a fresh P-256 key in the Keystore under [alias] and replaces the placeholder
     * certificate the Keystore mints at generation time with RideLink's own.
     *
     * `setCertificateSubject`/`setCertificateSerialNumber` are deliberately *not* used to shape
     * that placeholder: its encoding is outside our control, cannot be pinned by a shared vector,
     * and cannot be re-issued later — see ADR-017 §3.
     *
     * All or nothing. It refuses unless [alias] is empty, so the only entry its cleanup can ever
     * remove is the one this call created: never an identity that existed before, never a working one.
     */
    private fun generate(
        keyStore: KeyStore,
        now: UtcTime,
    ): X509Certificate {
        check(!keyStore.containsAlias(alias)) { "refusing to generate over an existing entry" }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(DeviceIdentity.CURVE))
                // SHA-256 for RideLink's own certificate signature (ADR-017 §3); NONE because
                // Conscrypt signs the TLS 1.3 CertificateVerify as NONEwithECDSA over a digest it
                // computed itself. Without NONE no handshake completes (ADR-017 Amendment A1).
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                // No setUserAuthenticationRequired: the identity has to be usable while the screen
                // is locked, which is the normal state of a phone in a tank bag mid-ride
                // (ARCHITECTURE §6.4).
                .build(),
        )
        val keyPair = generator.generateKeyPair()
        try {
            // The platform enforces what it recorded, not what was asked for: a key that cannot sign
            // a handshake must never be handed out as Ready.
            check(IdentityKeyAuthorization.isTlsCapable(authorizedDigests(keyPair.private))) {
                "Keystore did not record the digest authorizations TLS needs"
            }
            val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(keyPair.private) }
            val certificate = issueCertificate(keyPair.public, signer, now)
            commitEntry(keyStore, alias, keyPair.private, certificate)
            return certificate
        } catch (
            // Deliberately broad: whatever failed, the half-made entry must not survive this call,
            // and the failure itself must still reach the caller unchanged.
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            // If this delete fails too, discardUnfinishedGeneration removes the entry next time.
            runCatching { keyStore.deleteEntry(alias) }
            throw failure
        }
    }

    private fun reissue(
        keyStore: KeyStore,
        existing: ExistingIdentity,
        now: UtcTime,
    ): X509Certificate {
        val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(existing.privateKey) }
        val certificate = IdentityIssuer.issue(existing.certificate.publicKey, signer, now)
        keyStore.setKeyEntry(existing.alias, existing.privateKey, null, arrayOf(certificate))
        return certificate
    }

    /** The digests Keystore has recorded for [key], read through the supported `KeyInfo` API. */
    private fun authorizedDigests(key: PrivateKey): Set<String> =
        KeyFactory
            .getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
            .digests
            .toSet()

    private fun spkiOf(certificate: X509Certificate): SpkiHash = IdentityCertificate.spkiHashOfEncoded(certificate.publicKey.encoded)

    private fun isWithinValidity(
        certificate: X509Certificate,
        now: UtcTime,
    ): Boolean =
        now.isWithin(
            UtcTime(certificate.notBefore.time / MILLIS_PER_SECOND),
            UtcTime(certificate.notAfter.time / MILLIS_PER_SECOND),
        )

    private fun openKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /** Where keys authorised for TLS live (ADR-017 Amendment A1). */
        const val DEFAULT_ALIAS: String = "ridelink.identity.v2"

        /** Where every key generated before ADR-017 Amendment A1 lives. Never deleted by RideLink. */
        const val LEGACY_ALIAS: String = "ridelink.identity.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MILLIS_PER_SECOND = 1000L
    }
}
