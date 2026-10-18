package com.ridelink.network.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.core.security.UtcTime
import com.ridelink.network.control.ControlSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-017 Amendment A1 / STATUS §4 problem 122, on **real Android Keystore keys** — the thing no JVM
 * test can reach, and the reason two phones could not pair through Phase 9A.5.
 *
 * Conscrypt signs a TLS 1.3 `CertificateVerify` for a Keystore EC key as `NONEwithECDSA` over a
 * digest it computed itself. A key authorised for `SHA-256` alone — every RideLink identity generated
 * before the amendment — is refused by Keystore with `INCOMPATIBLE_DIGEST`, so no handshake completes.
 *
 * Every alias here is disposable and unique to the run. This module's test APK is its own package, so
 * its Keystore namespace cannot contain the app's `ridelink.identity.*` entries in the first place;
 * the aliases are also asserted never to equal them. Nothing here prints key material, an exporter
 * output or a SAS: the comparisons are assertions only. Trust validation is the production one —
 * the production [TlsControlChannel] and the platform's default TLS provider, unmodified.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTlsIdentityTest {
    private val run = UUID.randomUUID().toString().take(RUN_ID_CHARS)
    private val createdAliases = mutableListOf<String>()
    private val now = UtcTime(System.currentTimeMillis() / MILLIS_PER_SECOND)

    private fun alias(role: String): String {
        val alias = "ridelink.test.$run.$role"
        check(alias != AndroidKeystoreIdentityStore.DEFAULT_ALIAS && alias != AndroidKeystoreIdentityStore.LEGACY_ALIAS)
        createdAliases += alias
        return alias
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun privateKey(alias: String): PrivateKey = (keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    private fun certificate(alias: String): X509Certificate = keyStore().getCertificate(alias) as X509Certificate

    private fun digestsOf(key: PrivateKey): Set<String> =
        KeyFactory
            .getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
            .digests
            .toSet()

    /**
     * Exactly the key every build through `8f47a72` generated (ADR-017 §1 as accepted): `SHA-256`
     * only, with RideLink's own certificate written over the Keystore placeholder.
     */
    private fun generateLegacyIdentity(alias: String) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(DeviceIdentity.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        val keyPair = generator.generateKeyPair()
        val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(keyPair.private) }
        keyStore().setKeyEntry(alias, keyPair.private, null, arrayOf(IdentityIssuer.issue(keyPair.public, signer, now)))
    }

    /**
     * The production key spec, stopped where an interrupted generation stops: the key exists, with
     * Keystore's own placeholder certificate, and RideLink's certificate was never written.
     */
    private fun generateUnfinishedCurrentKey(alias: String) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(DeviceIdentity.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .build(),
        )
        generator.generateKeyPair()
    }

    /** A store whose certificate issuance or Keystore commit fails after the key has been generated. */
    private fun failingStore(
        current: String,
        legacy: String,
        failIssue: Boolean,
    ) = AndroidKeystoreIdentityStore(
        current,
        legacy,
        issueCertificate = {
            key,
            signer,
            at,
            ->
            if (failIssue) error("injected issuance failure") else IdentityIssuer.issue(key, signer, at)
        },
        commitEntry = { _, _, _, _ -> error("injected setKeyEntry failure") },
    )

    private fun spkiOfEntry(alias: String) = DeviceIdentity(certificate(alias), keyStore(), null, alias).identitySpkiSha256

    private fun ready(store: AndroidKeystoreIdentityStore): DeviceIdentity = assertIs<IdentityResolution.Ready>(store.resolve(now)).identity

    @After
    fun deleteEveryAliasThisRunCreated() {
        val keyStore = keyStore()
        createdAliases.forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
        createdAliases.forEach { assertFalse(keyStore().containsAlias(it), "test alias left behind") }
    }

    @Test
    fun theDigestNamesAreTheOnesKeystoreReports() {
        assertEquals(KeyProperties.DIGEST_SHA256, IdentityKeyAuthorization.DIGEST_SHA256)
        assertEquals(KeyProperties.DIGEST_NONE, IdentityKeyAuthorization.DIGEST_NONE)
    }

    // A — the shipped key reproduces the defect.
    @Test
    fun aSha256OnlyKeystoreKeyIsRefusedTheSignatureTlsUses() {
        val legacy = alias("legacy")
        generateLegacyIdentity(legacy)
        val key = privateKey(legacy)

        assertEquals(setOf(KeyProperties.DIGEST_SHA256), digestsOf(key))
        assertFalse(IdentityKeyAuthorization.isTlsCapable(digestsOf(key)))
        // Certificate issuance was never the problem…
        val issuance = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(key) }
        issuance.update(MESSAGE)
        assertTrue(issuance.sign().isNotEmpty())
        // …the digest-only signature Conscrypt's TLS callback asks for is.
        assertFailsWith<Exception> {
            Signature
                .getInstance(NONE_WITH_ECDSA)
                .apply { initSign(key) }
                .apply { update(sha256(MESSAGE)) }
                .sign()
        }
    }

    // A — and a real handshake with it fails, whichever side holds it.
    @Test
    fun aTls13HandshakeWithASha256OnlyIdentityFailsAsServerAndAsClient() =
        runBlocking<Unit> {
            val legacy = alias("legacy")
            generateLegacyIdentity(legacy)
            val legacyIdentity = DeviceIdentity(certificate(legacy), keyStore(), null, legacy)
            val good = ready(AndroidKeystoreIdentityStore(alias("good.v2"), alias("good.v1")))

            assertFalse(handshakeSucceeds(server = legacyIdentity, client = good), "legacy key as TLS server")
            assertFalse(handshakeSucceeds(server = good, client = legacyIdentity), "legacy key as TLS client")
        }

    // B — the amended key signs both operations.
    @Test
    fun aKeyAuthorisedForSha256AndNoneSignsCertificateIssuanceAndTheTlsDigest() {
        val current = alias("v2")
        val identity = ready(AndroidKeystoreIdentityStore(current, alias("v1")))
        val key = privateKey(current)

        assertEquals(setOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE), digestsOf(key))
        assertTrue(IdentityKeyAuthorization.isTlsCapable(digestsOf(key)))
        // Issuance: RideLink's certificate is self-signed SHA256withECDSA by the Keystore key.
        identity.certificate.verify(identity.certificate.publicKey)
        // TLS: NONEwithECDSA over SHA-256(m) is exactly a SHA256withECDSA signature over m.
        val signature =
            Signature
                .getInstance(NONE_WITH_ECDSA)
                .apply { initSign(key) }
                .apply { update(sha256(MESSAGE)) }
                .sign()
        val verifier = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initVerify(identity.certificate.publicKey) }
        verifier.update(MESSAGE)
        assertTrue(verifier.verify(signature))
        // P-256, and still the canonical SPKI both platforms hash (ADR-017 §1, §4).
        IdentityIssuer.uncompressedPoint(identity.certificate.publicKey)
        // F — the key stays non-exportable.
        assertNull(key.encoded, "a Keystore private key must expose no encoding")
        Log.i(TAG, "API ${Build.VERSION.SDK_INT}: identity key security level ${securityLevelOf(key)}")
    }

    // C + D — two Keystore identities complete a real mutually authenticated TLS 1.3 handshake.
    @Test
    fun twoKeystoreIdentitiesCompleteAMutuallyAuthenticatedTls13Handshake() =
        runBlocking<Unit> {
            val alice = ready(AndroidKeystoreIdentityStore(alias("alice.v2"), alias("alice.v1")))
            val bob = ready(AndroidKeystoreIdentityStore(alias("bob.v2"), alias("bob.v1")))
            assertNotEquals(alice.identitySpkiSha256, bob.identitySpkiSha256)

            connected(server = alice, client = bob) { server, client ->
                val serverSecurity = assertNotNull(server.security)
                val clientSecurity = assertNotNull(client.security)
                assertTrue(serverSecurity.negotiatedCipherSuite in TLS_1_3_SUITES, serverSecurity.negotiatedCipherSuite)
                assertEquals(serverSecurity.negotiatedCipherSuite, clientSecurity.negotiatedCipherSuite)
                // Each side derived the *other's* pinned identity from the certificate it was shown.
                assertEquals(bob.identitySpkiSha256, serverSecurity.peerIdentitySpkiSha256)
                assertEquals(alice.identitySpkiSha256, clientSecurity.peerIdentitySpkiSha256)
                assertTrue(serverSecurity.peerCertificateStructurallyValid)
                assertTrue(clientSecurity.peerCertificateStructurallyValid)
                // ADR-018: both ends derive the same six digits. Compared, never printed.
                val serverSas = assertNotNull(serverSecurity.deriveSas6())
                assertEquals(serverSas, clientSecurity.deriveSas6())
                assertTrue(serverSas.length == SAS_DIGITS && serverSas.all(Char::isDigit))
            }
        }

    // E — re-issue keeps the SPKI, and the re-issued identity still authenticates.
    @Test
    fun reissuingTheCertificateAroundAKeystoreKeyPreservesTheSpki() =
        runBlocking<Unit> {
            val current = alias("v2")
            val store = AndroidKeystoreIdentityStore(current, alias("v1"))
            val original = ready(store)

            val afterExpiry = UtcTime(now.epochSeconds + ELEVEN_YEARS_SECONDS)
            val reissued = assertIs<IdentityResolution.Ready>(store.resolve(afterExpiry)).identity
            assertEquals(original.identitySpkiSha256, reissued.identitySpkiSha256)
            assertFalse(original.certificate.serialNumber == reissued.certificate.serialNumber, "a re-issue mints a fresh serial")

            // Back at the real clock the future certificate is not yet valid, so it is re-issued again
            // around the same key — and that identity completes a real handshake.
            val current2 = ready(store)
            assertEquals(original.identitySpkiSha256, current2.identitySpkiSha256)
            val peer = ready(AndroidKeystoreIdentityStore(alias("peer.v2"), alias("peer.v1")))
            connected(server = current2, client = peer) { _, client ->
                assertEquals(original.identitySpkiSha256, assertNotNull(client.security).peerIdentitySpkiSha256)
            }
        }

    // Migration — reported, never replaced implicitly; replacement is explicit, new-SPKI and non-destructive.
    @Test
    fun anIncompatibleIdentityIsReportedAndOnlyAnExplicitReplacementChangesIt() =
        runBlocking<Unit> {
            val legacy = alias("v1")
            val current = alias("v2")
            generateLegacyIdentity(legacy)
            val legacyCertificate = certificate(legacy).encoded
            val legacySpki = DeviceIdentity(certificate(legacy), keyStore(), null, legacy).identitySpkiSha256
            val store = AndroidKeystoreIdentityStore(current, legacy)

            val reported = assertIs<IdentityResolution.ReplacementRequired>(store.resolve(now))
            assertEquals(legacySpki, reported.incompatibleSpki)
            assertFalse(keyStore().containsAlias(current), "resolve must never generate a replacement on its own")
            assertContentEquals(legacyCertificate, certificate(legacy).encoded, "resolve must not re-issue an incompatible key")

            val replaced = store.replaceIncompatibleIdentity(now)
            assertNotEquals(legacySpki, replaced.identitySpkiSha256, "a replacement is a new identity, not a re-issue")
            assertTrue(keyStore().containsAlias(legacy), "the incompatible key is kept, never deleted")
            assertContentEquals(legacyCertificate, certificate(legacy).encoded)
            assertEquals(replaced.identitySpkiSha256, ready(store).identitySpkiSha256)

            // The keystore now holds both; TLS presents the replacement, never the legacy key.
            val peer = ready(AndroidKeystoreIdentityStore(alias("peer.v2"), alias("peer.v1")))
            connected(server = replaced, client = peer) { _, client ->
                assertEquals(replaced.identitySpkiSha256, assertNotNull(client.security).peerIdentitySpkiSha256)
            }
            connected(server = peer, client = replaced) { server, _ ->
                assertEquals(replaced.identitySpkiSha256, assertNotNull(server.security).peerIdentitySpkiSha256)
            }

            assertFailsWith<IllegalStateException>("a working identity must never be replaced") { store.replaceIncompatibleIdentity(now) }
        }

    @Test
    fun replacementIsRefusedWhenThereIsNothingIncompatibleToReplace() {
        val current = alias("v2")
        val store = AndroidKeystoreIdentityStore(current, alias("v1"))

        assertFailsWith<IllegalStateException> { store.replaceIncompatibleIdentity(now) }
        assertFalse(keyStore().containsAlias(current), "a refused replacement creates nothing")

        val identity = ready(store)
        assertFailsWith<IllegalStateException> { store.replaceIncompatibleIdentity(now) }
        assertEquals(identity.identitySpkiSha256, ready(store).identitySpkiSha256)
    }

    // Partial creation — a failure after the key exists leaves nothing behind, on a fresh store.
    @Test
    fun aFailedCertificateIssueOrCommitLeavesNoIdentityBehind() {
        for (failIssue in listOf(true, false)) {
            val current = alias("v2.$failIssue")
            val legacy = alias("v1.$failIssue")

            assertFailsWith<IllegalStateException> { failingStore(current, legacy, failIssue).resolve(now) }
            assertFalse(keyStore().containsAlias(current), "a half-made key must not survive the failed call")

            // The next, healthy attempt starts from nothing and yields a complete RideLink identity.
            val identity = ready(AndroidKeystoreIdentityStore(current, legacy))
            assertTrue(IdentityIssuer.isRideLinkIdentityCertificate(identity.certificate))
        }
    }

    // Partial creation during an explicit replacement — the legacy identity is untouched and still reported.
    @Test
    fun aFailedReplacementLeavesTheLegacyIdentityExactlyAsItWas() {
        for (failIssue in listOf(true, false)) {
            val current = alias("v2.$failIssue")
            val legacy = alias("v1.$failIssue")
            generateLegacyIdentity(legacy)
            val legacyCertificate = certificate(legacy).encoded
            val legacySpki = spkiOfEntry(legacy)

            assertFailsWith<IllegalStateException> { failingStore(current, legacy, failIssue).replaceIncompatibleIdentity(now) }

            assertFalse(keyStore().containsAlias(current))
            assertContentEquals(legacyCertificate, certificate(legacy).encoded, "the legacy identity must be untouched")
            // Still unconfirmed as far as the store knows: the user is asked again, nothing is half-done.
            val reported = assertIs<IdentityResolution.ReplacementRequired>(AndroidKeystoreIdentityStore(current, legacy).resolve(now))
            assertEquals(legacySpki, reported.incompatibleSpki)
        }
    }

    // Partial creation that no in-process cleanup could catch (process death between the two steps).
    @Test
    fun aGenerationInterruptedBeforeItsCertificateIsNeverReady() {
        val current = alias("v2")
        generateUnfinishedCurrentKey(current)
        val placeholder = certificate(current)
        assertFalse(IdentityIssuer.isRideLinkIdentityCertificate(placeholder), "Keystore's placeholder is not a RideLink certificate")
        val unfinishedSpki = spkiOfEntry(current)

        val identity = ready(AndroidKeystoreIdentityStore(current, alias("v1")))

        assertTrue(IdentityIssuer.isRideLinkIdentityCertificate(identity.certificate))
        assertNotEquals(unfinishedSpki, identity.identitySpkiSha256, "the unfinished key is discarded, never adopted")
    }

    @Test
    fun anInterruptedReplacementFallsBackToTheUntouchedLegacyIdentity() {
        val current = alias("v2")
        val legacy = alias("v1")
        generateLegacyIdentity(legacy)
        val legacyCertificate = certificate(legacy).encoded
        generateUnfinishedCurrentKey(current)

        val reported = assertIs<IdentityResolution.ReplacementRequired>(AndroidKeystoreIdentityStore(current, legacy).resolve(now))

        assertEquals(spkiOfEntry(legacy), reported.incompatibleSpki)
        assertFalse(keyStore().containsAlias(current))
        assertContentEquals(legacyCertificate, certificate(legacy).encoded)
    }

    // Cleanup never reaches a working identity.
    @Test
    fun aWorkingIdentityIsNeverDiscardedOrReplaced() {
        val current = alias("v2")
        val store = AndroidKeystoreIdentityStore(current, alias("v1"))
        val identity = ready(store)
        val certificateBytes = certificate(current).encoded

        repeat(3) { assertEquals(identity.identitySpkiSha256, ready(store).identitySpkiSha256) }
        assertFailsWith<IllegalStateException> { store.replaceIncompatibleIdentity(now) }
        assertFailsWith<IllegalStateException> { failingStore(current, alias("v1b"), failIssue = true).replaceIncompatibleIdentity(now) }
        assertContentEquals(certificateBytes, certificate(current).encoded)
        assertEquals(identity.identitySpkiSha256, ready(store).identitySpkiSha256)
    }

    private suspend fun handshakeSucceeds(
        server: DeviceIdentity,
        client: DeviceIdentity,
    ): Boolean =
        runCatching {
            connected(server, client) { serverSocket, clientSocket ->
                check(serverSocket.security != null && clientSocket.security != null)
            }
        }.isSuccess

    private suspend fun connected(
        server: DeviceIdentity,
        client: DeviceIdentity,
        body: suspend (server: ControlSocket, client: ControlSocket) -> Unit,
    ) = coroutineScope {
        val listener = TlsControlChannel(server).bind()
        try {
            val accepted = async(Dispatchers.IO) { listener.acceptWithin(HANDSHAKE_BUDGET_MS.toInt()) }
            val clientSocket =
                try {
                    withContext(Dispatchers.IO) { TlsControlChannel(client).connect(LOOPBACK, listener.localPort) }
                } catch (failure: Exception) {
                    accepted.cancel()
                    throw failure
                }
            val serverSocket = withTimeout(HANDSHAKE_BUDGET_MS) { accepted.await() }
            try {
                body(serverSocket, clientSocket)
            } finally {
                serverSocket.close()
                clientSocket.close()
            }
        } finally {
            listener.close()
        }
    }

    private fun securityLevelOf(key: PrivateKey): Int =
        KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java).securityLevel

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val TAG = "KeystoreTlsIdentityTest"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val NONE_WITH_ECDSA = "NONEwithECDSA"
        const val LOOPBACK = "127.0.0.1"
        const val RUN_ID_CHARS = 8
        const val MILLIS_PER_SECOND = 1000L
        const val ELEVEN_YEARS_SECONDS = 11L * 366 * 24 * 60 * 60
        const val HANDSHAKE_BUDGET_MS = 30_000L
        const val SAS_DIGITS = 6
        val MESSAGE = "RideLink ADR-017 Amendment A1 signing probe".toByteArray()
        val TLS_1_3_SUITES = setOf("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")
    }
}
